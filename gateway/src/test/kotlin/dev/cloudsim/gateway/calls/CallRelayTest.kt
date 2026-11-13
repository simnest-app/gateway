package dev.cloudsim.gateway.calls

import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ServiceConfig
import dev.cloudsim.gateway.pairing.SoftwareSigner
import org.json.JSONObject
import dev.cloudsim.gateway.net.ControlClient.Companion.b64
import dev.cloudsim.gateway.net.ControlClient.Companion.unb64
import dev.cloudsim.gateway.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records what the relay sends instead of calling the service. */
private class FakeClient : ControlClient(ServiceConfig("127.0.0.1:1", ByteArray(32), "svc"), SoftwareSigner()) {
    val sent = mutableListOf<Pair<String, JSONObject?>>()
    /** What the service answers to a request, where a test cares; an empty object otherwise. */
    val answers = mutableMapOf<String, () -> JSONObject>()
    override fun call(method: String, path: String, body: JSONObject?, readTimeoutMs: Int): JSONObject {
        sent += "$method $path" to body
        return answers["$method $path"]?.invoke() ?: JSONObject()
    }
}

private class FakeTelephony : Telephony {
    var answers = 0
    var ends = 0
    val dialed = mutableListOf<String>()
    var systemIdle = true
    var preciseMode = false
    override fun idle() = systemIdle
    override fun precise() = preciseMode
    override fun isEmergency(number: String) = number in setOf("110", "112", "119", "120")
    override fun answer(): Boolean { answers++; return true }
    override fun end(): Boolean { ends++; return true }
    override fun dial(number: String): Boolean { dialed += number; return true }
    var ringingOn: String? = null
    val dialedOn = mutableListOf<String?>()
    var sims = setOf("work", "home")
    override fun ringingNumber() = ringingOn
    override fun hasNumber(numberId: String) = numberId in sims
    override fun dial(number: String, numberId: String?): Boolean { dialedOn += numberId; return dial(number) }
    val keys = StringBuilder()
    override fun dtmf(digit: Char): Boolean { keys.append(digit); return true }
}

/** Seals by wrapping the plaintext; opens only envelopes "signed" by the expected key. */
private class FakeSealer : Sealer {
    override fun seal(kind: String, to: String, id: String, plaintext: ByteArray) =
        JSONObject().put("kind", kind).put("to", to).put("id", id).put("plain", plaintext.decodeToString())
    override fun open(envelope: JSONObject, kind: String, senderKey: ByteArray): ByteArray? =
        if (envelope.optString("kind") == kind && envelope.optString("signer") == b64(senderKey)) envelope.getString("plain").toByteArray() else null
}

private class FakeBridge : MediaBridge {
    val sent = mutableListOf<JSONObject>()
    override fun send(command: JSONObject): Boolean { sent += command; return true }
}

/** Timers that fire only when the test moves the clock. */
class ManualScheduler : Scheduler {
    var nowMs = 0L
    private val tasks = mutableListOf<Triple<Long, () -> Unit, BooleanArray>>()
    override fun after(delayMs: Long, task: () -> Unit): () -> Unit {
        val cancelled = BooleanArray(1)
        tasks += Triple(nowMs + delayMs, task, cancelled)
        return { cancelled[0] = true }
    }
    fun advance(ms: Long) {
        val until = nowMs + ms
        while (true) {
            val next = tasks.filter { !it.third[0] && it.first <= until }.minByOrNull { it.first } ?: break
            tasks.remove(next)
            nowMs = next.first
            next.second()
        }
        nowMs = until
    }
}

class CallRelayTest {
    private val client = FakeClient()
    private val phone = FakeTelephony()
    private val bridge = FakeBridge()
    private val gatewayKey = SoftwareSigner()
    private val phoneKey = SoftwareSigner()
    private val phoneId = Protocol.deviceId(phoneKey.publicKey)
    private val clock = ManualScheduler()
    private var policy: SharingPolicy? = null
    private var leaseOk = true
    private val relay = CallRelay(client, phone, bridge, gatewayKey, { phoneId }, { policy }, clock, FakeSealer(), remote = { leaseOk })

    private fun answeredBy(device: String) {
        relay.onPhoneState(CallRelay.RINGING)
        event("call.answer", device)
        relay.onPhoneState(CallRelay.OFFHOOK)
    }

    private fun offer(signer: SoftwareSigner, from: String = phoneId, gen: Int = 0, sdp: String = "v=0") = relay.onEvent("call.media",
        JSONObject().put("call_id", relay.currentCall).put("kind", "offer").put("gen", gen).put("sdp", sdp).put("from", from)
            .put("public_key", b64(signer.publicKey)).put("signature", b64(signer.sign(Protocol.mediaMessage(relay.currentCall!!, "offer", gen, sdp)))))

    @Test
    fun signedOfferFromTheAnsweringPhoneReachesTheBridge() {
        answeredBy(phoneId)
        offer(phoneKey)
        assertEquals(listOf("quiet", "offer"), bridge.sent.map { it.getString("type") })
        relay.onBridge(JSONObject().put("type", "answer").put("call_id", relay.currentCall).put("gen", 0).put("sdp", "v=0 answer"))
        val answer = client.sent.last()
        assertEquals("POST /v1/calls/${relay.currentCall}/media", answer.first)
        assertTrue(Protocol.verify(gatewayKey.publicKey, Protocol.mediaMessage(relay.currentCall!!, "answer", 0, "v=0 answer"), unb64(answer.second!!.getString("signature"))))
    }

    private fun candidates(vararg ids: String) = org.json.JSONArray(ids.map {
        JSONObject().put("id", it).put("kind", "official").put("urls", org.json.JSONArray(listOf("turn:192.0.2.1:3478"))).put("username", "u-$it").put("credential", "p-$it")
    })

    /** The service hands this gateway its relays with the offer (RELAY_SPLIT.md 18.3): they go to the bridge as they are, nothing is asked. */
    @Test
    fun relaysCarriedByTheOfferReachTheBridge() {
        answeredBy(phoneId)
        relay.onEvent("call.media", JSONObject().put("call_id", relay.currentCall).put("kind", "offer").put("gen", 0).put("sdp", "v=0").put("from", phoneId)
            .put("public_key", b64(phoneKey.publicKey)).put("signature", b64(phoneKey.sign(Protocol.mediaMessage(relay.currentCall!!, "offer", 0, "v=0"))))
            .put("relays", candidates("a", "b")))
        val ice = bridge.sent.last { it.getString("type") == "offer" }.getJSONObject("ice")
        assertEquals(listOf("u-a", "u-b"), (0 until 2).map { ice.getJSONArray("relays").getJSONObject(it).getString("username") })
        assertTrue(client.sent.none { it.first == "GET /v1/relays" || it.first == "GET /v1/ice" })
    }

    /** The owner chose their own relay only: the service says "none" with the offer, and is not asked again. */
    @Test
    fun anOfferThatSaysNoRelaysIsNotAskedAbout() {
        answeredBy(phoneId)
        relay.onEvent("call.media", JSONObject().put("call_id", relay.currentCall).put("kind", "offer").put("gen", 0).put("sdp", "v=0").put("from", phoneId)
            .put("public_key", b64(phoneKey.publicKey)).put("signature", b64(phoneKey.sign(Protocol.mediaMessage(relay.currentCall!!, "offer", 0, "v=0"))))
            .put("relays", org.json.JSONArray()))
        assertTrue(client.sent.none { it.first == "GET /v1/relays" || it.first == "GET /v1/ice" })
        assertTrue(!bridge.sent.last { it.getString("type") == "offer" }.has("ice"))
    }

    /** An offer without them: asked once a call, not again for a restart. */
    @Test
    fun relaysAreAskedForOnceWhenTheOfferCarriesNone() {
        client.answers["GET /v1/relays"] = { JSONObject().put("relays", candidates("a")) }
        answeredBy(phoneId)
        offer(phoneKey)
        offer(phoneKey, gen = 1)
        assertEquals(1, client.sent.count { it.first == "GET /v1/relays" })
        assertTrue(bridge.sent.filter { it.getString("type") == "offer" }.all { it.getJSONObject("ice").getJSONArray("relays").length() == 1 })
    }

    /** A service from before the roster has no such route: the old one is asked and its answer passed on as it is. */
    @Test
    fun anOlderServiceIsAskedTheOldWay() {
        client.answers["GET /v1/relays"] = { throw dev.cloudsim.gateway.net.ServiceException("HTTP_404", "none", "not found") }
        client.answers["GET /v1/ice"] = { JSONObject().put("urls", org.json.JSONArray(listOf("turn:192.0.2.1:3478"))).put("username", "u").put("credential", "p") }
        answeredBy(phoneId)
        offer(phoneKey)
        assertEquals("u", bridge.sent.last { it.getString("type") == "offer" }.getJSONObject("ice").getString("username"))
    }

    /** No way to the service: the old route is not tried, and the call goes on without a relay. */
    @Test
    fun anUnreachableServiceLeavesTheCallWithoutRelays() {
        client.answers["GET /v1/relays"] = { throw dev.cloudsim.gateway.net.ServiceException("NETWORK", "query_original", "down") }
        answeredBy(phoneId)
        offer(phoneKey)
        assertTrue(client.sent.none { it.first == "GET /v1/ice" })
        assertTrue(!bridge.sent.last { it.getString("type") == "offer" }.has("ice"))
    }

    @Test
    fun offersFromOtherKeysAreRefused() {
        answeredBy(phoneId)
        // Another key claiming the paired phone's ID, and the right key over a different SDP.
        offer(SoftwareSigner())
        relay.onEvent("call.media", JSONObject().put("call_id", relay.currentCall).put("kind", "offer").put("gen", 0).put("sdp", "v=0 swapped").put("from", phoneId)
            .put("public_key", b64(phoneKey.publicKey)).put("signature", b64(phoneKey.sign(Protocol.mediaMessage(relay.currentCall!!, "offer", 0, "v=0")))))
        assertTrue(bridge.sent.none { it.getString("type") == "offer" })
    }

    private fun keys(signer: SoftwareSigner, digits: String, seq: Long, from: String = phoneId, call: String? = relay.currentCall) = relay.onEvent("call.dtmf",
        JSONObject().put("call_id", relay.currentCall).put("device_id", from).put("public_key", b64(signer.publicKey))
            .put("sealed", JSONObject().put("kind", "dtmf").put("signer", b64(signer.publicKey))
                .put("plain", JSONObject().put("call_id", call).put("digits", digits).put("seq", seq).toString()).toString()))

    @Test
    fun keysFromThePhoneInTheCallSoundInOrder() {
        answeredBy(phoneId)
        keys(phoneKey, "12", 1)
        assertEquals("1", phone.keys.toString())
        clock.advance(CallRelay.DTMF_GAP_MS)
        assertEquals("12", phone.keys.toString())
        keys(phoneKey, "#", 2)
        // A repeat or an older message changes nothing.
        keys(phoneKey, "9", 2)
        keys(phoneKey, "9", 1)
        assertEquals("12#", phone.keys.toString())
    }

    @Test
    fun keysFromAnyoneElseOrForAnotherCallAreRefused() {
        answeredBy(phoneId)
        val other = SoftwareSigner()
        keys(other, "1", 1, from = Protocol.deviceId(other.publicKey))
        // The answering phone's ID with another key, a message sealed for another call, nonsense keys.
        keys(other, "2", 2)
        keys(phoneKey, "3", 3, call = "another-call")
        keys(phoneKey, "12a", 4)
        assertEquals("", phone.keys.toString())
        // Ringing, before anyone answered: nothing either.
        relay.onPhoneState(CallRelay.IDLE)
        relay.onPhoneState(CallRelay.RINGING)
        keys(phoneKey, "5", 5)
        assertEquals("", phone.keys.toString())
    }

    @Test
    fun keysWaitForConfirmedPermissions() {
        answeredBy(phoneId)
        leaseOk = false
        keys(phoneKey, "1", 1)
        assertEquals("", phone.keys.toString())
    }

    @Test
    fun aCallDialledOnThePhoneLeavesTheUplinkWarmed() {
        // Idle with no call before it: nothing to warm.
        relay.onPhoneState(CallRelay.IDLE)
        assertEquals(emptyList<String>(), bridge.sent.map { it.getString("type") })
        relay.onPhoneState(CallRelay.OFFHOOK)
        relay.onPhoneState(CallRelay.IDLE)
        relay.onPhoneState(CallRelay.IDLE)
        assertEquals(listOf("warm"), bridge.sent.map { it.getString("type") })
    }

    @Test
    fun lostMediaEndsTheCall() {
        answeredBy(phoneId)
        offer(phoneKey)
        relay.onBridge(JSONObject().put("type", "ended").put("call_id", relay.currentCall).put("reason", "ice_lost"))
        assertEquals(1, phone.ends)
        relay.onPhoneState(CallRelay.IDLE)
        assertEquals(listOf("quiet", "offer", "stop", "warm"), bridge.sent.map { it.getString("type") })
    }

    private fun event(type: String, device: String = phoneId) =
        relay.onEvent(type, JSONObject().put("call_id", relay.currentCall).put("device_id", device).put("command_id", "c-$type"))

    private fun lastState() = client.sent.last().second!!

    @Test
    fun firstRemoteAnswerWins() {
        relay.onPhoneState(CallRelay.RINGING)
        assertEquals("POST /v1/calls", client.sent[0].first)
        event("call.answer", phoneId)
        event("call.answer", "phone-b")
        assertEquals(1, phone.answers)
        relay.onPhoneState(CallRelay.OFFHOOK)
        assertEquals("active", lastState().getString("state"))
        assertEquals(phoneId, lastState().getString("answered_by"))
        relay.onPhoneState(CallRelay.IDLE)
        assertEquals("ended", lastState().getString("state"))
        assertEquals("ended", lastState().getString("reason"))
    }

    @Test
    fun answeredOnTheGatewayItself() {
        relay.onPhoneState(CallRelay.RINGING)
        relay.onPhoneState(CallRelay.OFFHOOK)
        assertEquals(CallRelay.LOCAL, lastState().getString("answered_by"))
    }

    @Test
    fun phoneHangsUp() {
        relay.onPhoneState(CallRelay.RINGING)
        event("call.end")
        assertEquals(1, phone.ends)
        relay.onPhoneState(CallRelay.IDLE)
        assertEquals("not_answered", lastState().getString("reason"))
    }

    @Test
    fun ignoresEventsForOtherCalls() {
        relay.onPhoneState(CallRelay.RINGING)
        relay.onEvent("call.answer", JSONObject().put("call_id", "someone-else").put("device_id", "x"))
        assertEquals(0, phone.answers)
    }

    // Staged ringing (docs/architecture/FAMILY_RING_3D.md).

    private val familyKey = SoftwareSigner()
    private val familyId = Protocol.deviceId(familyKey.publicKey)
    private val otherFamily = SoftwareSigner()
    private val otherId = Protocol.deviceId(otherFamily.publicKey)

    private fun share(mode: String, assignee: String? = null, fallback: Boolean = false) {
        policy = SharingPolicy(1, listOf(Member(familyId, familyKey.publicKey, true), Member(otherId, otherFamily.publicKey, true)),
            listOf(phoneId), mode, 15, assignee, fallback)
    }

    private fun sent(path: String) = client.sent.filter { it.first == "POST /v1/calls/${relay.currentCall}/$path" }.map { it.second!! }
    private fun targets(json: JSONObject) = json.getJSONArray("targets").let { a -> (0 until a.length()).map(a::getString).toSet() }

    @Test
    fun simultaneousRingsEveryoneAtOnce() {
        share("simultaneous")
        relay.onPhoneState(CallRelay.RINGING)
        assertEquals(setOf(phoneId, familyId, otherId), targets(client.sent[0].second!!))
        event("call.answer", familyId)
        assertEquals(1, phone.answers)
    }

    @Test
    fun ownerFirstMovesToFamilyAfterTheWaitFromActualRinging() {
        share("owner_first")
        relay.onPhoneState(CallRelay.RINGING)
        assertEquals(setOf(phoneId), targets(client.sent[0].second!!))
        // Family cannot answer during the owner's stage.
        event("call.answer", familyId)
        assertEquals(0, phone.answers)
        clock.advance(3_000)
        event("call.ringing", phoneId)
        clock.advance(14_000)
        assertTrue(sent("offer").isEmpty())
        clock.advance(1_000)
        assertEquals(setOf(familyId, otherId), targets(sent("offer").single()))
        // The owner keeps ringing and may still answer.
        event("call.answer", phoneId)
        assertEquals(1, phone.answers)
    }

    @Test
    fun ownerFirstSkipsAheadWhenTheOwnerDeclinesOrNeverRings() {
        share("owner_first")
        relay.onPhoneState(CallRelay.RINGING)
        event("call.ringing", phoneId)
        event("call.decline", phoneId)
        assertEquals(1, sent("offer").size)
        // A declined owner can no longer answer.
        event("call.answer", phoneId)
        assertEquals(0, phone.answers)
        relay.onPhoneState(CallRelay.IDLE)

        relay.onPhoneState(CallRelay.RINGING)
        clock.advance(CallRelay.PREPARE_MS)
        assertEquals(1, sent("offer").size)
    }

    @Test
    fun strictAssignmentStopsEveryoneButLeavesTheCellularCall() {
        share("assigned", assignee = familyId)
        relay.onPhoneState(CallRelay.RINGING)
        assertEquals(setOf(familyId), targets(client.sent[0].second!!))
        event("call.ringing", familyId)
        clock.advance(15_000)
        assertEquals(setOf(familyId), targets(sent("withdraw").single()))
        event("call.answer", familyId)
        assertEquals(0, phone.answers)
        assertEquals(0, phone.ends)
    }

    @Test
    fun assignmentWithFallbackRingsTheRestOfTheFamily() {
        share("assigned", assignee = phoneId, fallback = true)
        relay.onPhoneState(CallRelay.RINGING)
        event("call.ringing", phoneId)
        clock.advance(15_000)
        assertEquals(setOf(familyId, otherId), targets(sent("offer").single()))
        assertTrue(sent("withdraw").isEmpty())
    }

    @Test
    fun aNewPolicyAppliesToTheNextCallButRemovalIsImmediate() {
        share("simultaneous")
        relay.onPhoneState(CallRelay.RINGING)
        // The owner switches to owner-first and removes one member during the call.
        policy = SharingPolicy(2, listOf(Member(familyId, familyKey.publicKey, true)), listOf(phoneId), "owner_first", 15, null, false)
        relay.onPolicyChanged()
        assertEquals(setOf(otherId), targets(sent("withdraw").single()))
        event("call.answer", otherId)
        assertEquals(0, phone.answers)
        // The frozen simultaneous stage still lets the remaining member answer now.
        event("call.answer", familyId)
        assertEquals(1, phone.answers)
    }

    @Test
    fun familyMediaIsAcceptedFromTheAnsweringMemberOnly() {
        share("simultaneous")
        answeredBy(familyId)
        offer(otherFamily, from = otherId)
        assertFalse(bridge.sent.any { it.getString("type") == "offer" })
        offer(familyKey, from = familyId)
        assertTrue(bridge.sent.any { it.getString("type") == "offer" })
        // Removing the member who is talking ends the call.
        policy = SharingPolicy(2, emptyList(), listOf(phoneId), "simultaneous", 15, null, false)
        relay.onPolicyChanged()
        assertEquals(1, phone.ends)
    }

    @Test
    fun policyMustBeSignedByThePairedOwner() {
        val gatewayId = Protocol.deviceId(gatewayKey.publicKey)
        fun signed(by: SoftwareSigner, revision: Int = 1): JSONObject {
            val doc = JSONObject().put("v", 1).put("gateway_id", gatewayId).put("revision", revision).put("members", org.json.JSONArray())
                .put("owner_ring", org.json.JSONArray().put(phoneId)).put("mode", "simultaneous").put("wait_s", 15).put("fallback", false)
            val raw = doc.toString().toByteArray()
            return JSONObject().put("revision", revision).put("policy", b64(raw)).put("signer_public_key", b64(by.publicKey))
                .put("signature", b64(by.sign(Protocol.policyMessage(gatewayId, revision, raw))))
        }
        assertEquals(1, SharingPolicy.verify(signed(phoneKey), gatewayId, phoneId, 0)?.revision)
        assertEquals(null, SharingPolicy.verify(signed(SoftwareSigner()), gatewayId, phoneId, 0))
        assertEquals(null, SharingPolicy.verify(signed(phoneKey), gatewayId, phoneId, 1))
    }

    // Caller number and dialing out (docs/architecture/CALLER_DIAL_3E.md).

    @Test
    fun callerNumberIsSealedForEachRingingPhone() {
        share("owner_first")
        relay.onPhoneState(CallRelay.RINGING, "13800138000")
        val sealed = client.sent[0].second!!.getJSONObject("sealed")
        assertEquals(setOf(phoneId), sealed.keys().asSequence().toSet())
        assertTrue(JSONObject(sealed.getString(phoneId)).getString("plain").contains("13800138000"))
        event("call.decline", phoneId)
        val offer = sent("offer").single().getJSONObject("sealed")
        assertEquals(setOf(familyId, otherId), offer.keys().asSequence().toSet())
    }

    private fun dialRequest(from: SoftwareSigner, number: String = "10086", id: String = "dial-0001", kind: String = "dial") = relay.onEvent("call.dial",
        JSONObject().put("call_id", id).put("device_id", Protocol.deviceId(from.publicKey)).put("public_key", b64(from.publicKey))
            .put("sealed", JSONObject().put("kind", kind).put("signer", b64(from.publicKey)).put("plain", JSONObject().put("number", number).toString()).toString()))

    private fun stateOf(id: String) = client.sent.last { it.first == "POST /v1/calls/$id/state" }.second!!

    @Test
    fun ownerDialsAndTheCallGoesActiveWhenOffHook() {
        dialRequest(phoneKey)
        assertEquals(listOf("10086"), phone.dialed)
        assertEquals("quiet", bridge.sent.last().getString("type"))
        relay.onPhoneState(CallRelay.OFFHOOK)
        assertEquals("active", stateOf("dial-0001").getString("state"))
        assertEquals(phoneId, stateOf("dial-0001").getString("answered_by"))
        // Media from the phone that dialed reaches the bridge, as for an answered call.
        offer(phoneKey)
        assertTrue(bridge.sent.any { it.getString("type") == "offer" })
        relay.onPhoneState(CallRelay.IDLE)
        assertEquals("ended", stateOf("dial-0001").getString("state"))
    }

    @Test
    fun dialNeedsTheRightAndTheSendersSeal() {
        share("simultaneous")
        dialRequest(familyKey, id = "dial-0002")
        assertEquals("refused", stateOf("dial-0002").getString("reason"))
        policy = policy!!.copy(revision = 2, members = listOf(Member(familyId, familyKey.publicKey, answer = false, dial = true)))
        // Sealed under another kind (a caller number replayed as a dial request) is refused.
        dialRequest(familyKey, id = "dial-0003", kind = "caller")
        assertEquals("refused", stateOf("dial-0003").getString("reason"))
        dialRequest(familyKey, number = "12345; rm", id = "dial-0004")
        assertEquals("refused", stateOf("dial-0004").getString("reason"))
        assertTrue(phone.dialed.isEmpty())
        dialRequest(familyKey, id = "dial-0005")
        assertEquals(listOf("10086"), phone.dialed)
    }

    @Test
    fun dialWhileBusyOrNeverOffHook() {
        relay.onPhoneState(CallRelay.RINGING, "13900000000")
        dialRequest(phoneKey, id = "dial-0006")
        assertEquals("busy", stateOf("dial-0006").getString("reason"))
        relay.onPhoneState(CallRelay.IDLE)
        dialRequest(phoneKey, id = "dial-0007")
        clock.advance(CallRelay.DIAL_MS)
        assertEquals("failed", stateOf("dial-0007").getString("reason"))
        assertEquals(1, phone.ends)
        // The next request is accepted again.
        dialRequest(phoneKey, id = "dial-0008")
        assertEquals(2, phone.dialed.size)
    }

    /** 2026-11-05: an update restarted the gateway during a dialed call, it re-read the service's events and dialed again. */
    @Test
    fun aReplayedOrStaleDialRequestIsNeverDialed() {
        relay.onEvent("call.dial", JSONObject().put("call_id", "dial-0009").put("device_id", phoneId).put("public_key", b64(phoneKey.publicKey))
            .put("_at_ms", System.currentTimeMillis() - 3 * 60_000)
            .put("sealed", JSONObject().put("kind", "dial").put("signer", b64(phoneKey.publicKey)).put("plain", JSONObject().put("number", "10086").toString()).toString()))
        assertTrue(phone.dialed.isEmpty())
        // The system already has a call this relay does not know about (it just restarted): busy, no second call.
        phone.systemIdle = false
        dialRequest(phoneKey, id = "dial-0010")
        assertTrue(phone.dialed.isEmpty())
        assertEquals("busy", stateOf("dial-0010").getString("reason"))
    }

    // Default phone app (docs/architecture/DIALER_3F.md).

    @Test
    fun aDialedCallIsConnectedOnlyWhenTheFarEndAnswers() {
        phone.preciseMode = true
        dialRequest(phoneKey, id = "dial-0011")
        relay.onPhoneState(CallRelay.OFFHOOK)
        assertEquals("alerting", stateOf("dial-0011").getString("reason"))
        relay.onFarAnswered()
        assertEquals("connected", stateOf("dial-0011").getString("reason"))
        assertEquals("active", stateOf("dial-0011").getString("state"))
        // Once is enough.
        val before = client.sent.size
        relay.onFarAnswered()
        assertEquals(before, client.sent.size)
    }

    @Test
    fun emergencyCallsAreLeftToTheSystem() {
        relay.onPhoneState(CallRelay.RINGING, "120", emergency = true)
        relay.onPhoneState(CallRelay.OFFHOOK)
        assertTrue("earpiece and media untouched", bridge.sent.isEmpty())
        relay.onPhoneState(CallRelay.IDLE)
        assertTrue("nothing reported", client.sent.isEmpty())
        // Only once it is over: the uplink is warmed for the next call.
        assertEquals(listOf("warm"), bridge.sent.map { it.getString("type") })
        // The next ordinary call is handled again.
        relay.onPhoneState(CallRelay.RINGING, "13800000000")
        assertEquals("POST /v1/calls", client.sent.single().first)
    }

    @Test
    fun anEmergencyNumberIsNeverDialedForAPhone() {
        dialRequest(phoneKey, number = "110", id = "dial-0012")
        assertTrue(phone.dialed.isEmpty())
        assertEquals("emergency", stateOf("dial-0012").getString("reason"))
    }

    // The 5-minute window (REVOKE_LEASE_5.md).

    @Test
    fun aCallCarriedOnAPhoneIsHungUpWhenPermissionsGoUnconfirmed() {
        answeredBy(phoneId)
        relay.onLeaseExpired()
        assertEquals(1, phone.ends)
    }

    @Test
    fun aCallOnTheRedmiItselfGoesOn() {
        relay.onPhoneState(CallRelay.RINGING)
        relay.onPhoneState(CallRelay.OFFHOOK)
        relay.onLeaseExpired()
        assertEquals(0, phone.ends)
    }

    @Test
    fun whilePausedACallIsNotRelayedAndAnswersAreRefused() {
        leaseOk = false
        relay.onPhoneState(CallRelay.RINGING)
        assertTrue(client.sent.none { it.first == "POST /v1/calls" })
        event("call.answer")
        assertEquals(0, phone.answers)
    }

    @Test
    fun ringingPhonesAreWithdrawnWhenTheWindowCloses() {
        relay.onPhoneState(CallRelay.RINGING)
        relay.onLeaseExpired()
        assertTrue(client.sent.last().first.endsWith("/withdraw"))
        leaseOk = false
        event("call.answer")
        assertEquals(0, phone.answers)
    }


    // Two SIMs (DUAL_SIM.md).

    private fun dialOn(numberId: String?, id: String) = relay.onEvent("call.dial",
        JSONObject().put("call_id", id).put("device_id", familyId).put("public_key", b64(familyKey.publicKey)).put("number_id", numberId ?: "")
            .put("sealed", JSONObject().put("kind", "dial").put("signer", b64(familyKey.publicKey)).put("plain", JSONObject().put("number", "10086").toString()).toString()))

    @Test
    fun aCallRingsOnlyThoseWithTheAnswerRightOnItsNumber() {
        policy = SharingPolicy(3, listOf(Member(familyId, familyKey.publicKey, answer = true, perNumber = mapOf("work" to Rights(answer = true)))),
            listOf(phoneId), "simultaneous", 15, null, false, listOf("work", "home"))
        phone.ringingOn = "home"
        relay.onPhoneState(CallRelay.RINGING)
        val home = client.sent.last { it.first == "POST /v1/calls" }.second!!
        assertEquals("home", home.getString("number_id"))
        assertEquals(listOf(phoneId), (0 until home.getJSONArray("targets").length()).map { home.getJSONArray("targets").getString(it) })
        event("call.answer", familyId)
        assertEquals("no answer on a number it may not answer", 0, phone.answers)
        relay.onPhoneState(CallRelay.IDLE)
        phone.ringingOn = "work"
        relay.onPhoneState(CallRelay.RINGING)
        val work = client.sent.last { it.first == "POST /v1/calls" }.second!!
        assertEquals(2, work.getJSONArray("targets").length())
    }

    @Test
    fun dialingUsesTheChosenSimAndOnlyWithTheRightThere() {
        policy = SharingPolicy(3, listOf(Member(familyId, familyKey.publicKey, answer = false, dial = true, perNumber = mapOf("work" to Rights(dial = true)))),
            listOf(phoneId), "simultaneous", 15, null, false, listOf("work", "home"))
        dialOn("home", "dial-home")
        assertTrue(phone.dialed.isEmpty())
        dialOn("gone", "dial-gone")
        assertEquals("no_sim", client.sent.last().second!!.getString("reason"))
        dialOn("work", "dial-work")
        assertEquals(listOf<String?>("work"), phone.dialedOn)
    }

    // Multi-party (MULTIPARTY_TRANSFER_V6.md 3.1).

    private fun posted(path: String) = client.sent.filter { it.first == "POST /v1/calls/${relay.currentCall}/$path" }.map { it.second!! }
    private fun members() = posted("member")
    private fun invite(target: String, kind: String = "invite", from: String = phoneId) = relay.onEvent("call.invite",
        JSONObject().put("call_id", relay.currentCall).put("device_id", from).put("target", target).put("kind", kind).put("command_id", "c-inv"))

    /** Answered on the owner's phone, with the family phone and another in the policy (simultaneous ringing). */
    private fun ownerInCall() {
        share("simultaneous")
        answeredBy(phoneId)
    }

    @Test
    fun anInvitedPhoneRingsJoinsTalksAndLeaves() {
        ownerInCall()
        offer(phoneKey)
        assertEquals(phoneId, bridge.sent.last().getString("member"))
        invite(familyId)
        val ringing = members().last()
        assertEquals(familyId, ringing.getString("device_id"))
        assertEquals("ringing", ringing.getString("state"))
        assertEquals(phoneId, ringing.getString("invited_by"))
        assertTrue("the caller's number is sealed for it", ringing.getString("sealed").contains(familyId))
        event("call.ringing", familyId)
        event("call.answer", familyId)
        assertEquals("joined", members().last().getString("state"))
        assertEquals(1, phone.answers) // the cellular call was answered once, by the owner
        offer(familyKey, from = familyId, sdp = "v=0 family")
        assertEquals(familyId, bridge.sent.last().getString("member"))
        relay.onBridge(JSONObject().put("type", "answer").put("call_id", relay.currentCall).put("member", familyId).put("gen", 0).put("sdp", "v=0 for family"))
        assertEquals(familyId, posted("media").last().getString("to"))
        // The family phone hangs up: only it leaves.
        event("call.leave", familyId)
        assertEquals(JSONObject().put("type", "leave").put("call_id", relay.currentCall).put("member", familyId).put("reason", "left").toString(), bridge.sent.last().toString())
        assertEquals("left", members().last().getString("state"))
        assertEquals(0, phone.ends)
        // The last phone leaving ends the cellular call.
        event("call.leave", phoneId)
        assertEquals(1, phone.ends)
    }

    @Test
    fun invitesNeedRightsRoomAndAPhoneInTheCall() {
        ownerInCall()
        // From a phone not in the call: ignored, nothing reported.
        invite(otherId, from = familyId)
        assertTrue(members().isEmpty())
        // A target the policy does not let answer.
        invite("stranger-device")
        assertEquals("target_not_allowed", members().last().getString("reason"))
        // The owner turned invites off for the family phone, which joined by transfer... first let it in.
        invite(familyId)
        event("call.ringing", familyId)
        event("call.answer", familyId)
        policy = SharingPolicy(2, listOf(Member(familyId, familyKey.publicKey, true, noInvite = true), Member(otherId, otherFamily.publicKey, true)),
            listOf(phoneId), "simultaneous", 15, null, false)
        invite(otherId, from = familyId)
        assertEquals("not_allowed", members().last().getString("reason"))
        // Transfer is still on for it.
        invite(otherId, kind = "transfer", from = familyId)
        assertEquals("ringing", members().last().getString("state"))
        assertEquals("transfer", members().last().getString("kind"))
    }

    @Test
    fun aCallHoldsThreePhones() {
        val third = SoftwareSigner()
        val thirdId = Protocol.deviceId(third.publicKey)
        policy = SharingPolicy(1, listOf(Member(familyId, familyKey.publicKey, true), Member(otherId, otherFamily.publicKey, true), Member(thirdId, third.publicKey, true)),
            listOf(phoneId), "simultaneous", 15, null, false)
        answeredBy(phoneId)
        invite(familyId)
        invite(otherId)
        invite(thirdId)
        assertEquals("full", members().last().getString("reason"))
    }

    @Test
    fun aTransferCompletesOnceTheTargetHasAudio() {
        ownerInCall()
        invite(familyId, kind = "transfer")
        event("call.ringing", familyId)
        event("call.answer", familyId)
        // Joined is not enough: the owner stays until the family phone's media is up.
        assertEquals(0, bridge.sent.count { it.getString("type") == "leave" })
        relay.onBridge(JSONObject().put("type", "member_connected").put("call_id", relay.currentCall).put("member", familyId))
        val left = members().last()
        assertEquals(phoneId, left.getString("device_id"))
        assertEquals("transferred", left.getString("reason"))
        assertEquals(familyId, left.getString("controller"))
        assertEquals(phoneId, bridge.sent.last().getString("member"))
        assertEquals(0, phone.ends)
        // Keys now come from the family phone.
        keys(familyKey, "5", 1, from = familyId)
        assertEquals("5", phone.keys.toString())
    }

    @Test
    fun anInviteLapsesUnshownOrUnanswered() {
        ownerInCall()
        invite(familyId)
        clock.advance(CallRelay.PREPARE_MS)
        assertEquals("unavailable", members().last().getString("reason"))
        invite(familyId)
        event("call.ringing", familyId)
        clock.advance(CallRelay.PREPARE_MS) // showing: the prepare timer does nothing
        assertEquals("ringing", members().last().getString("state"))
        clock.advance(15_000)
        assertEquals("no_answer", members().last().getString("reason"))
        // A decline ends it at once.
        invite(familyId)
        event("call.decline", familyId)
        assertEquals("declined", members().last().getString("reason"))
        event("call.answer", familyId) // too late
        assertEquals("declined", members().last().getString("reason"))
    }

    @Test
    fun aPhoneDroppingOutOrLosingItsRightGoesAlone() {
        ownerInCall()
        invite(familyId)
        event("call.ringing", familyId)
        event("call.answer", familyId)
        relay.onBridge(JSONObject().put("type", "member_ended").put("call_id", relay.currentCall).put("member", familyId).put("reason", "ice_lost"))
        assertEquals("ice_lost", members().last().getString("reason"))
        assertEquals(0, bridge.sent.count { it.getString("type") == "leave" }) // the bridge already let it go
        invite(familyId)
        event("call.ringing", familyId)
        event("call.answer", familyId)
        // The owner takes the family phone's answer right away: it leaves the call; the owner stays.
        policy = SharingPolicy(2, listOf(Member(otherId, otherFamily.publicKey, true)), listOf(phoneId), "simultaneous", 15, null, false)
        relay.onPolicyChanged()
        assertEquals(familyId, members().last().getString("device_id"))
        assertEquals("removed", members().last().getString("reason"))
        assertEquals(0, phone.ends)
        // Only the owner removes.
        invite(otherId)
        event("call.ringing", otherId)
        event("call.answer", otherId)
        relay.onEvent("call.remove", JSONObject().put("call_id", relay.currentCall).put("device_id", otherId).put("target", phoneId))
        assertEquals("joined", members().last().getString("state"))
        relay.onEvent("call.remove", JSONObject().put("call_id", relay.currentCall).put("device_id", phoneId).put("target", otherId))
        assertEquals("removed", members().last().getString("reason"))
    }

}

class LeaseTest {
    @Test
    fun fiveMinutesFromTheLastConfirmationAndNeverBeforeOne() {
        var t = 1_000L
        val lease = Lease { t }
        assertFalse(lease.valid())
        lease.confirm()
        t += Lease.WINDOW_MS
        assertTrue(lease.valid())
        t += 1
        assertFalse(lease.valid())
        lease.confirm()
        assertTrue(lease.valid())
    }
}