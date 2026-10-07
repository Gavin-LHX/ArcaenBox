package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.addCallback
import androidx.annotation.IdRes
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutMainShellBinding
import io.nekohasekai.sagernet.ktx.launchCustomTab

/**
 * The redesigned Material 3 interface: a bottom navigation bar on phones and a navigation
 * rail on wide screens, with a dashboard as the start page. When the classic interface is
 * enabled in settings, this activity forwards to [LegacyMainActivity] instead.
 */
class MainActivity : MainHostActivity() {

    private lateinit var binding: LayoutMainShellBinding
    private lateinit var navigation: NavigationBarView
    override val drawBehindBottomNavigationBar = true
    private var forwarded = false
    @IdRes private var selectedDestination = R.id.nav_home
    private var nodeFabEnabled = false

    override val fabView: FloatingActionButton?
        get() = if (::binding.isInitialized && nodeFabEnabled) binding.fab else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DataStore.useClassicUi) {
            forwarded = true
            startActivity(Intent(intent).setClass(this, LegacyMainActivity::class.java))
            finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
            return
        }

        binding = LayoutMainShellBinding.inflate(layoutInflater)
        binding.fab.initProgress(binding.fabProgress)
        binding.fab.setOnClickListener { toggleService() }
        navigation = binding.bottomNav ?: binding.navRail!!
        // The bar and rail have different IDs. Restore their shared destination without
        // firing a navigation transaction or replacing a restored secondary page.
        navigation.isSaveEnabled = false
        selectedDestination = savedInstanceState?.getInt(KEY_DESTINATION, R.id.nav_home)
            ?.takeIf { navigation.menu.findItem(it) != null } ?: R.id.nav_home
        navigation.selectedItemId = selectedDestination
        navigation.setOnItemSelectedListener { showTopLevel(it.itemId); true }
        navigation.setOnItemReselectedListener { popToTopLevel() }

        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.coordinator) { view, insets ->
            // The bottom bar pads itself for the gesture area; without one the content does.
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.updatePadding(bottom = if (binding.bottomNav == null) bottom else 0)
            WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.NONE)
                .build()
        }

        if (savedInstanceState == null) showTopLevel(R.id.nav_home)
        supportFragmentManager.addOnBackStackChangedListener { updateFab() }
        onBackPressedDispatcher.addCallback {
            val fragment = currentFragment()
            when {
                fragment?.onBackPressed() == true -> {}
                supportFragmentManager.backStackEntryCount > 0 -> supportFragmentManager.popBackStack()
                navigation.selectedItemId != R.id.nav_home -> navigation.selectedItemId = R.id.nav_home
                else -> moveTaskToBack(true)
            }
        }
        initHost()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!forwarded) outState.putInt(KEY_DESTINATION, selectedDestination)
    }

    private fun currentFragment() =
        supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment

    private fun isTopLevel(fragment: ToolbarFragment) = fragment is HomeFragment ||
            fragment is ConfigurationFragment || fragment is GroupFragment ||
            fragment is RouteFragment || fragment is MoreFragment

    private fun popToTopLevel() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
    }

    private fun showTopLevel(@IdRes id: Int) {
        val fragment = when (id) {
            R.id.nav_home -> HomeFragment()
            R.id.nav_configuration -> ConfigurationFragment()
            R.id.nav_group -> GroupFragment()
            R.id.nav_route -> RouteFragment()
            R.id.nav_more -> MoreFragment()
            else -> return
        }
        selectedDestination = id
        popToTopLevel()
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragment_holder, fragment)
            .commitAllowingStateLoss()
        updateFab(fragment)
    }

    /** Opens a secondary page above the current destination; Back returns to it. */
    override fun displayFragment(fragment: ToolbarFragment) {
        if (isTopLevel(fragment)) {
            val id = when (fragment) {
                is HomeFragment -> R.id.nav_home
                is ConfigurationFragment -> R.id.nav_configuration
                is GroupFragment -> R.id.nav_group
                is RouteFragment -> R.id.nav_route
                else -> R.id.nav_more
            }
            displayFragmentWithId(id)
            return
        }
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .setCustomAnimations(
                android.R.anim.fade_in, android.R.anim.fade_out,
                android.R.anim.fade_in, android.R.anim.fade_out,
            )
            .replace(R.id.fragment_holder, fragment)
            .addToBackStack(null)
            .commitAllowingStateLoss()
        updateFab(fragment)
    }

    override fun displayFragmentWithId(@IdRes id: Int): Boolean {
        when (id) {
            R.id.nav_home, R.id.nav_configuration, R.id.nav_group, R.id.nav_route, R.id.nav_more -> {
                if (navigation.selectedItemId == id) showTopLevel(id) else navigation.selectedItemId = id
            }

            R.id.nav_resources -> {
                startActivity(Intent(this, AssetsActivity::class.java))
                return false
            }

            R.id.nav_settings -> displayFragment(SettingsFragment())
            R.id.nav_traffic -> displayFragment(WebviewFragment())
            R.id.nav_tools -> displayFragment(ToolsFragment())
            R.id.nav_kernels -> displayFragment(KernelManagerFragment())
            R.id.nav_po0 -> displayFragment(Po0WhitelistFragment())
            R.id.nav_logcat -> displayFragment(LogcatFragment())
            R.id.nav_about -> displayFragment(AboutFragment())
            R.id.nav_faq -> {
                launchCustomTab("https://matsuridayo.github.io/")
                return false
            }

            else -> return false
        }
        return true
    }

    override fun setupToolbarNavigation(fragment: ToolbarFragment, toolbar: Toolbar) {
        if (isTopLevel(fragment)) {
            toolbar.navigationIcon = null
        } else {
            toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
            toolbar.setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
            toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
    }

    /** The connect button floats over the node list, where nodes are chosen. */
    private fun updateFab(fragment: ToolbarFragment? = currentFragment()) {
        nodeFabEnabled = fragment is ConfigurationFragment
        if (nodeFabEnabled) binding.fab.show() else binding.fab.hide()
    }

    override fun refreshNavMenu(clashApi: Boolean) {
        (currentFragment() as? MoreFragment)?.refresh()
    }

    override fun onServiceStateChanged(state: BaseService.State, animate: Boolean) {
        if (forwarded) return
        binding.fab.changeState(state, DataStore.serviceState, animate)
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            if (binding.fab.isShown) anchorView = binding.fab
        }
    }

    override fun onStart() {
        super.onStart()
        if (!forwarded) updateFab()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (super.onKeyDown(keyCode, event)) return true
        return currentFragment()?.onKeyDown(keyCode, event) == true
    }

    companion object {
        private const val KEY_DESTINATION = "main.destination"
    }

}
