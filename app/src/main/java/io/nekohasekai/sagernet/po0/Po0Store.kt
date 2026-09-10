package io.nekohasekai.sagernet.po0

import android.content.Context
import android.security.KeyPairGeneratorSpec
import android.util.AtomicFile
import android.util.Base64
import com.google.gson.Gson
import java.io.File
import java.io.RandomAccessFile
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Calendar
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

data class Po0State(
    val encryptedTokens: String = "",
    val automatic: Boolean = false,
    val revision: String = "",
    val checkedAt: Long = 0,
    val results: List<Po0Result> = emptyList(),
)

/** Atomic, cross-process storage outside Android backup and application configuration exports. */
object Po0Store {
    private val gson = Gson()
    private const val ALIAS = "arcaenbox.po0.tokens.v1"

    @Synchronized
    private fun <T> locked(context: Context, block: (AtomicFile, Po0State) -> T): T {
        val directory = File(context.noBackupFilesDir, "po0").apply { mkdirs() }
        return RandomAccessFile(File(directory, "state.lock"), "rw").use { file ->
            file.channel.lock().use {
                val atomic = AtomicFile(File(directory, "state.json"))
                val state = if (atomic.baseFile.exists()) {
                    gson.fromJson(atomic.openRead().bufferedReader().use { it.readText() }, Po0State::class.java)
                } else Po0State()
                block(atomic, state)
            }
        }
    }

    private fun write(file: AtomicFile, state: Po0State) {
        val stream = file.startWrite()
        try {
            stream.write(gson.toJson(state).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    fun state(context: Context): Po0State = locked(context) { _, state -> state }
    fun tokens(context: Context): String = locked(context) { _, state ->
        if (state.encryptedTokens.isEmpty()) "" else decrypt(state.encryptedTokens)
    }

    fun configuration(context: Context): Pair<Po0State, String> = locked(context) { _, state ->
        state to if (state.encryptedTokens.isEmpty()) "" else decrypt(state.encryptedTokens)
    }

    fun save(context: Context, tokens: String, automatic: Boolean): Po0State = locked(context) { file, _ ->
        val normalized = Po0Protocol.tokens(tokens).joinToString(",") { it.value + (it.slot?.let { n -> "@$n" } ?: "") }
        require(!automatic || normalized.isNotEmpty())
        Po0State(if (normalized.isEmpty()) "" else encrypt(context, normalized), automatic, UUID.randomUUID().toString())
            .also { write(file, it) }
    }

    fun record(context: Context, revision: String, results: List<Po0Result>) = locked(context) { file, state ->
        if (state.revision == revision) write(file, state.copy(checkedAt = System.currentTimeMillis(), results = results))
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    @Suppress("DEPRECATION")
    private fun encrypt(context: Context, text: String): String {
        val store = keyStore()
        if (!store.containsAlias(ALIAS)) {
            // RSA wraps a fresh AES key; this also supports Android 5, before Keystore AES support.
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }
            KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply {
                initialize(KeyPairGeneratorSpec.Builder(context).setAlias(ALIAS).setKeySize(2048)
                    .setSubject(X500Principal("CN=ArcaenBox Po0"))
                    .setSerialNumber(BigInteger.ONE).setStartDate(start.time).setEndDate(end.time).build())
            }.generateKeyPair()
        }
        val key = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val aes = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        }
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
            init(Cipher.ENCRYPT_MODE, keyStore().getCertificate(ALIAS).publicKey)
        }
        return listOf(rsa.doFinal(key), iv, aes.doFinal(text.toByteArray(Charsets.UTF_8))).joinToString(".", transform = ::encode)
    }

    private fun decrypt(text: String): String {
        val pieces = text.split('.').map(::decode)
        require(pieces.size == 3)
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
            init(Cipher.DECRYPT_MODE, keyStore().getKey(ALIAS, null))
        }
        val aes = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(rsa.doFinal(pieces[0]), "AES"), GCMParameterSpec(128, pieces[1]))
        }
        return String(aes.doFinal(pieces[2]), Charsets.UTF_8)
    }
}
