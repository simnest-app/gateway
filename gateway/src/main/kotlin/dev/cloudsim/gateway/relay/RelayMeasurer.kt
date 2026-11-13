package dev.cloudsim.gateway.relay

import dev.cloudsim.gateway.net.ControlClient
import org.json.JSONObject

/**
 * Times this gateway's round trip to each relay the service names (docs/architecture/RELAY_SPLIT.md 6.2) by a STUN
 * binding, which needs no credentials and takes no relay session, and reports the numbers, so the service can hand
 * each call the relays nearest to both ends. A gateway stays where it is, so its numbers are the steady half.
 * Only a relay's name and the milliseconds are sent: no address, no carrier.
 */
class RelayMeasurer(
    private val client: ControlClient,
    /** "wifi", "cellular" or "" when not known. */
    private val network: () -> String = { "" },
    private val binding: (host: String, port: Int) -> Long? = { h, p -> RelayProbe.binding(h, p) },
    private val log: (String) -> Unit = {},
) {
    /** One round of measuring and reporting; blocking. Quietly nothing with a service that names no relays to time. */
    fun run() {
        val targets = runCatching { client.call("GET", "/v1/relays").optJSONArray("probe") }.getOrNull() ?: return
        val rtts = JSONObject()
        for (i in 0 until minOf(targets.length(), MAX)) {
            val t = targets.optJSONObject(i) ?: continue
            val id = t.optString("id")
            val (host, port) = hostPort(t.optString("stun")) ?: continue
            if (id.isEmpty() || id.length > 32) continue
            // The better of two tries: one lost packet does not make a near relay look far.
            val rtt = listOfNotNull(binding(host, port), binding(host, port)).minOrNull()
            rtts.put(id, rtt ?: UNREACHABLE)
        }
        if (rtts.length() == 0) return
        runCatching { client.call("POST", "/v1/relays/measurements", JSONObject().put("network", network()).put("rtts", rtts)) }
            .onSuccess { log("relay round trips reported: $rtts") }
            .onFailure { log("relay round trips not reported: ${it.message}") }
    }

    companion object {
        const val MAX = 16
        /** What the service takes for a relay that did not answer. */
        const val UNREACHABLE = 5000L

        /** "host:port" or "[v6]:port" apart; null for anything else. */
        fun hostPort(text: String): Pair<String, Int>? {
            val at = text.lastIndexOf(':')
            if (at <= 0) return null
            val port = text.substring(at + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val host = text.substring(0, at).removePrefix("[").removeSuffix("]")
            return if (host.isEmpty() || !Regex("^[A-Za-z0-9.:-]{1,253}$").matches(host)) null else host to port
        }
    }
}
