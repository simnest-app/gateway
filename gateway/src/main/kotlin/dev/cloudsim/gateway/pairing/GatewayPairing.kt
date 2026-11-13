package dev.cloudsim.gateway.pairing

import dev.cloudsim.gateway.identity.Signer
import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ControlClient.Companion.b64
import dev.cloudsim.gateway.net.ControlClient.Companion.unb64
import dev.cloudsim.gateway.net.ServiceConfig
import dev.cloudsim.gateway.protocol.Protocol
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

/** A code on screen. The secret exists only here and in the QR code. */
class OpenCode(val pairingId: String, val expiresAtMs: Long, val link: String, internal val secret: ByteArray, val replace: Boolean = false)

/** What the gateway learned about the phone that claimed the code, after checking its proofs. */
data class Claimant(val phoneId: String, val phoneKey: ByteArray, val code: String)

sealed interface PairingState {
    data object Waiting : PairingState
    data class Claimed(val claimant: Claimant) : PairingState
    /** The claim's proofs did not match: someone other than the scanning phone, or a swapped key. */
    data object Mismatch : PairingState
    data object Confirmed : PairingState
    data class Ended(val state: String) : PairingState
}

/** Gateway side of pairing protocol v1 (docs/architecture/PAIRING_PROTOCOL.md). Blocking calls. */
class GatewayPairing(private val config: ServiceConfig, private val signer: Signer, private val client: ControlClient = ControlClient(config, signer)) {
    private val random = SecureRandom()

    /** [replace]: a code to take a new owner phone in place of the current one, opened here in person (BACKUP_RESTORE_6.md). */
    fun open(replace: Boolean = false): OpenCode {
        client.register("gateway")
        val secret = ByteArray(32).also(random::nextBytes)
        val body = JSONObject().put("claim_key", b64(Protocol.claimKey(secret)))
        if (replace) body.put("replace_owner", true)
        val p = client.call("POST", "/v1/pairings", body)
        val url = Base64.getUrlEncoder().withoutPadding()
        val link = "cloudsim://pair?v=1&h=${config.address}&c=${url.encodeToString(config.certSha256)}&sid=${p.getString("service_id")}" +
            "&p=${p.getString("pairing_id")}&g=${url.encodeToString(signer.publicKey)}&k=${url.encodeToString(secret)}"
        return OpenCode(p.getString("pairing_id"), p.getLong("expires_at_ms"), link, secret, replace)
    }

    fun poll(code: OpenCode): PairingState {
        val p = client.call("GET", "/v1/pairings/${code.pairingId}")
        return when (val state = p.getString("state")) {
            "open" -> PairingState.Waiting
            "claimed" -> review(code, p)
            "confirmed" -> PairingState.Confirmed
            else -> PairingState.Ended(state)
        }
    }

    /** Steps 4 and 5: the phone must prove it scanned this code and must have signed the transcript with the key it registered. */
    internal fun review(code: OpenCode, p: JSONObject): PairingState {
        val phoneId = p.optString("phone_id")
        val phoneKey = unb64(p.optString("phone_public_key")) ?: return PairingState.Mismatch
        val transcript = Protocol.transcript(config.serviceId, code.pairingId, signer.publicKey, phoneKey)
        val ok = Protocol.deviceId(phoneKey) == phoneId &&
            Protocol.equalMac(Protocol.authMac(Protocol.authKey(code.secret), transcript), unb64(p.optString("auth_mac"))) &&
            Protocol.verify(phoneKey, Protocol.bindMessage("phone", transcript), unb64(p.optString("phone_signature")))
        return if (ok) PairingState.Claimed(Claimant(phoneId, phoneKey, Protocol.confirmationCode(transcript))) else PairingState.Mismatch
    }

    /** Step 6, only after the user saw the same code on both screens. */
    fun confirm(code: OpenCode, claimant: Claimant, owner: dev.cloudsim.gateway.calls.OwnerKeeper.Statement? = null) {
        val transcript = Protocol.transcript(config.serviceId, code.pairingId, signer.publicKey, claimant.phoneKey)
        val body = JSONObject().put("phone_id", claimant.phoneId).put("gateway_signature", b64(signer.sign(Protocol.bindMessage("gateway", transcript))))
        owner?.let { body.put("owner_statement", it.json()) }
        client.call("POST", "/v1/pairings/${code.pairingId}/confirm", body)
    }

    fun cancel(code: OpenCode) {
        client.call("DELETE", "/v1/pairings/${code.pairingId}")
    }
}
