package io.github.mukhtharcm.simbridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PushTest {
    /** Decrypts as the browser does (RFC 8291, receiving side). */
    private fun browserDecrypts(body: ByteArray, browser: java.security.KeyPair, auth: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(body)
        val salt = ByteArray(16).also { buf.get(it) }
        assertEquals(4096, buf.int)
        val asPublic = ByteArray(buf.get().toInt()).also { buf.get(it) }
        val sealed = ByteArray(buf.remaining()).also { buf.get(it) }
        val uaPublic = Push.raw(browser.public as ECPublicKey)
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(browser.private); doPhase(Push.point(asPublic, browser.public as ECPublicKey), true); generateSecret()
        }
        val ikm = Push.hmac(Push.hmac(auth, shared), "WebPush: info\u0000".toByteArray() + uaPublic + asPublic + byteArrayOf(1))
        val prk = Push.hmac(salt, ikm)
        val cek = Push.hmac(prk, "Content-Encoding: aes128gcm\u0000".toByteArray() + byteArrayOf(1)).copyOf(16)
        val nonce = Push.hmac(prk, "Content-Encoding: nonce\u0000".toByteArray() + byteArrayOf(1)).copyOf(12)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce)); doFinal(sealed)
        }
        assertEquals("last record delimiter", 2, plain.last().toInt())
        return plain.copyOf(plain.size - 1)
    }

    @Test fun aBrowserCanReadWhatIsSent() {
        val browser = Push.newKeys()
        val auth = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val message = """{"title":"+15550100123","body":"Incoming call"}""".toByteArray()
        val body = Push.encrypt(message, Push.raw(browser.public as ECPublicKey), auth)
        assertEquals(65, body[20].toInt())
        assertArrayEquals(message, browserDecrypts(body, browser, auth))
    }

    @Test fun publicKeysAre65BytesStartingWith4() {
        val raw = Push.raw(Push.newKeys().public as ECPublicKey)
        assertEquals(65, raw.size)
        assertEquals(4, raw[0].toInt())
    }

    @Test fun theTokenIsSignedWithTheBridgesKey() {
        val keys = Push.newKeys()
        val (header, claims, sig) = Push.jwt("https://fcm.googleapis.com", keys).split(".")
        val dec = Base64.getUrlDecoder()
        assertTrue(String(dec.decode(claims)).contains("\"aud\":\"https://fcm.googleapis.com\""))
        val raw = dec.decode(sig)
        assertEquals(64, raw.size)
        // Back to DER, which is what Java checks.
        fun int(b: ByteArray) = BigInteger(1, b).toByteArray().let { byteArrayOf(2, it.size.toByte()) + it }
        val seq = int(raw.copyOf(32)) + int(raw.copyOfRange(32, 64))
        val der = byteArrayOf(0x30, seq.size.toByte()) + seq
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(keys.public); update("$header.$claims".toByteArray()); verify(der)
        }
        assertTrue(ok)
    }
}
