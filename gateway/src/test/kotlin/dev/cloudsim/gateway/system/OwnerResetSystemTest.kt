package dev.cloudsim.gateway.system

import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ControlClient.Companion.b64
import dev.cloudsim.gateway.net.ServiceConfig
import dev.cloudsim.gateway.net.ServiceException
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

/** "清除主人关联" against the real service (GATEWAY_RESET.md): the owner loses the gateway, and a new phone pairs at once. */
class OwnerResetSystemTest {
    companion object {
        private const val SERVICE_ID = "cloudsim-reset-test"
        private lateinit var server: LocalServer
        private lateinit var config: ServiceConfig

        @BeforeClass @JvmStatic
        fun up() {
            assumeTrue("set CLOUDSIM_V4=1 to run the owner-reset system test", System.getenv("CLOUDSIM_V4") == "1")
            server = LocalServer(SERVICE_ID).also { it.start() }
            config = ServiceConfig("127.0.0.1:${server.port}", server.certSha256, SERVICE_ID)
        }

        @AfterClass @JvmStatic
        fun down() {
            if (::server.isInitialized) server.stop()
        }
    }

    private class Device(kind: String) {
        val signer = SoftwareSigner()
        val client = ControlClient(config, signer)
        val id = client.deviceId
        init { client.register(kind) }
    }

    private fun pair(gateway: Device, phone: Device) {
        val pairing = GatewayPairing(config, gateway.signer, gateway.client)
        val code = pairing.open()
        val secret = java.util.Base64.getUrlDecoder().decode(code.link.substringAfter("&k="))
        val tr = Protocol.transcript(SERVICE_ID, code.pairingId, gateway.signer.publicKey, phone.signer.publicKey)
        phone.client.call("POST", "/v1/pairings/${code.pairingId}/claim", JSONObject()
            .put("claim_mac", b64(Protocol.claimMac(Protocol.claimKey(secret), phone.id)))
            .put("auth_mac", b64(Protocol.authMac(Protocol.authKey(secret), tr)))
            .put("phone_signature", b64(phone.signer.sign(Protocol.bindMessage("phone", tr)))))
        pairing.confirm(code, (pairing.poll(code) as PairingState.Claimed).claimant)
    }

    private fun manages(phone: Device) = phone.client.call("GET", "/v1/me").optJSONArray("manages_all")?.length() ?: 0

    @Test
    fun clearedOnTheGatewayThenPairedAgain() {
        val gateway = Device("gateway")
        val owner = Device("phone")
        pair(gateway, owner)
        assertEquals(1, manages(owner))

        // A second ordinary code is refused while the owner is there.
        val refused = runCatching { pair(gateway, Device("phone")) }.exceptionOrNull()
        assertTrue("paired over an owner: $refused", refused is ServiceException && refused.code == "ALREADY_PAIRED")

        val r = gateway.client.call("POST", "/v1/gateways/${gateway.id}/reset", JSONObject())
        assertEquals("reset", r.getString("status"))
        assertEquals(0, manages(owner))

        val next = Device("phone")
        pair(gateway, next)
        assertEquals(1, manages(next))
    }
}
