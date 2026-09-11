package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.core.app.ActivityCompat
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.Theme
import moe.matsuri.nb4a.ui.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class SettingsPreferenceFragment : PreferenceFragmentCompat() {

    private lateinit var isProxyApps: SwitchPreference

    private lateinit var globalCustomConfig: EditConfigPreference
    private var dnsEditorSnapshot: List<String>? = null


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listView.layoutManager = FixedLinearLayoutManager(listView)
    }

    private val reloadListener = Preference.OnPreferenceChangeListener { _, _ ->
        needReload()
        true
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        DataStore.initGlobal()
        addPreferencesFromResource(R.xml.global_preferences)

        findPreference<Preference>("systemVpnSettings")!!.setOnPreferenceClickListener {
            VpnRequestActivity.openSettings(requireContext())
            true
        }
        findPreference<Preference>("resourceFiles")!!.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), AssetsActivity::class.java))
            true
        }
        fun number(key: String, range: IntRange) {
            findPreference<EditTextPreference>(key)!!.apply {
                setOnBindEditTextListener { it.inputType = EditorInfo.TYPE_CLASS_NUMBER }
                if (key == "connectionTestConcurrency" && text == null) text = DataStore.connectionTestConcurrent.toString()
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
                setOnPreferenceChangeListener { _, value ->
                    val valid = value.toString().toIntOrNull()?.let { it in range } == true
                    if (valid) needReload() else snackbar(R.string.invalid_setting).show()
                    valid
                }
            }
        }
        number("udpTimeout", 0..86400)
        number("tlsFragmentDelay", 1..1000)
        number("globalMuxStreams", 1..1024)
        number("connectionTestConcurrency", 1..32)
        number("nodeTestTimeout", 2..60)
        number("speedTestLimitMiB", 1..256)
        number("udpTestPort", 1..65535)
        number("secondMixedPort", 1..65535)
        number("dnsQueryTimeout", 1..60)
        number("dnsCacheCapacity", 1024..65536)
        fun presets(key: String, values: List<String>) {
            findPreference<TestPresetPreference>(key)!!.apply {
                presets = values.map { it to it }
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            }
        }
        presets("connectionTestConcurrency", (1..8).map(Int::toString) + listOf("16", "32"))
        presets("nodeTestTimeout", listOf("5", "10", "15", "20", "25", "30", "60"))
        presets("speedTestLimitMiB", listOf("1", "5", "10", "20", "50", "100"))
        presets("speedTestURL", listOf(
            "https://speed.cloudflare.com/__down?bytes=10000000",
            "https://speed.cloudflare.com/__down?bytes=50000000",
            "https://speed.cloudflare.com/__down?bytes=99999999",
            "https://cachefly.cachefly.net/1mb.test", "https://cachefly.cachefly.net/10mb.test",
            "https://cachefly.cachefly.net/50mb.test", "https://cachefly.cachefly.net/100mb.test"))
        presets("connectionTestURL", listOf("https://www.gstatic.com/generate_204",
            "https://www.google.com/generate_204", "https://www.youtube.com/generate_204",
            "https://www.googlevideo.com/generate_204", "https://cp.cloudflare.com/generate_204",
            "https://www.apple.com/library/test/success.html", "http://www.msftconnecttest.com/connecttest.txt"))
        presets("udpTestHost", listOf("ntp:pool.ntp.org", "ntp:time.google.com", "ntp:time.cloudflare.com",
            "dns:1.1.1.1", "dns:8.8.8.8", "dns:dns.google", "stun:stun.voztovoice.org",
            "stun:stun.cloudflare.com", "stun:stun.l.google.com:19302",
            "mcbe:pms.mc-complex.com", "mcbe:bedrock.opblocks.com", "mcbe:play.craftersmc.net"))
        presets("exitIpURL", listOf("https://api.ipify.org", "https://api64.ipify.org",
            "https://api.ip.sb/geoip", "https://api-ipv4.ip.sb/geoip", "https://api-ipv6.ip.sb/geoip", "https://api.ipapi.is"))
        findPreference<EditTextPreference>("connectionTestURL")!!.setOnPreferenceChangeListener { _, value ->
            val url = value.toString().toHttpUrlOrNull()
            val valid = url != null && url.username.isEmpty() && url.password.isEmpty()
            if (!valid) snackbar(R.string.invalid_setting).show()
            valid
        }
        listOf("dnsCache", "dnsOptimistic", "dnsBlockAAAA", "dnsBlockHttps", "dnsSystemHosts", "customDnsEnabled",
            "coreCacheFile", "protocolSniffers", "defaultFingerprint", "inboundUsername", "inboundPassword", "secondMixedEnabled")
            .forEach { findPreference<Preference>(it)!!.onPreferenceChangeListener = reloadListener }
        findPreference<EditTextPreference>("inboundPassword")!!.setOnBindEditTextListener {
            it.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
        }
        findPreference<EditTextPreference>("dnsHosts")!!.apply {
            setOnBindEditTextListener { it.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE }
            setOnPreferenceChangeListener { _, value ->
                val valid = runCatching { io.nekohasekai.sagernet.fmt.AdvancedOptions.parseHosts(value.toString()) }.isSuccess
                if (valid) needReload() else snackbar(R.string.invalid_setting).show()
                valid
            }
        }
        findPreference<EditTextPreference>("bootstrapDns")!!.setOnPreferenceChangeListener { _, value ->
            val valid = value.toString().isBlank() || runCatching { io.nekohasekai.sagernet.fmt.AdvancedOptions.validateIp(value.toString().trim()) }.isSuccess
            if (valid) needReload() else snackbar(R.string.invalid_setting).show()
            valid
        }
        listOf("customDnsVpn", "customDnsProxy").forEach { key ->
            findPreference<EditConfigPreference>(key)!!.apply { useConfigStore(key); onPreferenceChangeListener = reloadListener }
        }
        findPreference<Preference>("customDnsDefaults")!!.setOnPreferenceClickListener {
            val template = """{"servers":[{"type":"udp","tag":"dns-direct","server":"223.5.5.5"},{"type":"https","tag":"dns-remote","server":"1.1.1.1","path":"/dns-query","detour":"proxy"}],"final":"dns-remote"}"""
            if (DataStore.customDnsVpn.isBlank()) DataStore.customDnsVpn = template
            if (DataStore.customDnsProxy.isBlank()) DataStore.customDnsProxy = template
            needReload()
            listOf("customDnsVpn", "customDnsProxy").forEach { key -> findPreference<EditConfigPreference>(key)!!.notifyChanged() }
            true
        }
        findPreference<EditTextPreference>("speedTestURL")!!.setOnPreferenceChangeListener { _, value ->
            val url = value.toString().toHttpUrlOrNull()
            val valid = url?.isHttps == true && url.username.isEmpty() && url.password.isEmpty()
            if (!valid) snackbar(R.string.invalid_setting).show()
            valid
        }
        findPreference<EditTextPreference>("udpTestHost")!!.setOnPreferenceChangeListener { _, value ->
            val valid = runCatching { io.nekohasekai.sagernet.bg.proto.UdpTestTarget.parse(value.toString(), DataStore.udpTestPort) }.isSuccess
            if (!valid) snackbar(R.string.invalid_setting).show()
            valid
        }
        listOf("tlsFragment", "globalMux", "globalMuxProtocol", "globalMuxPadding", "showExitIp",
            "domain_strategy_for_remote", "domain_strategy_for_direct", "domain_strategy_for_server")
            .forEach { findPreference<Preference>(it)!!.onPreferenceChangeListener = reloadListener }
        findPreference<Preference>("destinationStrategy")!!.setOnPreferenceChangeListener { _, value ->
            DataStore.resolveDestination = value.toString().isNotEmpty()
            findPreference<SwitchPreference>(Key.RESOLVE_DESTINATION)!!.isChecked = DataStore.resolveDestination
            needReload()
            true
        }
        findPreference<EditTextPreference>("exitIpURL")!!.setOnPreferenceChangeListener { _, value ->
            val url = value.toString().toHttpUrlOrNull()
            val valid = url?.isHttps == true && url.username.isEmpty() && url.password.isEmpty()
            if (!valid) snackbar(R.string.invalid_setting).show()
            valid
        }

        val appTheme = findPreference<ColorPickerPreference>(Key.APP_THEME)!!
        appTheme.setOnPreferenceChangeListener { _, newTheme ->
            if (DataStore.serviceState.started) {
                SagerNet.reloadService()
            }
            val theme = Theme.getTheme(newTheme as Int)
            app.setTheme(theme)
            requireActivity().apply {
                setTheme(theme)
                ActivityCompat.recreate(this)
            }
            true
        }

        val nightTheme = findPreference<SimpleMenuPreference>(Key.NIGHT_THEME)!!
        nightTheme.setOnPreferenceChangeListener { _, newTheme ->
            Theme.currentNightMode = (newTheme as String).toInt()
            Theme.applyNightTheme()
            true
        }
        val mixedPort = findPreference<EditTextPreference>(Key.MIXED_PORT)!!
        val serviceMode = findPreference<Preference>(Key.SERVICE_MODE)!!
        val allowAccess = findPreference<Preference>(Key.ALLOW_ACCESS)!!
        val appendHttpProxy = findPreference<SwitchPreference>(Key.APPEND_HTTP_PROXY)!!

        val showDirectSpeed = findPreference<SwitchPreference>(Key.SHOW_DIRECT_SPEED)!!
        val ipv6Mode = findPreference<Preference>(Key.IPV6_MODE)!!
        val trafficSniffing = findPreference<Preference>(Key.TRAFFIC_SNIFFING)!!

        val bypassLan = findPreference<SwitchPreference>(Key.BYPASS_LAN)!!
        val bypassLanInCore = findPreference<SwitchPreference>(Key.BYPASS_LAN_IN_CORE)!!

        val remoteDns = findPreference<EditTextPreference>(Key.REMOTE_DNS)!!
        val directDns = findPreference<EditTextPreference>(Key.DIRECT_DNS)!!
        val enableDnsRouting = findPreference<SwitchPreference>(Key.ENABLE_DNS_ROUTING)!!
        val enableFakeDns = findPreference<SwitchPreference>(Key.ENABLE_FAKEDNS)!!

        val logLevel = findPreference<LongClickListPreference>(Key.LOG_LEVEL)!!
        val mtu = findPreference<MTUPreference>(Key.MTU)!!
        globalCustomConfig = findPreference(Key.GLOBAL_CUSTOM_CONFIG)!!
        globalCustomConfig.useConfigStore(Key.GLOBAL_CUSTOM_CONFIG)

        logLevel.dialogLayoutResource = R.layout.layout_loglevel_help
        logLevel.setOnPreferenceChangeListener { _, _ ->
            needRestart()
            true
        }
        logLevel.setOnLongClickListener {
            if (context == null) return@setOnLongClickListener true

            val view = EditText(context).apply {
                inputType = EditorInfo.TYPE_CLASS_NUMBER
                var size = DataStore.logBufSize
                if (size == 0) size = 50
                setText(size.toString())
            }

            MaterialAlertDialogBuilder(requireContext()).setTitle("Log buffer size (kb)")
                .setView(view)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    DataStore.logBufSize = view.text.toString().toInt()
                    if (DataStore.logBufSize <= 0) DataStore.logBufSize = 50
                    needRestart()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        mixedPort.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)

        val metedNetwork = findPreference<Preference>(Key.METERED_NETWORK)!!
        if (Build.VERSION.SDK_INT < 28) {
            metedNetwork.remove()
        }
        isProxyApps = findPreference(Key.PROXY_APPS)!!
        isProxyApps.setOnPreferenceChangeListener { _, newValue ->
            startActivity(Intent(activity, AppManagerActivity::class.java))
            if (newValue as Boolean) DataStore.dirty = true
            newValue
        }

        val profileTrafficStatistics =
            findPreference<SwitchPreference>(Key.PROFILE_TRAFFIC_STATISTICS)!!
        val speedInterval = findPreference<SimpleMenuPreference>(Key.SPEED_INTERVAL)!!
        profileTrafficStatistics.isEnabled = speedInterval.value.toString() != "0"
        speedInterval.setOnPreferenceChangeListener { _, newValue ->
            profileTrafficStatistics.isEnabled = newValue.toString() != "0"
            needReload()
            true
        }

        serviceMode.setOnPreferenceChangeListener { _, _ ->
            if (DataStore.serviceState.started) SagerNet.stopService()
            true
        }

        val tunImplementation = findPreference<SimpleMenuPreference>(Key.TUN_IMPLEMENTATION)!!
        val resolveDestination = findPreference<SwitchPreference>(Key.RESOLVE_DESTINATION)!!
        val acquireWakeLock = findPreference<SwitchPreference>(Key.ACQUIRE_WAKE_LOCK)!!
        val enableClashAPI = findPreference<SwitchPreference>(Key.ENABLE_CLASH_API)!!
        enableClashAPI.setOnPreferenceChangeListener { _, newValue ->
            (activity as MainActivity?)?.refreshNavMenu(newValue as Boolean)
            needReload()
            true
        }

        mixedPort.onPreferenceChangeListener = reloadListener
        appendHttpProxy.onPreferenceChangeListener = reloadListener
        showDirectSpeed.onPreferenceChangeListener = reloadListener
        trafficSniffing.onPreferenceChangeListener = reloadListener
        bypassLan.onPreferenceChangeListener = reloadListener
        bypassLanInCore.onPreferenceChangeListener = reloadListener
        mtu.onPreferenceChangeListener = reloadListener

        enableFakeDns.onPreferenceChangeListener = reloadListener
        remoteDns.onPreferenceChangeListener = reloadListener
        directDns.onPreferenceChangeListener = reloadListener
        enableDnsRouting.onPreferenceChangeListener = reloadListener

        ipv6Mode.onPreferenceChangeListener = reloadListener
        allowAccess.onPreferenceChangeListener = reloadListener

        resolveDestination.onPreferenceChangeListener = reloadListener
        tunImplementation.onPreferenceChangeListener = reloadListener
        acquireWakeLock.onPreferenceChangeListener = reloadListener
        globalCustomConfig.onPreferenceChangeListener = reloadListener
    }

    override fun onPause() {
        dnsEditorSnapshot = listOf(DataStore.customDnsVpn, DataStore.customDnsProxy)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        val dnsValues = listOf(DataStore.customDnsVpn, DataStore.customDnsProxy)
        if (dnsEditorSnapshot != null && dnsEditorSnapshot != dnsValues) needReload()
        dnsEditorSnapshot = dnsValues

        if (::isProxyApps.isInitialized) {
            isProxyApps.isChecked = DataStore.proxyApps
        }
        if (::globalCustomConfig.isInitialized) {
            globalCustomConfig.notifyChanged()
        }
        listOf("customDnsVpn", "customDnsProxy").forEach { findPreference<EditConfigPreference>(it)?.notifyChanged() }
    }

}
