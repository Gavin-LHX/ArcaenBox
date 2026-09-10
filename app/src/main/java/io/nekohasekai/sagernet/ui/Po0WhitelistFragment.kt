package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutPo0WhitelistBinding
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.po0.*
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

class Po0WhitelistFragment : ToolbarFragment(R.layout.layout_po0_whitelist) {
    private var binding: LayoutPo0WhitelistBinding? = null
    private var pending = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.po0_title)
        val ui = LayoutPo0WhitelistBinding.bind(view)
        binding = ui
        val context = requireContext().applicationContext
        ui.po0Tokens.doAfterTextChanged { ui.po0TokenInput.error = null }
        ui.po0Guide.setOnClickListener { requireContext().launchCustomTab(Po0Protocol.GUIDE) }
        ui.po0Save.setOnClickListener { save(false) }
        ui.po0Add.setOnClickListener { save(true) }
        ui.po0Save.isEnabled = false
        ui.po0Add.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val state = withContext(Dispatchers.IO) { Po0Store.state(context) }
                val tokens = withContext(Dispatchers.IO) { Po0Store.tokens(context) }
                ui.po0Tokens.setText(tokens)
                ui.po0Automatic.isChecked = state.automatic
            } catch (_: Exception) {
                ui.po0TokenInput.error = getString(R.string.po0_storage)
            }
            ui.po0Save.isEnabled = true
            ui.po0Add.isEnabled = true
            while (isActive) {
                try {
                    render(withContext(Dispatchers.IO) { Po0Store.state(context) })
                } catch (_: Exception) {
                    ui.po0Status.text = getString(R.string.po0_storage)
                }
                delay(1500)
            }
        }
    }

    private fun save(addNow: Boolean) {
        val ui = binding ?: return
        val text = ui.po0Tokens.text?.toString().orEmpty()
        val tokens = try { Po0Protocol.tokens(text) } catch (_: IllegalArgumentException) {
            ui.po0TokenInput.error = getString(R.string.po0_invalid_tokens)
            return
        }
        if (tokens.isEmpty() && (addNow || ui.po0Automatic.isChecked)) {
            ui.po0TokenInput.error = getString(R.string.po0_tokens_required)
            return
        }
        val automatic = ui.po0Automatic.isChecked
        val context = requireContext().applicationContext
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(ui.po0Tokens.windowToken, 0)
        ui.po0Tokens.clearFocus()
        ui.po0Save.isEnabled = false
        ui.po0Add.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val state = withContext(Dispatchers.IO) {
                    Po0Store.save(context, text, automatic).also {
                        Po0Whitelist.configure(context)
                        if (addNow || automatic) Po0Whitelist.enqueue(context, manual = addNow)
                    }
                }
                pending = addNow || automatic
                render(state)
                (activity as? MainActivity)?.snackbar(getString(R.string.po0_saved))?.show()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ui.po0Status.text = getString(R.string.po0_storage)
            } finally {
                ui.po0Save.isEnabled = true
                ui.po0Add.isEnabled = true
            }
        }
    }

    private fun render(state: Po0State) {
        val ui = binding ?: return
        if (state.checkedAt == 0L) {
            ui.po0Status.text = getString(if (pending) R.string.po0_queued else R.string.po0_no_result)
            return
        }
        pending = false
        val lines = mutableListOf(getString(R.string.po0_last_check,
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(state.checkedAt))))
        state.results.forEachIndexed { index, result ->
            val message = when (result.status) {
                "success" -> R.string.po0_success
                "disabled" -> R.string.po0_disabled
                "not_applied" -> R.string.po0_not_applied
                "slot_mismatch" -> R.string.po0_slot_mismatch
                "forbidden" -> R.string.po0_forbidden
                "http" -> R.string.po0_http
                "tls" -> R.string.po0_tls
                "network" -> R.string.po0_network
                "no_network" -> R.string.po0_no_network
                "storage" -> R.string.po0_storage
                else -> R.string.po0_invalid_response
            }
            lines += "\n${getString(R.string.po0_machine, index + 1)} · ${getString(message)}" +
                (if (result.httpCode != 0) " (${result.httpCode})" else "")
            if (result.currentIp.isNotEmpty()) lines += getString(R.string.po0_exit, result.currentIp)
            if (result.entries.isNotEmpty()) {
                lines += getString(R.string.po0_entries, result.entries.size, result.limit)
                result.entries.forEach { entry ->
                    lines += "• ${entry.ip}" + (entry.slot?.let { " · ${getString(R.string.po0_slot, it)}" } ?: "")
                }
            }
        }
        ui.po0Status.text = lines.joinToString("\n")
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
