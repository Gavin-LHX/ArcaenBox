package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.util.Linkify
import android.view.View
import android.widget.Toast
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.danielstone.materialaboutlibrary.adapters.MaterialAboutListAdapter
import com.danielstone.materialaboutlibrary.items.MaterialAboutActionItem
import com.danielstone.materialaboutlibrary.model.MaterialAboutCard
import com.danielstone.materialaboutlibrary.model.MaterialAboutList
import com.danielstone.materialaboutlibrary.util.DefaultViewTypeManager
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutAboutBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager.loadString
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.ListListener
import libcore.Libcore
import moe.matsuri.nb4a.plugin.Plugins
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.SagerNet
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.update.*
import kotlinx.coroutines.*

class AboutFragment : ToolbarFragment(R.layout.layout_about) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutAboutBinding.bind(view)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_about)

        if (childFragmentManager.findFragmentById(R.id.about_fragment_holder) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.about_fragment_holder, AboutContent())
                .commit()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val license = withContext(Dispatchers.IO) {
                view.context.assets.open("LICENSE").bufferedReader().use { it.readText() }
            }
            binding.license.text = license
            Linkify.addLinks(binding.license, Linkify.EMAIL_ADDRESSES or Linkify.WEB_URLS)
        }
    }

    class AboutContent : Fragment(com.danielstone.materialaboutlibrary.R.layout.mal_material_about_content) {

        val requestIgnoreBatteryOptimizations = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { (resultCode, _) ->
            if (resultCode == Activity.RESULT_OK && isAdded && view != null && !parentFragmentManager.isStateSaved) {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.about_fragment_holder, AboutContent())
                    .commitAllowingStateLoss()
            }
        }

        private fun buildAboutList(activityContext: Context): MaterialAboutList {
            return MaterialAboutList.Builder()
                .addCard(
                    MaterialAboutCard.Builder()
                        .outline(false)
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_update_24)
                                .text(R.string.app_version)
                                .subText(SagerNet.appVersionNameForDisplay)
                                .setOnClickAction {
                                    requireContext().launchCustomTab(
                                        "https://github.com/Gavin-LHX/ArcaenBox/releases"
                                    )
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .text(R.string.check_update_release)
                                .setOnClickAction {
                                    checkUpdate(false)
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .text(R.string.check_update_preview)
                                .setOnClickAction {
                                    checkUpdate(true)
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_layers_24)
                                .text(activityContext.getString(R.string.version_x, "sing-box"))
                                .subText(Libcore.versionBox())
                                .setOnClickAction { (requireActivity() as MainActivity).displayFragment(CoreUpdatesFragment()) }
                                .build())
                        .addItem(MaterialAboutActionItem.Builder()
                            .text(R.string.kernel_manager)
                            .setOnClickAction { (requireActivity() as MainActivity).displayFragment(KernelManagerFragment()) }
                            .build())
                        .addItem(MaterialAboutActionItem.Builder()
                            .text(R.string.builtin_protocols)
                            .subText(R.string.builtin_protocols_summary)
                            .setOnClickAction {
                                val manifest = org.json.JSONObject(requireContext().assets.open("builtins/manifest.json").bufferedReader().use { it.readText() })
                                val components = manifest.getJSONArray("components")
                                val versions = (0 until components.length()).joinToString("\n") { index ->
                                    components.getJSONObject(index).let { "${it.getString("name")}: ${it.getString("version")}" }
                                }
                                MaterialAlertDialogBuilder(requireContext())
                                    .setTitle(R.string.builtin_protocols)
                                    .setMessage(versions + "\n\n" + getString(R.string.snell_version_help))
                                    .setPositiveButton(android.R.string.ok, null).show()
                            }.build())

                        .apply {
                            PackageCache.awaitLoadSync()
                            for ((_, pkg) in PackageCache.installedPluginPackages) {
                                try {
                                    val pluginId =
                                        pkg.providers?.get(0)?.loadString(Plugins.METADATA_KEY_ID)
                                    if (pluginId.isNullOrBlank()) continue
                                    if (pluginId in setOf("trojan-go-plugin", "naive-plugin", "mieru-plugin")) continue
                                    addItem(
                                        MaterialAboutActionItem.Builder()
                                            .icon(R.drawable.ic_baseline_nfc_24)
                                            .text(
                                                activityContext.getString(
                                                    R.string.version_x,
                                                    pluginId
                                                ) + " (${Plugins.displayExeProvider(pkg.packageName)})"
                                            )
                                            .subText("v" + pkg.versionName)
                                            .setOnClickAction {
                                                startActivity(Intent().apply {
                                                    action =
                                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                                    data = Uri.fromParts(
                                                        "package", pkg.packageName, null
                                                    )
                                                })
                                            }
                                            .build())
                                } catch (e: Exception) {
                                    Logs.w(e)
                                }
                            }
                        }
                        .apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                                if (!pm.isIgnoringBatteryOptimizations(app.packageName)) {
                                    addItem(
                                        MaterialAboutActionItem.Builder()
                                            .icon(R.drawable.ic_baseline_running_with_errors_24)
                                            .text(R.string.ignore_battery_optimizations)
                                            .subText(R.string.ignore_battery_optimizations_sum)
                                            .setOnClickAction {
                                                requestIgnoreBatteryOptimizations.launch(
                                                    Intent(
                                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                        "package:${app.packageName}".toUri()
                                                    )
                                                )
                                            }
                                            .build())
                                }
                            }
                        }
                        .build())
                .addCard(
                    MaterialAboutCard.Builder()
                        .outline(false)
                        .title(R.string.project)
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_sanitizer_24)
                                .text(R.string.github)
                                .setOnClickAction {
                                    requireContext().launchCustomTab(
                                        "https://github.com/Gavin-LHX/ArcaenBox"

                                    )
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_arcaenbox)
                                .text(R.string.telegram)
                                .setOnClickAction {
                                    requireContext().launchCustomTab(
                                        "https://t.me/MatsuriDayo"
                                    )
                                }
                                .build())
                        .build())
                .build()

        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            val adapter = MaterialAboutListAdapter(DefaultViewTypeManager())
            view.findViewById<RecyclerView>(R.id.mal_recyclerview).apply {
                overScrollMode = RecyclerView.OVER_SCROLL_NEVER
                layoutManager = LinearLayoutManager(context)
                this.adapter = adapter
            }
            // The library's AsyncTask can outlive a detached Fragment. Capture
            // the context while attached and discard work when this view dies.
            val activityContext = view.context
            LatestSnapshotLoader(viewLifecycleOwner.lifecycleScope,
                load = { withContext(Dispatchers.Default) { buildAboutList(activityContext) } },
                apply = { adapter.setData(it.cards) },
            ).reload()
        }

        override fun onDestroyView() {
            view?.findViewById<RecyclerView>(R.id.mal_recyclerview)?.adapter = null
            super.onDestroyView()
        }

        private var checking = false
        private fun updateMessage(message: Int) {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.update_dialog_title)
                .setMessage(message).setPositiveButton(android.R.string.ok,null).show()
        }
        fun checkUpdate(checkPreview: Boolean) {
            if (checking || parentFragmentManager.findFragmentByTag(AppUpdateDialogFragment.TAG) != null) return
            checking = true
            Toast.makeText(requireContext(),R.string.update_checking,Toast.LENGTH_SHORT).show()
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val release = ReleaseService.applicationRelease(ReleaseService.releases(),checkPreview)
                    val context = requireContext()
                    if (release == null) {
                        updateMessage(if (checkPreview) R.string.update_no_preview else R.string.update_no_release)
                        return@launch
                    }
                    val current = ReleaseVersion.parse(BuildConfig.VERSION_NAME)
                    val available = ReleaseVersion.parse(release.tag)
                    if (available != null && (current == null || available > current)) {
                        MaterialAlertDialogBuilder(context)
                            .setTitle(R.string.update_dialog_title)
                            .setMessage(getString(R.string.app_update_available,SagerNet.appVersionNameForDisplay,release.tag))
                            .setPositiveButton(R.string.app_update_download) { _, _ ->
                                AppUpdateDialogFragment.newInstance(release).show(parentFragmentManager, AppUpdateDialogFragment.TAG)
                            }
                            .setNegativeButton(R.string.no,null).show()
                    } else {
                        updateMessage(R.string.check_update_no)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (isAdded) updateMessage(UpdateMessages.resource(e)) }
                finally { checking = false }
            }
        }
    }
}
