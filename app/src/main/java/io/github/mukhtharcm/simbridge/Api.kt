package io.github.mukhtharcm.simbridge

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshakeBuilder
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.concurrent.thread

/** The parts of the API that are plain rules, kept apart from Android so they can be tested. */
object Rules {
    private val NUMBER = Regex("""^\+?[0-9]{3,20}$""")

    /** A phone number and nothing else: codes with * or # (call forwarding, USSD) are refused. */
    fun dialable(number: String) = NUMBER.matches(number)

    /** The token in a request path such as "/v0?token=abc", or null when the path is not ours. */
    fun tokenIn(path: String): String? {
        if (!path.startsWith("/v0?")) return null
        return path.substringAfter('?').split('&').firstOrNull { it.startsWith("token=") }?.substringAfter('=')
    }

    /** Not empty, and at most ten SMS parts long. */
    fun sendable(text: String) = text.isNotBlank() && text.length <= 1530

    fun sameToken(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    fun endReason(cause: Int) = when (cause) {
        DisconnectCause.LOCAL -> "local"
        DisconnectCause.REMOTE -> "remote"
        DisconnectCause.REJECTED -> "rejected"
        DisconnectCause.MISSED -> "missed"
        DisconnectCause.BUSY -> "busy"
        else -> "error"
    }
}

/**
 * Where the call and the API meet: the one call that is up, the audio waiting to be played into
 * it, and the clients listening. See docs/API.md.
 */
object Hub {
    private const val TAG = "simbridge"
    private val silence = ShortArray(Pcm.FRAME)

    // ponytail: one call at a time; a second call replaces this one in the API's view.
    @Volatile var call: Call? = null
    @Volatile var callId = ""
    private var calls = 0
    @Volatile private var server: Server? = null
    private lateinit var app: Context

    /** Audio from the client, waiting to go into the call. */
    val toCall = JitterBuffer()

    fun newCall(c: Call): String {
        call = c
        toCall.reset()
        return "c${++calls}".also { callId = it }
    }

    fun event(json: JSONObject) {
        Log.i(TAG, "event $json")
        server?.broadcast(json.toString())
    }

    /** A frame of the caller's voice, on its way to the clients. */
    fun fromCall(frame: ShortArray, n: Int) {
        val s = server ?: return
        if (s.connections.isEmpty()) return
        val buf = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) buf.putShort(frame[i])
        s.broadcast(buf.array())
    }

    /** The next frame to play into the call; silence when the client has sent nothing. */
    fun forCall(): ShortArray = toCall.take() ?: silence

    /** Starts the server once the configured address exists, which for a VPN address can be well after boot. */
    fun start(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        thread(name = "sb-api") {
            val file = File(app.filesDir, "config.json")
            if (!file.exists()) { Log.w(TAG, "no config.json; the API is off"); return@thread }
            val cfg = JSONObject(file.readText())
            val token = cfg.getString("token")
            if (token.length < 16) { Log.e(TAG, "token too short; the API is off"); return@thread }
            val addr = InetAddress.getByName(cfg.optString("bind", "127.0.0.1"))
            while (NetworkInterface.getByInetAddress(addr) == null) Thread.sleep(5000)
            server = Server(InetSocketAddress(addr, cfg.optInt("port", 8088)), token).apply {
                isReuseAddr = true
                start()
            }
            Sms.start(app)
        }
    }

    private class Server(address: InetSocketAddress, private val token: String) : WebSocketServer(address) {
        override fun onWebsocketHandshakeReceivedAsServer(
            conn: WebSocket, draft: Draft, request: ClientHandshake,
        ): ServerHandshakeBuilder {
            val given = Rules.tokenIn(request.resourceDescriptor)
            if (given == null || !Rules.sameToken(given, token)) {
                throw InvalidDataException(CloseFrame.POLICY_VALIDATION, "bad token")
            }
            return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request)
        }

        override fun onStart() { Log.i(TAG, "API listening on $address") }
        override fun onError(conn: WebSocket?, e: Exception) { Log.w(TAG, "API error: $e") }
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            Log.i(TAG, "client connected: ${conn.remoteSocketAddress}")
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            Log.i(TAG, "client gone: $code")
            // Nobody is left to talk or to hang up, so do not leave the SIM on a call.
            if (connections.none { it !== conn && it.isOpen }) call?.let { hangUp(it) }
        }

        override fun onMessage(conn: WebSocket, audio: ByteBuffer) {
            val samples = audio.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            if (samples.remaining() != Pcm.FRAME) return
            toCall.put(ShortArray(Pcm.FRAME).also { samples.get(it) })
        }

        override fun onMessage(conn: WebSocket, text: String) {
            val reply = JSONObject()
            try {
                val msg = JSONObject(text)
                if (msg.has("ref")) reply.put("ref", msg.get("ref"))
                val error = command(msg, reply)
                reply.put("ok", error == null)
                if (error != null) reply.put("error", error)
            } catch (e: SecurityException) {
                Log.w(TAG, "not permitted: $e")
                reply.put("ok", false).put("error", "no_permission")
            } catch (e: Exception) {
                Log.w(TAG, "bad command: $e")
                reply.put("ok", false).put("error", "bad_request")
            }
            conn.send(reply.toString())
        }
    }

    /** Runs one command; returns null when it worked, or the error name. Results go into [reply]. */
    private fun command(msg: JSONObject, reply: JSONObject): String? {
        val c = call
        when (msg.getString("cmd")) {
            "dial" -> {
                val number = msg.getString("number")
                if (!Rules.dialable(number)) return "bad_number"
                if (c != null) return "busy"
                app.getSystemService(TelecomManager::class.java)
                    .placeCall(Uri.fromParts("tel", number, null), Bundle())
            }
            "answer" -> {
                if (c == null || msg.optString("id") != callId) return "no_such_call"
                c.answer(0)
            }
            "hangup" -> {
                if (c == null || msg.optString("id") != callId) return "no_such_call"
                hangUp(c)
            }
            "dtmf" -> {
                if (c == null || msg.optString("id") != callId) return "no_such_call"
                val digits = msg.getString("digits")
                if (!digits.all { it in "0123456789*#" } || digits.length > 32) return "bad_digits"
                thread { for (d in digits) { c.playDtmfTone(d); Thread.sleep(150); c.stopDtmfTone(); Thread.sleep(80) } }
            }
            "sms_send" -> {
                val to = msg.getString("to")
                val text = msg.getString("text")
                if (!Rules.dialable(to)) return "bad_number"
                if (!Rules.sendable(text)) return "bad_text"
                reply.put("id", Sms.send(to, text, msg.opt("ref")))
            }
            "sms_read" -> Sms.markRead(msg.getString("thread"))
            "sms_threads" -> reply.put("threads", Sms.threads())
            "sms_messages" -> reply.put(
                "messages",
                Sms.messages(msg.getString("thread"), msg.optString("before").ifEmpty { null }, msg.optInt("limit", 50)),
            )
            else -> return "unknown_command"
        }
        return null
    }

    private fun hangUp(c: Call) {
        @Suppress("DEPRECATION")
        if (c.state == Call.STATE_RINGING) c.reject(false, null) else c.disconnect()
    }
}
