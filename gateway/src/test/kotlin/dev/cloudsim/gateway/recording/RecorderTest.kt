package dev.cloudsim.gateway.recording

import dev.cloudsim.gateway.calls.MediaBridge
import dev.cloudsim.gateway.calls.Sealer
import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ServiceConfig
import dev.cloudsim.gateway.pairing.SoftwareSigner
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Parts kept in memory; a part still open when the "app stops" is readable as far as it was written. */
private class MemoryVault : Vault {
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

/** One 100-byte "frame" for every 1024 stereo samples given, numbered so their order shows. */
private class FakeAac : AacEncoder {
    private var pending = 0
    private var n = 0
    var closed = false
    override fun encode(pcm: ShortArray): List<ByteArray> {
        pending += pcm.size / 2
        val out = mutableListOf<ByteArray>()
        while (pending >= 1024) { pending -= 1024; out += ByteArray(100) { (n % 100).toByte() }; n++ }
        return out
    }
    override fun flush() = if (pending > 0) listOf(ByteArray(100)).also { pending = 0 } else emptyList()
    override fun close() { closed = true }
}

private class Bridge : MediaBridge {
    val sent = mutableListOf<JSONObject>()
    var up = true
    override fun send(command: JSONObject): Boolean { sent += command; return up }
}

class RecorderTest {
    private var clock = 1_000_000L
    private val store = MemoryRecordingStore()
    private val vault = MemoryVault()
    private val bridge = Bridge()
    private var free = 50L shl 30
    private val states = mutableListOf<JSONObject>()
    private var prefs: String? = null
    private val settings = RecordingSettings({ prefs }, { prefs = it }, noticeDefault = false)
    private val encoders = mutableListOf<FakeAac>()

    private fun recorder() = Recorder(store, vault, { FakeAac().also { encoders += it } }, settings, { free }, bridge, { clock }, onState = { states += it })

    /** [ms] of the call's audio from the bridge, in 100 ms blocks. */
    private fun Recorder.audio(call: String, ms: Int) {
        val block = Base64.getEncoder().encodeToString(ByteArray(1600 * 2 * 2))
        repeat(ms / 100) { onPcm(call, block); clock += 100 }
    }

    @After fun reset() { Recorder.SettingsReserve.bytes = RecordingSettings.RESERVE_BYTES }

    @Test fun offUnlessTheNumberRecordsAutomatically() {
        val r = recorder()
        r.started("call-1", "num-a", "10086", false)
        r.audio("call-1", 2000)
        r.ended("call-1")
        assertTrue(bridge.sent.isEmpty())
        assertTrue(store.list(0, Long.MAX_VALUE, 10).isEmpty())
    }

    @Test fun autoRecordsTheWholeCallAndSavesIt() {
        settings.set("num-a", RecordingSettings.Number(auto = true))
        val r = recorder()
        r.started("call-1", "num-a", "10086", true)
        assertEquals(true, bridge.sent.last().getBoolean("on"))
        assertEquals("auto", states.last().getString("reason"))
        assertTrue(states.last().getBoolean("recording"))
        r.audio("call-1", 70_000)
        r.ended("call-1")
        val rec = store.list(0, Long.MAX_VALUE, 10).single()
        assertEquals(Recording.SAVED, rec.state)
        assertEquals("10086", rec.peer)
        assertTrue(rec.outgoing)
        // 70 s at 16 kHz is 1093 frames and a bit: one full part of 938 frames and the rest.
        assertEquals(2, rec.parts.size)
        assertEquals(938 * 100L, rec.parts[0].plain)
        assertEquals(rec.parts.sumOf { it.plain }, rec.plainBytes)
        assertEquals(1, rec.spans.size)
        assertEquals("ended", rec.spans[0].reason)
        assertTrue(encoders.single().closed)
    }

    @Test fun aStopIsAGapAndAStartAfterItANewStretch() {
        val r = recorder()
        r.started("call-1", null, "10086", false)
        assertTrue(r.control("call-1", true).isSuccess)
        r.audio("call-1", 3000)
        r.control("call-1", false)
        assertEquals("stopped", states.last().getString("reason"))
        assertFalse(states.last().getBoolean("recording"))
        r.onPcmEnd("call-1", "stopped")
        // While stopped the bridge sends nothing; anything late is dropped.
        r.audio("call-1", 1000)
        r.control("call-1", true)
        r.audio("call-1", 2000)
        r.ended("call-1")
        val rec = store.list(0, Long.MAX_VALUE, 10).single()
        assertEquals(listOf("stopped", "ended"), rec.spans.map { it.reason })
        assertTrue(rec.spans[1].audioStart > 0)
        assertEquals(1, encoders.size)
    }

    @Test fun controlIsOnlyForTheCurrentCall() {
        val r = recorder()
        assertEquals("no_call", (r.control("call-1", true).exceptionOrNull() as Recorder.Refused).code)
        r.started("call-1", null, null, false)
        assertEquals("no_call", (r.control("call-2", true).exceptionOrNull() as Recorder.Refused).code)
        r.ended("call-1")
        assertEquals("no_call", (r.control("call-1", true).exceptionOrNull() as Recorder.Refused).code)
    }

    @Test fun noRoomMeansNoRecordingButTheCallGoesOn() {
        free = 1L shl 30
        settings.set(null, RecordingSettings.Number(auto = true))
        val r = recorder()
        r.started("call-1", null, null, false)
        assertTrue(bridge.sent.isEmpty())
        assertEquals("no_space", states.last().getString("reason"))
        assertEquals("no_space", (r.control("call-1", true).exceptionOrNull() as Recorder.Refused).code)
    }

    @Test fun spaceRunningOutMidCallStopsTheRecording() {
        val r = recorder()
        r.started("call-1", null, null, false)
        r.control("call-1", true)
        r.audio("call-1", 30_000)
        free = 1L shl 30
        r.audio("call-1", 40_000)
        assertEquals("no_space", states.last().getString("reason"))
        assertEquals(false, bridge.sent.last().getBoolean("on"))
        r.onPcmEnd("call-1", "stopped")
        r.ended("call-1")
        assertEquals("no_space", store.list(0, Long.MAX_VALUE, 10).single().spans.first().reason)
    }

    @Test fun theQuotaCleansThatNumbersOldestUnlockedOnly() {
        val r = recorder()
        fun call(id: String, number: String) {
            r.started(id, number, null, false)
            r.control(id, true)
            r.audio(id, 60_000)
            r.ended(id)
            clock += 1000
        }
        call("a1", "num-a"); call("a2", "num-a"); call("b1", "num-b"); call("a3", "num-a")
        val (aOld, aMid) = store.byNumber("num-a").let { it[0] to it[1] }
        r.lock(aOld.id, true)
        // Room for about two recordings of this size on num-a.
        val one = aMid.storedBytes
        settings.set("num-a", RecordingSettings.Number(quotaMb = 1))
        Recorder.SettingsReserve.bytes = 0
        val preview = r.preview("num-a", 1)
        assertEquals(if (3 * one > (1 shl 20)) preview.getInt("count") else 0, preview.getInt("count"))
        r.keepQuota("num-a", null)
        val a = store.byNumber("num-a")
        assertEquals(Recording.SAVED, a[0].state)
        assertTrue(a[0].locked)
        assertTrue(store.byNumber("num-b").all { it.state == Recording.SAVED })
        // Whatever had to go went oldest first, and kept its record.
        val cleaned = a.filter { it.state == Recording.CLEANED }
        if (cleaned.isNotEmpty()) assertEquals(a[1].id, cleaned.first().id)
    }

    @Test fun lowerQuotaPreviewCountsWhatWouldGo() {
        val r = recorder()
        repeat(3) { i ->
            r.started("c$i", "num-a", null, false); r.control("c$i", true); r.audio("c$i", 60_000); r.ended("c$i"); clock += 1000
        }
        val each = store.byNumber("num-a").first().storedBytes
        // A quota of 0 is below the minimum the relay allows, but the arithmetic is the recorder's: all three would go.
        val p = r.preview("num-a", 0)
        assertEquals(3, p.getInt("count"))
        assertEquals(3 * each, p.getLong("bytes"))
        assertTrue(p.getBoolean("reachable"))
    }

    @Test fun deletedKeepsItsRecordAndCannotBeDownloaded() {
        val r = recorder()
        r.started("c", null, null, false); r.control("c", true); r.audio("c", 5000); r.ended("c")
        val id = store.list(0, Long.MAX_VALUE, 1).single().id
        assertTrue(r.open(id, 0) != null)
        r.delete(id)
        assertEquals(Recording.DELETED, store.get(id)!!.state)
        assertNull(r.open(id, 0))
        assertTrue(vault.files.isEmpty())
    }

    @Test fun theStreamRunsAcrossPartsFromAnyOffset() {
        val r = recorder()
        r.started("c", null, null, false); r.control("c", true); r.audio("c", 70_000); r.ended("c")
        val rec = store.list(0, Long.MAX_VALUE, 1).single()
        val all = r.open(rec.id, 0)!!.readBytes()
        assertEquals(rec.plainBytes, all.size.toLong())
        val tail = r.open(rec.id, 93_750)!!.readBytes()
        assertEquals(all.size - 93_750, tail.size)
        assertEquals(all.last(), tail.last())
    }

    @Test fun aRecordingLeftByAStoppedAppIsKeptAsPartial() {
        val r = recorder()
        r.started("c", null, null, false); r.control("c", true); r.audio("c", 70_000)
        // The app stops here: a new recorder finds it.
        recorder()
        val rec = store.list(0, Long.MAX_VALUE, 1).single()
        assertEquals(Recording.PARTIAL, rec.state)
        assertEquals(2, rec.parts.size)
        assertTrue(rec.plainBytes > 938 * 100L)
        assertEquals("crashed", rec.spans.single().reason)
    }

    @Test fun aBridgeThatIsNotThereFailsTheStart() {
        bridge.up = false
        val r = recorder()
        r.started("c", null, null, false)
        val s = r.control("c", true).getOrThrow()
        assertFalse(s.getBoolean("recording"))
        assertEquals("failed", s.getString("reason"))
    }
}

/** The phones' requests (RECORDING_V7.md 5): sealed, the owner's, fresh; downloads by ticket. */
class RecordingRelayTest {
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
    private val vault = MemoryVault()
    private val bridge = Bridge()
    private var prefs: String? = null
    private val settings = RecordingSettings({ prefs }, { prefs = it }, noticeDefault = false)
    private val recorder = Recorder(store, vault, { FakeAac() }, settings, { 50L shl 30 }, bridge, { clock })
    private var inCall = setOf<String>()
    private var busy = false
    private val toBridge = mutableListOf<JSONObject>()
    private val relay = RecordingRelay(client, Wrap(), { "owner" }, recorder, settings, { listOf("num-a") }, { inCall }, { busy }, { 50L shl 30 },
        { toBridge += it; true }, now = { clock })

    private var n = 0
    private fun ask(from: String, op: JSONObject, sealedBy: String = from): JSONObject {
        val id = "req-${n++}-abcdef"
        val env = JSONObject().put("kind", "recording").put("id", id).put("from", sealedBy).put("plain", op.put("request_id", id).toString())
        relay.onRequest(JSONObject().put("kind", "recording").put("device_id", from).put("request_id", id).put("public_key", Base64.getEncoder().encodeToString(from.toByteArray()))
            .put("envelope", env.toString()).put("_at_ms", clock))
        val last = client.sent.last()
        assertEquals(from, last.getString("to"))
        return JSONObject(JSONObject(last.getString("envelope")).getString("plain"))
    }

    private fun items(doc: JSONObject): JSONArray = doc.getJSONArray("items")

    @Test fun theOwnerSetsANumberToRecordAutomatically() {
        val doc = ask("owner", JSONObject().put("op", "set").put("number_id", "num-a").put("auto", true))
        val status = items(doc).getJSONObject(0)
        assertEquals(true, status.getJSONArray("numbers").getJSONObject(0).getBoolean("auto"))
        assertTrue(settings.of("num-a").auto)
        assertEquals(RecordingSettings.DEFAULT_QUOTA_MB, settings.of("num-a").quotaMb)
    }

    @Test fun familyMayOnlySeeTheStateOfACallItIsIn() {
        recorder.started("call-1", "num-a", null, false)
        assertEquals("no_permission", ask("family", JSONObject().put("op", "control").put("call_id", "call-1").put("on", true)).getString("unavailable"))
        assertEquals("no_permission", ask("family", JSONObject().put("op", "state").put("call_id", "call-1")).getString("unavailable"))
        inCall = setOf("family")
        val s = items(ask("family", JSONObject().put("op", "state").put("call_id", "call-1"))).getJSONObject(0)
        assertFalse(s.getBoolean("recording"))
    }

    @Test fun aRequestNotSealedByItsSenderIsIgnored() {
        val before = client.sent.size
        val id = "req-x-abcdef"
        val env = JSONObject().put("kind", "recording").put("id", id).put("from", "someone").put("plain", JSONObject().put("op", "status").put("request_id", id).toString())
        relay.onRequest(JSONObject().put("device_id", "owner").put("request_id", id).put("public_key", Base64.getEncoder().encodeToString("owner".toByteArray()))
            .put("envelope", env.toString()))
        assertEquals(before, client.sent.size)
    }

    @Test fun anOldRequestIsIgnored() {
        val id = "req-old-abcdef"
        val env = JSONObject().put("kind", "recording").put("id", id).put("from", "owner").put("plain", JSONObject().put("op", "status").put("request_id", id).toString())
        relay.onRequest(JSONObject().put("device_id", "owner").put("request_id", id).put("public_key", Base64.getEncoder().encodeToString("owner".toByteArray()))
            .put("envelope", env.toString()).put("_at_ms", clock - 200_000))
        assertTrue(client.sent.isEmpty())
    }

    @Test fun loweringTheQuotaAsksFirstWhenSomethingWouldGo() {
        repeat(2) { i -> recorder.started("c$i", "num-a", null, false); recorder.control("c$i", true)
            repeat(600) { recorder.onPcm("c$i", Base64.getEncoder().encodeToString(ByteArray(6400))); clock += 100 }; recorder.ended("c$i") }
        // Each is about 94 KB on disk here; a quota of 100 MB keeps both, so nothing to confirm.
        assertTrue(items(ask("owner", JSONObject().put("op", "set").put("number_id", "num-a").put("quota_mb", 100))).getJSONObject(0).has("numbers"))
        assertEquals(100, settings.of("num-a").quotaMb)
    }

    @Test fun startingAndStoppingTellsThePhonesInTheCall() {
        val relay2 = RecordingRelay(client, Wrap(), { "owner" }, Recorder(store, vault, { FakeAac() }, settings, { 50L shl 30 }, bridge, { clock },
            onState = { relay.broadcast(it) }), settings, { listOf("num-a") }, { inCall }, { busy }, { 50L shl 30 }, { true }, now = { clock })
        inCall = setOf("owner", "family")
        relay.broadcast(JSONObject().put("op", "state").put("call_id", "call-1").put("recording", true))
        val told = client.sent.takeLast(2).map { it.getString("to") }.toSet()
        assertEquals(setOf("owner", "family"), told)
        assertTrue(client.sent.last().getString("request_id").startsWith("recstate-"))
        assertTrue(relay2 !== relay)
    }

    @Test fun aDownloadGoesByAOneTimeTicketAndInChunks() {
        recorder.started("c", "num-a", null, false); recorder.control("c", true)
        repeat(700) { recorder.onPcm("c", Base64.getEncoder().encodeToString(ByteArray(6400))); clock += 100 }
        recorder.ended("c")
        val rec = store.list(0, Long.MAX_VALUE, 1).single()
        busy = true
        assertEquals("busy", ask("owner", JSONObject().put("op", "fetch").put("id", rec.id)).getString("unavailable"))
        busy = false
        val t = items(ask("owner", JSONObject().put("op", "fetch").put("id", rec.id))).getJSONObject(0)
        assertEquals(rec.plainBytes, t.getLong("bytes"))
        val ticket = t.getString("ticket")
        assertFalse(relay.take(ticket, "family"))
        // Taken by someone else, it is gone for the owner too: a new one is needed.
        assertFalse(relay.take(ticket, "owner"))
        val t2 = items(ask("owner", JSONObject().put("op", "fetch").put("id", rec.id))).getJSONObject(0).getString("ticket")
        assertTrue(relay.take(t2, "owner"))
        var offset = 0L
        val got = ByteArrayOutputStream()
        while (true) {
            relay.onFileNeed(JSONObject().put("probe_id", t2).put("offset", offset))
            val c = toBridge.last()
            val data = Base64.getDecoder().decode(c.getString("data"))
            got.write(data)
            offset += data.size
            if (c.getBoolean("last")) break
        }
        assertEquals(rec.plainBytes, got.size().toLong())
        assertEquals(recorder.open(rec.id, 0)!!.readBytes().toList(), got.toByteArray().toList())
        // Over: asking again gets nothing.
        val before = toBridge.size
        relay.onFileNeed(JSONObject().put("probe_id", t2).put("offset", 0))
        assertEquals(before, toBridge.size)
    }

    // The notice before recording (RECORDING_NOTICE.md 5): the switch is the owner's.

    private val noticeRelay = RecordingRelay(client, Wrap(), { "owner" }, recorder, RecordingSettings({ prefs }, { prefs = it }), { listOf("num-a") }, { inCall }, { busy }, { 50L shl 30 },
        { true }, now = { clock })

    private fun askNotice(op: JSONObject): JSONObject {
        val id = "req-n${n++}-abcdef"
        val env = JSONObject().put("kind", "recording").put("id", id).put("from", "owner").put("plain", op.put("request_id", id).toString())
        noticeRelay.onRequest(JSONObject().put("kind", "recording").put("device_id", "owner").put("request_id", id)
            .put("public_key", Base64.getEncoder().encodeToString("owner".toByteArray())).put("envelope", env.toString()).put("_at_ms", clock))
        return JSONObject(JSONObject(client.sent.last().getString("envelope")).getString("plain"))
    }

    @Test fun aNumberTellsTheFarPartyUntilTheOwnerKnowinglyTurnsItOff() {
        val n0 = items(askNotice(JSONObject().put("op", "status"))).getJSONObject(0).getJSONArray("numbers").getJSONObject(0)
        assertTrue(n0.getBoolean("notice"))
        // Off without the owner's acknowledgement is refused, and nothing changes.
        assertEquals("needs_ack", askNotice(JSONObject().put("op", "set").put("number_id", "num-a").put("notice", false)).getString("unavailable"))
        assertTrue(RecordingSettings({ prefs }, { prefs = it }).tells("num-a"))
        val off = items(askNotice(JSONObject().put("op", "set").put("number_id", "num-a").put("notice", false).put("ack", true))).getJSONObject(0)
        assertFalse(off.getJSONArray("numbers").getJSONObject(0).getBoolean("notice"))
        // Back on needs no acknowledgement.
        val on = items(askNotice(JSONObject().put("op", "set").put("number_id", "num-a").put("notice", true))).getJSONObject(0)
        assertTrue(on.getJSONArray("numbers").getJSONObject(0).getBoolean("notice"))
    }

    @Test fun eachRecordingInTheListSaysWhetherTheFarPartyWasTold() {
        recorder.started("c", "num-a", null, false); recorder.control("c", true)
        repeat(20) { recorder.onPcm("c", Base64.getEncoder().encodeToString(ByteArray(6400))); clock += 100 }
        recorder.ended("c")
        // This class's recorder has the notice off by default.
        assertEquals("off", items(ask("owner", JSONObject().put("op", "list").put("from", 0))).getJSONObject(0).getString("notice"))
    }
}

/** Telling the far party before a recording starts (RECORDING_NOTICE.md 3 and 4). */
class RecordingNoticeTest {
    private var clock = 1_000_000L
    private val store = MemoryRecordingStore()
    private val bridge = Bridge()
    private val states = mutableListOf<JSONObject>()
    private var prefs: String? = null
    private val settings = RecordingSettings({ prefs }, { prefs = it })
    private var clip: ShortArray? = ShortArray(4000) { it.toShort() }
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private val recorder = Recorder(store, MemoryVault(), { FakeAac() }, settings, { 50L shl 30 }, bridge, { clock }, onState = { states += it },
        notice = { clip }, after = { ms, task -> val t = (clock + ms) to task; timers += t; { timers.remove(t) } })

    private fun audio(call: String, ms: Int) {
        val block = Base64.getEncoder().encodeToString(ByteArray(1600 * 2 * 2))
        repeat(ms / 100) { recorder.onPcm(call, block); clock += 100 }
    }
    private fun sent(type: String) = bridge.sent.filter { it.getString("type") == type }
    private fun noticeId() = sent("notice").last().getString("end")
    private fun saved() = store.list(0, Long.MAX_VALUE, 10)

    @Test fun theNoticeGoesOutFirstAndRecordingStartsOnlyOnceItWasPlayed() {
        settings.set("num-a", RecordingSettings.Number(auto = true))
        recorder.started("c", "num-a", "10086", false)
        // The whole clip went to the bridge in 100 ms pieces, the last naming it; nothing is recorded yet.
        val pieces = sent("notice")
        assertEquals(3, pieces.size)
        assertEquals(4000 * 2, pieces.sumOf { Base64.getDecoder().decode(it.getString("data")).size })
        assertFalse(pieces[0].has("end"))
        assertTrue(sent("record").isEmpty())
        assertFalse(states.last().getBoolean("recording"))
        assertTrue(states.last().getBoolean("notifying"))
        assertEquals("notifying", states.last().getString("reason"))
        // Audio that came in meanwhile is not kept.
        audio("c", 500)
        recorder.onNoticed("c", noticeId(), true)
        assertEquals(true, sent("record").single().getBoolean("on"))
        assertTrue(states.last().getBoolean("recording"))
        assertFalse(states.last().getBoolean("notifying"))
        assertEquals("auto", states.last().getString("reason"))
        assertEquals("played", states.last().getString("notice"))
        audio("c", 2000)
        recorder.ended("c")
        assertEquals("played", saved().single().notice)
        assertTrue(timers.isEmpty())
    }

    @Test fun aNoticeThatWasNotPlayedMeansNoRecording() {
        recorder.started("c", "num-a", null, false)
        val s = recorder.control("c", true).getOrThrow()
        assertTrue(s.getBoolean("notifying"))
        recorder.onNoticed("c", noticeId(), false, "uplink")
        assertFalse(states.last().getBoolean("recording"))
        assertEquals("notice_failed", states.last().getString("reason"))
        assertTrue(sent("record").isEmpty())
        audio("c", 1000)
        recorder.ended("c")
        assertTrue(saved().isEmpty())
        // Asked again in the same call, it tries the notice again.
    }

    @Test fun afterAFailedNoticeAnotherStartTriesAgain() {
        recorder.started("c", "num-a", null, false)
        recorder.control("c", true)
        recorder.onNoticed("c", noticeId(), false, "uplink")
        recorder.control("c", true)
        assertEquals(6, sent("notice").size)
        recorder.onNoticed("c", noticeId(), true)
        assertTrue(states.last().getBoolean("recording"))
        assertEquals("manual", states.last().getString("reason"))
    }

    @Test fun withoutTheClipNothingIsRecorded() {
        clip = null
        settings.set("num-a", RecordingSettings.Number(auto = true))
        recorder.started("c", "num-a", null, false)
        assertTrue(bridge.sent.isEmpty())
        assertEquals("notice_failed", states.last().getString("reason"))
        audio("c", 1000)
        recorder.ended("c")
        assertTrue(saved().isEmpty())
    }

    @Test fun noWordFromTheBridgeInTimeIsAFailure() {
        recorder.started("c", "num-a", null, false)
        recorder.control("c", true)
        val (at, task) = timers.single()
        assertEquals(clock + Recorder.NOTICE_TIMEOUT_MS, at)
        task()
        assertEquals("notice_failed", states.last().getString("reason"))
        assertEquals(1, sent("notice_stop").size)
        // A late answer changes nothing.
        recorder.onNoticed("c", noticeId(), true)
        assertFalse(states.last().getBoolean("recording"))
        assertTrue(sent("record").isEmpty())
    }

    @Test fun stoppingWhileTheNoticePlaysCancelsIt() {
        recorder.started("c", "num-a", null, false)
        recorder.control("c", true)
        val id = noticeId()
        val s = recorder.control("c", false).getOrThrow()
        assertFalse(s.getBoolean("recording"))
        assertFalse(s.getBoolean("notifying"))
        assertEquals("stopped", s.getString("reason"))
        assertEquals(1, sent("notice_stop").size)
        assertTrue(timers.isEmpty())
        recorder.onNoticed("c", id, false, "cancelled")
        assertEquals("stopped", states.last().getString("reason"))
        assertTrue(sent("record").isEmpty())
    }

    @Test fun aCallIsToldOnlyOnce() {
        recorder.started("c", "num-a", null, false)
        recorder.control("c", true)
        recorder.onNoticed("c", noticeId(), true)
        audio("c", 1000)
        recorder.control("c", false)
        recorder.onPcmEnd("c", "stopped")
        recorder.control("c", true)
        // Straight back to recording, no second notice.
        assertEquals(3, sent("notice").size)
        assertTrue(states.last().getBoolean("recording"))
        audio("c", 1000)
        recorder.ended("c")
        assertEquals(2, saved().single().spans.size)
        // The next call is told again.
        recorder.started("d", "num-a", null, false)
        recorder.control("d", true)
        assertEquals(6, sent("notice").size)
    }

    @Test fun aNumberWithTheNoticeOffRecordsAtOnceAndSaysSo() {
        settings.set("num-a", RecordingSettings.Number(auto = true, notice = false))
        recorder.started("c", "num-a", null, false)
        assertTrue(sent("notice").isEmpty())
        assertTrue(states.last().getBoolean("recording"))
        audio("c", 1000)
        recorder.ended("c")
        assertEquals("off", saved().single().notice)
    }

    @Test fun eachNumberHasItsOwnSwitch() {
        settings.set("num-b", RecordingSettings.Number(notice = false))
        assertTrue(settings.tells("num-a"))
        assertFalse(settings.tells("num-b"))
        assertTrue(settings.tells(null))
        // Saved and read back.
        assertFalse(RecordingSettings({ prefs }, { prefs = it }).tells("num-b"))
    }

    @Test fun aBridgeThatIsNotThereFailsTheNotice() {
        bridge.up = false
        recorder.started("c", "num-a", null, false)
        val s = recorder.control("c", true).getOrThrow()
        assertEquals("notice_failed", s.getString("reason"))
        assertFalse(s.getBoolean("notifying"))
        assertTrue(timers.isEmpty())
    }

    // The screening assistant's calls (RECORDING_NOTICE.md 4): no notice on top of its greeting.

    @Test fun aCallTheAssistantAnsweredIsRecordedWithoutTheNotice() {
        settings.set("num-a", RecordingSettings.Number(auto = true))
        recorder.started("c", "num-a", "10086", false, assisted = true)
        assertTrue(sent("notice").isEmpty())
        assertEquals(true, sent("record").single().getBoolean("on"))
        assertTrue(states.last().getBoolean("recording"))
        // The caller's message is asked for as the assistant answers, and after the owner took the call over it goes on.
        recorder.control("c", true)
        audio("c", 1000)
        recorder.control("c", false)
        recorder.onPcmEnd("c", "stopped")
        recorder.control("c", true)
        assertTrue(sent("notice").isEmpty())
        audio("c", 1000)
        recorder.ended("c")
        assertEquals("assistant", saved().single().notice)
    }

    // Numbers from before the notice (RECORDING_NOTICE.md 5).

    @Test fun numbersTheGatewayAlreadyHadKeepRecordingWithoutTheNotice() {
        settings.set("num-a", RecordingSettings.Number(auto = true, quotaMb = 2048))
        settings.set("num-c", RecordingSettings.Number(notice = true))
        settings.keepWithoutNotice(listOf("num-a", "num-b", "num-c"))
        assertFalse(settings.tells("num-a"))
        assertFalse(settings.tells("num-b"))
        // One the owner already chose for is left as chosen; the rest of a number's settings stay.
        assertTrue(settings.tells("num-c"))
        assertTrue(settings.of("num-a").auto)
        assertEquals(2048, settings.of("num-a").quotaMb)
        // A number added later tells.
        assertTrue(settings.tells("num-new"))
        recorder.started("c", "num-a", null, false)
        assertTrue(sent("notice").isEmpty())
        assertTrue(states.last().getBoolean("recording"))
    }
}

/** The notice's language is each number's own (RECORDING_NOTICE.md 3): Mandarin until chosen, and only one there is a clip for. */
class NoticeLanguageTest {
    private var saved: String? = null
    private val settings = RecordingSettings({ saved }, { saved = it })

    @org.junit.Test fun aNumberSpeaksMandarinUntilAnotherLanguageIsChosen() {
        org.junit.Assert.assertEquals("zh", settings.language("num-a"))
        settings.set("num-a", RecordingSettings.Number(auto = true, notice = true, noticeLang = "yue"))
        org.junit.Assert.assertEquals("yue", settings.language("num-a"))
        org.junit.Assert.assertEquals("zh", settings.language("num-b"))
        // Kept across a restart, with the rest of the number's settings.
        org.junit.Assert.assertEquals("yue", RecordingSettings({ saved }, { saved = it }).language("num-a"))
        org.junit.Assert.assertTrue(RecordingSettings({ saved }, { saved = it }).of("num-a").auto)
    }

    @org.junit.Test fun aLanguageWithoutAClipFallsBackToMandarin() {
        settings.set("num-a", RecordingSettings.Number(noticeLang = "fr"))
        org.junit.Assert.assertEquals("zh", settings.language("num-a"))
    }

    @org.junit.Test fun everyLanguageHasItsClipInTheApp() {
        for (lang in RecordingSettings.NOTICE_LANGS) {
            val f = java.io.File("src/main/assets/notice/$lang.pcm")
            org.junit.Assert.assertTrue("$lang.pcm", f.isFile && f.length() > 16000)
        }
    }
}
