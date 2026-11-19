package io.github.mukhtharcm.simbridge

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Web Push: tells a browser or an installed web client about a call or a text while it is closed.
 *
 * The message goes through the browser maker's push service (Google, Apple, Mozilla), encrypted so
 * that only the browser can read it (RFC 8291). The bridge identifies itself with its own key pair
 * (VAPID, RFC 8292), made on first use and kept in the app's private files.
 */
object Push {
    private const val TAG = "simbridge"
    // Push services of the browsers that support Web Push. A subscription pointing anywhere else is
    // refused, so a client cannot make the phone send requests to an address of its choosing.
    private val SERVICES = listOf("fcm.googleapis.com", "push.services.mozilla.com", "push.apple.com", "notify.windows.com")
    private val sender = Executors.newSingleThreadExecutor()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private lateinit var app: Context
    private lateinit var keys: KeyPair

    fun start(context: Context) {
        app = context.applicationContext
        val file = File(app.filesDir, "vapid.key")
        keys = if (file.exists()) {
            val (priv, pub) = file.readLines()
            val kf = KeyFactory.getInstance("EC")
            KeyPair(
                kf.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pub))),
                kf.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(priv))),
            )
        } else {
            newKeys().also {
                val enc = Base64.getEncoder()
                file.writeText(enc.encodeToString(it.private.encoded) + "\n" + enc.encodeToString(it.public.encoded) + "\n")
            }
        }
    }

    /** The public half of the bridge's key, which a browser needs to subscribe. */
    fun publicKey(): String = b64.encodeToString(raw(keys.public as ECPublicKey))

    /** Keeps a browser's subscription; returns null when it was kept, or the error name. */
    fun subscribe(subscription: JSONObject): String? {
        val endpoint = subscription.getString("endpoint")
        val url = URL(endpoint)
        if (url.protocol != "https" || SERVICES.none { url.host == it || url.host.endsWith(".$it") }) return "bad_endpoint"
        val p256dh = subscription.getJSONObject("keys").getString("p256dh")
        val auth = subscription.getJSONObject("keys").getString("auth")
        synchronized(this) {
            val subs = load().filter { it.getString("endpoint") != endpoint }.takeLast(19)
            save(subs + JSONObject().put("endpoint", endpoint).put("p256dh", p256dh).put("auth", auth))
        }
        return null
    }

    /** Sends a notification to every subscribed browser; returns how many there are. */
    fun send(title: String, body: String, tag: String, urgent: Boolean, ttlSeconds: Int): Int {
        val subs = synchronized(this) { load() }
        val payload = JSONObject().put("title", title).put("body", body.take(300)).put("tag", tag)
            .put("sticky", urgent).toString().toByteArray()
        for (sub in subs) sender.execute {
            try {
                val code = post(sub, payload, urgent, ttlSeconds)
                if (code == 404 || code == 410) forget(sub.getString("endpoint")) // the browser unsubscribed
                else if (code !in 200..299) Log.w(TAG, "push refused: $code")
            } catch (e: Exception) { Log.w(TAG, "push failed: ${e.javaClass.simpleName}") }
        }
        return subs.size
    }

    /** Pushes what a person would want to hear about while the client is closed. */
    fun forEvent(event: JSONObject) {
        if (!::app.isInitialized) return
        val who = event.optString("name").ifEmpty { event.optString("number").ifEmpty { "Unknown caller" } }
        when {
            event.optString("event") == "call" && event.optString("state") == "ringing" ->
                send(who, "Incoming call", "call", urgent = true, ttlSeconds = 30)
            event.optString("event") == "call" && event.optString("reason") == "missed" ->
                send(who, "Missed call", "call", urgent = false, ttlSeconds = 86400)
            event.optString("event") == "sms" && !event.optBoolean("out") ->
                send(who, event.optString("text"), "sms-${event.optString("thread")}", urgent = false, ttlSeconds = 86400)
        }
    }

    private fun post(sub: JSONObject, payload: ByteArray, urgent: Boolean, ttl: Int): Int {
        val endpoint = URL(sub.getString("endpoint"))
        val body = encrypt(
            payload, Base64.getUrlDecoder().decode(sub.getString("p256dh")), Base64.getUrlDecoder().decode(sub.getString("auth")),
        )
        val audience = "${endpoint.protocol}://${endpoint.host}" + if (endpoint.port != -1) ":${endpoint.port}" else ""
        val conn = endpoint.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10_000; conn.readTimeout = 10_000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "vapid t=${jwt(audience)}, k=${publicKey()}")
        conn.setRequestProperty("Content-Encoding", "aes128gcm")
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.setRequestProperty("TTL", ttl.toString())
        conn.setRequestProperty("Urgency", if (urgent) "high" else "normal")
        conn.outputStream.use { it.write(body) }
        return conn.responseCode.also { conn.disconnect() }
    }

    /** The VAPID token: a JWT signed with the bridge's key, valid for twelve hours. */
    internal fun jwt(audience: String, key: KeyPair = keys): String {
        val header = b64.encodeToString("""{"typ":"JWT","alg":"ES256"}""".toByteArray())
        val exp = System.currentTimeMillis() / 1000 + 12 * 3600
        // Written by hand: the audience is a push service's origin, which holds no quotes.
        val claims = b64.encodeToString(
            """{"aud":"$audience","exp":$exp,"sub":"https://github.com/mukhtharcm/simbridge"}""".toByteArray(),
        )
        val signer = Signature.getInstance("SHA256withECDSA").apply { initSign(key.private); update("$header.$claims".toByteArray()) }
        return "$header.$claims.${b64.encodeToString(derToRaw(signer.sign()))}"
    }

    /** Encrypts a push message for one browser, as RFC 8291 lays out. */
    internal fun encrypt(
        payload: ByteArray, uaPublic: ByteArray, authSecret: ByteArray,
        salt: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }, ephemeral: KeyPair = newKeys(),
    ): ByteArray {
        val asPublic = raw(ephemeral.public as ECPublicKey)
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(ephemeral.private); doPhase(point(uaPublic, ephemeral.public as ECPublicKey), true); generateSecret()
        }
        val ikm = hmac(hmac(authSecret, shared), "WebPush: info\u0000".toByteArray() + uaPublic + asPublic + byteArrayOf(1))
        val prk = hmac(salt, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm\u0000".toByteArray() + byteArrayOf(1)).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce\u0000".toByteArray() + byteArrayOf(1)).copyOf(12)
        val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
            doFinal(payload + byteArrayOf(2)) // 2 marks the last (and only) record
        }
        return ByteBuffer.allocate(16 + 4 + 1 + asPublic.size + sealed.size)
            .put(salt).putInt(4096).put(asPublic.size.toByte()).put(asPublic).put(sealed).array()
    }

    internal fun newKeys(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** A public key as the 65 bytes browsers use: 4, then x and y. */
    internal fun raw(key: ECPublicKey): ByteArray = byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)

    internal fun point(raw: ByteArray, like: ECPublicKey) = KeyFactory.getInstance("EC").generatePublic(
        ECPublicKeySpec(ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65))), like.params),
    )

    internal fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** Java signs ECDSA as DER; a JWT wants the two 32-byte numbers side by side. */
    internal fun derToRaw(der: ByteArray): ByteArray {
        var i = 2 + if (der[1].toInt() and 0x80 != 0) der[1].toInt() and 0x7f else 0
        fun next(): ByteArray {
            val len = der[i + 1].toInt(); val n = der.copyOfRange(i + 2, i + 2 + len); i += 2 + len
            return fixed(BigInteger(1, n))
        }
        return next() + next()
    }

    private fun fixed(n: BigInteger): ByteArray {
        val b = n.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return ByteArray(32 - b.size) + b
    }

    private fun load(): List<JSONObject> {
        val f = File(app.filesDir, "push.json")
        if (!f.exists()) return emptyList()
        val a = JSONArray(f.readText())
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    private fun save(subs: List<JSONObject>) = File(app.filesDir, "push.json").writeText(JSONArray(subs).toString())

    private fun forget(endpoint: String) = synchronized(this) { save(load().filter { it.getString("endpoint") != endpoint }) }
}
