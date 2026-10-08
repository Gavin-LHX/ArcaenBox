package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.MenuItem
import androidx.activity.addCallback
import androidx.annotation.IdRes
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.Insets
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import android.content.Intent
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.widget.glass.GlassDrawable
import io.nekohasekai.sagernet.widget.glass.GlassScene

/**
 * The classic drawer interface. It is kept unchanged as a fallback for the redesigned
 * [MainActivity] and is opened when "Classic interface" is enabled in settings.
 */
class LegacyMainActivity : MainHostActivity(),
    NavigationView.OnNavigationItemSelectedListener {

    lateinit var binding: LayoutMainBinding
    lateinit var navigation: NavigationView
    override val drawBehindBottomNavigationBar = true
    private var bottomNavigationInset = 0

    override val fabView: FloatingActionButton? get() =
        if (::binding.isInitialized && binding.stats.allowShow) binding.fab else null
    override val listBottomPaddingDp: Int get() =
        if (::binding.isInitialized && binding.stats.allowShow && !DataStore.serviceState.connected) 80 else 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutMainBinding.inflate(layoutInflater)
        binding.fab.initProgress(binding.fabProgress)
        navigation = binding.navView
        navigation.setNavigationItemSelectedListener(this)

        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_configuration)
        }
        onBackPressedDispatcher.addCallback {
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            } else if (supportFragmentManager.findFragmentById(R.id.fragment_holder) is ConfigurationFragment) {
                moveTaskToBack(true)
            } else {
                displayFragmentWithId(R.id.nav_configuration)
            }
        }

        binding.fab.setOnClickListener { toggleService() }
        binding.stats.setOnClickListener { if (DataStore.serviceState.connected) binding.stats.testConnection() }

        setContentView(binding.root)
        if (DataStore.interfaceStyle == "liquid_glass") {
            binding.drawerLayout.background = GlassScene(binding.drawerLayout)
            navigation.background = GlassDrawable(navigation, radiusDp = 0f, sampleContent = true, heavy = true)
            navigation.elevation = 0f
            binding.drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
                override fun onDrawerSlide(drawerView: android.view.View, slideOffset: Float) {
                    navigation.invalidate()
                }
            })
        }
        binding.stats.allowShow = savedInstanceState == null || DataStore.showBottomBar ||
            supportFragmentManager.findFragmentById(R.id.fragment_holder) is ConfigurationFragment
        if (!binding.stats.allowShow) binding.fab.hide()
        ViewCompat.setOnApplyWindowInsetsListener(binding.fragmentHolder) { _, insets ->
            bottomNavigationInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            updateContentSpace()
            // The fragment already ends above the complete bar (including its safe area).
            WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.NONE)
                .build()
        }
        binding.stats.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateContentSpace() }
        initHost()
    }

    override fun refreshNavMenu(clashApi: Boolean) {
        if (::navigation.isInitialized) {
            navigation.menu.findItem(R.id.nav_traffic)?.isVisible = clashApi
        }
    }

    override fun setupToolbarNavigation(fragment: ToolbarFragment, toolbar: Toolbar) {
        toolbar.setNavigationIcon(R.drawable.ic_navigation_menu)
        toolbar.setNavigationContentDescription(R.string.app_name)
        toolbar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (item.isChecked) binding.drawerLayout.closeDrawers() else {
            return displayFragmentWithId(item.itemId)
        }
        return true
    }


    @SuppressLint("CommitTransaction")
    override fun displayFragment(fragment: ToolbarFragment) {
        binding.stats.allowShow = fragment is ConfigurationFragment || DataStore.showBottomBar
        if (binding.stats.allowShow) {
            binding.stats.performShow()
            binding.fab.show()
        } else {
            binding.stats.performHide()
            binding.fab.hide()
        }
        updateContentSpace()
        beginShellTransaction()
            .replace(R.id.fragment_holder, fragment)
            .commitAllowingStateLoss()
        binding.drawerLayout.closeDrawers()
    }

    private fun updateContentSpace() {
        val params = binding.fragmentHolder.layoutParams as android.view.ViewGroup.MarginLayoutParams
        val bottom = if (binding.stats.allowShow && DataStore.serviceState.connected) binding.stats.height else bottomNavigationInset
        if (params.bottomMargin != bottom) {
            params.bottomMargin = bottom
            binding.fragmentHolder.layoutParams = params
        }
    }

    override fun displayFragmentWithId(@IdRes id: Int): Boolean {
        when (id) {
            R.id.nav_configuration -> {
                displayFragment(ConfigurationFragment())
            }

            R.id.nav_group -> displayFragment(GroupFragment())
            R.id.nav_route -> displayFragment(RouteFragment())
            R.id.nav_resources -> {
                binding.drawerLayout.closeDrawers()
                startActivity(Intent(this, AssetsActivity::class.java))
                return false
            }
            R.id.nav_settings -> displayFragment(SettingsFragment())
            R.id.nav_traffic -> displayFragment(WebviewFragment())
            R.id.nav_tools -> displayFragment(ToolsFragment())
            R.id.nav_kernels -> displayFragment(KernelManagerFragment())
            R.id.nav_po0 -> displayFragment(Po0WhitelistFragment())
            R.id.nav_logcat -> displayFragment(LogcatFragment())
            R.id.nav_faq -> {
                launchCustomTab("https://matsuridayo.github.io/")
                return false
            }

            R.id.nav_about -> displayFragment(AboutFragment())

            else -> return false
        }
        navigation.menu.findItem(id).isChecked = true
        return true
    }

    override fun onServiceStateChanged(state: BaseService.State, animate: Boolean) {
        binding.fab.changeState(state, DataStore.serviceState, animate)
        binding.stats.changeState(state)
        updateContentSpace()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            if (binding.fab.isShown) {
                anchorView = binding.fab
            }
            // TODO
        }
    }

    override fun onSpeedUpdated(txRate: Long, rxRate: Long) {
        binding.stats.updateSpeed(txRate, rxRate)
    }

    override fun onSelectedProxyChanged(id: Long) {
        binding.stats.refreshExitIp()
    }

    override fun onBottomBarPreferenceChanged() {
        binding.stats.allowShow = supportFragmentManager.findFragmentById(R.id.fragment_holder) is ConfigurationFragment ||
            DataStore.showBottomBar
        if (binding.stats.allowShow) {
            binding.stats.performShow()
            binding.fab.show()
        } else {
            binding.stats.performHide()
            binding.fab.hide()
        }
        updateContentSpace()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (super.onKeyDown(keyCode, event)) return true
                binding.drawerLayout.open()
                navigation.requestFocus()
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.drawerLayout.isOpen) {
                    binding.drawerLayout.close()
                    return true
                }
            }
        }

        if (super.onKeyDown(keyCode, event)) return true
        if (binding.drawerLayout.isOpen) return false

        val fragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }

}
