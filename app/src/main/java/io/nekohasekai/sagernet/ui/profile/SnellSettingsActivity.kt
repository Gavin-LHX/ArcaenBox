package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.snell.validate
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import android.widget.Toast
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class SnellSettingsActivity : ProfileSettingsActivity<SnellBean>() {
    override fun createEntity() = SnellBean().applyDefaultValues()

    override suspend fun saveAndExit() {
        try {
            // Validate a copy before stopping the active connection or touching the database.
            createEntity().apply { serialize() }
            super.saveAndExit()
        } catch (error: IllegalArgumentException) {
            onMainDispatcher { Toast.makeText(this@SnellSettingsActivity, error.message, Toast.LENGTH_LONG).show() }
        }
    }

    override fun SnellBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverPassword = psk
        DataStore.protocolVersion = version
        DataStore.snellUDP = udp
        DataStore.snellReuse = reuse
        DataStore.serverObfs = obfs
        DataStore.snellObfsHost = obfsHost
        DataStore.snellMode = mode
    }

    override fun SnellBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        psk = DataStore.serverPassword
        version = DataStore.protocolVersion
        udp = DataStore.snellUDP
        reuse = DataStore.snellReuse
        obfs = if (version == 6) "none" else DataStore.serverObfs
        obfsHost = DataStore.snellObfsHost
        mode = if (version == 6) DataStore.snellMode else "default"
        validate()
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.snell_preferences)
        findPreference<EditTextPreference>(Key.SERVER_PORT)!!
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.summaryProvider = PasswordSummaryProvider
        val obfs = findPreference<SimpleMenuPreference>(Key.SERVER_OBFS)!!
        val host = findPreference<EditTextPreference>("snellObfsHost")!!
        val version = findPreference<SimpleMenuPreference>(Key.PROTOCOL_VERSION)!!
        val mode = findPreference<SimpleMenuPreference>("snellMode")!!
        fun refresh(v: String) {
            mode.isVisible = v == "6"
            obfs.isVisible = v != "6"
            host.isVisible = v != "6" && obfs.value != "none"
        }
        refresh(version.value)
        version.setOnPreferenceChangeListener { _, value -> refresh(value.toString()); true }
        obfs.setOnPreferenceChangeListener { _, value -> host.isVisible = version.value != "6" && value != "none"; true }
    }
}
