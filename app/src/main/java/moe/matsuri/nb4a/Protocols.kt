package moe.matsuri.nb4a

import android.content.Context
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_NEKO
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr
import moe.matsuri.nb4a.proxy.config.ConfigBean

// Settings for all protocols, built-in or plugin
object Protocols {

    // Deduplication

    class Deduplication(bean: AbstractBean, private val type: String) {
        // Compare complete connection settings while ignoring only the display name.
        // Address and port alone collapse different credentials, transports and Snell versions.
        private val bytes = io.nekohasekai.sagernet.fmt.KryoConverters.serialize(bean.clone().apply { name = "" })
        override fun hashCode() = 31 * type.hashCode() + bytes.contentHashCode()
        override fun equals(other: Any?) = other is Deduplication && type == other.type && bytes.contentEquals(other.bytes)
    }

    // Display

    fun Context.getProtocolColor(type: Int): Int {
        return when (type) {
            TYPE_NEKO -> getColorAttr(android.R.attr.textColorPrimary)
            else -> getColorAttr(R.attr.accentOrTextSecondary)
        }
    }

    // Test

    fun genFriendlyMsg(msg: String): String {
        val msgL = msg.lowercase()
        return when {
            msgL.contains("timeout") || msgL.contains("deadline") -> {
                app.getString(R.string.connection_test_timeout)
            }

            msgL.contains("refused") || msgL.contains("closed pipe") -> {
                app.getString(R.string.connection_test_refused)
            }

            else -> msg
        }
    }

}