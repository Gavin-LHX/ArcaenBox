package moe.matsuri.nb4a.ui

import android.content.Context
import android.util.AttributeSet
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreference
import com.google.android.material.materialswitch.MaterialSwitch
import io.nekohasekai.sagernet.R

/** Retains the existing preference keys, dependencies and change listeners. */
class MaterialSwitchPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SwitchPreference(context, attrs) {
    init {
        widgetLayoutResource = R.layout.layout_md3_switch
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.findViewById(R.id.material_switch)?.let { view ->
            (view as MaterialSwitch).apply {
                setOnCheckedChangeListener(null)
                isChecked = this@MaterialSwitchPreference.isChecked
                isEnabled = this@MaterialSwitchPreference.isEnabled
                setOnCheckedChangeListener { button, checked ->
                    if (checked != this@MaterialSwitchPreference.isChecked) {
                        if (callChangeListener(checked)) {
                            this@MaterialSwitchPreference.isChecked = checked
                        } else {
                            button.isChecked = this@MaterialSwitchPreference.isChecked
                        }
                    }
                }
            }
        }
    }
}
