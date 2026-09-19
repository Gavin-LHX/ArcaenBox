package moe.matsuri.nb4a.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import io.nekohasekai.sagernet.R

/** Hides rows without removing them, so stored values and dependencies stay intact. */
class ExpandablePreferenceCategory @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : PreferenceCategory(context, attrs) {
    var expanded = false
        set(value) {
            field = value
            for (index in 0 until preferenceCount) getPreference(index).isVisible = value
            notifyChanged()
        }

    init {
        isSelectable = true
        isPersistent = false
        layoutResource = R.layout.preference_expandable_category
    }

    override fun isEnabled() = true
    override fun onClick() { expanded = !expanded }

    override fun onAttached() {
        super.onAttached()
        expanded = expanded
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.expand_arrow) as ImageView).rotation = if (expanded) 180f else 0f
        holder.itemView.contentDescription = "$title, ${context.getString(if (expanded) R.string.settings_collapse else R.string.settings_expand)}"
    }
}
