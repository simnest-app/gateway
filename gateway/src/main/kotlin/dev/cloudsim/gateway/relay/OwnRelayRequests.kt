package dev.cloudsim.gateway.relay

import dev.cloudsim.gateway.calls.Sealer
import dev.cloudsim.gateway.calls.SharingPolicy
import dev.cloudsim.gateway.net.ControlClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Answers a phone that asks for this gateway's own relay (RELAY_SPLIT.md 17.2): the owner or a family device in the
 * owner-signed policy, checked here again, gets the owner's choice and its credentials sealed for it alone. The
 * service passes the answer on and can read none of it.
 */
class OwnRelayRequests(
    private val client: ControlClient,
    private val sealer: Sealer,
    private val owner: () -> String?,
    private val policy: () -> SharingPolicy?,
    private val keeper: OwnRelayKeeper,
    private val remote: () -> Boolean = { true },
    private val log: (String) -> Unit = {},
) {
    fun onRequest(data: JSONObject) {
        val to = data.optString("device_id")
        val requestId = data.optString("request_id")
        if (requestId.length !in 8..64) return
        if (!remote()) return log("relay request from ${to.take(8)} refused: permissions unconfirmed for 5 minutes")
        val o = owner() ?: return
        if (to != o && policy()?.members?.none { it.deviceId == to } != false) return log("relay request from ${to.take(8)} refused: not one of this gateway's phones")
        val plain = JSONObject().put("kind", KIND).put("request_id", requestId).put("index", 0).put("count", 1).put("items", JSONArray().put(keeper.forPhone()))
        val env = sealer.seal("records", to, "$requestId-0", plain.toString().toByteArray()) ?: return log("no recipient key for ${to.take(8)}")
        runCatching {
            client.call("POST", "/v1/records/deliver", JSONObject().put("to", to).put("kind", KIND).put("request_id", requestId)
                .put("index", 0).put("count", 1).put("envelope", env.toString()))
        }.onFailure { return log("relay answer for ${to.take(8)} not delivered: ${it.message}") }
        log("relay answer for ${to.take(8)}: choice ${keeper.choice.ifEmpty { "official" }}")
    }

    companion object {
        const val KIND = "relay"
    }
}
