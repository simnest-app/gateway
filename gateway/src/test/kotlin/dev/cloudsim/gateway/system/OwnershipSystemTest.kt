package dev.cloudsim.gateway.system

import dev.cloudsim.gateway.calls.Lease
import dev.cloudsim.gateway.calls.OwnerKeeper
import dev.cloudsim.gateway.calls.OwnerState
import dev.cloudsim.gateway.calls.PolicyKeeper
import dev.cloudsim.gateway.calls.PreviousOwner
import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ControlClient.Companion.b64
import dev.cloudsim.gateway.net.ServiceConfig
import dev.cloudsim.gateway.net.ServiceException
import dev.cloudsim.gateway.pairing.GatewayPairing
import dev.cloudsim.gateway.pairing.PairingState
import dev.cloudsim.gateway.pairing.SoftwareSigner
import dev.cloudsim.gateway.protocol.Protocol
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Changing the gateway's owner phone (docs/architecture/BACKUP_RESTORE_6.md) with the gateway's own OwnerKeeper and
 * PolicyKeeper against a real control service: handed over by the old owner, by the recovery key, and in person; the
 * policy the old owner signed keeps working, with the new owner in the old one's place. Runs with CLOUDSIM_V4=1.
 */
class OwnershipSystemTest {
    companion object {
        private const val SERVICE_ID = "owntest"
        private lateinit var server: LocalServer
        private lateinit var config: ServiceConfig

        @BeforeClass @JvmStatic
        fun up() {
            assumeTrue("set CLOUDSIM_V4=1 to run the ownership system test", System.getenv("CLOUDSIM_V4") == "1")
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

    private class MemoryState(override var owner: String?) : OwnerState {
        override var previous: PreviousOwner? = null
        override var recoveryKey: ByteArray? = null
    }

    private val gateway = Device(config, "gateway")
    private val owner = Device(config, "phone")
    private val state = MemoryState(null)
    private var stored: String? = null
    private val log = mutableListOf<String>()
    private val keeper = PolicyKeeper(gateway.client, { state.owner }, Lease(System::currentTimeMillis), { stored = it }, {}, { log += it }, { state.previous })
    private val owners = OwnerKeeper(gateway.client, gateway.signer, state, { keeper.policy?.revision ?: 0 }, log = { log += it })

    init {
        pair(owner)
        state.owner = owner.id
        signPolicy(owner, 1)
        assertTrue(keeper.refresh())
        assertEquals(1, keeper.policy?.revision)
    }

    private fun pair(phone: Device) {
        val pairing = GatewayPairing(config, gateway.signer, gateway.client)
        val code = pairing.open()
        claim(phone, code.pairingId, code.link)
        val claimed = pairing.poll(code) as PairingState.Claimed
        pairing.confirm(code, claimed.claimant)
    }

    private fun claim(phone: Device, pairingId: String, link: String) {
        val secret = java.util.Base64.getUrlDecoder().decode(link.substringAfter("&k="))
        val tr = Protocol.transcript(SERVICE_ID, pairingId, gateway.signer.publicKey, phone.signer.publicKey)
        phone.client.call("POST", "/v1/pairings/$pairingId/claim", JSONObject()
            .put("claim_mac", b64(Protocol.claimMac(Protocol.claimKey(secret), phone.id)))
            .put("auth_mac", b64(Protocol.authMac(Protocol.authKey(secret), tr)))
            .put("phone_signature", b64(phone.signer.sign(Protocol.bindMessage("phone", tr)))))
    }

    private fun signPolicy(by: Device, revision: Int) {
        val raw = JSONObject().put("v", 1).put("gateway_id", gateway.id).put("revision", revision).put("members", JSONArray())
            .put("owner_ring", JSONArray(listOf(by.id))).put("mode", "owner_first").put("wait_s", 15).put("fallback", false).toString().toByteArray()
        by.client.call("POST", "/v1/gateways/${gateway.id}/policy", JSONObject().put("policy", b64(raw))
            .put("signature", b64(by.signer.sign(Protocol.policyMessage(gateway.id, revision, raw)))))
    }

    private fun registerRecovery(by: Device, recovery: SoftwareSigner) {
        by.client.call("POST", "/v1/gateways/${gateway.id}/recovery", JSONObject()
            .put("recovery_key", b64(recovery.publicKey))
            .put("owner_signature", b64(by.signer.sign(Protocol.recoveryMessage(gateway.id, recovery.publicKey))))
            .put("gateway_proof", b64(recovery.sign(Protocol.recoveryGatewayMessage(gateway.id, gateway.signer.publicKey)))))
    }

    private fun request(by: Device, next: Device, kind: String, signedBy: (ByteArray) -> ByteArray) {
        val at = System.currentTimeMillis()
        by.client.call("POST", "/v1/gateways/${gateway.id}/handover", JSONObject().put("new_owner_id", next.id).put("kind", kind).put("at_ms", at)
            .put("signature", b64(signedBy(Protocol.handoverMessage(gateway.id, next.signer.publicKey, at)))))
    }

    @Test
    fun oldOwnerHandsOverAndItsPolicyStillCounts() {
        val next = Device(config, "phone")
        request(owner, next, "owner") { owner.signer.sign(it) }
        assertTrue(owners.checkHandover())
        assertEquals(next.id, state.owner)
        assertEquals(PreviousOwner(owner.id, 1), state.previous)
        // The old owner no longer manages it; the policy it signed still holds, with the new owner ringing first.
        assertTrue(keeper.refresh())
        assertEquals(listOf(next.id), keeper.policy?.ownerRing)
        assertEquals(listOf(listOf(next.id)), keeper.policy?.stages())
        // After a restart too.
        val again = PolicyKeeper(gateway.client, { state.owner }, Lease(System::currentTimeMillis), {}, {}, {}, { state.previous })
        again.load(stored)
        assertEquals(1, again.policy?.revision)
        // A policy the old owner signs now is refused (by the service already; and it could not be newer here).
        assertTrue(runCatching { signPolicy(owner, 2) }.exceptionOrNull() is ServiceException)
        // The new owner signs the next one.
        signPolicy(next, 2)
        assertTrue(keeper.refresh())
        assertEquals(2, keeper.policy?.revision)
        assertEquals(listOf(next.id), keeper.policy?.ownerRing)
    }

    @Test
    fun recoveryKeyHandsOverOnlyWhenTheGatewayHoldsIt() {
        val recovery = SoftwareSigner()
        registerRecovery(owner, recovery)
        val next = Device(config, "phone")
        // The gateway missed the registration: it refuses, whatever the service accepted.
        request(next, next, "recovery") { recovery.sign(it) }
        assertFalse(owners.checkHandover())
        assertEquals(owner.id, state.owner)
        owners.refreshRecovery()
        assertTrue(state.recoveryKey!!.contentEquals(recovery.publicKey))
        assertTrue(owners.checkHandover())
        assertEquals(next.id, state.owner)
        // The used key is gone, here and at the service.
        assertNull(state.recoveryKey)
        owners.refreshRecovery()
        assertNull(state.recoveryKey)
    }

    @Test
    fun recoveryKeyRegisteredByAnotherPhoneIsRefused() {
        val recovery = SoftwareSigner()
        registerRecovery(owner, recovery)
        // The gateway checks the owner's signature on it against the owner it paired with.
        val impostor = MemoryState("0".repeat(32))
        OwnerKeeper(gateway.client, gateway.signer, impostor, { 1 }).refreshRecovery()
        assertNull(impostor.recoveryKey)
    }

    @Test
    fun inPersonReplacement() {
        val next = Device(config, "phone")
        val pairing = GatewayPairing(config, gateway.signer, gateway.client)
        val code = pairing.open(replace = true)
        claim(next, code.pairingId, code.link)
        val claimed = pairing.poll(code) as PairingState.Claimed
        val st = owners.statement(claimed.claimant.phoneKey)
        pairing.confirm(code, claimed.claimant, st)
        owners.take(claimed.claimant.phoneId, st)
        assertEquals(next.id, state.owner)
        assertEquals(PreviousOwner(owner.id, 1), state.previous)
        assertTrue(keeper.refresh())
        assertEquals(listOf(next.id), keeper.policy?.ownerRing)
        // The family can check who owns it now with the gateway's key.
        val o = next.client.call("GET", "/v1/gateways/${gateway.id}/owner")
        assertTrue(Protocol.verify(gateway.signer.publicKey,
            Protocol.ownerMessage(gateway.id, ControlClient.unb64(o.getString("owner_public_key"))!!, owner.id, o.getInt("policy_revision"), o.getLong("at_ms")),
            ControlClient.unb64(o.getString("signature"))))
    }
}
