package io.legado.app.help.book.library

import android.util.Base64
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object LibraryCloudCrypto {

    private const val VERSION = 1
    private const val PREFIX = "LEGADO_LIBRARY_AES_GCM:"
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    data class EncryptedPayload(
        val version: Int = VERSION,
        val salt: String = "",
        val nonce: String = "",
        val cipherText: String = ""
    )

    fun encodeJson(value: Any, password: String?): ByteArray {
        val json = GSON.toJson(value)
        if (password.isNullOrBlank()) return json.toByteArray()
        return (PREFIX + GSON.toJson(encrypt(json.toByteArray(), password))).toByteArray()
    }

    fun decodeString(bytes: ByteArray, password: String?): String {
        val raw = String(bytes)
        if (!raw.startsWith(PREFIX)) return raw
        require(!password.isNullOrBlank()) { "云端内容已加密，请先设置密码" }
        val payload = GSON.fromJsonObject<EncryptedPayload>(raw.removePrefix(PREFIX))
            .getOrThrow()
        return String(decrypt(payload, password))
    }

    private fun encrypt(bytes: ByteArray, password: String): EncryptedPayload {
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_BITS, nonce))
        return EncryptedPayload(
            salt = salt.b64(),
            nonce = nonce.b64(),
            cipherText = cipher.doFinal(bytes).b64()
        )
    }

    private fun decrypt(payload: EncryptedPayload, password: String): ByteArray {
        val salt = payload.salt.fromB64()
        val nonce = payload.nonce.fromB64()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(payload.cipherText.fromB64())
    }

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.fromB64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
}
