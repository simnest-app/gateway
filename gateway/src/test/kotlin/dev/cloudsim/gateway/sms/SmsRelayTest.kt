package dev.cloudsim.gateway.sms

import dev.cloudsim.gateway.calls.ManualScheduler
import dev.cloudsim.gateway.calls.Member
import dev.cloudsim.gateway.calls.Sealer
import dev.cloudsim.gateway.calls.SharingPolicy
import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ServiceConfig
import dev.cloudsim.gateway.pairing.SoftwareSigner
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class Recorder : ControlClient(ServiceConfig("127.0.0.1:1", ByteArray(32), "svc"), SoftwareSigner()) {
    val items = mutableListOf<JSONObject>()
    override fun call(method: String, path: String, body: JSONObject?, readTimeoutMs: Int): JSONObject {
        if (path == "/v1/sms/deliver") body!!.getJSONArray("items").let { a -> (0 until a.length()).forEach { items += a.getJSONObject(it) } }
        return JSONObject()
    }
}

private class MemoryLog : SmsLog {
    val list = mutableListOf<Sms>()
    private var rev = 0L
    private fun put(s: Sms): Sms { list.removeAll { it.id == s.id }; list += s; return s }
    override fun add(id: String, peer: String, body: String, at: Long) = add(id, peer, body, at, null)
    override fun add(id: String, peer: String, body: String, at: Long, numberId: String?) = put(Sms(lastSeq() + 1, id, peer, body, at, rev = ++rev, numberId = numberId))
    override fun addOutgoing(id: String, peer: String, body: String, at: Long, sender: String, commandId: String, segments: List<String>, numberId: String?) =
        put(Sms(lastSeq() + 1, id, peer, body, at, "out", sender, commandId, segments, segments.map { Part.SENDING }, ++rev, numberId))
    override fun addOutgoing(id: String, peer: String, body: String, at: Long, sender: String, commandId: String, segments: List<String>) =
        put(Sms(lastSeq() + 1, id, peer, body, at, "out", sender, commandId, segments, segments.map { Part.SENDING }, ++rev))
    override fun update(id: String, parts: List<Part>) = byId(id)?.let { put(it.copy(parts = parts, rev = ++rev)) }
    override fun byId(id: String) = list.find { it.id == id }
    override fun byCommand(commandId: String) = list.find { it.commandId == commandId }
    override fun changedAfter(rev: Long, limit: Int) = list.filter { it.rev > rev }.sortedBy { it.rev }.take(limit)
    override fun lastSeq() = list.maxOfOrNull { it.seq } ?: 0
    override fun sending() = list.filter { it.parts.contains(Part.SENDING) }
    override fun between(from: Long, to: Long, limit: Int) = list.filter { it.at in from until to }.sortedBy { it.at }.take(limit)
    override fun firstAt() = list.minOfOrNull { it.at }
    val void = mutableSetOf<String>()
    override fun cancel(commandId: String) { void += commandId }
    override fun cancelled(commandId: String) = commandId in void
}

private class MemorySince : Since {
    val map = mutableMapOf<String, Long>()
    override fun get(device: String) = map[device]
    override fun put(device: String, seq: Long) { map[device] = seq }
    override fun remove(device: String) { map.remove(device) }
    override fun devices() = map.keys.toSet()
}

/** "Seals" by wrapping; opens only envelopes whose "signer" names the sender's key. */
private class WrapSealer : Sealer {
    override fun seal(kind: String, to: String, id: String, plaintext: ByteArray) =
        JSONObject().put("kind", kind).put("to", to).put("id", id).put("plain", plaintext.decodeToString())
    override fun open(envelope: JSONObject, kind: String, senderKey: ByteArray): ByteArray? =
        if (envelope.optString("kind") == kind && envelope.optString("signer") == senderKey.decodeToString()) envelope.getString("plain").toByteArray() else null
}

private class FakeRadio : SmsRadio {
    val sent = mutableListOf<Pair<String, List<Int>>>()
    var sentBox: Boolean? = false
    override fun divide(body: String) = body.chunked(10)
    override fun send(id: String, peer: String, segments: List<Pair<Int, String>>): Boolean { sent += id to segments.map { it.first }; return true }
    val sentOn = mutableListOf<String?>()
    override fun send(id: String, peer: String, segments: List<Pair<Int, String>>, numberId: String?): Boolean { sentOn += numberId; return send(id, peer, segments) }
    override fun inSentBox(peer: String, body: String, sinceMs: Long) = sentBox
    val system = mutableListOf<Sms>()
    override fun systemHistory(from: Long, to: Long, limit: Int) = system.filter { it.at in from until to }.take(limit)
}

class SmsRelayTest {
    private val client = Recorder()
    private val log = MemoryLog()
    private val radio = FakeRadio()
    private val clock = ManualScheduler()
    private var policy: SharingPolicy? = null
    private var leaseOk = true
    private val relay = SmsRelay(client, log, MemorySince(), WrapSealer(), radio, clock, { "owner" }, { policy }, now = { clock.nowMs + 1_000_000 }, remote = { leaseOk })

    private fun records(d: String) = client.items.filter { it.getString("to") == d && JSONObject(it.getString("envelope")).getString("kind") == "sms" }
        .map { JSONObject(JSONObject(it.getString("envelope")).getString("plain")) }
    private fun controls(d: String) = client.items.filter { it.getString("to") == d && JSONObject(it.getString("envelope")).getString("kind") == "smsctl" }
        .map { JSONObject(JSONObject(it.getString("envelope")).getString("plain")).getString("op") }
    private fun latest(d: String, id: String) = records(d).last { it.getString("id") == id }

    private fun members(vararg m: Member) {
        policy = SharingPolicy(1, m.toList(), listOf("owner"), "simultaneous", 15, null, false)
        relay.onPolicy()
    }

    private var n = 0
    private fun command(from: String, op: String, peer: String = "13800138000", body: String = "", message: String = "", confirm: Boolean = false, target: String = "", number: String = "",
                        id: String = "cmd-${++n}", at: Long = clock.nowMs + 1_000_000) {
        val plain = JSONObject().put("op", op).put("peer", peer).put("body", body).put("message", message).put("confirm_unknown", confirm).put("target", target).put("number_id", number)
        relay.onCommand(from, from.toByteArray(), id, JSONObject().put("kind", "smscmd").put("id", id).put("signer", from).put("plain", plain.toString()), at)
    }

    // Receiving (SMS_4A.md).

    @Test
    fun onlyReadersGetReceivedMessagesWithANotice() {
        members(Member("fam", ByteArray(0), answer = true, smsView = true), Member("nosms", ByteArray(0), answer = true))
        relay.onReceived("+8613800138000", "你好", 1)
        assertEquals(listOf("你好"), records("owner").map { it.getString("body") })
        assertEquals(1, records("fam").size)
        assertTrue(records("nosms").isEmpty())
        assertTrue(client.items.all { it.getBoolean("notify") })
    }

    @Test
    fun aNewReaderStartsAtTheNextMessageAndSyncRespectsIt() {
        relay.onReceived("10086", "before", 1)
        members(Member("late", ByteArray(0), answer = false, smsView = true))
        relay.onReceived("10086", "after", 2)
        client.items.clear()
        relay.onSync("late", 0)
        assertEquals(listOf("after"), records("late").map { it.getString("body") })
    }

    // Sending (SMS_4B.md).

    @Test
    fun sendSplitsReportsEachPartAndReachesReadersAndTheSender() {
        members(Member("fam", ByteArray(0), answer = false, smsView = true), Member("sender", ByteArray(0), answer = false, smsSend = true))
        command("sender", "send", body = "a long message of 3 parts")
        val (id, parts) = radio.sent.single()
        assertEquals(listOf(0, 1, 2), parts)
        assertEquals("sending", latest("sender", id).getString("status"))
        relay.onPart(id, 0, true); relay.onPart(id, 1, true); relay.onPart(id, 2, true)
        assertEquals("sent", latest("sender", id).getString("status"))
        assertTrue(latest("sender", id).getBoolean("mine"))
        assertEquals("sent", latest("fam", id).getString("status"))
        assertEquals("sent", latest("owner", id).getString("status"))
        // Sending never makes a lock-screen notice.
        assertTrue(client.items.none { it.getBoolean("notify") })
    }

    @Test
    fun aRepeatedOrStaleOrForeignCommandSendsNothingMore() {
        members(Member("sender", ByteArray(0), answer = false, smsSend = true), Member("viewer", ByteArray(0), answer = false, smsView = true))
        command("sender", "send", body = "hello", id = "cmd-same")
        command("sender", "send", body = "hello", id = "cmd-same")
        assertEquals(1, radio.sent.size)
        command("sender", "send", body = "old", at = clock.nowMs + 1_000_000 - 2 * SmsRelay.FRESH_MS)
        command("viewer", "send", body = "no right")
        relay.onCommand("sender", "someone-else".toByteArray(), "cmd-x", JSONObject().put("kind", "smscmd").put("id", "cmd-x").put("signer", "sender")
            .put("plain", JSONObject().put("op", "send").put("peer", "13800138000").put("body", "forged").toString()), clock.nowMs + 1_000_000)
        assertEquals(1, radio.sent.size)
    }

    @Test
    fun aSendOnlyMemberSeesOnlyItsOwnRecords() {
        members(Member("sender", ByteArray(0), answer = false, smsSend = true))
        relay.onReceived("10086", "incoming", 1)
        command("owner", "send", body = "owner's")
        command("sender", "send", body = "mine")
        assertEquals(listOf("mine"), records("sender").map { it.getString("body") }.distinct())
        relay.onSync("sender", 0)
        assertEquals(listOf("mine"), records("sender").map { it.getString("body") }.distinct())
    }

    /** 2026-11-05: the owner held "+86…" from a received SMS while a family phone typed the bare number; no conflict showed. */
    @Test
    fun aTypedNumberAndTheNetworkFormAreOneConversation() {
        members(Member("fam", ByteArray(0), answer = false, smsSend = true))
        command("owner", "occupy", peer = "+8613800138000")
        command("fam", "occupy", peer = "13800138000")
        assertEquals(listOf("busy"), controls("fam"))
    }

    @Test
    fun replyOccupationAndExplicitTakeover() {
        members(Member("fam", ByteArray(0), answer = false, smsSend = true))
        command("owner", "occupy")
        assertEquals(listOf("occupied"), controls("owner"))
        command("fam", "send", body = "x")
        assertEquals(listOf("busy"), controls("fam"))
        assertTrue(radio.sent.isEmpty())
        command("fam", "takeover")
        assertEquals("taken", controls("owner").last())
        command("fam", "send", body = "now mine")
        assertEquals(1, radio.sent.size)
        // Two minutes without editing lets the conversation go.
        command("owner", "occupy")
        clock.advance(SmsRelay.HOLD_MS)
        command("fam", "occupy")
        assertEquals("occupied", controls("fam").last())
    }

    @Test
    fun noReportIsCheckedInTheSentBoxAndUnknownNeedsConfirmationToResend() {
        command("owner", "send", body = "twenty characters!!")
        val id = radio.sent.single().first
        relay.onPart(id, 0, true)
        radio.sentBox = false
        clock.advance(SmsRelay.RESULT_MS)
        assertEquals("unknown", latest("owner", id).getString("status"))
        command("owner", "retry", message = id)
        assertEquals(1, radio.sent.size)
        command("owner", "retry", message = id, confirm = true)
        assertEquals(listOf(1), radio.sent.last().second)
    }

    @Test
    fun foundInTheSentBoxCountsAsSent() {
        command("owner", "send", body = "short")
        val id = radio.sent.single().first
        radio.sentBox = true
        clock.advance(SmsRelay.RESULT_MS)
        assertEquals("sent", latest("owner", id).getString("status"))
    }

    @Test
    fun aPartlySentMessageResendsOnlyTheFailedParts() {
        command("owner", "send", body = "0123456789abcdefghij0123")
        val id = radio.sent.single().first
        relay.onPart(id, 0, true); relay.onPart(id, 1, false); relay.onPart(id, 2, true)
        assertEquals("partial", latest("owner", id).getString("status"))
        command("owner", "retry", message = id)
        assertEquals(listOf(1), radio.sent.last().second)
        relay.onPart(id, 1, true)
        assertEquals("sent", latest("owner", id).getString("status"))
    }

    @Test
    fun aRestartChecksWhatWasStillSending() {
        command("owner", "send", body = "cut off")
        val id = radio.sent.single().first
        radio.sentBox = null
        relay.onStart()
        assertEquals("unknown", latest("owner", id).getString("status"))
        assertEquals(1, radio.sent.size)
    }

    /** 2026-11-05: a report that arrives after the Sent box check (MIUI held the send for confirmation) still counts. */
    @Test
    fun aLateReportReplacesUnknown() {
        command("owner", "send", body = "late")
        val id = radio.sent.single().first
        clock.advance(SmsRelay.RESULT_MS)
        assertEquals("unknown", latest("owner", id).getString("status"))
        relay.onPart(id, 0, false)
        assertEquals("failed", latest("owner", id).getString("status"))
    }

    // Two SIMs (DUAL_SIM.md).

    private fun twoNumbers(vararg m: Member) {
        policy = SharingPolicy(2, m.toList(), listOf("owner"), "simultaneous", 15, null, false, listOf("work", "home"))
        relay.onPolicy()
    }

    @Test
    fun aMessageGoesOnlyToThoseWhoMayReadItsNumber() {
        twoNumbers(Member("fam", ByteArray(0), answer = false, smsView = true, perNumber = mapOf("work" to dev.cloudsim.gateway.calls.Rights(smsView = true))))
        relay.onReceived("13800138000", "on home", 1, "home")
        relay.onReceived("13800138000", "on work", 2, "work")
        assertEquals(listOf("on work"), records("fam").map { it.getString("body") })
        assertEquals(listOf("home", "work"), records("owner").map { it.getString("number_id") })
        assertEquals("work", client.items.last { it.getString("to") == "fam" }.getString("number_id"))
    }

    @Test
    fun sendingNeedsTheRightOnThatNumberAndUsesItsSim() {
        twoNumbers(Member("fam", ByteArray(0), answer = false, smsSend = true, perNumber = mapOf("work" to dev.cloudsim.gateway.calls.Rights(smsSend = true))))
        command("fam", "send", body = "x", number = "home")
        assertTrue(radio.sent.isEmpty())
        command("fam", "send", body = "x", number = "work")
        assertEquals(listOf<String?>("work"), radio.sentOn)
    }

    @Test
    fun replyHoldsArePerNumber() {
        twoNumbers(Member("fam", ByteArray(0), answer = false, smsSend = true, perNumber = mapOf(
            "work" to dev.cloudsim.gateway.calls.Rights(smsSend = true), "home" to dev.cloudsim.gateway.calls.Rights(smsSend = true))))
        command("owner", "occupy", number = "work")
        command("fam", "occupy", number = "home")
        assertEquals(listOf("occupied"), controls("fam"))
        command("fam", "occupy", number = "work")
        assertEquals("busy", controls("fam").last())
    }

    // The 5-minute window (REVOKE_LEASE_5.md).

    @Test
    fun whilePausedMessagesAreHeldAndCommandsRefused() {
        leaseOk = false
        relay.onReceived("13800138000", "while away", 1)
        assertTrue(records("owner").isEmpty())
        command("owner", "send", body = "x")
        assertTrue(radio.sent.isEmpty())
        leaseOk = true
        relay.onResume()
        assertEquals("while away", records("owner").single().getString("body"))
        assertTrue(client.items.none { it.optBoolean("notify") })
    }

    // Cancelling and history (SMS_4C.md).

    private fun controlTargets(d: String) = client.items.filter { it.getString("to") == d && JSONObject(it.getString("envelope")).getString("kind") == "smsctl" }
        .map { JSONObject(JSONObject(it.getString("envelope")).getString("plain")) }.filter { it.has("target") }

    @Test
    fun aCancelledCommandIsNeverSentEvenIfItArrivesLater() {
        command("owner", "cancel", target = "pending-command-1")
        assertEquals("cancelled", controlTargets("owner").single().getString("op"))
        command("owner", "send", body = "late", id = "pending-command-1")
        assertTrue(radio.sent.isEmpty())
        assertEquals(listOf("cancelled", "cancelled"), controlTargets("owner").map { it.getString("op") })
    }

    @Test
    fun cancellingASentCommandReturnsItsRealState() {
        command("owner", "send", body = "already", id = "sent-command-1")
        val id = radio.sent.single().first
        relay.onPart(id, 0, true)
        command("owner", "cancel", target = "sent-command-1")
        assertTrue(controlTargets("owner").isEmpty())
        assertEquals("sent", latest("owner", id).getString("status"))
        assertEquals(1, radio.sent.size)
    }

    @Test
    fun historyIsClippedToTheGrantedRangeAndIncludesTheSystemStoreBeforeTheGateway() {
        val t = 1_000_000_000L
        radio.system += Sms(0, "sys-1", "+8613800138000", "old", t - 5_000, "in")
        radio.system += Sms(0, "sys-2", "+8613800138000", "too old", t - 50_000, "in")
        log.add("kept-1", "+8613800138000", "kept", t)
        log.add("kept-2", "+8613800138000", "outside", t + 50_000)
        members(Member("fam", ByteArray(0), answer = false, smsView = true, historyFrom = t - 10_000, historyTo = t + 10_000),
            Member("nohist", ByteArray(0), answer = false, smsView = true))
        client.items.clear()
        relay.onHistory("fam", 0, Long.MAX_VALUE)
        assertEquals(listOf("sys-1", "kept-1"), records("fam").map { it.getString("id") })
        assertTrue(client.items.none { it.optBoolean("notify") })
        relay.onHistory("nohist", 0, Long.MAX_VALUE)
        assertTrue(records("nohist").isEmpty())
        relay.onHistory("owner", 0, Long.MAX_VALUE)
        assertEquals(listOf("sys-1", "sys-2", "kept-1", "kept-2").sorted(), records("owner").map { it.getString("id") }.sorted())
    }

}

class CanonicalPeerTest {
    @Test
    fun oneFormPerNumber() {
        assertEquals("+8613800138000", canonicalPeer("+8613800138000"))
        assertEquals("+8613800138000", canonicalPeer("13800138000"))
        assertEquals("+8613800138000", canonicalPeer("0086 138-0013-8000"))
        assertEquals("+8613800138000", canonicalPeer("+86 138 0013 8000"))
        assertEquals("10086", canonicalPeer("10086"))
        assertEquals("+12025550100", canonicalPeer("+1 202 555 0100"))
    }
}
