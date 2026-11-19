package io.github.mukhtharcm.simbridge

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * SMS, with simbridge as the phone's default SMS app.
 *
 * It has to be the default app: Android hides the stored copy of a message from the app that sent
 * it unless that app is the default one, and reserves marking read to it. The price is that Android
 * no longer stores anything by itself, so [SmsDeliverReceiver] stores what arrives and [send]
 * stores what goes out. Everything is kept in Android's own message store, so another SMS app
 * taking over later finds the full history.
 */
object Sms {
    private const val TAG = "simbridge"
    private const val SENT = "io.github.mukhtharcm.simbridge.SMS_SENT"
    private val STORE = Uri.parse("content://sms")
    private val COLUMNS = arrayOf("_id", "thread_id", "address", "body", "date", "type", "read")
    private const val TYPE_INBOX = 1
    private const val TYPE_SENT = 2
    private const val TYPE_DRAFT = 3
    private const val TYPE_OUTBOX = 4
    private const val TYPE_FAILED = 5

    private lateinit var app: Context
    private var lastId = 0L
    private var sends = 0

    fun start(context: Context) {
        app = context.applicationContext
        val handler = Handler(HandlerThread("sb-sms").apply { start() }.looper)
        handler.post {
            try {
                lastId = query(null, null, "_id DESC LIMIT 1") { it.getLong(0) }.firstOrNull() ?: 0
                app.contentResolver.registerContentObserver(STORE, true, object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) = announceNew()
                })
                Log.i(TAG, "watching SMS from message $lastId")
            } catch (e: SecurityException) {
                Log.e(TAG, "SMS is off: READ_SMS is not granted")
            }
        }
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val ok = resultCode == Activity.RESULT_OK
                // The stored copy was filed as "on its way"; file it under its outcome.
                // The row travels as text, not as the intent's address: an intent addressed to a
                // content: row gets that row's type attached, and then no longer matches this receiver.
                val row = intent.getStringExtra("row")?.let(Uri::parse)
                row?.let { row ->
                    try {
                        app.contentResolver.update(
                            row, ContentValues().apply { put("type", if (ok) TYPE_SENT else TYPE_FAILED) }, null, null,
                        )
                    } catch (e: Exception) { Log.w(TAG, "could not file the sent message: $e") }
                }
                val status = JSONObject().put("event", "sms_status").put("status", if (ok) "sent" else "failed")
                row?.lastPathSegment?.let { status.put("id", "m$it") }
                if (intent.hasExtra("ref")) status.put("ref", JSONObject(intent.getStringExtra("ref")!!).get("ref"))
                Hub.event(status)
            }
        }, IntentFilter(SENT), Context.RECEIVER_NOT_EXPORTED)
    }

    /** Tells clients about every message stored since the last look, received or sent. */
    private fun announceNew() {
        val fresh = try {
            query("_id > ? AND type != $TYPE_DRAFT", arrayOf(lastId.toString()), "_id ASC", ::message)
        } catch (e: SecurityException) { return }
        for (m in fresh) {
            lastId = m.getString("id").drop(1).toLong()
            Hub.event(m.put("event", "sms"))
        }
    }

    /** Conversations, newest first, each with its latest message. */
    fun threads(): JSONArray {
        val seen = HashSet<String>()
        val out = JSONArray()
        // ponytail: scans the newest 5000 messages for one per conversation; older conversations are
        // not listed. Query the conversations table instead if that ever matters.
        for (m in query("type != $TYPE_DRAFT", null, "date DESC LIMIT 5000", ::message)) {
            if (seen.add(m.getString("thread"))) out.put(m)
        }
        return out
    }

    /** Messages of one conversation, newest first, up to [limit], older than message [before] if given. */
    fun messages(thread: String, before: String?, limit: Int): JSONArray {
        val where = StringBuilder("thread_id = ? AND type != $TYPE_DRAFT")
        val args = mutableListOf(thread.removePrefix("t").toLong().toString())
        if (before != null) { where.append(" AND _id < ?"); args += before.removePrefix("m").toLong().toString() }
        val rows = query(where.toString(), args.toTypedArray(), "_id DESC LIMIT ${limit.coerceIn(1, 200)}", ::message)
        return JSONArray(rows)
    }

    /** Marks every received message of one conversation as read. */
    fun markRead(thread: String) {
        app.contentResolver.update(
            STORE, ContentValues().apply { put("read", 1); put("seen", 1) },
            "thread_id = ? AND read = 0", arrayOf(thread.removePrefix("t").toLong().toString()),
        )
    }

    /**
     * Stores a message as "on its way" and hands it to the network; returns the stored message's id.
     * The outcome arrives later as an `sms_status` event.
     */
    fun send(to: String, text: String, ref: Any?): String {
        val row = app.contentResolver.insert(STORE, ContentValues().apply {
            put("address", to); put("body", text); put("date", System.currentTimeMillis())
            put("read", 1); put("seen", 1); put("type", TYPE_OUTBOX)
        }) ?: throw IllegalStateException("the message store refused the message; is simbridge the default SMS app?")
        val sms = app.getSystemService(SmsManager::class.java)
        val parts = sms.divideMessage(text)
        // Only the last part reports, so one message gives one status.
        val report = Intent(SENT).setPackage(app.packageName).putExtra("row", row.toString())
        if (ref != null) report.putExtra("ref", JSONObject().put("ref", ref).toString())
        val done = PendingIntent.getBroadcast(
            app, ++sends, report, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )
        val reports = ArrayList<PendingIntent?>(parts.map { null }).also { it[it.size - 1] = done }
        sms.sendMultipartTextMessage(to, null, parts, reports, null)
        return "m${row.lastPathSegment}"
    }

    /**
     * Stores a message that just arrived. Called by Android through [SmsDeliverReceiver], possibly
     * in a process that was started for this alone, so it relies on nothing [start] sets up.
     * If the store refuses it, the message is appended to a file instead of being lost.
     */
    fun storeIncoming(context: Context, intent: Intent) {
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val values = ContentValues().apply {
            put("address", parts[0].displayOriginatingAddress)
            put("body", parts.joinToString("") { it.displayMessageBody.orEmpty() })
            put("date", System.currentTimeMillis())
            put("date_sent", parts[0].timestampMillis)
            put("service_center", parts[0].serviceCenterAddress)
            put("read", 0); put("seen", 0); put("type", TYPE_INBOX)
            if (intent.hasExtra("subscription")) put("sub_id", intent.getIntExtra("subscription", -1))
        }
        try {
            checkNotNull(context.contentResolver.insert(STORE, values)) { "insert returned nothing" }
        } catch (e: Exception) {
            Log.e(TAG, "the message store refused an incoming SMS; keeping it in sms-unsaved.jsonl", e)
            File(context.filesDir, "sms-unsaved.jsonl").appendText(
                JSONObject().put("number", values.getAsString("address")).put("text", values.getAsString("body"))
                    .put("at", values.getAsLong("date") / 1000).toString() + "\n",
            )
        }
    }

    private fun message(c: Cursor) = JSONObject()
        .put("id", "m${c.getLong(0)}")
        .put("thread", "t${c.getLong(1)}")
        .put("number", c.getString(2).orEmpty())
        .put("text", c.getString(3).orEmpty())
        .put("at", c.getLong(4) / 1000)
        .put("out", c.getInt(5) != TYPE_INBOX)
        .put("failed", c.getInt(5) == TYPE_FAILED)
        .put("read", c.getInt(6) != 0)

    private fun <T> query(where: String?, args: Array<String>?, order: String, row: (Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        app.contentResolver.query(STORE, COLUMNS, where, args, order)?.use { c ->
            while (c.moveToNext()) out += row(c)
        }
        return out
    }
}

/** Android delivers every incoming SMS here, and only here, while simbridge is the default SMS app. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Sms.storeIncoming(context, intent)
        // Make sure the API is up to tell clients, for instance after a reboot.
        try { context.startForegroundService(Intent(context, BridgeService::class.java)) } catch (e: Exception) {
            Log.w("simbridge", "foreground service did not start: ${e.message}")
        }
    }
}

/**
 * Android requires a default SMS app to accept MMS notifications. simbridge has no MMS support:
 * the notification is logged and the MMS is not fetched.
 */
class MmsIgnoredReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.w("simbridge", "an MMS arrived and was ignored: MMS is not supported")
    }
}

/** Android requires a default SMS app to offer "reply to a call with a message". simbridge has no such screen. */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
