package io.github.mukhtharcm.simbridge

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Names for numbers, and the call history, both from the phone's own stores, so they match what
 * the phone's other apps show. Contacts saved here have no account: they live on the phone only.
 */
object Phonebook {
    // number -> name, "" when there is none.
    // ponytail: cleared only when simbridge itself changes contacts; edits made in another app on the
    // phone show after a restart. Watch the contacts provider if the phone ever gets a contacts app in use.
    private val names = ConcurrentHashMap<String, String>()

    /** The contact name for a number, or null. Never throws: no permission just means no names. */
    fun name(context: Context, number: String): String? {
        if (number.isBlank()) return null
        val name = names.getOrPut(number) {
            try {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" } ?: ""
            } catch (e: Exception) { "" }
        }
        return name.ifEmpty { null }
    }

    /** Adds the contact name to a message, call or call event, when there is one. */
    fun named(context: Context, item: JSONObject): JSONObject {
        name(context, item.optString("number"))?.let { item.put("name", it) }
        return item
    }

    /** Every saved number with its name, for clients to complete from. */
    fun contacts(context: Context): JSONArray {
        val out = JSONArray()
        context.contentResolver.query(
            Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, "${Phone.DISPLAY_NAME} ASC",
        )?.use { c -> while (c.moveToNext()) out.put(JSONObject().put("name", c.getString(0)).put("number", c.getString(1))) }
        return out
    }

    /** Gives a number a name: renames the contact that has it, or makes a new one. */
    fun save(context: Context, number: String, name: String) {
        val resolver = context.contentResolver
        // PhoneLookup matches the number however it was written (+91…, 0…, spaces).
        val contact = resolver.query(
            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
            arrayOf(ContactsContract.PhoneLookup._ID), null, null, null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        val raw = contact?.let {
            resolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID), "${RawContacts.CONTACT_ID} = ?", arrayOf(it.toString()), null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }
        if (raw == null) {
            insert(context, listOf(name to listOf(number)))
        } else {
            val values = ContentValues().apply { put(StructuredName.DISPLAY_NAME, name) }
            val where = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?"
            val args = arrayOf(raw.toString(), StructuredName.CONTENT_ITEM_TYPE)
            if (resolver.update(Data.CONTENT_URI, values, where, args) == 0) {
                resolver.insert(Data.CONTENT_URI, values.apply {
                    put(Data.RAW_CONTACT_ID, raw); put(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                })
            }
        }
        names.clear()
    }

    /** Adds contacts whose numbers the phone does not know yet; returns how many were added. */
    fun import(context: Context, people: List<Pair<String, List<String>>>): Int {
        val fresh = people.filter { (_, numbers) -> numbers.none { name(context, it) != null } }
        insert(context, fresh)
        names.clear()
        return fresh.size
    }

    private fun insert(context: Context, people: List<Pair<String, List<String>>>) {
        for (chunk in people.chunked(50)) { // the provider refuses very large batches
            val ops = ArrayList<ContentProviderOperation>()
            for ((name, numbers) in chunk) {
                val back = ops.size
                ops += ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                    .withValue(RawContacts.ACCOUNT_TYPE, null).withValue(RawContacts.ACCOUNT_NAME, null).build()
                ops += ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, back)
                    .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE).withValue(StructuredName.DISPLAY_NAME, name).build()
                for (n in numbers) {
                    ops += ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, back)
                        .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE).withValue(Phone.NUMBER, n)
                        .withValue(Phone.TYPE, Phone.TYPE_MOBILE).build()
                }
            }
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        }
    }

    /** Recent calls, newest first, from the phone's call log. */
    fun calls(context: Context, limit: Int): JSONArray {
        val out = JSONArray()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null, "${CallLog.Calls.DATE} DESC LIMIT ${limit.coerceIn(1, 200)}",
        )?.use { c ->
            while (c.moveToNext()) {
                val type = c.getInt(2)
                out.put(named(context, JSONObject()
                    .put("id", "l${c.getLong(0)}")
                    .put("number", c.getString(1).orEmpty())
                    .put("direction", if (type == CallLog.Calls.OUTGOING_TYPE) "out" else "in")
                    .put("missed", type == CallLog.Calls.MISSED_TYPE)
                    .put("rejected", type == CallLog.Calls.REJECTED_TYPE || type == CallLog.Calls.BLOCKED_TYPE)
                    .put("at", c.getLong(3) / 1000)
                    .put("duration", c.getLong(4))))
            }
        }
        return out
    }
}
