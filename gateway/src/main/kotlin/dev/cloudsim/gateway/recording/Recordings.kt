package dev.cloudsim.gateway.recording

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/**
 * One call's recording on this phone (RECORDING_V7.md 4): the source, which only the owner manages. [state]:
 * recording, saved, partial (not all of it could be kept, the app stopped mid-way), cleaned (made room for newer ones,
 * the record kept so the call still shows "录音已删除"), deleted (by the owner).
 */
data class Recording(
    val id: String,
    val callId: String,
    val numberId: String?,
    val peer: String?,
    val outgoing: Boolean,
    val started: Long,
    val ended: Long = 0,
    /** Recorded audio, gaps left out. */
    val audioMs: Long = 0,
    /** The AAC stream's length, all parts together: what a download carries. */
    val plainBytes: Long = 0,
    /** On disk, encrypted: what counts against the quota. */
    val storedBytes: Long = 0,
    val state: String = RECORDING,
    val locked: Boolean = false,
    val spans: List<Span> = emptyList(),
    val parts: List<Part> = emptyList(),
    /** When each family phone was in the call (RECORDING_V7.md 4), the owner's left out: what a member may get. */
    val presence: List<Presence> = emptyList(),
    /**
     * Whether the far party was told before it started (RECORDING_NOTICE.md 7): played (the notice, before recording),
     * off (the number's notice is off), assistant (the screening assistant answered; not told on top of its greeting);
     * empty for recordings from before there was a notice.
     */
    val notice: String = "",
) {
    val hasAudio get() = state in setOf(SAVED, PARTIAL) && plainBytes > 0

    /** What a phone sees in the list: never more than the owner may see (7a: only the owner asks). */
    fun toJson(): JSONObject = JSONObject().put("id", id).put("call_id", callId).put("number_id", numberId ?: "").put("peer", peer ?: "")
        .put("outgoing", outgoing).put("started", started).put("ended", ended).put("audio_ms", audioMs).put("bytes", plainBytes)
        .put("state", state).put("locked", locked).put("notice", notice)
        .put("spans", JSONArray(spans.map { JSONObject().put("from", it.wallStart).put("to", it.wallEnd).put("why", it.reason) }))

    companion object {
        const val RECORDING = "recording"
        const val SAVED = "saved"
        const val PARTIAL = "partial"
        const val CLEANED = "cleaned"
        const val DELETED = "deleted"
    }
}

/**
 * A stretch recorded without a break: wall-clock times, where it starts in the audio, and why it ended (stopped by
 * someone, no_space, media (the call's audio went), ended (the call did), crashed).
 */
data class Span(val wallStart: Long, val wallEnd: Long, val audioStart: Long, val reason: String)

/** A phone in the call from [from] to [to], wall-clock ms. */
data class Presence(val device: String, val from: Long, val to: Long)

/** One file of a recording, about a minute: its AAC bytes and what it takes on disk. */
data class Part(val seq: Int, val plain: Long, val stored: Long)

/** Where recordings are listed; SQLite in the app, a map in tests. */
interface RecordingStore {
    fun put(r: Recording)
    fun get(id: String): Recording?
    /** Started in [from, to), newest first. */
    fun list(from: Long, to: Long, limit: Int): List<Recording>
    /** All of one number's, oldest first: the order they are cleaned in. */
    fun byNumber(numberId: String?): List<Recording>
    fun unfinished(): List<Recording>
}

/** The encrypted files themselves; Tink's streaming AEAD in the app, plain memory in tests. */
interface Vault {
    fun writer(recordingId: String, seq: Int): PartWriter
    /** The part's AAC bytes from [offset]. */
    fun reader(recordingId: String, seq: Int, offset: Long): InputStream
    /** How much of a part that was being written when the app stopped can still be read. */
    fun readable(recordingId: String, seq: Int): Long
    fun stored(recordingId: String, seq: Int): Long
    fun delete(recordingId: String)
}

interface PartWriter {
    fun write(bytes: ByteArray)
    /** Closes the file; returns its size on disk. */
    fun close(): Long
}

/** 16 kHz stereo PCM in, ADTS AAC frames out (MediaCodec in the app). */
interface AacEncoder {
    fun encode(pcm: ShortArray): List<ByteArray>
    fun flush(): List<ByteArray>
    fun close()
}

/** Each number's recording settings (RECORDING_V7.md 7), set by the owner, kept on this phone. */
class RecordingSettings(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    /** A number never set tells the far party before recording (RECORDING_NOTICE.md 5); tests of the rest turn it off. */
    private val noticeDefault: Boolean = true,
) {
    /** [notice]: tell the far party before recording; null while nobody chose, and the default holds. */
    /** [noticeLang]: which of the notice's languages the far party hears ([NOTICE_LANGS]); empty while nobody chose. */
    data class Number(val auto: Boolean = false, val quotaMb: Int = DEFAULT_QUOTA_MB, val notice: Boolean? = null, val noticeLang: String = "")

    @Synchronized
    fun of(numberId: String?): Number = all()[numberId ?: ""] ?: Number()

    /** Whether recordings on this number tell the far party first. */
    fun tells(numberId: String?): Boolean = of(numberId).notice ?: noticeDefault

    /** The language of this number's notice (the user's 2026-11-12 choice: one clip a language); Mandarin unless chosen. */
    fun language(numberId: String?): String = of(numberId).noticeLang.takeIf { it in NOTICE_LANGS } ?: NOTICE_LANGS.first()

    /**
     * The numbers a gateway already had when the notice came with an update keep recording as they did, without it
     * (the user's 2026-11-11 choice); the owner can turn it on. Numbers added later tell unless turned off.
     */
    @Synchronized
    fun keepWithoutNotice(numberIds: Collection<String>) {
        for (id in numberIds) if (of(id).notice == null) set(id, of(id).copy(notice = false))
    }

    @Synchronized
    fun all(): Map<String, Number> = runCatching {
        val o = JSONObject(load() ?: "{}")
        o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { Number(it.optBoolean("auto"), it.optInt("quota_mb", DEFAULT_QUOTA_MB),
            if (it.has("notice")) it.optBoolean("notice") else null, it.optString("notice_lang")) } }
    }.getOrDefault(emptyMap())

    @Synchronized
    fun set(numberId: String?, n: Number) {
        val o = runCatching { JSONObject(load() ?: "{}") }.getOrDefault(JSONObject())
        o.put(numberId ?: "", JSONObject().put("auto", n.auto).put("quota_mb", n.quotaMb).apply {
            n.notice?.let { put("notice", it) }
            if (n.noticeLang.isNotEmpty()) put("notice_lang", n.noticeLang)
        })
        save(o.toString())
    }

    companion object {
        /** The notice's languages, each a clip in the app (assets/notice/<code>.pcm): Mandarin, Cantonese, English. */
        val NOTICE_LANGS = listOf("zh", "yue", "en")
        /** The user's 2026-11-09 choice: 5 GB a number, changeable. */
        const val DEFAULT_QUOTA_MB = 5 * 1024
        const val MIN_QUOTA_MB = 100
        const val MAX_QUOTA_MB = 200 * 1024
        /** Space the phone keeps free for itself: below it, no new recording is written. */
        const val RESERVE_BYTES = 5L shl 30
    }
}

/** Parses and writes a recording's lists, for the database. */
internal object RecordingJson {
    fun spans(r: Recording) = JSONArray(r.spans.map { JSONArray().put(it.wallStart).put(it.wallEnd).put(it.audioStart).put(it.reason) }).toString()
    fun parts(r: Recording) = JSONArray(r.parts.map { JSONArray().put(it.seq).put(it.plain).put(it.stored) }).toString()
    fun presence(r: Recording) = JSONArray(r.presence.map { JSONArray().put(it.device).put(it.from).put(it.to) }).toString()
    fun presence(s: String?): List<Presence> = runCatching {
        JSONArray(s ?: "[]").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { x -> Presence(x.getString(0), x.getLong(1), x.getLong(2)) } } }
    }.getOrDefault(emptyList())
    fun spans(s: String): List<Span> = JSONArray(s).let { a -> (0 until a.length()).map { a.getJSONArray(it).let { x -> Span(x.getLong(0), x.getLong(1), x.getLong(2), x.getString(3)) } } }
    fun parts(s: String): List<Part> = JSONArray(s).let { a -> (0 until a.length()).map { a.getJSONArray(it).let { x -> Part(x.getInt(0), x.getLong(1), x.getLong(2)) } } }
}

class MemoryRecordingStore : RecordingStore {
    private val rows = linkedMapOf<String, Recording>()
    @Synchronized override fun put(r: Recording) { rows[r.id] = r }
    @Synchronized override fun get(id: String) = rows[id]
    @Synchronized override fun list(from: Long, to: Long, limit: Int) = rows.values.filter { it.started in from until to }.sortedByDescending { it.started }.take(limit)
    @Synchronized override fun byNumber(numberId: String?) = rows.values.filter { it.numberId == numberId }.sortedBy { it.started }
    @Synchronized override fun unfinished() = rows.values.filter { it.state == Recording.RECORDING }
}
