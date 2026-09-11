package io.nekohasekai.sagernet.update

import com.google.gson.JsonParser
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

data class ComponentManifest(
    val component: String, val channel: String, val version: String, val revision: Long,
    val minSdk: Int, val protocols: Set<Int>, val assets: Map<String, CoreAsset>
) {
    companion object {
        val ids = setOf("trojan-go", "naive", "mieru", "snell")
        fun verify(bytes: ByteArray, signature: ByteArray, publicKey: ByteArray, component: String, channel: String): ComponentManifest {
            try {
                if (bytes.size > 65536 || signature.size > 1024) throw UpdateException("invalid")
                val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKey))
                val verifier = Signature.getInstance("SHA256withRSA")
                verifier.initVerify(key); verifier.update(bytes)
                if (!verifier.verify(signature)) throw UpdateException("signature")
                val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
                if (root["schema"].asInt != 1 || root["contract"].asInt != 1) throw UpdateException("incompatible")
                if (component !in ids || channel !in listOf("stable", "preview") ||
                    root["component"].asString != component || root["channel"].asString != channel) throw UpdateException("invalid")
                val version = root["version"].asString
                val revision = root["revision"].asLong
                val minSdk = root["min_sdk"].asInt
                // Upstreams use different version schemes. A signed, per-channel revision orders releases.
                if (version.isBlank() || version.length > 120 || version.any { it.isISOControl() } || revision < 1 || minSdk < 21) throw UpdateException("invalid")
                val protocols = root.getAsJsonArray("protocols").map { it.asInt }.toSet()
                if (protocols.any { it !in 4..6 } || (component == "snell" && protocols.isEmpty())) throw UpdateException("invalid")
                val assets = root.getAsJsonObject("assets").entrySet().associate { (abi, raw) ->
                    val item = raw.asJsonObject
                    val name = item["name"].asString; val size = item["size"].asLong; val hash = item["sha256"].asString
                    if (abi !in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") ||
                        !name.matches(Regex("[a-zA-Z0-9._-]+\\.so")) || name.contains("..") ||
                        size !in 1024..100L*1024*1024 || !hash.matches(Regex("[0-9a-f]{64}"))) throw UpdateException("invalid")
                    abi to CoreAsset(name, size, hash)
                }
                return ComponentManifest(component, channel, version, revision, minSdk, protocols, assets)
            } catch (e: UpdateException) { throw e }
            catch (_: java.security.GeneralSecurityException) { throw UpdateException("signature") }
            catch (_: RuntimeException) { throw UpdateException("invalid") }
        }
    }
}
