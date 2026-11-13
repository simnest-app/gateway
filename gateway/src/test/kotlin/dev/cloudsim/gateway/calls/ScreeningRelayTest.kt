package dev.cloudsim.gateway.calls

import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ControlClient.Companion.b64
import dev.cloudsim.gateway.net.ServiceConfig
import dev.cloudsim.gateway.pairing.SoftwareSigner
import dev.cloudsim.gateway.protocol.Protocol
import dev.cloudsim.gateway.screening.Decision
import dev.cloudsim.gateway.screening.Known
import dev.cloudsim.gateway.screening.RingContext
import dev.cloudsim.gateway.screening.ScreenClips
import dev.cloudsim.gateway.screening.ScreenSettings
import dev.cloudsim.gateway.screening.Screener
import dev.cloudsim.gateway.screening.ScreeningRules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class ScreenClient : ControlClient(ServiceConfig("127.0.0.1:1", ByteArray(32), "svc"), SoftwareSigner()) {
    val sent = mutableListOf<Pair<String, JSONObject?>>()
    override fun call(method: String, path: String, body: JSONObject?, readTimeoutMs: Int): JSONObject {
        sent += "$method $path" to body
        return JSONObject()
    }
}

private class ScreenTelephony : Telephony {
    var answers = 0
    var ends = 0
    override fun answer(): Boolean { answers++; return true }
    override fun end(): Boolean { ends++; return true }
    override fun dial(number: String) = true
}

private class ScreenBridge : MediaBridge {
    val sent = mutableListOf<JSONObject>()
    override fun send(command: JSONObject): Boolean { sent += command; return true }
    fun types() = sent.map { it.getString("type") }
}

private class ScreenSealer : Sealer {
    override fun seal(kind: String, to: String, id: String, plaintext: ByteArray) = JSONObject().put("kind", kind).put("to", to)
    override fun open(envelope: JSONObject, kind: String, senderKey: ByteArray): ByteArray? =
        if (envelope.optString("kind") == kind && envelope.optString("signer") == b64(senderKey)) envelope.getString("plain").toByteArray() else null
}

/** Call screening in the relay (SCREENING_ASSISTANT.md 10.3): who answers, what the bridge is told, what phones may do. */
class ScreeningRelayTest {
    private val client = ScreenClient()
    private val phone = ScreenTelephony()
    private val bridge = ScreenBridge()
    private val ownerKey = SoftwareSigner()
    private val owner = Protocol.deviceId(ownerKey.publicKey)
    private val clock = ManualScheduler()
    private var settings = ScreenSettings(ScreenSettings.DIRECT, assets = "set")
    private val rules = ScreeningRules(now = { clock.nowMs + 1_000_000 })
    private var known = Known.UNKNOWN
    private val finished = mutableListOf<JSONObject>()
    private val recorded = mutableListOf<String>()
    private val transcribe = mutableListOf<String>()
    private val screener = object : Screener {
        override fun decide(settings: ScreenSettings?, numberId: String?, caller: String?, emergencyCaller: Boolean, remote: Boolean): Decision =
            rules.decide(RingContext(settings, caller, known, emergencyCaller, remote, bridgeReady = true, assetsReady = true))
        override fun clips(numberId: String?) = object : ScreenClips {
            override fun greeting() = ShortArray(16000)
            override fun hold() = ShortArray(8000)
            override fun closing() = ShortArray(8000)
            override fun reply(index: Int) = if (index in 0..3) ShortArray(4000) else null
        }
        override fun emergency() { rules.lastEmergency = clock.nowMs + 1_000_000 }
        override fun recordMessage(callId: String) { recorded += callId }
        override fun prepare(callId: String) { transcribe += "prepare" }
        override fun heard(callId: String, pcm: ShortArray) { transcribe += "heard" }
        override fun said(callId: String, numberId: String?, clip: String) { transcribe += "said:$clip" }
        override fun stopTranscribing(callId: String, over: Boolean) { transcribe += if (over) "stop:over" else "stop" }
        override fun finished(callId: String, numberId: String?, summary: JSONObject) { finished += summary }
    }
    private val relay = CallRelay(client, phone, bridge, SoftwareSigner(), { owner },
        { SharingPolicy.none(owner).copy(screening = mapOf("*" to settings)) }, clock, ScreenSealer(), now = { clock.nowMs }, screener = screener)

    /** A call rings; screened directly, the assistant answers after its few rings. */
    private fun ring(number: String = "13800138000") {
        relay.onPhoneState(CallRelay.RINGING, number)
        if (settings.mode == ScreenSettings.DIRECT) clock.advance(CallRelay.DIRECT_WAIT_MS)
    }
    private fun states() = client.sent.filter { it.first.endsWith("/state") }.map { it.second!! }
    private fun said(id: String) = relay.onBridge(JSONObject().put("type", "said").put("call_id", relay.currentCall).put("id", id))

    @Test
    fun theAssistantAnswersAnUnknownCallerAtOnce() {
        ring()
        val reported = client.sent.first { it.first == "POST /v1/calls" }.second!!
        assertEquals("direct", reported.getString("screening"))
        assertEquals(1, phone.answers)
        relay.onPhoneState(CallRelay.OFFHOOK)
        val active = states().last()
        assertEquals("assistant", active.getString("answered_by"))
        assertEquals("screen:greeting", active.getString("reason"))
        assertEquals(listOf("quiet", "assist"), bridge.types().take(2))
        // The greeting goes to the bridge in 100 ms pieces; the last names it.
        val says = bridge.sent.filter { it.getString("type") == "say" }
        assertEquals("greeting", says.last().getString("end"))
        assertEquals(listOf(relay.currentCall), recorded)
    }

    /** The notice goes at once, the assistant answers only after a few rings (the user's 2026-11-10 request). */
    @Test
    fun theAssistantLetsItRingAFewSecondsFirst() {
        relay.onPhoneState(CallRelay.RINGING, "13800138000")
        assertEquals("direct", client.sent.first { it.first == "POST /v1/calls" }.second!!.getString("screening"))
        clock.advance(CallRelay.DIRECT_WAIT_MS - 1)
        assertEquals(0, phone.answers)
        clock.advance(1)
        assertEquals(1, phone.answers)
    }

    /** 旁听 during those rings: the assistant answers now and the phone listens as soon as it has. */
    @Test
    fun listeningDuringTheRingsAnswersAtOnce() {
        relay.onPhoneState(CallRelay.RINGING, "13800138000")
        relay.onEvent("call.answer", JSONObject().put("call_id", relay.currentCall).put("device_id", owner).put("listen", true))
        assertEquals(1, phone.answers)
        relay.onPhoneState(CallRelay.OFFHOOK)
        assertEquals("assistant", states().last { it.has("answered_by") && it.optString("state") == "active" }.getString("answered_by"))
        assertEquals("listen", client.sent.last { it.first.endsWith("/member") }.second!!.getString("kind"))
        assertTrue(bridge.sent.last { it.getString("type") == "listen" }.getBoolean("on"))
        clock.advance(CallRelay.DIRECT_WAIT_MS)
        assertEquals(1, phone.answers)
    }

    /** Ready during the rings, fed while the assistant has the call, stopped when the owner takes it, over at the end. */
    @Test
    fun theTranscriberFollowsTheScreening() {
        ring()
        relay.onPhoneState(CallRelay.OFFHOOK)
        relay.onBridge(JSONObject().put("type", "far").put("call_id", relay.currentCall).put("data", b64(ByteArray(3200))))
        relay.onEvent("call.screen", JSONObject().put("call_id", relay.currentCall).put("device_id", owner).put("public_key", b64(ownerKey.publicKey))
            .put("sealed", JSONObject().put("kind", "screen").put("signer", b64(ownerKey.publicKey))
                .put("plain", JSONObject().put("call_id", relay.currentCall).put("action", "takeover").toString()).toString()))
        relay.onBridge(JSONObject().put("type", "far").put("call_id", relay.currentCall).put("data", b64(ByteArray(3200))))
        relay.onPhoneState(CallRelay.IDLE)
        assertEquals(listOf("prepare", "said:greeting", "heard", "stop", "stop:over"), transcribe)
    }

    /** The caller gives up during the rings: nobody answers. */
    @Test
    fun aCallerWhoHangsUpDuringTheRingsIsNotAnswered() {
        relay.onPhoneState(CallRelay.RINGING, "13800138000")
        relay.onPhoneState(CallRelay.IDLE)
        clock.advance(CallRelay.DIRECT_WAIT_MS)
        assertEquals(0, phone.answers)
        assertEquals("ended", states().last().getString("state"))
    }

    @Test
    fun knownCallersAndRepeatsRingAsUsual() {
        known = Known.KNOWN
        ring()
        assertEquals(0, phone.answers)
        assertFalse(client.sent.first { it.first == "POST /v1/calls" }.second!!.has("screening"))
        relay.onPhoneState(CallRelay.IDLE)
        // Unknown, but the same number again within 3 minutes: it rings too.
        known = Known.UNKNOWN
        clock.advance(60_000)
        ring()
        assertEquals(0, phone.answers)
    }

    @Test
    fun aCallerWhoSaysNothingIsToldGoodbye() {
        ring()
        relay.onPhoneState(CallRelay.OFFHOOK)
        said("greeting")
        clock.advance(8_000)
        assertEquals("screen:closing", states().last().getString("reason"))
        said("closing")
        assertEquals(1, phone.ends)
        relay.onPhoneState(CallRelay.IDLE)
        assertEquals("message", finished.single().getString("result"))
    }

    private fun unheard() = relay.onBridge(JSONObject().put("type", "ended").put("call_id", relay.currentCall).put("reason", "route_unknown"))
    private fun members() = client.sent.filter { it.first.endsWith("/member") }.map { it.second!! }

    /** The assistant's uplink failed: the caller is not hung up on, the phones ring (the user's 2026-11-11 decision). */
    @Test
    fun anAssistantNotHeardRingsThePhonesInstead() {
        ring()
        relay.onPhoneState(CallRelay.OFFHOOK)
        unheard()
        assertEquals(0, phone.ends)
        val rung = members().last()
        assertEquals(owner, rung.getString("device_id"))
        assertEquals("ringing", rung.getString("state"))
        assertEquals("assistant", rung.getString("kind"))
        assertEquals("screen:unheard", states().last().getString("reason"))
        // The earpiece and the microphone stay silent while it rings.
        assertEquals("quiet", bridge.types().last())
        assertTrue("stop" in transcribe)
        // The owner answers: an ordinary call from here on.
        relay.onEvent("call.ringing", JSONObject().put("call_id", relay.currentCall).put("device_id", owner))
        relay.onEvent("call.answer", JSONObject().put("call_id", relay.currentCall).put("device_id", owner))
        assertEquals("joined", members().last().getString("state"))
        assertEquals(owner, states().last().getString("answered_by"))
        clock.advance(60_000)
        assertEquals(0, phone.ends)
        relay.onPhoneState(CallRelay.IDLE)
        assertEquals("taken", finished.single().getString("result"))
    }

    @Test
    fun nobodyAnsweringForAnAssistantNotHeardEndsTheCall() {
        ring()
        relay.onPhoneState(CallRelay.OFFHOOK)
        unheard()
        relay.onEvent("call.ringing", JSONObject().put("call_id", relay.currentCall).put("device_id", owner))
        clock.advance(CallRelay.DEFAULT_WAIT_S * 1000L - 1)
        assertEquals(0, phone.ends)
        clock.advance(1)
        assertEquals(1, phone.ends)
        assertEquals("left", members().last().getString("state"))
        relay.onPhoneState(CallRelay.IDLE)
        assertEquals("unheard", finished.single().getString("result"))
    }

    /** A phone that never shows the ring is given up after the usual time, and the call ends. */
    @Test
    fun aPhoneThatDoesNotShowTheRingIsGivenUp() {
        ring()
        relay.onPhoneState(CallRelay.OFFHOOK)
        unheard()
        clock.advance(CallRelay.PREPARE_MS)
        assertEquals(1, phone.ends)
    }

    /** With a phone already on the call the old rule holds: the call ends. */
    @Test
    fun anUplinkLostWithAPhoneOnTheCallStillEndsIt() {
        ring()
        relay.onPhoneState(CallRelay.OFFHOOK)
        relay.onEvent("call.answer", JSONObject().put("call_id", relay.currentCall).put("device_id", owner).put("listen", true))
        unheard()
        assertEquals(1, phone.ends)
    }

    @Test
    fun aListenerTakesTheCallOver() {
        ring()
        relay.onPhoneState(CallRelay.OFFHOOK)
        relay.onEvent("call.answer", JSONObject().put("call_id", relay.currentCall).put("device_id", owner).put("listen", true))
        assertEquals(owner, bridge.sent.last { it.getString("type") == "listen" }.getString("member"))
        assertTrue(bridge.sent.last { it.getString("type") == "listen" }.getBoolean("on"))
        val joined = client.sent.last { it.first.endsWith("/member") }.second!!
        assertEquals("listen", joined.getString("kind"))
        // The owner takes over, sealed by its own key.
        relay.onEvent("call.screen", JSONObject().put("call_id", relay.currentCall).put("device_id", owner).put("public_key", b64(ownerKey.publicKey))
            .put("sealed", JSONObject().put("kind", "screen").put("signer", b64(ownerKey.publicKey))
                .put("plain", JSONObject().put("call_id", relay.currentCall).put("action", "takeover").toString()).toString()))
        assertEquals(owner, states().last().getString("answered_by"))
        assertFalse(bridge.sent.last { it.getString("type") == "assist" }.getBoolean("on"))
        assertFalse(bridge.sent.last { it.getString("type") == "listen" }.getBoolean("on"))
        // From now on the owner's hang-up is an ordinary one.
        relay.onPhoneState(CallRelay.IDLE)
        assertEquals("taken", finished.single().getString("result"))
    }

    @Test
    fun actionsFromAnotherKeyAreRefused() {
        ring()
        relay.onPhoneState(CallRelay.OFFHOOK)
        val other = SoftwareSigner()
        relay.onEvent("call.screen", JSONObject().put("call_id", relay.currentCall).put("device_id", owner).put("public_key", b64(other.publicKey))
            .put("sealed", JSONObject().put("kind", "screen").put("signer", b64(other.publicKey))
                .put("plain", JSONObject().put("call_id", relay.currentCall).put("action", "takeover").toString()).toString()))
        assertEquals("assistant", states().last().getString("answered_by"))
    }

    @Test
    fun afterRingTheAssistantAnswersWhenNobodyDid() {
        settings = ScreenSettings(ScreenSettings.AFTER_RING, ringS = 20, assets = "set")
        ring()
        assertEquals(0, phone.answers)
        clock.advance(19_000)
        assertEquals(0, phone.answers)
        clock.advance(1_000)
        assertEquals(1, phone.answers)
        assertTrue(client.sent.any { it.first.endsWith("/withdraw") })
    }

    @Test
    fun noScreeningAfterAnEmergencyCall() {
        relay.onPhoneState(CallRelay.OFFHOOK, "110", emergency = true)
        relay.onPhoneState(CallRelay.IDLE)
        clock.advance(10 * 60_000)
        ring()
        assertEquals(0, phone.answers)
        assertNull(client.sent.first { it.first == "POST /v1/calls" }.second!!.optString("screening").ifEmpty { null })
    }
}
