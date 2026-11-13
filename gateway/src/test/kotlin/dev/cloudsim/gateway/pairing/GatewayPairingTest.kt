package dev.cloudsim.gateway.pairing

import dev.cloudsim.gateway.identity.Signer
import dev.cloudsim.gateway.net.ControlClient.Companion.b64
import dev.cloudsim.gateway.net.ServiceConfig
import dev.cloudsim.gateway.protocol.Protocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class SoftwareSigner : Signer {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    override val publicKey: ByteArray = pair.public.encoded
    override fun sign(message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(message); sign() }
}

/** The gateway's check of a claim (steps 4 and 5), without a server. */
class GatewayPairingTest {
    private val config = ServiceConfig("127.0.0.1:1", ByteArray(32), "svc")
    private val gateway = SoftwareSigner()
    private val pairing = GatewayPairing(config, gateway)
    private val secret = ByteArray(32) { 7 }
    private val code = OpenCode("pair", 0, "", secret)

    private fun claim(phone: Signer, authSecret: ByteArray = secret, registeredKey: ByteArray = phone.publicKey): JSONObject {
        val transcript = Protocol.transcript("svc", "pair", gateway.publicKey, phone.publicKey)
        return JSONObject().put("phone_id", Protocol.deviceId(registeredKey)).put("phone_public_key", b64(registeredKey))
            .put("auth_mac", b64(Protocol.authMac(Protocol.authKey(authSecret), transcript)))
            .put("phone_signature", b64(phone.sign(Protocol.bindMessage("phone", transcript))))
    }

    @Test
    fun acceptsThePhoneThatScanned() {
        val phone = SoftwareSigner()
        val state = pairing.review(code, claim(phone)) as PairingState.Claimed
        assertEquals(Protocol.confirmationCode(Protocol.transcript("svc", "pair", gateway.publicKey, phone.publicKey)), state.claimant.code)
    }

    @Test
    fun rejectsWithoutTheSecret() {
        assertEquals(PairingState.Mismatch, pairing.review(code, claim(SoftwareSigner(), authSecret = ByteArray(32))))
    }

    @Test
    fun rejectsASwappedKey() {
        // The service reports a different key than the one that produced the proofs.
        assertEquals(PairingState.Mismatch, pairing.review(code, claim(SoftwareSigner(), registeredKey = SoftwareSigner().publicKey)))
    }
}
