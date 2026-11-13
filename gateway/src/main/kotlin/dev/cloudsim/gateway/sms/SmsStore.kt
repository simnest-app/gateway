package dev.cloudsim.gateway.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.telephony.SmsManager
import org.json.JSONArray

/** SMS in the gateway's private database; never leaves the phone except sealed for an allowed device. */
class SmsStore(private val context: Context) : SQLiteOpenHelper(context, "sms.db", null, 3), SmsLog {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sms (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, peer TEXT NOT NULL, body TEXT NOT NULL, at INTEGER NOT NULL)")
        onUpgrade(db, 1, 3)
    }

    /** 2 (SMS_4B.md): outgoing messages, their segments and states, and a change counter for sync. */
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            db.execSQL("ALTER TABLE sms ADD COLUMN dir TEXT NOT NULL DEFAULT 'in'")
            db.execSQL("ALTER TABLE sms ADD COLUMN sender TEXT")
            db.execSQL("ALTER TABLE sms ADD COLUMN command_id TEXT")
            db.execSQL("ALTER TABLE sms ADD COLUMN segments TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE sms ADD COLUMN parts TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE sms ADD COLUMN rev INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE sms SET rev = seq")
            db.execSQL("CREATE UNIQUE INDEX sms_command ON sms(command_id) WHERE command_id IS NOT NULL")
            db.execSQL("CREATE INDEX sms_rev ON sms(rev)")
        }
        // 3 (DUAL_SIM.md): the gateway number each message is on.
        if (old < 3) db.execSQL("ALTER TABLE sms ADD COLUMN number_id TEXT")
    }

    private val columns = "seq, id, peer, body, at, dir, sender, command_id, segments, parts, rev, number_id"

    private fun read(c: Cursor) = Sms(
        c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getString(5), c.getString(6), c.getString(7),
        JSONArray(c.getString(8)).let { a -> (0 until a.length()).map(a::getString) },
        JSONArray(c.getString(9)).let { a -> (0 until a.length()).map { Part.valueOf(a.getString(it)) } }, c.getLong(10),
        if (c.isNull(11)) null else c.getString(11),
    )

    private fun one(where: String, vararg args: String): Sms? =
        readableDatabase.rawQuery("SELECT $columns FROM sms WHERE $where LIMIT 1", args).use { if (it.moveToFirst()) read(it) else null }

    private fun nextRev(db: SQLiteDatabase): Long = db.rawQuery("SELECT coalesce(max(rev), 0) + 1 FROM sms", null).use { it.moveToFirst(); it.getLong(0) }

    override fun add(id: String, peer: String, body: String, at: Long): Sms = add(id, peer, body, at, null)

    @Synchronized
    override fun add(id: String, peer: String, body: String, at: Long, numberId: String?): Sms {
        val db = writableDatabase
        val seq = db.insertOrThrow("sms", null, ContentValues().apply {
            put("id", id); put("peer", peer); put("body", body); put("at", at); put("rev", nextRev(db)); put("number_id", numberId)
        })
        return one("seq = ?", seq.toString())!!
    }

    override fun addOutgoing(id: String, peer: String, body: String, at: Long, sender: String, commandId: String, segments: List<String>): Sms =
        addOutgoing(id, peer, body, at, sender, commandId, segments, null)

    @Synchronized
    override fun addOutgoing(id: String, peer: String, body: String, at: Long, sender: String, commandId: String, segments: List<String>, numberId: String?): Sms {
        val db = writableDatabase
        db.insertOrThrow("sms", null, ContentValues().apply {
            put("id", id); put("peer", peer); put("body", body); put("at", at); put("dir", "out"); put("sender", sender); put("command_id", commandId); put("number_id", numberId)
            put("segments", JSONArray(segments).toString()); put("parts", JSONArray(segments.map { Part.SENDING.name }).toString()); put("rev", nextRev(db))
        })
        return one("id = ?", id)!!
    }

    @Synchronized
    override fun update(id: String, parts: List<Part>): Sms? {
        val db = writableDatabase
        db.update("sms", ContentValues().apply { put("parts", JSONArray(parts.map { it.name }).toString()); put("rev", nextRev(db)) }, "id = ?", arrayOf(id))
        return one("id = ?", id)
    }

    override fun byId(id: String) = one("id = ?", id)
    override fun byCommand(commandId: String) = one("command_id = ?", commandId)

    override fun changedAfter(rev: Long, limit: Int): List<Sms> =
        readableDatabase.rawQuery("SELECT $columns FROM sms WHERE rev > ? ORDER BY rev LIMIT ?", arrayOf(rev.toString(), limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(read(c)) }
        }

    override fun lastSeq(): Long = readableDatabase.rawQuery("SELECT coalesce(max(seq), 0) FROM sms", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }

    override fun between(from: Long, to: Long, limit: Int): List<Sms> =
        readableDatabase.rawQuery("SELECT $columns FROM sms WHERE at >= ? AND at < ? ORDER BY at LIMIT ?", arrayOf(from.toString(), to.toString(), limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(read(c)) }
        }

    /**
     * Messages for a tool connected over MCP (GATEWAY_MCP.md 3.1), newest first: by the other party, by words in the
     * text, by time. Only what this database holds; messages from before the gateway took over are not looked up.
     */
    fun query(q: dev.cloudsim.gateway.mcp.SmsQuery): List<dev.cloudsim.gateway.mcp.McpSms> {
        val where = StringBuilder("at >= ? AND at < ?")
        val args = mutableListOf(q.from.toString(), q.to.toString())
        if (q.peer != null) { where.append(" AND peer = ?"); args += canonicalPeer(q.peer) }
        if (q.text != null) { where.append(" AND instr(body, ?) > 0"); args += q.text }
        args += q.limit.coerceIn(1, 500).toString()
        return readableDatabase.rawQuery("SELECT $columns FROM sms WHERE $where ORDER BY at DESC, seq DESC LIMIT ?", args.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) read(c).let { m -> add(dev.cloudsim.gateway.mcp.McpSms(m.id, m.peer, m.body, m.at, m.dir, m.status, m.numberId)) } }
        }
    }

    override fun firstAt(): Long? = readableDatabase.rawQuery("SELECT min(at) FROM sms", null).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    private val cancels = context.getSharedPreferences("sms_cancelled", Context.MODE_PRIVATE)
    override fun cancel(commandId: String) { cancels.edit().putLong(commandId, System.currentTimeMillis()).apply() }
    override fun cancelled(commandId: String) = cancels.contains(commandId)

    override fun sending(): List<Sms> =
        readableDatabase.rawQuery("SELECT $columns FROM sms WHERE dir = 'out' AND parts LIKE '%SENDING%'", null).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
}

/** The per-member starting points, in the app's preferences. */
class PrefsSince(context: Context) : Since {
    private val prefs = context.getSharedPreferences("sms_since", Context.MODE_PRIVATE)
    override fun get(device: String) = if (prefs.contains(device)) prefs.getLong(device, 0) else null
    override fun put(device: String, seq: Long) { prefs.edit().putLong(device, seq).apply() }
    override fun remove(device: String) { prefs.edit().remove(device).apply() }
    override fun devices(): Set<String> = prefs.all.keys
}

/**
 * The system SMS service. Each segment reports through its own broadcast to this app only; a resent segment goes out as
 * a message of its own (the recipient cannot join it to the original parts). No delivery reports: "sent" means reached
 * the SMS centre, never "delivered".
 */
class SystemRadio(
    private val context: Context,
    /** Gateway number ↔ SIM subscription (DUAL_SIM.md). */
    private val subscriptionFor: (String) -> Int? = { null },
    private val numberFor: (Int) -> String? = { null },
    private val onPart: (String, Int, Boolean) -> Unit,
) : SmsRadio {
    private val manager: SmsManager = context.getSystemService(SmsManager::class.java)

    private fun managerFor(numberId: String?): SmsManager =
        numberId?.let(subscriptionFor)?.let { sub -> runCatching { manager.createForSubscriptionId(sub) }.getOrNull() } ?: manager
    private val action = "${context.packageName}.SMS_SENT"

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val id = intent.getStringExtra("id") ?: return
            onPart(id, intent.getIntExtra("part", -1), resultCode == Activity.RESULT_OK)
        }
    }

    override fun divide(body: String): List<String> = manager.divideMessage(body)

    override fun send(id: String, peer: String, segments: List<Pair<Int, String>>): Boolean = send(id, peer, segments, null)

    override fun send(id: String, peer: String, segments: List<Pair<Int, String>>, numberId: String?): Boolean = runCatching {
        val manager = managerFor(numberId)
        val intents = segments.map { (index, _) ->
            PendingIntent.getBroadcast(context, (id.hashCode() * 31) + index,
                Intent(action).setPackage(context.packageName).putExtra("id", id).putExtra("part", index), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val texts = segments.map { it.second }
        // All segments of the original message go together as one long SMS; resent ones go one by one.
        if (texts.size > 1 && segments.map { it.first } == texts.indices.toList()) manager.sendMultipartTextMessage(peer, null, ArrayList(texts), ArrayList(intents), null)
        else segments.forEachIndexed { i, (_, text) -> manager.sendTextMessage(peer, null, text, intents[i], null) }
    }.isSuccess

    private val persisted = context.getSharedPreferences("sms_persisted", Context.MODE_PRIVATE)

    /** Once per message (and again if a retry changes the outcome), as the default SMS app must. */
    override fun persist(sms: Sms) {
        if (!DefaultSms.isDefault(context)) return
        val ok = sms.status == "sent"
        val key = "${sms.id}:${sms.status}"
        if (persisted.getBoolean(key, false)) return
        persisted.getString(sms.id, null)?.let { runCatching { context.contentResolver.delete(Uri.parse(it), null, null) } }
        DefaultSms.writeSent(context, sms.peer, sms.body, sms.at, ok, sms.numberId?.let(subscriptionFor) ?: -1)?.let { persisted.edit().putString(sms.id, it.toString()).putBoolean(key, true).apply() }
    }

    override fun systemHistory(from: Long, to: Long, limit: Int): List<Sms> = runCatching {
        // How much of the system store this app is shown at all (MIUI may hide some messages from it).
        context.contentResolver.query(android.provider.Telephony.Sms.CONTENT_URI, arrayOf("_id"), null, null, null)?.use {
            android.util.Log.i("CloudSimGateway", "sms system store visible to the gateway: ${it.count} row(s)")
        }
        context.contentResolver.query(android.provider.Telephony.Sms.CONTENT_URI, arrayOf("_id", "address", "body", "date", "type", "sub_id"),
            "date >= ? AND date < ? AND type IN (1, 2)", arrayOf(from.toString(), to.toString()), "date ASC LIMIT $limit")?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val peer = (c.getString(1) ?: "").let { if (it.any(Char::isDigit)) canonicalPeer(it) else it }
                    val out = c.getInt(4) == 2
                    add(Sms(0, "sys-${c.getLong(0)}", peer, c.getString(2) ?: "", c.getLong(3), if (out) "out" else "in",
                        parts = if (out) listOf(Part.SENT) else emptyList(), rev = 0, numberId = numberFor(c.getInt(5))))
                }
            }
        } ?: emptyList()
    }.getOrDefault(emptyList())

    override fun inSentBox(peer: String, body: String, sinceMs: Long): Boolean? = inSentBox(peer, body, sinceMs, null)

    override fun inSentBox(peer: String, body: String, sinceMs: Long, numberId: String?): Boolean? = runCatching {
        val tail = peer.filter { it.isDigit() }.takeLast(8)
        val sub = numberId?.let(subscriptionFor)
        context.contentResolver.query(android.provider.Telephony.Sms.Sent.CONTENT_URI, arrayOf("address", "body", "sub_id"),
            "date >= ?", arrayOf(sinceMs.toString()), "date DESC")?.use { c ->
            var found = false
            while (c.moveToNext() && !found) found = (c.getString(0) ?: "").filter { it.isDigit() }.endsWith(tail) && (c.getString(1) ?: "") == body &&
                (sub == null || c.getInt(2) == sub)
            found
        }
    }.getOrNull()
}

