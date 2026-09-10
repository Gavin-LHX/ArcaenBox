package io.nekohasekai.sagernet.update

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

data class CoreState(var channel: String = "stable", val installed: MutableMap<String,String> = mutableMapOf(), val enabled: MutableMap<String,String> = mutableMapOf())
data class CoreChoice(val channel: String, val version: String, val revision: Long, val installed: Boolean, val path: File)
data class CoreUpdate(val manifest: CoreManifest, val metadata: ByteArray, val signature: ByteArray, val downloadUrl: String)

/** No Go references here: this runs from Seq's class initializer, before JNI is available. */
object CoreRuntime {
    private lateinit var context: Context
    private var loaded = false
    lateinit var active: CoreChoice
        private set
    var recovered = false
        private set
    private val gson = Gson()
    private val root get() = File(context.noBackupFilesDir,"cores").apply { mkdirs() }
    private val bootMarker get() = File(root, "boot-${if (moe.matsuri.nb4a.utils.JavaUtil.getProcessName().endsWith(":bg")) "bg" else "main"}.json")
    fun initialize(context: Context) { this.context = context }
    val bridge: String get() = bundled().get("bridge").asString
    private fun bundled() = context.assets.open("cores/bundled.json").bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
    private fun publicKey() = context.assets.open("cores/public-key.der").use { it.readBytes() }

    val abi: String get() {
        val header = ByteArray(20)
        File(context.applicationInfo.nativeLibraryDir,"libgojni.so").inputStream().use { if (it.read(header) != header.size) throw UpdateException("incompatible") }
        return when ((header[18].toInt() and 255) or ((header[19].toInt() and 255) shl 8)) {
            183 -> "arm64-v8a"; 40 -> "armeabi-v7a"; 62 -> "x86_64"; 3 -> "x86"; else -> throw UpdateException("incompatible")
        }
    }

    private inline fun <T> locked(action: () -> T): T = synchronized(this) {
        RandomAccessFile(File(root,".lock"),"rw").use { file -> file.channel.lock().use { action() } }
    }
    private fun readState(): CoreState = try {
        val state = AtomicFile(File(root,"state.json")).openRead().use { gson.fromJson(it.bufferedReader(),CoreState::class.java) }
        require(state.channel in listOf("stable","preview"))
        require(state.installed.values.all { it.matches(Regex("[0-9a-f]{64}")) })
        require(state.enabled.values.all { it.matches(Regex("[0-9a-f]{64}")) })
        state
    } catch (_: Exception) { CoreState() }
    private fun writeState(state: CoreState) {
        val file = AtomicFile(File(root,"state.json")); val output = file.startWrite()
        try { output.write(gson.toJson(state).toByteArray()); file.finishWrite(output) } catch (e: Exception) { file.failWrite(output); throw e }
    }
    private fun builtIn(channel: String): CoreChoice {
        val info = bundled().getAsJsonObject(channel)
        return CoreChoice(channel, info.get("version").asString, info.get("revision").asLong, false,
            File(context.applicationInfo.nativeLibraryDir,if (channel == "preview") "libgojni_preview.so" else "libgojni.so"))
    }
    private fun installed(channel: String, id: String): CoreChoice {
        require(id.matches(Regex("[0-9a-f]{64}")))
        val dir = File(root,"packages/$id")
        val metadata = File(dir,"manifest.json").readBytes()
        if (CoreManifest.sha256(metadata) != id) throw UpdateException("checksum")
        val manifest = CoreManifest.verify(metadata,File(dir,"manifest.sig").readBytes(),publicKey(),bridge,channel)
        val asset = manifest.assets[abi] ?: throw UpdateException("incompatible")
        val binary = File(dir,"libgojni.so")
        CoreManifest.checkBinary(binary,asset)
        return CoreChoice(channel,manifest.version,manifest.revision,true,binary)
    }
    fun choice(channel: String): CoreChoice = locked {
        readState().installed[channel]?.let { id -> runCatching { installed(channel,id) }.getOrNull() } ?: builtIn(channel)
    }
    fun selectedChannel(): String = locked { readState().channel }

    @JvmStatic fun load() = locked {
        if (loaded) return@locked
        var state = readState()
        if (bootMarker.exists()) {
            // A previous initialization did not complete (including a native crash).
            state.channel = "stable"
            state.installed.clear()
            state.enabled.clear()
            writeState(state)
            recovered = true
        }
        active = state.enabled[state.channel]?.let { id ->
            try { installed(state.channel,id) } catch (_: Exception) {
                state.installed.remove(state.channel); state.enabled.remove(state.channel); writeState(state); recovered = true; null
            }
        } ?: builtIn(state.channel)
        bootMarker.writeText("${active.channel}:${active.version}:${active.revision}")
        // Never load a second Go runtime in this process, even when the first fails to initialize.
        System.load(active.path.absolutePath)
        loaded = true
    }
    fun markReady() = locked { bootMarker.delete(); Unit }

    fun select(channel: String, restoreBundled: Boolean = false) = locked {
        require(channel in listOf("stable","preview"))
        val state = readState(); state.channel = channel
        if (restoreBundled) { state.installed.remove(channel); state.enabled.remove(channel) }
        else { state.installed[channel]?.let { state.enabled[channel] = it } ?: state.enabled.remove(channel) }
        writeState(state)
    }

    suspend fun check(channel: String): CoreUpdate? {
        val releases = ReleaseService.releases().filter { it.tag.startsWith("core-$channel-") && it.assets.any { a -> a.name == "manifest.json" } }
        var best: CoreUpdate? = null
        var incompatible = false
        for (release in releases.take(20)) {
            val metadataAsset = release.assets.first { it.name == "manifest.json" }
            val signatureAsset = release.assets.firstOrNull { it.name == "manifest.sig" } ?: continue
            val metadata = ReleaseService.fetch(metadataAsset.url,65536)
            val signature = ReleaseService.fetch(signatureAsset.url,1024)
            val manifest = try { CoreManifest.verify(metadata,signature,publicKey(),bridge,channel) } catch (e: UpdateException) {
                if (e.reason == "incompatible") { incompatible = true; continue } else throw e
            }
            val asset = manifest.assets[abi] ?: continue
            val binary = release.assets.firstOrNull { it.name == asset.name } ?: continue
            val candidate = CoreUpdate(manifest,metadata,signature,binary.url)
            if (best == null || newer(manifest.version,manifest.revision,best!!.manifest.version,best!!.manifest.revision)) best = candidate
        }
        if (best == null && incompatible) throw UpdateException("incompatible")
        val current = choice(channel)
        return best?.takeIf { newer(it.manifest.version,it.manifest.revision,current.version,current.revision) }
    }
    private fun newer(a: String, ar: Long, b: String, br: Long): Boolean {
        val av = ReleaseVersion.parse(a) ?: return false; val bv = ReleaseVersion.parse(b) ?: return true
        return av > bv || av == bv && ar > br
    }
    suspend fun install(update: CoreUpdate, progress: (Int) -> Unit) {
        val asset = update.manifest.assets[abi] ?: throw UpdateException("incompatible")
        val id = CoreManifest.sha256(update.metadata)
        val stage = File(root,"staging-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val binary = File(stage,"libgojni.so")
            ReleaseService.download(update.downloadUrl,binary,asset.size,progress)
            CoreManifest.checkBinary(binary,asset)
            CoreManifest.verify(update.metadata,update.signature,publicKey(),bridge,update.manifest.channel)
            File(stage,"manifest.json").writeBytes(update.metadata)
            File(stage,"manifest.sig").writeBytes(update.signature)
            // Downloaded executable code is immutable before Android's loader opens it.
            if (!binary.setReadOnly()) throw UpdateException("storage")
            locked {
                val destination = File(root,"packages/$id")
                destination.parentFile!!.mkdirs()
                if (destination.exists()) { installed(update.manifest.channel,id) }
                else if (!stage.renameTo(destination)) throw UpdateException("storage")
                val state = readState(); state.installed[update.manifest.channel] = id; writeState(state)
            }
        } finally { stage.deleteRecursively() }
    }
}
