package dev.cloudsim.gateway.calls

import dev.cloudsim.gateway.identity.Signer
import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ControlClient.Companion.b64
import dev.cloudsim.gateway.net.ControlClient.Companion.unb64
import dev.cloudsim.gateway.net.ServiceException
import dev.cloudsim.gateway.protocol.Protocol
import org.json.JSONObject

/** Who owns this gateway, kept on the gateway (SharedPreferences on Android, memory in tests). */
interface OwnerState {
    var owner: String?
    var previous: PreviousOwner?
    /** The recovery key the owner registered (SubjectPublicKeyInfo), checked against the owner's signature. */
    var recoveryKey: ByteArray?
}

/**
 * Changing this gateway's owner phone (docs/architecture/BACKUP_RESTORE_6.md). The service only passes requests on:
 * this gateway checks each one itself (the current owner's signature, or the recovery key it holds), then signs the
 * statement that makes the change. The family and the sharing policy stay; the previous owner's signature keeps
 * counting for the policy revisions it signed, until the new owner signs the next one.
 */
class OwnerKeeper(
    private val client: ControlClient,
    private val signer: Signer,
    private val state: OwnerState,
    /** The policy revision in force here, which the previous owner's signature will still cover. */
    private val policyRevision: () -> Int,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    /** Fetches the owner's recovery key and keeps it only if the paired owner signed it; clears it when none is set. */
    @Synchronized
    fun refreshRecovery() {
        val owner = state.owner ?: return
        try {
            val r = client.call("GET", "/v1/gateways/${client.deviceId}/recovery")
            if (!r.optBoolean("set")) {
                if (state.recoveryKey != null) log("recovery key removed")
                state.recoveryKey = null
                return
            }
            val key = unb64(r.optString("recovery_key")) ?: return
            val ownerKey = unb64(r.optString("owner_public_key")) ?: return
            if (Protocol.deviceId(ownerKey) != owner || !Protocol.verify(ownerKey, Protocol.recoveryMessage(client.deviceId, key), unb64(r.optString("owner_signature")))) {
                log("recovery key refused: not signed by the paired owner")
                return
            }
            state.recoveryKey = key
            log("recovery key set")
        } catch (e: ServiceException) {
            log("recovery: ${e.code} ${e.message}")
        }
    }

    /** Carries out a waiting handover if its signature holds; true when the owner changed. */
    @Synchronized
    fun checkHandover(): Boolean {
        val owner = state.owner ?: return false
        try {
            val p = client.call("GET", "/v1/gateways/${client.deviceId}/handover")
            if (!p.optBoolean("pending")) return false
            val newId = p.optString("new_owner_id")
            val newKey = unb64(p.optString("new_owner_public_key")) ?: return false
            val at = p.optLong("at_ms")
            if (Protocol.deviceId(newKey) != newId || newId == owner) return refuse("new phone's key does not match")
            if (kotlin.math.abs(now() - at) > WINDOW_MS) return refuse("request too old")
            val signerKey = when (p.optString("kind")) {
                "owner" -> unb64(p.optString("owner_public_key"))?.takeIf { Protocol.deviceId(it) == owner } ?: return refuse("not the paired owner")
                "recovery" -> state.recoveryKey ?: return refuse("no recovery key here")
                else -> return refuse("unknown kind")
            }
            if (!Protocol.verify(signerKey, Protocol.handoverMessage(client.deviceId, newKey, at), unb64(p.optString("signature")))) return refuse("signature does not hold")
            confirm(newId, newKey, owner, p.optString("kind"))
            return true
        } catch (e: ServiceException) {
            log("handover: ${e.code} ${e.message}")
            return false
        }
    }

    /**
     * The statement this gateway signs for a new owner, for an in-person change (a replace code confirmed on this
     * screen) as for a signed request; [take] makes it the owner here once the service accepted it.
     */
    fun statement(newKey: ByteArray): Statement {
        val revision = policyRevision()
        val at = now()
        val previous = state.owner ?: ""
        return Statement(revision, at, signer.sign(Protocol.ownerMessage(client.deviceId, newKey, previous, revision, at)), previous)
    }

    class Statement(val policyRevision: Int, val atMs: Long, val signature: ByteArray, val previousId: String) {
        fun json(): JSONObject = JSONObject().put("policy_revision", policyRevision).put("at_ms", atMs).put("signature", b64(signature))
    }

    /** Records [newId] as the owner, the old one as previous for the policies it signed; the old recovery key goes. */
    @Synchronized
    fun take(newId: String, st: Statement) {
        if (st.previousId.isNotEmpty()) state.previous = PreviousOwner(st.previousId, st.policyRevision)
        state.owner = newId
        state.recoveryKey = null
        log("owner changed to ${newId.take(8)} (was ${st.previousId.take(8)}); policy up to ${st.policyRevision} still counts")
    }

    private fun confirm(newId: String, newKey: ByteArray, owner: String, kind: String) {
        val st = statement(newKey)
        client.call("POST", "/v1/gateways/${client.deviceId}/handover/confirm", st.json().put("new_owner_id", newId))
        take(newId, st)
        log("handover by $kind from ${owner.take(8)}")
    }

    private fun refuse(why: String): Boolean {
        log("handover refused: $why")
        return false
    }

    companion object {
        const val WINDOW_MS = 10 * 60 * 1000L
    }
}
