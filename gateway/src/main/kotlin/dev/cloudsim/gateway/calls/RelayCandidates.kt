package dev.cloudsim.gateway.calls

import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ServiceException
import org.json.JSONObject

/**
 * This gateway's relay candidates, in the form the audio bridge takes as "ice": `{"relays":[…]}`, each relay with its
 * own credentials (docs/architecture/RELAY_SPLIT.md 18), or the single answer of GET /v1/ice from a service that has
 * no roster yet.
 */
object RelayCandidates {
    /** The candidates the service put on an event or an answer; null when it carries none. */
    fun carried(data: JSONObject): JSONObject? =
        data.optJSONArray("relays")?.takeIf { it.length() > 0 }?.let { JSONObject().put("relays", it) }

    /** Asked for outside a call's own signalling (a self-test), or when an offer came without them. */
    fun fetch(client: ControlClient, log: (String) -> Unit = {}): JSONObject? {
        try {
            return carried(client.call("GET", "/v1/relays"))
        } catch (e: Exception) {
            // Unreachable is unreachable for the old route too; anything else is a service from before the roster.
            if (e is ServiceException && e.code == "NETWORK") {
                log("no relay credentials: ${e.message}")
                return null
            }
        }
        return runCatching { client.call("GET", "/v1/ice") }.onFailure { log("no relay credentials: ${it.message}") }.getOrNull()?.takeIf { it.has("urls") }
    }
}
