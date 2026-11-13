package dev.cloudsim.gateway.calls

import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ServiceException
import org.json.JSONObject

/**
 * The gateway's hold on the owner-signed sharing policy and the 5-minute window (REVOKE_LEASE_5.md), apart from
 * Android so the V4 system test runs this same code against a real control service.
 */
class PolicyKeeper(
    private val client: ControlClient,
    private val owner: () -> String?,
    val lease: Lease,
    /** Keeps the verified response for the next start. */
    private val store: (String) -> Unit,
    /** Carries a new policy out on the threads that use it; returns once it is in force. */
    private val apply: () -> Unit,
    private val log: (String) -> Unit = {},
    /** The owner this gateway was handed from, while its policies still count (BACKUP_RESTORE_6.md). */
    private val previous: () -> PreviousOwner? = { null },
) {
    @Volatile private var held: SharingPolicy? = null

    /**
     * The newest verified sharing policy, or null before the owner set one. One the previous owner signed reads with
     * the current owner in its place, so the new owner rings where the old one did.
     */
    val policy: SharingPolicy?
        get() {
            val p = held ?: return null
            val prev = previous() ?: return p
            val o = owner() ?: return p
            return if (p.revision <= prev.revision) p.replacingOwner(prev.id, o) else p
        }

    /** Re-checks the stored policy at start: trusted only as long as the paired owner's signature holds. */
    fun load(raw: String?) {
        val o = owner() ?: return
        held = raw?.let { runCatching { SharingPolicy.verify(JSONObject(it), client.deviceId, o, 0, previous()) }.getOrNull() }
    }

    /**
     * Fetches the policy; keeps it only if the paired owner signed it and it is newer than the one held. When this
     * gateway then holds the service's current revision, that is the confirmation the window runs from.
     */
    @Synchronized
    fun refresh(): Boolean {
        val o = owner() ?: return false
        try {
            val response = client.call("GET", "/v1/gateways/${client.deviceId}/policy")
            val latest = response.optInt("revision")
            val have = held?.revision ?: 0
            if (latest < have) {
                log("sharing policy: the service has revision $latest, older than $have here; not confirmed")
                return false
            }
            if (latest > have) {
                val next = SharingPolicy.verify(response, client.deviceId, o, have, previous()) ?: run {
                    log("sharing policy refused: not signed by the paired owner")
                    return false
                }
                store(response.toString())
                held = next
                log("sharing policy ${next.revision}: mode=${next.mode} members=${next.members.size} wait=${next.waitS}s")
                apply()
            }
            if (latest > 0 && response.optInt("applied_revision") < latest) {
                client.call("POST", "/v1/gateways/${client.deviceId}/policy/applied", JSONObject().put("revision", latest))
                log("sharing policy $latest applied and reported")
            }
            lease.confirm()
            return true
        } catch (e: ServiceException) {
            log("policy: ${e.code} ${e.message}")
            return false
        }
    }

    private var open = false

    /** Called every few seconds: on each change of the window, pause or resume remote access. */
    fun watch(onPause: () -> Unit, onResume: () -> Unit) {
        val now = lease.valid()
        if (now == open) return
        open = now
        if (now) {
            log("permissions confirmed: remote access on")
            onResume()
        } else {
            log("permissions unconfirmed for ${(lease.age() ?: 0) / 1000}s: remote access paused")
            onPause()
        }
    }
}
