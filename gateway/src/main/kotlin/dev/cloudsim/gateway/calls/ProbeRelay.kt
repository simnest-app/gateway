package dev.cloudsim.gateway.calls

import dev.cloudsim.gateway.identity.Signer
import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ControlClient.Companion.b64
import dev.cloudsim.gateway.net.ControlClient.Companion.unb64
import dev.cloudsim.gateway.net.ServiceException
import dev.cloudsim.gateway.protocol.Protocol
import org.json.JSONObject

/**
 * The phones' connection self-test (docs/architecture/SELF_TEST.md). A request without an offer is a ping: answered at
 * once, so the phone times the round trip to this gateway. With an offer, the audio bridge opens a data-only test
 * connection (no audio, nothing played or recorded) that echoes what the phone sends, so the phone can see whether the
 * path is direct or relayed and how fast it is. Only the owner and the family may ask, each request signed by the
 * phone; replies are signed by this gateway. Never during a call.
 */
class ProbeRelay(
    private val client: ControlClient,
    private val signer: Signer,
    private val owner: () -> String?,
    private val policy: () -> SharingPolicy?,
    private val bridge: (JSONObject) -> Boolean,
    private val busy: () -> Boolean,
    private val log: (String) -> Unit = {},
    /** Sealed for one phone; with [status], a ping's reply carries this phone's state (PRE_DEPARTURE_7.md). */
    private val sealer: Sealer? = null,
    private val status: ((to: String) -> JSONObject)? = null,
    /** A request whose ID is a recording's download ticket opens the file connection instead (RECORDING_V7.md 5). */
    private val files: dev.cloudsim.gateway.recording.FileTickets? = null,
    /** As [CallRelay]'s: the relays a test connection may use, by the owner's choice. */
    private val relays: (JSONObject?) -> JSONObject? = { it },
) {
    private val waiting = mutableMapOf<String, String>()

    fun onRequest(d: JSONObject) {
        val from = d.optString("from")
        val key = unb64(d.optString("public_key")) ?: return
        val id = d.optString("probe_id")
        val sdp = d.optString("sdp")
        val allowed = owner()?.let { o -> listOf(o) + (policy()?.members?.map { it.deviceId } ?: emptyList()) } ?: emptyList()
        if (Protocol.deviceId(key) != from || from !in allowed ||
            !Protocol.verify(key, Protocol.probeMessage("request", client.deviceId, id, sdp), unb64(d.optString("signature")))) {
            log("self-test refused: not one of this gateway's phones")
            return
        }
        val file = files?.take(id, from) == true
        if (sdp.isEmpty()) return reply(id, from, if (file) "bad_request" else "ok", "", if (file) null else state(id, from))
        if (busy()) return reply(id, from, "busy", "")
        val ice = relays(RelayCandidates.fetch(client))
        synchronized(waiting) { waiting[id] = from }
        if (!bridge(JSONObject().put("type", "probe").put("probe_id", id).put("sdp", sdp).put("ice", ice).put("file", file))) {
            synchronized(waiting) { waiting.remove(id) }
            reply(id, from, "no_bridge", "")
        }
    }

    /** The audio bridge's answer to a test connection, or why it could not open one. */
    fun onBridge(m: JSONObject) {
        val id = m.optString("probe_id")
        val to = synchronized(waiting) { waiting.remove(id) } ?: return
        when (m.optString("type")) {
            "probe_answer" -> reply(id, to, "ok", m.optString("sdp"))
            else -> reply(id, to, m.optString("reason").ifEmpty { "failed" }, "")
        }
    }

    /** This phone's state for [to], bound to this request, sealed so only that phone (not the service) reads it. */
    private fun state(id: String, to: String): JSONObject? {
        val doc = runCatching { status?.invoke(to) }.onFailure { log("self-test state: ${it.javaClass.simpleName}") }.getOrNull() ?: return null
        return sealer?.seal("probe", to, id, doc.put("probe_id", id).toString().toByteArray())
    }

    private fun reply(id: String, to: String, status: String, sdp: String, envelope: JSONObject? = null) {
        try {
            client.call("POST", "/v1/gateways/${client.deviceId}/probe/reply", JSONObject().put("probe_id", id).put("to", to).put("status", status)
                .put("sdp", sdp).put("signature", b64(signer.sign(Protocol.probeMessage("reply", client.deviceId, id, sdp))))
                .apply { envelope?.let { put("envelope", it.toString()) } })
            log("self-test reply for ${to.take(8)}: $status${if (sdp.isEmpty()) "" else " with a test connection"}")
        } catch (e: ServiceException) {
            // A service from before the state was added refuses the field: the reply still counts without it.
            if (envelope != null && e.code == "BAD_REQUEST") return reply(id, to, status, sdp)
            log("self-test reply: ${e.code} ${e.message}")
        }
    }
}
