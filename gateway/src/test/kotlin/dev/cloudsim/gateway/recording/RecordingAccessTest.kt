package dev.cloudsim.gateway.recording

import dev.cloudsim.gateway.calls.MediaBridge
import dev.cloudsim.gateway.calls.RecRights
import dev.cloudsim.gateway.calls.Sealer
import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ServiceConfig
import dev.cloudsim.gateway.pairing.SoftwareSigner
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Parts in memory, as in RecorderTest. */
private class AccessVault : Vault {
    val files = mutableMapOf<String, ByteArrayOutputStream>()
    override fun writer(recordingId: String, seq: Int): PartWriter {
        val b = ByteArrayOutputStream().also { files["$recordingId/$seq"] = it }
        return object : PartWriter {
            override fun write(bytes: ByteArray) = b.write(bytes)
            override fun close() = b.size().toLong() + 10
        }
    }
    override fun reader(recordingId: String, seq: Int, offset: Long) = ByteArrayInputStream(files["$recordingId/$seq"]!!.toByteArray()).also { it.skip(offset) }
    override fun readable(recordingId: String, seq: Int) = files["$recordingId/$seq"]?.size()?.toLong() ?: -1
    override fun stored(recordingId: String, seq: Int) = (files["$recordingId/$seq"]?.size()?.toLong() ?: 0) + 10
    override fun delete(recordingId: String) { files.keys.removeAll { it.startsWith("$recordingId/") } }
}

/** Real ADTS framing (7-byte header with the frame's length), a body that says the frame's number. */
private class AdtsAac : AacEncoder {
    private var pending = 0
    var n = 0
    override fun encode(pcm: ShortArray): List<ByteArray> {
        pending += pcm.size / 2
        val out = mutableListOf<ByteArray>()
        while (pending >= 1024) { pending -= 1024; out += frame(n++) }
        return out
    }
    override fun flush() = emptyList<ByteArray>()
    override fun close() {}

    companion object {
        /** A frame of 7 + 5 bytes: the header, then the frame number. */
        fun frame(i: Int): ByteArray {
            val len = 12
            val h = byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x60, (0x80 or ((len shr 11) and 0x3)).toByte(), ((len shr 3) and 0xff).toByte(),
                (((len and 0x7) shl 5) or 0x1f).toByte(), 0xfc.toByte())
            return h + byteArrayOf(0, (i shr 24).toByte(), (i shr 16).toByte(), (i shr 8).toByte(), i.toByte())
        }
        fun numbers(stream: ByteArray): List<Int> = stream.toList().chunked(12).map { b ->
            ((b[8].toInt() and 0xff) shl 24) or ((b[9].toInt() and 0xff) shl 16) or ((b[10].toInt() and 0xff) shl 8) or (b[11].toInt() and 0xff)
        }
    }
}

private class AccessBridge : MediaBridge {
    override fun send(command: JSONObject) = true
}

/** RECORDING_V7.md 15: what a family phone may get, cut to the stretches it was in. */
class RecordingAccessTest {
    private fun rec(spans: List<Span>, presence: List<Presence>, started: Long = 0) =
        Recording("r", "c", "num-a", null, false, started, 100_000, audioMs = 60_000, plainBytes = 1000, state = Recording.SAVED, spans = spans, presence = presence)

    @Test fun aPhoneGetsOnlyTheTimeItWasInWhileRecording() {
        // Recorded 0–20 s and, after a stop, 30–50 s of the call (audio 0–20 s, 20–40 s); the phone was in 10–40 s.
        val r = rec(listOf(Span(0, 20_000, 0, "stopped"), Span(30_000, 50_000, 20_000, "ended")), listOf(Presence("fam", 10_000, 40_000)))
        assertEquals(listOf(10_000L until 20_000L, 20_000L until 30_000L).let(RecordingAccess::merge),
            RecordingAccess.ranges(r, "fam", RecRights(play = true)))
        assertEquals(20_000L, RecordingAccess.length(r, RecordingAccess.ranges(r, "fam", RecRights(play = true))))
    }

    @Test fun withoutPlayOrExportNothing() {
        val r = rec(listOf(Span(0, 20_000, 0, "ended")), listOf(Presence("fam", 0, 20_000)))
        assertTrue(RecordingAccess.ranges(r, "fam", RecRights(control = true))!!.isEmpty())
    }

    @Test fun aPhoneNotInTheCallGetsNothingUnlessGivenTheWholeNumber() {
        val r = rec(listOf(Span(0, 20_000, 0, "ended")), listOf(Presence("other", 0, 20_000)), started = 5_000)
        assertTrue(RecordingAccess.ranges(r, "fam", RecRights(play = true))!!.isEmpty())
        assertNull(RecordingAccess.ranges(r, "fam", RecRights(play = true, all = true, allFrom = 0)))
        // Whole recordings only from the date given on: an older one stays to its own stretches (none here).
        assertTrue(RecordingAccess.ranges(r, "fam", RecRights(play = true, all = true, allFrom = 6_000))!!.isEmpty())
    }

    @Test fun cuttingKeepsWholeFramesInOrder() {
        val stream = (0 until 10).map(AdtsAac::frame).reduce { a, b -> a + b }
        val ranges = listOf(128L until 320L) // frames 2, 3, 4 (each 64 ms)
        val cut = ClippedAdts(ByteArrayInputStream(stream)) { RecordingAccess.keeps(ranges, it) }.readBytes()
        assertEquals(listOf(2, 3, 4), AdtsAac.numbers(cut))
        assertEquals(36L, ClippedAdts.size(ByteArrayInputStream(stream)) { RecordingAccess.keeps(ranges, it) })
    }
}

/** RECORDING_V7.md 15 end to end on the gateway: presence kept while recording, family requests by their rights. */
class FamilyRecordingTest {
    private class Client : ControlClient(ServiceConfig("127.0.0.1:1", ByteArray(32), "svc"), SoftwareSigner()) {
        val sent = mutableListOf<JSONObject>()
        override fun call(method: String, path: String, body: JSONObject?, readTimeoutMs: Int): JSONObject { sent += body!!; return JSONObject() }
    }

    private class Wrap : Sealer {
        override fun seal(kind: String, to: String, id: String, plaintext: ByteArray) =
            JSONObject().put("kind", kind).put("to", to).put("id", id).put("plain", plaintext.decodeToString())
        override fun open(envelope: JSONObject, kind: String, senderKey: ByteArray): ByteArray? =
            if (envelope.optString("kind") == kind && senderKey.decodeToString() == envelope.optString("from")) envelope.optString("plain").toByteArray() else null
    }

    private var clock = 1_000_000L
    private val client = Client()
    private val store = MemoryRecordingStore()
    private val vault = AccessVault()
    private var prefs: String? = null
    private val settings = RecordingSettings({ prefs }, { prefs = it }, noticeDefault = false)
    private var inCall = setOf<String>()
    private var rights = mapOf<String, RecRights>()
    private val toBridge = mutableListOf<JSONObject>()
    private val recorder = Recorder(store, vault, { AdtsAac() }, settings, { 50L shl 30 }, AccessBridge(), { clock },
        participants = { inCall - "owner" })
    private val relay = RecordingRelay(client, Wrap(), { "owner" }, recorder, settings, { listOf("num-a") }, { inCall }, { false }, { 50L shl 30 },
        { toBridge += it; true }, now = { clock }, rights = { d, _ -> rights[d] ?: RecRights() })

    private var n = 0
    private fun ask(from: String, op: JSONObject): JSONObject {
        val id = "req-${n++}-abcdef"
        val env = JSONObject().put("kind", "recording").put("id", id).put("from", from).put("plain", op.put("request_id", id).toString())
        relay.onRequest(JSONObject().put("kind", "recording").put("device_id", from).put("request_id", id).put("public_key", Base64.getEncoder().encodeToString(from.toByteArray()))
            .put("envelope", env.toString()).put("_at_ms", clock))
        return JSONObject(JSONObject(client.sent.last().getString("envelope")).getString("plain"))
    }
    private fun items(doc: JSONObject): JSONArray = doc.getJSONArray("items")

    /** 100 ms of audio: 1600 stereo samples; a frame for each 1024. */
    private fun talk(seconds: Int) = repeat(seconds * 10) { recorder.onPcm("c", Base64.getEncoder().encodeToString(ByteArray(6400))); clock += 100 }

    /** The owner answers, the recording runs 20 s; "fam" is in from 5 s to 15 s. */
    private fun aCall(): Recording {
        inCall = setOf("owner")
        recorder.started("c", "num-a", null, false)
        recorder.control("c", true)
        talk(5)
        inCall = setOf("owner", "fam")
        talk(10)
        inCall = setOf("owner")
        talk(5)
        recorder.ended("c")
        return store.list(0, Long.MAX_VALUE, 1).single()
    }

    private fun download(from: String, id: String): ByteArray {
        val t = items(ask(from, JSONObject().put("op", "fetch").put("id", id))).getJSONObject(0)
        val ticket = t.getString("ticket")
        assertTrue(relay.take(ticket, from))
        var offset = 0L
        val got = ByteArrayOutputStream()
        while (true) {
            relay.onFileNeed(JSONObject().put("probe_id", ticket).put("offset", offset))
            val c = toBridge.last()
            val data = Base64.getDecoder().decode(c.getString("data"))
            got.write(data)
            offset += data.size
            if (c.getBoolean("last")) break
        }
        assertEquals(t.getLong("bytes"), got.size().toLong())
        return got.toByteArray()
    }

    @Test fun theRecordingKeepsWhenEachFamilyPhoneWasIn() {
        val r = aCall()
        val p = r.presence.single()
        assertEquals("fam", p.device)
        assertTrue(p.from in 1_005_000L..1_005_100L)
        assertTrue(p.to in 1_014_900L..1_015_100L)
    }

    @Test fun familyWithoutRightsSeesNothingAndCannotDownload() {
        val r = aCall()
        assertEquals(0, items(ask("fam", JSONObject().put("op", "list"))).length())
        assertEquals("no_permission", ask("fam", JSONObject().put("op", "fetch").put("id", r.id)).getString("unavailable"))
    }

    @Test fun familyGetsOnlyItsOwnStretch() {
        val r = aCall()
        rights = mapOf("fam" to RecRights(play = true))
        val item = items(ask("fam", JSONObject().put("op", "list"))).getJSONObject(0)
        assertTrue(item.getBoolean("part"))
        assertTrue(item.getBoolean("may_play"))
        assertFalse(item.getBoolean("may_export"))
        assertTrue(item.getLong("audio_ms") in 9_800L..10_200L)
        val got = AdtsAac.numbers(download("fam", r.id))
        // Frames from about 5 s to 15 s of a 20 s recording (each 64 ms): around 78 to 234, nothing from either end.
        assertTrue(got.first() in 76..80)
        assertTrue(got.last() in 231..236)
        assertEquals((got.first()..got.last()).toList(), got)
        // The owner gets all of it.
        val all = AdtsAac.numbers(download("owner", r.id))
        assertEquals(0, all.first())
        assertTrue(all.size > got.size * 2 - 10)
    }

    @Test fun theWholeNumberMeansWholeRecordings() {
        val r = aCall()
        rights = mapOf("other" to RecRights(play = true, all = true))
        val item = items(ask("other", JSONObject().put("op", "list"))).getJSONObject(0)
        assertFalse(item.getBoolean("part"))
        assertEquals(r.audioMs, item.getLong("audio_ms"))
    }

    @Test fun familyStartsAndStopsOnlyWithTheRightAndWhileInTheCall() {
        inCall = setOf("owner", "fam")
        recorder.started("c", "num-a", null, false)
        val on = JSONObject().put("op", "control").put("call_id", "c").put("on", true)
        assertEquals("no_permission", ask("fam", on).getString("unavailable"))
        rights = mapOf("fam" to RecRights(control = true))
        assertTrue(items(ask("fam", JSONObject(on.toString()))).getJSONObject(0).getBoolean("recording"))
        // The state it is told says it may.
        assertTrue(items(ask("fam", JSONObject().put("op", "state").put("call_id", "c"))).getJSONObject(0).getBoolean("can_control"))
        inCall = setOf("owner")
        assertEquals("no_permission", ask("fam", JSONObject().put("op", "control").put("call_id", "c").put("on", false)).getString("unavailable"))
    }

    @Test fun theOwnerSeesTheCallGoingOnWithoutBeingInIt() {
        inCall = setOf("fam")
        recorder.started("c", "num-a", "10086", false)
        val live = items(ask("owner", JSONObject().put("op", "live"))).getJSONObject(0)
        assertEquals("c", live.getString("call_id"))
        assertTrue(live.getBoolean("can_control"))
        assertTrue(items(ask("owner", JSONObject().put("op", "control").put("call_id", "c").put("on", true))).getJSONObject(0).getBoolean("recording"))
        assertEquals("no_permission", ask("fam", JSONObject().put("op", "live")).getString("unavailable"))
    }
}
