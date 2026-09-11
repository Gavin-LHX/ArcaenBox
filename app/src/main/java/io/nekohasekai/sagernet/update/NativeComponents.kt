package io.nekohasekai.sagernet.update

import android.os.Build
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.SagerNet
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

data class NativeState(
    val channels: MutableMap<String, String> = mutableMapOf(),
    val installed: MutableMap<String, String> = mutableMapOf(),
    val enabled: MutableMap<String, String> = mutableMapOf()
)
data class NativeChoice(val component: String, val channel: String, val version: String, val revision: Long,
    val path: File, val downloaded: Boolean, val minSdk: Int, val protocols: Set<Int>)
data class NativeUpdate(val manifest: ComponentManifest, val metadata: ByteArray, val signature: ByteArray, val url: String)

/** Independent channels and immutable packages, shared by the UI and VPN processes. */
object NativeComponents {
    val names = linkedMapOf("trojan-go" to "Trojan-Go", "naive" to "NaïveProxy", "mieru" to "Mieru", "snell" to "Snell")
    val plugins = mapOf("trojan-go-plugin" to "trojan-go", "naive-plugin" to "naive", "mieru-plugin" to "mieru", "snell-builtin" to "snell")
    private val context get() = SagerNet.application
    private val root get() = File(context.noBackupFilesDir, "components").apply { mkdirs() }
    private val gson = Gson()
    private fun publicKey() = context.assets.open("cores/public-key.der").use { it.readBytes() }
    private inline fun <T> locked(action: () -> T): T = synchronized(this) {
        RandomAccessFile(File(root, ".lock"), "rw").use { file -> file.channel.lock().use { action() } }
    }
    private fun readState(): NativeState = try {
        AtomicFile(File(root, "state.json")).openRead().use { gson.fromJson(it.bufferedReader(), NativeState::class.java) }.also { s ->
            require(s.channels.all { it.key in names && it.value in listOf("stable", "preview") })
            require(s.installed.all { it.key in names.keys.flatMap { id -> listOf("$id:stable", "$id:preview") } && it.value.matches(Regex("[0-9a-f]{64}")) })
            require(s.enabled.all { it.key in names && it.value.matches(Regex("[0-9a-f]{64}")) })
        }
    } catch (_: Exception) { NativeState() }
    private fun writeState(state: NativeState) {
        val file = AtomicFile(File(root, "state.json")); val output = file.startWrite()
        try { output.write(gson.toJson(state).toByteArray()); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    private fun builtin(id: String, channel: String): NativeChoice? {
        require(id in names && channel in listOf("stable", "preview"))
        val entries = context.assets.open("builtins/manifest.json").bufferedReader().use { JsonParser.parseReader(it).asJsonObject.getAsJsonArray("components") }
        val entry = entries.firstOrNull { it.asJsonObject["id"].asString == id && it.asJsonObject["channel"].asString == channel }?.asJsonObject ?: return null
        return NativeChoice(id, channel, entry["version"].asString, entry["revision"].asLong,
            File(context.applicationInfo.nativeLibraryDir, entry["library"].asString), false,
            entry["min_sdk"].asInt, entry.getAsJsonArray("protocols").map { it.asInt }.toSet())
    }
    private fun installed(id: String, channel: String, hash: String): NativeChoice {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        val dir = File(root, "packages/$hash")
        val metadata = File(dir, "manifest.json").readBytes()
        if (CoreManifest.sha256(metadata) != hash) throw UpdateException("checksum")
        val manifest = ComponentManifest.verify(metadata, File(dir, "manifest.sig").readBytes(), publicKey(), id, channel)
        if (Build.VERSION.SDK_INT < manifest.minSdk) throw UpdateException("incompatible")
        val asset = manifest.assets[CoreRuntime.abi] ?: throw UpdateException("incompatible")
        val file = File(dir, "libcomponent.so")
        CoreManifest.checkBinary(file, asset)
        checkElf(file)
        return NativeChoice(id, channel, manifest.version, manifest.revision, file, true, manifest.minSdk, manifest.protocols)
    }
    private fun checkElf(file: File) {
        val h = ByteArray(20)
        file.inputStream().use { if (it.read(h) != 20) throw UpdateException("incompatible") }
        val machine = mapOf("arm64-v8a" to 183, "armeabi-v7a" to 40, "x86_64" to 62, "x86" to 3)[CoreRuntime.abi]
        if (!h.take(4).toByteArray().contentEquals(byteArrayOf(127,69,76,70)) || h[5].toInt() != 1 ||
            h[16].toInt() != 3 || h[17].toInt() != 0 || (h[18].toInt() and 255) != machine || h[19].toInt() != 0) throw UpdateException("incompatible")
    }
    fun channel(id: String): String = locked { readState().channels[id] ?: "stable" }
    fun choice(id: String, channel: String): NativeChoice? = locked {
        readState().installed["$id:$channel"]?.let { runCatching { installed(id, channel, it) }.getOrNull() } ?: builtin(id, channel)
    }
    fun selected(id: String): NativeChoice = locked {
        val state = readState(); val channel = state.channels[id] ?: "stable"
        val hash = state.enabled[id]
        if (hash != null) {
            try { return@locked installed(id, channel, hash) } catch (_: Exception) {
                state.enabled.remove(id); state.channels[id] = "stable"; writeState(state)
                return@locked builtin(id, "stable") ?: throw UpdateException("incompatible")
            }
        }
        builtin(id, channel) ?: builtin(id, "stable") ?: throw UpdateException("incompatible")
    }
    fun select(id: String, channel: String, restore: Boolean = false) = locked {
        val state = readState(); val key = "$id:$channel"
        val hash = if (restore) null else state.installed[key]
        val target = hash?.let { installed(id, channel, it) } ?: builtin(id, channel) ?: throw UpdateException("component_unavailable")
        if (Build.VERSION.SDK_INT < target.minSdk) throw UpdateException("incompatible")
        state.channels[id] = channel
        if (restore) state.installed.remove(key)
        if (hash != null) state.enabled[id] = hash else state.enabled.remove(id)
        writeState(state)
    }
    fun command(choice: NativeChoice, vararg args: String): MutableList<String> {
        // Android 10 disallows execve of app-data files. The system linker maps our signed PIE.
        val loader = if (CoreRuntime.abi in listOf("arm64-v8a", "x86_64")) "/system/bin/linker64" else "/system/bin/linker"
        return (if (choice.downloaded) listOf(loader, choice.path.absolutePath) else listOf(choice.path.absolutePath)).toMutableList().apply { addAll(args) }
    }
    suspend fun check(id: String, channel: String): NativeUpdate? {
        require(id in names && channel in listOf("stable", "preview"))
        var best: NativeUpdate? = null
        var incompatible = false
        val releases = ReleaseService.releases().filter { it.tag.startsWith("component-$id-$channel-") && it.preview == (channel == "preview") }
        for (release in releases.take(30)) {
            val metadataAsset = release.assets.firstOrNull { it.name == "manifest.json" } ?: continue
            val signatureAsset = release.assets.firstOrNull { it.name == "manifest.sig" } ?: continue
            val metadata = ReleaseService.fetch(metadataAsset.url, 65536)
            val signature = ReleaseService.fetch(signatureAsset.url, 1024)
            val manifest = try { ComponentManifest.verify(metadata, signature, publicKey(), id, channel) }
                catch (e: UpdateException) { if (e.reason == "incompatible") { incompatible = true; continue } else throw e }
            if (Build.VERSION.SDK_INT < manifest.minSdk) { incompatible = true; continue }
            val asset = manifest.assets[CoreRuntime.abi] ?: continue
            val binary = release.assets.firstOrNull { it.name == asset.name } ?: continue
            if (best == null || manifest.revision > best!!.manifest.revision) best = NativeUpdate(manifest, metadata, signature, binary.url)
        }
        if (best == null && incompatible) throw UpdateException("incompatible")
        if (best == null && choice(id, channel) == null) throw UpdateException("component_unavailable")
        val current = choice(id, channel)
        return best?.takeIf { current == null || it.manifest.revision >= current.revision }
    }
    suspend fun install(update: NativeUpdate, progress: (Int) -> Unit) {
        val manifest = ComponentManifest.verify(update.metadata, update.signature, publicKey(), update.manifest.component, update.manifest.channel)
        if (Build.VERSION.SDK_INT < manifest.minSdk) throw UpdateException("incompatible")
        val asset = manifest.assets[CoreRuntime.abi] ?: throw UpdateException("incompatible")
        val hash = CoreManifest.sha256(update.metadata)
        val stage = File(root, "staging-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val binary = File(stage, "libcomponent.so")
            ReleaseService.download(update.url, binary, asset.size, progress)
            CoreManifest.checkBinary(binary, asset); checkElf(binary)
            File(stage, "manifest.json").writeBytes(update.metadata)
            File(stage, "manifest.sig").writeBytes(update.signature)
            if (!binary.setReadOnly()) throw UpdateException("storage")
            locked {
                val dir = File(root, "packages/$hash"); dir.parentFile!!.mkdirs()
                if (dir.exists()) {
                    try { installed(manifest.component, manifest.channel, hash) } catch (_: Exception) {
                        val rejected = File(root, "rejected-${UUID.randomUUID()}")
                        if (!dir.renameTo(rejected)) throw UpdateException("storage")
                        if (!stage.renameTo(dir)) { rejected.renameTo(dir); throw UpdateException("storage") }
                        rejected.deleteRecursively()
                    }
                } else if (!stage.renameTo(dir)) throw UpdateException("storage")
                val state = readState(); state.installed["${manifest.component}:${manifest.channel}"] = hash; writeState(state)
            }
        } finally { stage.deleteRecursively() }
    }
}
