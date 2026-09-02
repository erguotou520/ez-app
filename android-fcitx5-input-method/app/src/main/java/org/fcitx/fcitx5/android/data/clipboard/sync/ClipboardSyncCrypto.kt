package org.fcitx.fcitx5.android.data.clipboard.sync

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ClipboardSyncCrypto {
    private const val AAD = "ez-clipboard-v1"
    private const val MQTT_KEY_SALT =
        "ez-clipboard-mqtt-v1::c53e2b1394a74c83b5ad7f61d28e04cc"
    private val random = SecureRandom()
    private val x25519Prefix = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
        0x6e, 0x03, 0x21, 0x00,
    )

    fun createKeyPair(): KeyPair = KeyPairGenerator.getInstance("X25519").generateKeyPair()

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
        return hkdfSha256(agreement.generateSecret(), AAD.toByteArray())
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
            "$MQTT_KEY_SALT|$username".toByteArray(StandardCharsets.UTF_8),
        )
    }

    fun encrypt(
        key: ByteArray,
        text: String,
        updatedAt: Long,
        senderId: String,
        id: String = UUID.randomUUID().toString(),
    ): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce),
        )
        cipher.updateAAD(AAD.toByteArray())
        return JSONObject()
            .put("type", "clip")
            .put("id", id)
            .put("updatedAt", updatedAt)
            .put("senderId", senderId)
            .put("nonce", nonce.toBase64())
            .put("data", cipher.doFinal(text.toByteArray()).toBase64())
            .toString()
    }

    fun decrypt(key: ByteArray, message: JSONObject): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, message.getString("nonce").fromBase64()),
        )
        cipher.updateAAD(AAD.toByteArray())
        return String(cipher.doFinal(message.getString("data").fromBase64()))
    }

    private fun hkdfSha256(input: ByteArray, salt: ByteArray): ByteArray {
        val extract = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(salt, "HmacSHA256"))
        }.doFinal(input)
        val info = "clipboard-key".toByteArray()
        val output = ArrayList<Byte>(32)
        var previous = byteArrayOf()
        var counter = 1
        while (output.size < 32) {
            previous = Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(extract, "HmacSHA256"))
            }.doFinal(previous + info + counter.toByte())
            output.addAll(previous.toList())
            counter++
        }
        return output.take(32).toByteArray()
    }

    private fun ByteArray.toBase64() = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.fromBase64() = Base64.decode(this, Base64.NO_WRAP)
}
