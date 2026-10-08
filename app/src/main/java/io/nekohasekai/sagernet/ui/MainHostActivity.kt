package io.nekohasekai.sagernet.ui

import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import androidx.annotation.IdRes
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.FragmentTransaction
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.group.GroupInterfaceAdapter
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.widget.glass.GlassTouch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import moe.matsuri.nb4a.utils.Util

/**
 * Behaviour shared by the redesigned [MainActivity] and the classic [LegacyMainActivity]:
 * the service connection, deep-link imports, plugin prompts and preference reactions.
 * Each subclass only provides its own navigation shell.
 */
abstract class MainHostActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener {

    private val _serviceState = MutableStateFlow(BaseService.State.Idle)
    private val _speed = MutableStateFlow(0L to 0L)
    private val _selectedProxy = MutableStateFlow(DataStore.selectedProxy)

    /** Latest service state, for pages that render connection status. */
    val serviceState: StateFlow<BaseService.State> get() = _serviceState

    /** Latest proxy upload/download rate in bytes per second. */
    val speed: StateFlow<Pair<Long, Long>> get() = _speed

    /** Profile chosen by the running service; changes when a selector switches nodes. */
    val selectedProxy: StateFlow<Long> get() = _selectedProxy

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)

    /** Floating control that list pages keep clear of while scrolling, if the shell shows one. */
    open val fabView: FloatingActionButton? get() = null

    /** Scroll space below lists when the idle FAB floats over their content. */
    open val listBottomPaddingDp: Int get() = 0

    /** The classic release keeps its FAB fixed; the node shell can hide it on overscroll. */
    open val fabFollowsListScroll: Boolean get() = false

    abstract fun displayFragment(fragment: ToolbarFragment)

    abstract fun displayFragmentWithId(@IdRes id: Int): Boolean

    /** Sets the navigation icon of a page's toolbar; the shells navigate differently. */
    abstract fun setupToolbarNavigation(fragment: ToolbarFragment, toolbar: Toolbar)

    open fun refreshNavMenu(clashApi: Boolean) {}

    /** Called after the service state was stored in [DataStore.serviceState]. */
    protected abstract fun onServiceStateChanged(state: BaseService.State, animate: Boolean)

    protected open fun onSpeedUpdated(txRate: Long, rxRate: Long) {}

    protected open fun onSelectedProxyChanged(id: Long) {}

    protected open fun onBottomBarPreferenceChanged() {}

    protected fun beginShellTransaction(secondary: Boolean = false): FragmentTransaction =
        supportFragmentManager.beginTransaction().apply {
            if (DataStore.interfaceStyle == "liquid_glass") {
                if (GlassTouch.motionEnabled(window.decorView)) {
                    setCustomAnimations(R.anim.glass_enter, R.anim.glass_exit,
                        R.anim.glass_enter, R.anim.glass_exit)
                }
            } else if (secondary) {
                setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out,
                    android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }

    private val groupInterface by lazy { GroupInterfaceAdapter(this) }

    /** False for an activity that forwarded elsewhere before building its interface. */
    private var hostInitialized = false

    protected fun initHost() {
        hostInitialized = true
        changeState(BaseService.State.Idle)
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)
        GroupManager.userInterface = groupInterface

        if (intent?.action == Intent.ACTION_VIEW) {
            onNewIntent(intent)
        }

        refreshNavMenu(DataStore.enableClashAPI)
        requestNotificationPermission()

        if (isPreview) {
            MaterialAlertDialogBuilder(this)
                .setTitle(BuildConfig.PRE_VERSION_NAME)
                .setMessage(R.string.preview_version_hint)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val checkPermission = ContextCompat.checkSelfPermission(this, POST_NOTIFICATIONS)
            if (checkPermission != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(POST_NOTIFICATIONS), 0)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val uri = intent.data ?: return

        runOnDefaultDispatcher {
            if (uri.scheme == "sn" && uri.host == "subscription" || uri.scheme == "clash") {
                importSubscription(uri)
            } else {
                importProfile(uri)
            }
        }
    }

    fun toggleService() {
        if (DataStore.serviceState.canStop) SagerNet.stopService() else connect.launch(null)
    }

    fun urlTest(): Int {
        if (!DataStore.serviceState.connected || connection.service == null) {
            error("not started")
        }
        return connection.service!!.urlTest()
    }

    suspend fun importSubscription(uri: Uri) {
        val group: ProxyGroup

        val url = uri.getQueryParameter("url")
        if (!url.isNullOrBlank()) {
            group = ProxyGroup(type = GroupType.SUBSCRIPTION)
            val subscription = SubscriptionBean()
            group.subscription = subscription

            // cleartext format
            subscription.link = url
            group.name = uri.getQueryParameter("name")
        } else {
            val data = uri.encodedQuery.takeIf { !it.isNullOrBlank() } ?: return
            try {
                group = KryoConverters.deserialize(
                    ProxyGroup().apply { export = true }, Util.zlibDecompress(Util.b64Decode(data))
                ).apply {
                    export = false
                }
            } catch (e: Exception) {
                onMainDispatcher {
                    alert(e.readableMessage).show()
                }
                return
            }
        }

        val name = group.name.takeIf { !it.isNullOrBlank() } ?: group.subscription?.link
        ?: group.subscription?.token
        if (name.isNullOrBlank()) return

        group.name = group.name.takeIf { !it.isNullOrBlank() }
            ?: ("Subscription #" + System.currentTimeMillis())

        onMainDispatcher {

            displayFragmentWithId(R.id.nav_group)

            MaterialAlertDialogBuilder(this@MainHostActivity).setTitle(R.string.subscription_import)
                .setMessage(getString(R.string.subscription_import_message, name))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportSubscription(group)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

        }

    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        GroupManager.createGroup(subscription)
        GroupUpdater.startUpdate(subscription, true)
    }

    suspend fun importProfile(uri: Uri) {
        val profile = try {
            parseProxies(uri.toString()).getOrNull(0) ?: error(getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            onMainDispatcher {
                alert(e.readableMessage).show()
            }
            return
        }

        onMainDispatcher {
            MaterialAlertDialogBuilder(this@MainHostActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportProfile(profile)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

    }

    private suspend fun finishImportProfile(profile: AbstractBean) {
        val targetId = DataStore.selectedGroupForImport()

        ProfileManager.createProfile(targetId, profile)

        onMainDispatcher {
            displayFragmentWithId(R.id.nav_configuration)

            snackbar(resources.getQuantityString(R.plurals.added, 1, 1)).show()
        }
    }

    /** Imports profiles or a subscription link from the clipboard into the import group. */
    fun importFromClipboard() {
        val text = SagerNet.getClipboardText()
        if (text.isBlank()) {
            snackbar(getString(R.string.clipboard_empty)).show()
            return
        }
        runOnDefaultDispatcher {
            try {
                val proxies = RawUpdater.parseRaw(text)
                if (proxies.isNullOrEmpty()) onMainDispatcher {
                    snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                } else {
                    val targetId = DataStore.selectedGroupForImport()
                    for (proxy in proxies) ProfileManager.createProfile(targetId, proxy)
                    onMainDispatcher {
                        DataStore.editingGroup = targetId
                        snackbar(resources.getQuantityString(R.plurals.added, proxies.size, proxies.size)).show()
                    }
                }
            } catch (e: SubscriptionFoundException) {
                importSubscription(e.link.toUri())
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher { snackbar(e.readableMessage).show() }
            }
        }
    }

    /** Asks for confirmation, then refreshes every subscription group. */
    fun confirmUpdateAllSubscriptions() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.confirm)
            .setMessage(R.string.update_all_subscription)
            .setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    SagerDatabase.groupDao.allGroups()
                        .filter { it.type == GroupType.SUBSCRIPTION }
                        .forEach { GroupUpdater.startUpdate(it, true) }
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    override fun missingPlugin(profileName: String, pluginName: String) {
        val pluginEntity = PluginEntry.find(pluginName)

        // unknown exe or neko plugin
        if (pluginEntity == null) {
            snackbar(getString(R.string.plugin_unknown, pluginName)).show()
            return
        }

        // official exe

        MaterialAlertDialogBuilder(this).setTitle(R.string.missing_plugin)
            .setMessage(
                getString(
                    R.string.profile_requiring_plugin, profileName, pluginEntity.displayName
                )
            )
            .setPositiveButton(R.string.action_download) { _, _ ->
                showDownloadDialog(pluginEntity)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.action_learn_more) { _, _ ->
                launchCustomTab("https://matsuridayo.github.io/nb4a-plugin/")
            }
            .show()
    }

    private fun showDownloadDialog(pluginEntry: PluginEntry) {
        var index = 0
        var playIndex = -1
        var fdroidIndex = -1

        val items = mutableListOf<String>()
        if (pluginEntry.downloadSource.playStore) {
            items.add(getString(R.string.install_from_play_store))
            playIndex = index++
        }
        if (pluginEntry.downloadSource.fdroid) {
            items.add(getString(R.string.install_from_fdroid))
            fdroidIndex = index++
        }

        items.add(getString(R.string.download))
        val downloadIndex = index

        MaterialAlertDialogBuilder(this).setTitle(pluginEntry.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    playIndex -> launchCustomTab("https://play.google.com/store/apps/details?id=${pluginEntry.packageName}")
                    fdroidIndex -> launchCustomTab("https://f-droid.org/packages/${pluginEntry.packageName}/")
                    downloadIndex -> launchCustomTab(pluginEntry.downloadSource.downloadLink)
                }
            }
            .show()
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
    ) {
        DataStore.serviceState = state
        _serviceState.value = state
        if (!state.connected) _speed.value = 0L to 0L

        onServiceStateChanged(state, animate)
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg, true)
    }

    override fun onServiceConnected(service: ISagerNetService) = changeState(
        try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
    )

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) VpnRequestActivity.showPermissionHelp(this)
    }

    // may NOT called when app is in background
    // ONLY do UI update here, write DB in bg process
    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        _speed.value = stats.txRateProxy to stats.rxRateProxy
        onSpeedUpdated(stats.txRateProxy, stats.rxRateProxy)
    }

    override fun cbTrafficUpdate(data: TrafficData) {
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(data)
        }
    }

    override fun cbSelectorUpdate(id: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        _selectedProxy.value = id
        onSelectedProxyChanged(id)
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.SHOW_BOTTOM_BAR -> onBottomBarPreferenceChanged()
            Key.SERVICE_MODE -> onBinderDied()
            Key.PROFILE_ID -> _selectedProxy.value = DataStore.selectedProxy
            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
                if (DataStore.serviceState.canStop) {
                    snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                        SagerNet.reloadService()
                    }.show()
                }
            }
        }
    }

    override fun onStart() {
        if (hostInitialized) connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
    }

    override fun onStop() {
        if (hostInitialized) connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!hostInitialized) return
        // When switching interfaces the next activity may already have registered itself.
        if (GroupManager.userInterface === groupInterface) GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

}
