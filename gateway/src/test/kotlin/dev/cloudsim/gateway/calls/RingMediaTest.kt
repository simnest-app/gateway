package dev.cloudsim.gateway.calls

import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ControlClient.Companion.b64
import dev.cloudsim.gateway.net.ServiceConfig
import dev.cloudsim.gateway.pairing.SoftwareSigner
import dev.cloudsim.gateway.protocol.Protocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class RingClient : ControlClient(ServiceConfig("127.0.0.1:1", ByteArray(32), "svc"), SoftwareSigner()) {
    val sent = mutableListOf<Pair<String, JSONObject?>>()
    override fun call(method: String, path: String, body: JSONObject?, readTimeoutMs: Int): JSONObject {
        sent += "$method $path" to body
        return JSONObject()
    }
}

private class RingTelephony : Telephony {
    var answers = 0
    var ends = 0
    var dialed = 0
    var precise = false
    override fun answer(): Boolean { answers++; return true }
    override fun end(): Boolean { ends++; return true }
    override fun dial(number: String): Boolean { dialed++; return true }
    override fun precise() = precise
    override fun isEmergency(number: String) = number == "112"
}

private class RingSealer : Sealer {
    override fun seal(kind: String, to: String, id: String, plaintext: ByteArray) =
        JSONObject().put("kind", kind).put("to", to).put("id", id).put("plain", plaintext.decodeToString())
    override fun open(envelope: JSONObject, kind: String, senderKey: ByteArray): ByteArray? =
        if (envelope.optString("kind") == kind && envelope.optString("signer") == b64(senderKey)) envelope.getString("plain").toByteArray() else null
}

private class RingBridge : MediaBridge {
    val sent = mutableListOf<JSONObject>()
    override fun send(command: JSONObject): Boolean { sent += command; return true }
}

/**
 * The owner's phone connecting while the call rings, and full speed from the ring to the end (the user's 2026-11-16
 * decisions, docs/architecture/ANSWER_DELAY.md 14).
 */
class RingMediaTest {
    private val client = RingClient()
    private val phone = RingTelephony()
    private val bridge = RingBridge()
    private val gatewayKey = SoftwareSigner()
    private val ownerKey = SoftwareSigner()
    private val ownerId = Protocol.deviceId(ownerKey.publicKey)
    private val familyKey = SoftwareSigner()
    private val familyId = Protocol.deviceId(familyKey.publicKey)
    private val clock = ManualScheduler()
    private var leaseOk = true
    private var held = 0
    private var released = 0
    private val policy = SharingPolicy(1, listOf(Member(familyId, familyKey.publicKey, true)), listOf(ownerId), "simultaneous", 15, null, false)
    private val relay = CallRelay(client, phone, bridge, gatewayKey, { ownerId }, { policy }, clock, RingSealer(), remote = { leaseOk },
        fullSpeed = { held++; AutoCloseable { released++ } })

    private fun event(type: String, device: String) =
        relay.onEvent(type, JSONObject().put("call_id", relay.currentCall).put("device_id", device).put("command_id", "c-$type"))

    private fun offer(signer: SoftwareSigner, gen: Int = 0, sdp: String = "v=0") {
        val from = Protocol.deviceId(signer.publicKey)
        relay.onEvent("call.media", JSONObject().put("call_id", relay.currentCall).put("kind", "offer").put("gen", gen).put("sdp", sdp).put("from", from)
            .put("public_key", b64(signer.publicKey)).put("signature", b64(signer.sign(Protocol.mediaMessage(relay.currentCall!!, "offer", gen, sdp)))))
    }

    private fun types() = bridge.sent.map { it.getString("type") }

    @Test
    fun onlyTheOwnerIsToldItMayConnectWhileItRings() {
        relay.onPhoneState(CallRelay.RINGING)
        val sealed = client.sent.first { it.first == "POST /v1/calls" }.second!!.getJSONObject("sealed")
        fun plain(device: String) = JSONObject(JSONObject(sealed.getString(device)).getString("plain"))
        assertEquals(1, plain(ownerId).optInt("ring_media"))
        assertFalse(plain(familyId).has("ring_media"))
    }

    @Test
    fun theOwnersOfferWhileRingingConnectsWithoutAudioAndOpensAtItsAnswer() {
        relay.onPhoneState(CallRelay.RINGING)
        offer(ownerKey)
        val o = bridge.sent.single { it.getString("type") == "offer" }
        assertTrue(o.getBoolean("ring"))
        assertEquals(ownerId, o.getString("member"))
        // The bridge's answer goes back to the owner before anyone answered.
        relay.onBridge(JSONObject().put("type", "answer").put("call_id", relay.currentCall).put("member", ownerId).put("gen", 0).put("sdp", "v=0 answer"))
        val posted = client.sent.last()
        assertEquals("POST /v1/calls/${relay.currentCall}/media", posted.first)
        assertEquals(ownerId, posted.second!!.getString("to"))
        // A retry while it rings goes the same way.
        offer(ownerKey, gen = 1)
        assertEquals(2, bridge.sent.count { it.getString("type") == "offer" && it.optBoolean("ring") })
        event("call.answer", ownerId)
        relay.onPhoneState(CallRelay.OFFHOOK)
        val open = bridge.sent.last()
        assertEquals("open", open.getString("type"))
        assertEquals(ownerId, open.getString("member"))
        assertTrue(bridge.sent.none { it.getString("type") == "leave" })
    }

    @Test
    fun aFamilyPhonesOfferWhileRingingIsRefused() {
        relay.onPhoneState(CallRelay.RINGING)
        offer(familyKey)
        assertTrue(bridge.sent.none { it.getString("type") == "offer" })
    }

    @Test
    fun anotherAnswerClosesTheOwnersConnectionFirst() {
        relay.onPhoneState(CallRelay.RINGING)
        offer(ownerKey)
        event("call.answer", familyId)
        relay.onPhoneState(CallRelay.OFFHOOK)
        val leave = bridge.sent.single { it.getString("type") == "leave" }
        assertEquals(ownerId, leave.getString("member"))
        assertEquals("answered_elsewhere", leave.getString("reason"))
        assertTrue(bridge.sent.none { it.getString("type") == "open" })
        // The family phone's offer then starts its media as any answered call's.
        offer(familyKey)
        assertFalse(bridge.sent.last().optBoolean("ring"))
    }

    @Test
    fun answeredOnTheGatewayClosesItAndLetsGoOfFullSpeed() {
        relay.onPhoneState(CallRelay.RINGING)
        offer(ownerKey)
        assertEquals(1, held)
        relay.onPhoneState(CallRelay.OFFHOOK)
        assertEquals("leave", bridge.sent.last { it.getString("type") != "quiet" }.getString("type"))
        assertEquals(1, released)
        relay.onPhoneState(CallRelay.IDLE)
        assertEquals(1, released)
    }

    @Test
    fun theOwnerDecliningClosesIt() {
        relay.onPhoneState(CallRelay.RINGING)
        offer(ownerKey)
        event("call.decline", ownerId)
        assertEquals("declined", bridge.sent.single { it.getString("type") == "leave" }.getString("reason"))
        // Its later offers while it still rings are no longer taken.
        offer(ownerKey, gen = 1)
        assertEquals(1, bridge.sent.count { it.getString("type") == "offer" })
    }

    @Test
    fun theBridgeEndingTheConnectionWhileRingingDoesNotEndTheCall() {
        relay.onPhoneState(CallRelay.RINGING)
        offer(ownerKey)
        relay.onBridge(JSONObject().put("type", "member_ended").put("call_id", relay.currentCall).put("member", ownerId).put("reason", "ice_lost"))
        relay.onBridge(JSONObject().put("type", "ended").put("call_id", relay.currentCall).put("reason", "ice_lost"))
        event("call.answer", ownerId)
        relay.onPhoneState(CallRelay.OFFHOOK)
        // Nothing to open: the phone's offer at the answer starts the media.
        assertTrue(bridge.sent.none { it.getString("type") == "open" })
        offer(ownerKey)
        assertFalse(bridge.sent.last().optBoolean("ring"))
    }

    @Test
    fun fullSpeedIsHeldFromTheRingToEveryEnd() {
        // Not answered.
        relay.onPhoneState(CallRelay.RINGING)
        assertEquals(1 to 0, held to released)
        relay.onPhoneState(CallRelay.IDLE)
        assertEquals(1 to 1, held to released)
        // Answered by a phone, then hung up.
        relay.onPhoneState(CallRelay.RINGING)
        event("call.answer", ownerId)
        relay.onPhoneState(CallRelay.OFFHOOK)
        assertEquals(2 to 1, held to released)
        relay.onPhoneState(CallRelay.IDLE)
        assertEquals(2 to 2, held to released)
    }

    @Test
    fun noFullSpeedForAnEmergencyCallOrOneNotRelayed() {
        relay.onPhoneState(CallRelay.RINGING, "112", emergency = true)
        relay.onPhoneState(CallRelay.IDLE)
        leaseOk = false
        relay.onPhoneState(CallRelay.RINGING)
        relay.onPhoneState(CallRelay.IDLE)
        assertEquals(0, held)
    }

    @Test
    fun withdrawnWhenPermissionsLapseItCloses() {
        relay.onPhoneState(CallRelay.RINGING)
        offer(ownerKey)
        relay.onLeaseExpired()
        assertEquals("lease", bridge.sent.single { it.getString("type") == "leave" }.getString("reason"))
    }

    // A call dialed through the gateway the same way (the user's 2026-11-16 addition, ANSWER_DELAY.md 14.10).

    private fun dial(signer: SoftwareSigner, number: String = "10086", id: String = "dial-0001") = relay.onEvent("call.dial",
        JSONObject().put("call_id", id).put("device_id", Protocol.deviceId(signer.publicKey)).put("public_key", b64(signer.publicKey))
            .put("sealed", JSONObject().put("kind", "dial").put("signer", b64(signer.publicKey)).put("plain", JSONObject().put("number", number).toString()).toString()))

    @Test
    fun fullSpeedFromTheDialRequestToTheEnd() {
        dial(ownerKey)
        assertEquals(1 to 0, held to released)
        relay.onPhoneState(CallRelay.OFFHOOK)
        relay.onFarAnswered()
        assertEquals(1 to 0, held to released)
        relay.onPhoneState(CallRelay.IDLE)
        assertEquals(1 to 1, held to released)
    }

    @Test
    fun aDialedCallThatNeverGoesOffHookLetsGo() {
        dial(ownerKey)
        clock.advance(CallRelay.DIAL_MS)
        assertEquals(1, phone.ends)
        assertEquals(1 to 1, held to released)
    }

    @Test
    fun noFullSpeedForARefusedDial() {
        // Not sealed by the sender, an emergency number, or not relayed: nothing dialed, nothing held.
        dial(familyKey)
        dial(ownerKey, number = "112", id = "dial-0002")
        leaseOk = false
        dial(ownerKey, id = "dial-0003")
        assertEquals(0, phone.dialed)
        assertEquals(0, held)
    }

    @Test
    fun aDialedCallConnectsWhileTheFarEndRingsWithItsRingback() {
        // As the default phone app the gateway knows the far end still rings: the dialing phone's offer is taken then
        // and its media runs (the ringback is heard on it), so the far end's answer leaves nothing to set up.
        phone.precise = true
        dial(ownerKey)
        relay.onPhoneState(CallRelay.OFFHOOK)
        val alerting = client.sent.last { it.first == "POST /v1/calls/dial-0001/state" }.second!!
        assertEquals("alerting", alerting.getString("reason"))
        offer(ownerKey)
        val o = bridge.sent.single { it.getString("type") == "offer" }
        assertFalse(o.optBoolean("ring"))
        // A retry while it rings goes the same way.
        offer(ownerKey, gen = 1)
        assertEquals(2, bridge.sent.count { it.getString("type") == "offer" })
        relay.onFarAnswered()
        assertTrue(bridge.sent.none { it.getString("type") in setOf("open", "leave") })
    }
}
