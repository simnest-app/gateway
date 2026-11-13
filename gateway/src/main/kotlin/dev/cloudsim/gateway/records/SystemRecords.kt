package dev.cloudsim.gateway.records

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import org.json.JSONArray
import org.json.JSONObject

/** This phone's contacts and call log, from the system providers; nothing is kept. */
class SystemRecords(private val context: Context, private val numberIdFor: (accountId: String?) -> String?) : RecordSource {
    override fun contacts(): List<JSONObject> {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) throw MissingPermission("READ_CONTACTS")
        val byContact = linkedMapOf<Long, Pair<String, JSONArray>>()
        context.contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.TYPE, Phone.LABEL),
            null, null, Phone.DISPLAY_NAME_PRIMARY)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val number = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                val label = Phone.getTypeLabel(context.resources, c.getInt(3), c.getString(4)).toString()
                val entry = byContact.getOrPut(id) { (c.getString(1) ?: number) to JSONArray() }
                entry.second.put(JSONObject().put("label", label).put("number", number))
            }
        }
        return byContact.map { (id, e) -> JSONObject().put("id", id.toString()).put("name", e.first).put("numbers", e.second) }
    }

    override fun calls(from: Long, to: Long): List<JSONObject> {
        if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) throw MissingPermission("READ_CALL_LOG")
        val out = mutableListOf<JSONObject>()
        context.contentResolver.query(CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.PHONE_ACCOUNT_ID),
            "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} < ?", arrayOf(from.toString(), to.toString()), "${CallLog.Calls.DATE} DESC")?.use { c ->
            while (c.moveToNext() && out.size < RecordsRelay.MAX_CALLS) {
                val type = when (c.getInt(4)) {
                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    CallLog.Calls.MISSED_TYPE -> "missed"
                    CallLog.Calls.REJECTED_TYPE -> "rejected"
                    CallLog.Calls.BLOCKED_TYPE -> "blocked"
                    CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "elsewhere"
                    else -> "other"
                }
                out += JSONObject().put("id", c.getLong(0).toString()).put("number", c.getString(1) ?: "").put("at", c.getLong(2))
                    .put("duration_s", c.getLong(3)).put("type", type).apply { numberIdFor(c.getString(5))?.let { put("number_id", it) } }
            }
        }
        return out
    }

    /** One raw contact in the phone's own (local) account, so it is not sent to a cloud account. */
    override fun addContact(contact: NewContact) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) throw MissingPermission("WRITE_CONTACTS")
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build(),
        )
        if (contact.family.isNotEmpty() || contact.given.isNotEmpty() || contact.name.isNotEmpty()) {
            ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                .apply {
                    // Family and given as typed; an older phone sends the whole name only.
                    if (contact.family.isNotEmpty() || contact.given.isNotEmpty()) {
                        withValue(StructuredName.FAMILY_NAME, contact.family.ifEmpty { null })
                        withValue(StructuredName.GIVEN_NAME, contact.given.ifEmpty { null })
                    }
                    if (contact.name.isNotEmpty()) withValue(StructuredName.DISPLAY_NAME, contact.name)
                }.build()
        }
        if (contact.company.isNotEmpty()) {
            ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, Organization.CONTENT_ITEM_TYPE)
                .withValue(Organization.COMPANY, contact.company)
                .withValue(Organization.TYPE, Organization.TYPE_WORK).build()
        }
        for (n in contact.numbers) ops += phoneRow(n).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).build()
        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    /** Numbers onto one of the contact's own raw contacts; its name and numbers stay, and one it has is not repeated. */
    override fun addNumbers(contactId: String, numbers: List<ContactNumber>) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) throw MissingPermission("WRITE_CONTACTS")
        val id = contactId.toLongOrNull() ?: throw ContactGone()
        // A local raw contact first (not synced to a cloud account), else the first one there is.
        var raw: Long? = null
        context.contentResolver.query(ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_TYPE),
            "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.DELETED} = 0", arrayOf(id.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                if (raw == null || c.isNull(1)) raw = c.getLong(0)
                if (c.isNull(1)) break
            }
        }
        val target = raw ?: throw ContactGone()
        val have = mutableListOf<String>()
        context.contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.NUMBER), "${Phone.CONTACT_ID} = ?", arrayOf(id.toString()), null)?.use { c ->
            while (c.moveToNext()) c.getString(0)?.let { have += it }
        }
        val fresh = numbers.filter { n -> have.none { same(it, n.number) } }
        if (fresh.isEmpty()) return
        val ops = ArrayList(fresh.map { phoneRow(it).withValue(ContactsContract.Data.RAW_CONTACT_ID, target).build() })
        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    private fun phoneRow(n: ContactNumber): ContentProviderOperation.Builder {
        val type = when (n.type) {
            "mobile", "iphone" -> Phone.TYPE_MOBILE
            "home" -> Phone.TYPE_HOME
            "work" -> Phone.TYPE_WORK
            "main" -> Phone.TYPE_MAIN
            "other" -> Phone.TYPE_OTHER
            else -> Phone.TYPE_CUSTOM
        }
        return ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
            .withValue(Phone.NUMBER, n.number)
            .withValue(Phone.TYPE, type)
            .apply { if (type == Phone.TYPE_CUSTOM) withValue(Phone.LABEL, n.label.ifEmpty { "电话" }) }
    }

    /** The same number with or without +86 and spaces: the last 11 digits. */
    private fun same(a: String, b: String): Boolean {
        val x = a.filter(Char::isDigit).takeLast(11)
        return x.isNotEmpty() && x == b.filter(Char::isDigit).takeLast(11)
    }
}
