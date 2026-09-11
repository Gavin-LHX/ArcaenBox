package moe.matsuri.nb4a.ui

import android.content.Context
import android.util.AttributeSet
import androidx.preference.EditTextPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R

/** Presets and custom input share the same persisted value and validation. */
class TestPresetPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    EditTextPreference(context, attrs) {
    var presets: List<Pair<String, String>> = emptyList()

    override fun onClick() {
        val options = presets.map { it.first } + context.getString(R.string.test_preset_custom)
        val selected = presets.indexOfFirst { it.second == text }.takeIf { it >= 0 } ?: presets.size
        MaterialAlertDialogBuilder(context).setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, which ->
                dialog.dismiss()
                if (which == presets.size) showCustom()
                else if (callChangeListener(presets[which].second)) text = presets[which].second
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showCustom() = super.onClick()
}
