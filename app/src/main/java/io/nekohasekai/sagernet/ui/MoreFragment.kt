package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.core.view.updatePadding
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutMoreBinding
import io.nekohasekai.sagernet.databinding.LayoutMoreItemBinding
import io.nekohasekai.sagernet.databinding.LayoutMoreSectionBinding

/** Secondary destinations of the redesigned interface, grouped like a settings list. */
class MoreFragment : ToolbarFragment(R.layout.layout_more) {

    private class Entry(
        @IdRes val id: Int,
        @DrawableRes val icon: Int,
        @StringRes val title: Int,
        @StringRes val summary: Int,
    )

    private var binding: LayoutMoreBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = LayoutMoreBinding.bind(view)
        toolbar.setTitle(R.string.shell_more)
        binding!!.moreScroll.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left == oldRight - oldLeft) return@addOnLayoutChangeListener
            val maxWidth = resources.getDimensionPixelSize(R.dimen.shell_wide_content_width)
            val side = maxOf(resources.getDimensionPixelSize(R.dimen.shell_side_padding), (right - left - maxWidth) / 2)
            binding?.moreContent?.updatePadding(left = side, right = side)
        }
        refresh()
    }

    /** Rebuilds the list, e.g. after the Clash API dashboard was turned on or off. */
    fun refresh() {
        val content = binding?.moreContent ?: return
        content.removeAllViews()

        section(content, R.string.shell_section_proxy, listOfNotNull(
            Entry(R.id.nav_resources, R.drawable.ic_baseline_rule_folder_24, R.string.route_resources_button, R.string.shell_resources_summary),
            Entry(R.id.nav_po0, R.drawable.ic_po0_shield, R.string.po0_title, R.string.shell_po0_summary),
            Entry(R.id.nav_traffic, R.drawable.ic_baseline_transform_24, R.string.menu_dashboard, R.string.shell_dashboard_summary)
                .takeIf { DataStore.enableClashAPI },
        ))
        section(content, R.string.shell_section_app, listOf(
            Entry(R.id.nav_settings, R.drawable.ic_action_settings, R.string.settings, R.string.shell_settings_summary),
            Entry(R.id.nav_tools, R.drawable.baseline_construction_24, R.string.menu_tools, R.string.shell_tools_summary),
            Entry(R.id.nav_kernels, R.drawable.baseline_developer_board_24, R.string.kernel_manager, R.string.shell_kernels_summary),
            Entry(R.id.nav_logcat, R.drawable.ic_baseline_bug_report_24, R.string.menu_log, R.string.shell_logs_summary),
            Entry(R.id.nav_classic_ui, R.drawable.ic_shell_swap, R.string.classic_ui_switch, R.string.classic_ui_switch_summary),
        ))
        section(content, R.string.shell_section_help, listOf(
            Entry(R.id.nav_faq, R.drawable.ic_device_data_usage, R.string.document, R.string.shell_document_summary),
            Entry(R.id.nav_about, R.drawable.ic_baseline_info_24, R.string.menu_about, R.string.shell_about_summary),
        ))
    }

    private fun section(parent: LinearLayout, @StringRes title: Int, entries: List<Entry>) {
        val section = LayoutMoreSectionBinding.inflate(layoutInflater, parent, true)
        section.sectionTitle.setText(title)
        for (entry in entries) {
            val item = LayoutMoreItemBinding.inflate(layoutInflater, section.sectionItems, true)
            item.itemIcon.setImageResource(entry.icon)
            item.itemTitle.setText(entry.title)
            item.itemSummary.setText(entry.summary)
            item.root.setOnClickListener { open(entry.id) }
        }
    }

    private fun open(@IdRes id: Int) {
        if (id == R.id.nav_classic_ui) {
            ClassicUi.switch(requireActivity(), true)
            return
        }
        (requireActivity() as MainHostActivity).displayFragmentWithId(id)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
