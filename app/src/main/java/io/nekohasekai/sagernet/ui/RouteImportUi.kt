package io.nekohasekai.sagernet.ui

import android.content.ClipboardManager
import android.content.Context
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.*
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.ktx.readableMessage
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

class RouteImportUi(private val fragment: Fragment, private val changed: () -> Unit) {
    private val file = fragment.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load { fragment.requireContext().contentResolver.openInputStream(uri)!!.use { read(it) } }
    }
    private val export = fragment.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) fragment.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val rules = if (DataStore.routePreset == "custom") SagerDatabase.rulesDao.allRules() else RoutePresets.rules(DataStore.routePreset)
                    val profiles = SagerDatabase.proxyDao.getAll().associateBy { it.id }
                    val output = JsonObject().apply {
                        addProperty("format", "arcaenbox-route-rules")
                        addProperty("version", 1)
                        add("rules", JsonArray().apply {
                            rules.forEach { rule ->
                                val item = Gson().toJsonTree(rule).asJsonObject
                                item.remove("id"); item.remove("userOrder")
                                item.addProperty("outbound", when (rule.outbound) {
                                    0L -> "proxy"; -1L -> "direct"; -2L -> "block"
                                    else -> profiles[rule.outbound]?.displayName() ?: error("Missing outbound for ${rule.name}")
                                })
                                add(item)
                            }
                        })
                    }
                    fragment.requireContext().contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(GsonBuilder().setPrettyPrinting().create().toJson(output)) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error(e) }
        }
    }

    fun open() {
        MaterialAlertDialogBuilder(fragment.requireContext()).setTitle(R.string.route_import)
            .setItems(R.array.route_import_sources) { _, source -> when (source) {
                0 -> file.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                1 -> {
                    val clipboard = fragment.requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(fragment.requireContext())?.toString().orEmpty()
                    load { require(text.toByteArray().size <= LIMIT); text }
                }
                2 -> url()
            } }.show()
    }

    fun export() = export.launch("ArcaenBox-routes.json")

    private fun url() {
        val edit = EditText(fragment.requireContext()).apply { hint = "https://"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        val dialog = MaterialAlertDialogBuilder(fragment.requireContext()).setTitle(R.string.route_import_url).setView(edit)
            .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val link = edit.text.toString().trim().toHttpUrlOrNull()
                if (link?.isHttps != true || link.username.isNotEmpty() || link.password.isNotEmpty()) {
                    edit.error = fragment.getString(R.string.invalid_setting)
                } else {
                    dialog.dismiss()
                    load {
                        val builder = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).followSslRedirects(false)
                        if (DataStore.serviceState.connected) builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", DataStore.mixedPort)))
                        val client = builder.build()
                        try {
                            client.newCall(Request.Builder().url(link).build()).execute().use { response ->
                                check(response.isSuccessful) { "HTTP ${response.code}" }
                                response.body!!.byteStream().use { read(it) }
                            }
                        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun load(reader: () -> String) {
        fragment.lifecycleScope.launch {
            try {
                val rules = withContext(Dispatchers.IO) {
                    decode(reader(), SagerDatabase.proxyDao.getAll())
                }
                MaterialAlertDialogBuilder(fragment.requireContext()).setTitle(fragment.getString(R.string.route_import_count, rules.size))
                    .setMessage(fragment.getString(R.string.route_import_help) + "\n\n" + rules.take(20).joinToString("\n") { "${it.name} → ${it.displayOutbound()}" })
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.route_import) { _, _ ->
                        fragment.lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    SagerDatabase.instance.runInTransaction {
                                        var order = SagerDatabase.rulesDao.nextOrder() ?: 1L
                                        rules.forEach { it.id = 0; it.userOrder = order++; SagerDatabase.rulesDao.createRule(it) }
                                    }
                                    DataStore.routePreset = "custom"
                                }
                                changed()
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error(e) }
                        }
                    }.show()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error(e) }
        }
    }

    private fun error(e: Exception) {
        if (fragment.isAdded) MaterialAlertDialogBuilder(fragment.requireContext()).setTitle(R.string.error_title)
            .setMessage(e.readableMessage).setPositiveButton(android.R.string.ok, null).show()
    }

    companion object {
        const val LIMIT = 2 * 1024 * 1024
        private fun read(input: InputStream): String {
            val bytes = input.readBytesBounded()
            return String(bytes, Charsets.UTF_8)
        }
        private fun InputStream.readBytesBounded(): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { val count = read(buffer); if (count < 0) break; require(output.size() + count <= LIMIT) { "Route file exceeds 2 MiB" }; output.write(buffer, 0, count) }
            return output.toByteArray()
        }

        fun decode(text: String, profiles: List<ProxyEntity>): List<RuleEntity> {
            val root = JsonParser.parseString(text)
            val own = root.isJsonObject && root.asJsonObject.get("format")?.asString == "arcaenbox-route-rules"
            if (own) require(root.asJsonObject.get("version")?.asInt == 1) { "Unsupported route export version" }
            val container = when {
                root.isJsonObject && root.asJsonObject.has("routing") -> root.asJsonObject.getAsJsonObject("routing")
                root.isJsonObject && root.asJsonObject.has("route") -> root.asJsonObject.getAsJsonObject("route")
                else -> root
            }
            val list = if (container.isJsonArray) container.asJsonArray else container.asJsonObject.getAsJsonArray("rules")
            require(list != null && list.size() in 1..1000) { "Expected an array of 1–1000 routing rules" }
            return list.mapIndexed { index, element ->
                val item = element.asJsonObject
                fun field(key: String): String {
                    val value = item.get(key) ?: return ""
                    return if (value.isJsonArray) value.asJsonArray.joinToString("\n") { it.asString } else if (value.isJsonNull) "" else value.asString
                }
                val name = field("name").ifBlank { field("remarks") }.ifBlank { "Imported rule ${index + 1}" }
                val supported = if (own) setOf("name", "config", "enabled", "domains", "ip", "port", "sourcePort", "network", "source", "protocol", "outbound", "packages")
                    else setOf("name", "remarks", "enabled", "type", "domain", "domain_suffix", "domain_keyword", "domain_regex", "ip", "ip_cidr", "port", "port_range", "sourcePort", "source_port", "source_port_range", "network", "source", "source_ip_cidr", "protocol", "outbound", "outboundTag", "action", "inbound", "inboundTag", "package_name")
                val unknown = item.keySet() - supported
                require(unknown.isEmpty()) { "$name: unsupported fields ${unknown.joinToString()}. Windows process rules must be recreated with Android app packages." }
                if (!own) require(field("type") in listOf("", "field", "default")) { "$name: unsupported rule type" }
                require(field("action") in listOf("", "route", "reject")) { "$name: unsupported routing action" }
                val target = field("outboundTag").ifBlank { field("outbound") }.ifBlank { if (field("action") == "reject") "block" else "proxy" }
                val outbound = when (target) {
                    "proxy" -> 0L; "direct", "bypass" -> -1L; "block", "reject" -> -2L
                    else -> {
                        val matches = profiles.filter { it.displayName() == target }
                        require(matches.size == 1) { "$name: outbound '$target' must match exactly one existing node" }
                        matches.single().id
                    }
                }
                val rule = RuleEntity(name = name, enabled = item.get("enabled")?.asBoolean ?: true, outbound = outbound)
                if (own) {
                    rule.domains = field("domains"); rule.config = field("config")
                    if (rule.config.isNotBlank()) JsonParser.parseString(rule.config).asJsonObject
                    rule.packages = field("packages").lines().filter { it.isNotBlank() }.toSet()
                } else {
                    val xray = item.has("outboundTag") || field("type") == "field"
                    rule.domains = listOf(field("domain").lines().filter { it.isNotBlank() }.joinToString("\n") { if (xray) it else "full:$it" },
                        field("domain_suffix").lines().filter { it.isNotBlank() }.joinToString("\n") { "domain:${it.trimStart('.')}" },
                        field("domain_keyword").lines().filter { it.isNotBlank() }.joinToString("\n") { "keyword:$it" },
                        field("domain_regex").lines().filter { it.isNotBlank() }.joinToString("\n") { "regexp:$it" }).filter { it.isNotBlank() }.joinToString("\n")
                    rule.packages = field("package_name").lines().filter { it.isNotBlank() }.toSet()
                    val inbounds = field("inboundTag").ifBlank { field("inbound") }.lines().filter { it.isNotBlank() }.map {
                        when (it) { "tun", "tun-in" -> "tun-in"; "socks", "http", "mixed", "mixed-in" -> "mixed-in"; else -> error("$name: unsupported inbound $it") }
                    }
                    if (inbounds.isNotEmpty()) rule.config = JsonObject().apply { add("inbound", Gson().toJsonTree(inbounds.distinct())) }.toString()
                }
                rule.ip = field("ip").ifBlank { field("ip_cidr") }
                fun ports(vararg keys: String): String = keys.map { field(it) }.filter { it.isNotBlank() }.joinToString("\n").replace('-', ':').also { value ->
                    value.split(Regex("[\\s,]+" )).filter { it.isNotBlank() }.forEach { range ->
                        val numbers = range.split(':').map { it.toIntOrNull() ?: error("$name: invalid port") }
                        require(numbers.size in 1..2 && numbers.all { it in 1..65535 } && numbers.first() <= numbers.last()) { "$name: invalid port range" }
                    }
                }
                rule.port = ports("port", "port_range"); rule.sourcePort = ports("sourcePort", "source_port", "source_port_range")
                rule.source = field("source").ifBlank { field("source_ip_cidr") }
                rule.protocol = field("protocol")
                val networks = field("network").split(Regex("[\\s,]+" )).filter { it.isNotBlank() }.toSet()
                require(networks.all { it == "tcp" || it == "udp" }) { "$name: invalid network" }
                rule.network = if (networks.size == 1) networks.first() else ""
                require(listOf(rule.domains, rule.ip, rule.port, rule.sourcePort, rule.source, rule.protocol, rule.config, rule.network).any { it.isNotBlank() } || rule.packages.isNotEmpty()) {
                    "$name: empty catch-all rule. Choose a routing preset for the default policy."
                }
                rule
            }
        }
    }
}
