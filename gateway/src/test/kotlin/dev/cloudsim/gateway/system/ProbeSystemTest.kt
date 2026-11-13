package dev.cloudsim.gateway.system

import dev.cloudsim.gateway.calls.ProbeRelay
import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ControlClient.Companion.b64
import dev.cloudsim.gateway.net.ServiceConfig
import dev.cloudsim.gateway.pairing.GatewayPairing
import dev.cloudsim.gateway.pairing.PairingState
import dev.cloudsim.gateway.pairing.SoftwareSigner
import dev.cloudsim.gateway.protocol.Protocol
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The connection self-test (docs/architecture/SELF_TEST.md) with the gateway's ProbeRelay against a real control
 * service: a ping answered and signed by the gateway, a stranger ignored, a test connection refused during a call and
 * handed to the audio bridge otherwise. Runs with CLOUDSIM_V4=1.
 */
class ProbeSystemTest {
    companion object {
        private const val SERVICE_ID = "probetest"
        private lateinit var server: LocalServer
        private lateinit var config: ServiceConfig

        @BeforeClass @JvmStatic
        fun up() {
            assumeTrue("set CLOUDSIM_V4=1 to run the self-test system test", System.getenv("CLOUDSIM_V4") == "1")
            server = LocalServer(SERVICE_ID).also { it.start() }
            config = ServiceConfig("127.0.0.1:${server.port}", server.certSha256, SERVICE_ID)
        }

        @AfterClass @JvmStatic
        fun down() {
            if (::server.isInitialized) server.stop()
        }
    }

    private class Device(config: ServiceConfig, kind: String) {
        val signer = SoftwareSigner()
        val client = ControlClient(config, signer)
        val id = client.deviceId
        init { client.register(kind) }
    }

    private val gateway = Device(config, "gateway")
    private val owner = Device(config, "phone")
    private var busy = false
    private val toBridge = mutableListOf<JSONObject>()
    private val probes = ProbeRelay(gateway.client, gateway.signer, { owner.id }, { null }, { toBridge += it; true }, { busy })

    init {
        val pairing = GatewayPairing(config, gateway.signer, gateway.client)
        val code = pairing.open()
        val secret = java.util.Base64.getUrlDecoder().decode(code.link.substringAfter("&k="))
        val tr = Protocol.transcript(SERVICE_ID, code.pairingId, gateway.signer.publicKey, owner.signer.publicKey)
        owner.client.call("POST", "/v1/pairings/${code.pairingId}/claim", JSONObject()
            .put("claim_mac", b64(Protocol.claimMac(Protocol.claimKey(secret), owner.id)))
            .put("auth_mac", b64(Protocol.authMac(Protocol.authKey(secret), tr)))
            .put("phone_signature", b64(owner.signer.sign(Protocol.bindMessage("phone", tr)))))
        pairing.confirm(code, (pairing.poll(code) as PairingState.Claimed).claimant)
    }

    private fun request(from: Device, id: String, sdp: String): JSONObject {
        from.client.call("POST", "/v1/gateways/${gateway.id}/probe", JSONObject().put("probe_id", id).put("sdp", sdp)
            .put("signature", b64(from.signer.sign(Protocol.probeMessage("request", gateway.id, id, sdp)))))
        val events = gateway.client.call("GET", "/v1/events?after=0").getJSONArray("events")
        return (0 until events.length()).map { events.getJSONObject(it) }.last { it.getString("type") == "probe.request" }.getJSONObject("data")
    }

    private fun reply(id: String): JSONObject? {
        val events = owner.client.call("GET", "/v1/events?after=0").getJSONArray("events")
        return (0 until events.length()).map { events.getJSONObject(it) }.lastOrNull { it.getString("type") == "probe.reply" && it.getJSONObject("data").getString("probe_id") == id }?.getJSONObject("data")
    }

    @Test
    fun pingIsAnsweredAndSignedByTheGateway() {
        probes.onRequest(request(owner, "aaaa-0001", ""))
        val r = reply("aaaa-0001")!!
        assertEquals("ok", r.getString("status"))
        assertTrue(Protocol.verify(gateway.signer.publicKey, Protocol.probeMessage("reply", gateway.id, "aaaa-0001", ""), ControlClient.unb64(r.getString("signature"))))
    }

    @Test
    fun aPingCarriesThePhoneStateSealedForTheAskingPhone() {
        val sealed = mutableListOf<String>()
        val withState = ProbeRelay(gateway.client, gateway.signer, { owner.id }, { null }, { true }, { false }, {},
            object : dev.cloudsim.gateway.calls.Sealer {
                override fun seal(kind: String, to: String, id: String, plaintext: ByteArray): JSONObject {
                    sealed += "$kind:$to:$id"
                    return JSONObject().put("to", to).put("plain", plaintext.decodeToString())
                }
                override fun open(envelope: JSONObject, kind: String, senderKey: ByteArray): ByteArray? = null
            }, { to -> JSONObject().put("battery", 42).put("for", to) })
        withState.onRequest(request(owner, "eeee-0001", ""))
        val r = reply("eeee-0001")!!
        assertEquals("ok", r.getString("status"))
        assertEquals(listOf("probe:${owner.id}:eeee-0001"), sealed)
        val doc = JSONObject(JSONObject(r.getString("envelope")).getString("plain"))
        assertEquals(42, doc.getInt("battery"))
        assertEquals("eeee-0001", doc.getString("probe_id"))
        // A test connection's reply carries none.
        withState.onRequest(request(owner, "eeee-0002", "v=0 offer"))
        assertEquals(1, sealed.size)
    }

    @Test
    fun testConnectionGoesToTheBridgeAndItsAnswerBack() {
        probes.onRequest(request(owner, "bbbb-0001", "v=0 offer"))
        assertEquals("probe", toBridge.single().getString("type"))
        probes.onBridge(JSONObject().put("type", "probe_answer").put("probe_id", "bbbb-0001").put("sdp", "v=0 answer"))
        val r = reply("bbbb-0001")!!
        assertEquals("v=0 answer", r.getString("sdp"))
        assertTrue(Protocol.verify(gateway.signer.publicKey, Protocol.probeMessage("reply", gateway.id, "bbbb-0001", "v=0 answer"), ControlClient.unb64(r.getString("signature"))))
    }

    @Test
    fun noTestConnectionDuringACall() {
        busy = true
        probes.onRequest(request(owner, "cccc-0001", "v=0 offer"))
        assertTrue(toBridge.isEmpty())
        assertEquals("busy", reply("cccc-0001")!!.getString("status"))
    }

    @Test
    fun aRequestNotFromItsPhonesIsIgnored() {
        val data = request(owner, "dddd-0001", "")
        // The same request with another phone's key in it, as a forged one would be.
        val other = Device(config, "phone")
        data.put("from", other.id).put("public_key", b64(other.signer.publicKey))
        probes.onRequest(data)
        // A real ping after it, so the phone has an event to read: the forged one got no reply.
        probes.onRequest(request(owner, "dddd-0002", ""))
        assertEquals("ok", reply("dddd-0002")!!.getString("status"))
        assertEquals(null, reply("dddd-0001"))
    }
}
