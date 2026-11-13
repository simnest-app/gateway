package dev.cloudsim.gateway.recording

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.Channels

/** The recordings list in the app's private database (RECORDING_V7.md 4). */
class SqlRecordingStore(context: Context) : SQLiteOpenHelper(context, "recordings.db", null, 3), RecordingStore {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE recording (id TEXT PRIMARY KEY, call_id TEXT NOT NULL, number_id TEXT, peer TEXT, outgoing INTEGER NOT NULL,
            started INTEGER NOT NULL, ended INTEGER NOT NULL, audio_ms INTEGER NOT NULL, plain INTEGER NOT NULL, stored INTEGER NOT NULL,
            state TEXT NOT NULL, locked INTEGER NOT NULL, spans TEXT NOT NULL, parts TEXT NOT NULL, presence TEXT NOT NULL DEFAULT '[]',
            notice TEXT NOT NULL DEFAULT '')""")
        db.execSQL("CREATE INDEX recording_started ON recording(started)")
        db.execSQL("CREATE INDEX recording_number ON recording(number_id, started)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // 7b: who was in the call and when; recordings from 7a have none (only the owner gets those whole).
        if (old < 2) db.execSQL("ALTER TABLE recording ADD COLUMN presence TEXT NOT NULL DEFAULT '[]'")
        // Whether the far party was told (RECORDING_NOTICE.md 7); recordings from before have nothing here.
        if (old < 3) db.execSQL("ALTER TABLE recording ADD COLUMN notice TEXT NOT NULL DEFAULT ''")
    }

    private val columns = "id, call_id, number_id, peer, outgoing, started, ended, audio_ms, plain, stored, state, locked, spans, parts, presence, notice"

    private fun read(c: Cursor) = Recording(c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getString(2), if (c.isNull(3)) null else c.getString(3),
        c.getInt(4) != 0, c.getLong(5), c.getLong(6), c.getLong(7), c.getLong(8), c.getLong(9), c.getString(10), c.getInt(11) != 0,
        RecordingJson.spans(c.getString(12)), RecordingJson.parts(c.getString(13)), RecordingJson.presence(c.getString(14)), c.getString(15))

    private fun query(where: String, args: Array<String>, order: String, limit: Int = -1): List<Recording> =
        readableDatabase.rawQuery("SELECT $columns FROM recording WHERE $where ORDER BY $order${if (limit > 0) " LIMIT $limit" else ""}", args).use { c ->
            buildList { while (c.moveToNext()) add(read(c)) }
        }

    @Synchronized
    override fun put(r: Recording) {
        writableDatabase.insertWithOnConflict("recording", null, ContentValues().apply {
            put("id", r.id); put("call_id", r.callId); put("number_id", r.numberId); put("peer", r.peer); put("outgoing", if (r.outgoing) 1 else 0)
            put("started", r.started); put("ended", r.ended); put("audio_ms", r.audioMs); put("plain", r.plainBytes); put("stored", r.storedBytes)
            put("state", r.state); put("locked", if (r.locked) 1 else 0); put("spans", RecordingJson.spans(r)); put("parts", RecordingJson.parts(r))
            put("presence", RecordingJson.presence(r)); put("notice", r.notice)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun get(id: String) = query("id = ?", arrayOf(id), "started").firstOrNull()
    override fun list(from: Long, to: Long, limit: Int) = query("started >= ? AND started < ?", arrayOf("$from", "$to"), "started DESC", limit)
    override fun byNumber(numberId: String?) =
        if (numberId == null) query("number_id IS NULL", emptyArray(), "started") else query("number_id = ?", arrayOf(numberId), "started")
    override fun unfinished() = query("state = ?", arrayOf(Recording.RECORDING), "started")
}

/**
 * The parts on disk, each encrypted with Tink's streaming AEAD (AES-GCM-HKDF, 4 KB segments) under a keyset that the
 * Android Keystore wraps: the files cannot be read off this phone, nor by any other app. Each part's associated data
 * names its recording and place, so parts cannot be swapped around.
 */
class TinkVault(context: Context) : Vault {
    private val root = File(context.filesDir, "recordings").apply { mkdirs() }
    private val aead: StreamingAead by lazy {
        StreamingAeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, "recording_keyset", "recording_keys")
            .withKeyTemplate(com.google.crypto.tink.KeyTemplates.get("AES128_GCM_HKDF_4KB"))
            .withMasterKeyUri("android-keystore://cloudsim_recording_master")
            .build().keysetHandle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }

    private fun file(id: String, seq: Int) = File(File(root, id), "$seq.aac.enc")
    private fun ad(id: String, seq: Int) = "cloudsim-recording-v1/$id/$seq".toByteArray()

    override fun writer(recordingId: String, seq: Int): PartWriter {
        val f = file(recordingId, seq).apply { parentFile?.mkdirs() }
        val out = aead.newEncryptingStream(FileOutputStream(f), ad(recordingId, seq))
        return object : PartWriter {
            override fun write(bytes: ByteArray) = out.write(bytes)
            override fun close(): Long { out.close(); return f.length() }
        }
    }

    override fun reader(recordingId: String, seq: Int, offset: Long): InputStream {
        val channel = aead.newSeekableDecryptingChannel(FileInputStream(file(recordingId, seq)).channel, ad(recordingId, seq))
        channel.position(offset)
        return Channels.newInputStream(channel)
    }

    override fun readable(recordingId: String, seq: Int): Long {
        val f = file(recordingId, seq)
        if (!f.exists()) return -1
        var n = 0L
        runCatching {
            aead.newDecryptingStream(FileInputStream(f), ad(recordingId, seq)).use { s ->
                val buf = ByteArray(8192)
                while (true) { val r = s.read(buf); if (r < 0) break; n += r }
            }
        }
        return n
    }

    override fun stored(recordingId: String, seq: Int) = file(recordingId, seq).length()

    override fun delete(recordingId: String) { File(root, recordingId).deleteRecursively() }
}

/**
 * AAC-LC at 16 kHz stereo, about 40 kbps, with Android's own encoder; each frame comes out with its ADTS header, so
 * a part, or any run of frames, plays as it is.
 */
class MediaCodecAac : AacEncoder {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, CHANNELS)
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        f.setInteger(MediaFormat.KEY_BIT_RATE, 40_000)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        start()
    }
    private val info = MediaCodec.BufferInfo()
    private var samples = 0L

    override fun encode(pcm: ShortArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var at = 0
        while (at < pcm.size) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) { drain(out); continue }
            val buf = codec.getInputBuffer(index)!!
            buf.clear()
            val n = minOf(pcm.size - at, buf.capacity() / 2)
            for (i in 0 until n) { val v = pcm[at + i].toInt(); buf.put((v and 0xff).toByte()); buf.put((v shr 8).toByte()) }
            codec.queueInputBuffer(index, 0, n * 2, samples * 1_000_000 / (RATE * CHANNELS), 0)
            samples += n
            at += n
            drain(out)
        }
        return out
    }

    override fun flush(): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        val index = codec.dequeueInputBuffer(50_000)
        if (index >= 0) codec.queueInputBuffer(index, 0, 0, samples * 1_000_000 / (RATE * CHANNELS), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        val end = System.currentTimeMillis() + 1000
        while (System.currentTimeMillis() < end) { if (drain(out, 20_000)) break }
        return out
    }

    /** Takes what the encoder has ready; true once it reported the end of the stream. */
    private fun drain(out: MutableList<ByteArray>, waitUs: Long = 0): Boolean {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, waitUs)
            if (index < 0) return false
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val buf = codec.getOutputBuffer(index)!!
                buf.position(info.offset)
                val frame = ByteArray(info.size + 7)
                adts(frame, info.size + 7)
                buf.get(frame, 7, info.size)
                out += frame
            }
            codec.releaseOutputBuffer(index, false)
            if (eos) return true
        }
    }

    override fun close() { runCatching { codec.stop() }; codec.release() }

    companion object {
        const val RATE = 16000
        const val CHANNELS = 2

        /** The 7-byte ADTS header: MPEG-4 AAC LC, 16 kHz (index 8), two channels, no CRC. */
        fun adts(b: ByteArray, length: Int) {
            val profile = 2 - 1
            val freq = 8
            b[0] = 0xFF.toByte()
            b[1] = 0xF1.toByte()
            b[2] = ((profile shl 6) or (freq shl 2) or (CHANNELS shr 2)).toByte()
            b[3] = (((CHANNELS and 3) shl 6) or (length shr 11)).toByte()
            b[4] = ((length shr 3) and 0xFF).toByte()
            b[5] = (((length and 7) shl 5) or 0x1F).toByte()
            b[6] = 0xFC.toByte()
        }
    }
}
