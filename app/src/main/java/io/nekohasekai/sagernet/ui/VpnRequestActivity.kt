package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.net.Uri
import android.provider.Settings
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.broadcastReceiver

class VpnRequestActivity : AppCompatActivity() {
    private var receiver: BroadcastReceiver? = null
    private var requested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requested = savedInstanceState?.getBoolean("requested") ?: false
        if (requested) return
        if (getSystemService<KeyguardManager>()!!.isKeyguardLocked) {
            receiver = broadcastReceiver { _, _ -> request() }
            if (SDK_INT >= 33) {
                registerReceiver(
                    receiver,
                    IntentFilter(Intent.ACTION_USER_PRESENT),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT))
            }
        } else request()
    }

    private fun request() {
        if (requested) return
        requested = true
        connect.launch(null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("requested", requested)
        super.onSaveInstanceState(outState)
    }

    private val connect = registerForActivityResult(StartService()) {
        if (it) showPermissionHelp(this) { finish() } else finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (receiver != null) unregisterReceiver(receiver)
    }

    class StartService : ActivityResultContract<Void?, Boolean>() {
        private var cachedIntent: Intent? = null

        override fun getSynchronousResult(
            context: Context,
            input: Void?,
        ): SynchronousResult<Boolean>? {
            try {
                if (DataStore.serviceMode == Key.MODE_VPN) VpnService.prepare(context)?.let { intent ->
                    cachedIntent = intent
                    return null
                }
                SagerNet.startService()
                return SynchronousResult(false)
            } catch (e: Exception) {
                Logs.w(e)
                return SynchronousResult(true)
            }
        }

        override fun createIntent(context: Context, input: Void?) =
            cachedIntent!!.also { cachedIntent = null }

        override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
            // OEM permission screens can return CANCELLED even after authorization.
            // Conversely, RESULT_OK alone does not mean this app still owns the VPN.
            val prepared = runCatching {
                VpnService.prepare(SagerNet.application) == null
            }.getOrDefault(false)
            return if (prepared) {
                SagerNet.startService()
                false
            } else {
                Logs.w("VPN authorization unavailable (result=$resultCode)")
                true
            }
        }
    }

    companion object {
        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_VPN_SETTINGS)
            runCatching { context.startActivity(intent) }.onFailure {
                context.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        fun showPermissionHelp(context: Context, onDismiss: () -> Unit = {}) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.vpn_permission_denied)
                .setMessage(R.string.vpn_permission_help)
                .setPositiveButton(R.string.system_vpn_settings) { _, _ -> openSettings(context) }
                .setNeutralButton(R.string.app_permission_settings) { _, _ ->
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener { onDismiss() }
                .show()
        }
    }

}
