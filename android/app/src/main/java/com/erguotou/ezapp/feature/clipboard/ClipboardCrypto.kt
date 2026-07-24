package com.erguotou.ezapp.feature.clipboard

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.spec.X509EncodedKeySpec

object ClipboardCrypto {
    private const val AAD = "ez-clipboard-v1"
    private val x25519Prefix = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
        0x6e, 0x03, 0x21, 0x00,
    )
    private val secureRandom = SecureRandom()

    fun createKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("X25519").generateKeyPair()

    fun publicKeyBase64(keyPair: KeyPair): String =
        keyPair.public.encoded.takeLast(32).toByteArray().toBase64()

    fun deriveSessionKey(keyPair: KeyPair, peerPublicKeyBase64: String): ByteArray {
        val peerRaw = peerPublicKeyBase64.fromBase64()
        require(peerRaw.size == 32) { "电脑身份无效" }
        val peerPublic = KeyFactory.getInstance("X25519")
            .generatePublic(X509EncodedKeySpec(x25519Prefix + peerRaw))
        val agreement = KeyAgreement.getInstance("X25519")
        agreement.init(keyPair.private)
        agreement.doPhase(peerPublic, true)
        return hkdfSha256(agreement.generateSecret(), 32)
    }

    fun encryptText(
        sessionKey: ByteArray,
        text: String,
        updatedAt: Long = System.currentTimeMillis(),
        id: String = UUID.randomUUID().toString(),
        senderId: String? = null,
    ): String {
        val nonce = ByteArray(12).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(sessionKey, "AES"),
            GCMParameterSpec(128, nonce),
        )
        cipher.updateAAD(AAD.toByteArray())
        val encrypted = cipher.doFinal(text.toByteArray(StandardCharsets.UTF_8))
        return JSONObject()
            .put("type", "clip")
            .put("id", id)
            .put("updatedAt", updatedAt)
            .put("nonce", nonce.toBase64())
            .put("data", encrypted.toBase64())
            .apply { senderId?.let { put("senderId", it) } }
            .toString()
    }

    fun mqttTopic(username: String): String {
        val channel = Base64.encodeToString(
            username.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE,
        )
        return "ez-clipboard/v1/u/$channel/clips"
    }

    fun mqttKey(username: String, password: String): ByteArray {
        val seed = "$username\u0000$password".toByteArray(StandardCharsets.UTF_8)
        return hkdfSha256(
            seed,
            32,
            "$MQTT_KEY_SALT|$username".toByteArray(StandardCharsets.UTF_8),
        )
    }

    fun decryptText(sessionKey: ByteArray, message: JSONObject): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(sessionKey, "AES"),
            GCMParameterSpec(128, message.getString("nonce").fromBase64()),
        )
        cipher.updateAAD(AAD.toByteArray())
        return String(cipher.doFinal(message.getString("data").fromBase64()), StandardCharsets.UTF_8)
    }

    private fun hkdfSha256(
        input: ByteArray,
        size: Int,
        salt: ByteArray = AAD.toByteArray(StandardCharsets.UTF_8),
    ): ByteArray {
        val extract = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(salt, "HmacSHA256"))
        }.doFinal(input)
        val info = "clipboard-key".toByteArray(StandardCharsets.UTF_8)
        val output = ArrayList<Byte>(size)
        var previous = byteArrayOf()
        var counter = 1
        while (output.size < size) {
            previous = Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(extract, "HmacSHA256"))
            }.doFinal(previous + info + counter.toByte())
            output.addAll(previous.toList())
            counter++
        }
        return output.take(size).toByteArray()
    }

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private const val MQTT_KEY_SALT =
        "ez-clipboard-mqtt-v1::c53e2b1394a74c83b5ad7f61d28e04cc"
}
