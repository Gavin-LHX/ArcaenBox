package io.nekohasekai.sagernet.update

import com.google.gson.JsonParser
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

data class CoreAsset(val name: String, val size: Long, val sha256: String)
data class CoreManifest(val channel: String, val version: String, val revision: Long, val bridge: String, val assets: Map<String, CoreAsset>) {
    val displayVersion get() = "$version / r$revision"
    companion object {
        fun verify(bytes: ByteArray, signature: ByteArray, publicKey: ByteArray, bridge: String, channel: String): CoreManifest {
            return try { verifyUnchecked(bytes,signature,publicKey,bridge,channel) }
            catch (e: UpdateException) { throw e }
            catch (_: java.security.GeneralSecurityException) { throw UpdateException("signature") }
            catch (_: RuntimeException) { throw UpdateException("invalid") }
        }
        private fun verifyUnchecked(bytes: ByteArray, signature: ByteArray, publicKey: ByteArray, bridge: String, channel: String): CoreManifest {
            if (bytes.size > 65536 || signature.size > 1024) throw UpdateException("invalid")
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKey))
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(key); verifier.update(bytes)
            if (!verifier.verify(signature)) throw UpdateException("signature")
            val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            if (root.get("schema").asInt != 1 || root.get("bridge").asString != bridge) throw UpdateException("incompatible")
            if (root.get("channel").asString != channel || channel !in listOf("stable","preview")) throw UpdateException("invalid")
            val version = root.get("version").asString
            val parsed = ReleaseVersion.parse(version) ?: throw UpdateException("invalid")
            if (channel == "stable" && parsed.preview.isNotEmpty()) throw UpdateException("invalid")
            val revision = root.get("revision").asLong
            if (revision < 1) throw UpdateException("invalid")
            val assets = root.getAsJsonObject("assets").entrySet().associate { (abi,raw) ->
                val item = raw.asJsonObject
                val name = item.get("name").asString; val hash = item.get("sha256").asString; val size = item.get("size").asLong
                if (!name.matches(Regex("[a-zA-Z0-9._-]+\\.so")) || !hash.matches(Regex("[0-9a-f]{64}")) || size !in 1024..100L*1024*1024) throw UpdateException("invalid")
                abi to CoreAsset(name,size,hash)
            }
            return CoreManifest(channel,version,revision,bridge,assets)
        }
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(32768); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer,0,n) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun checkBinary(file: File, asset: CoreAsset) {
            if (file.length() != asset.size || sha256(file) != asset.sha256) throw UpdateException("checksum")
        }
    }
}
