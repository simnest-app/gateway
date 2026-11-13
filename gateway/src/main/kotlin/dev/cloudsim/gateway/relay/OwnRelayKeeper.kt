package dev.cloudsim.gateway.relay

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** Where the own relay is kept between starts; the app keeps its secret under a key in the system keystore. */
interface RelayStore {
    fun load(): OwnRelay?
    fun save(relay: OwnRelay)
    fun clear()
}

/** Reaching a relay; the real network in the app, a table in tests. */
interface Prober {
    /** Round trip in ms; null when it does not answer. */
    fun binding(host: String, port: Int): Long?
    /** Null when an allocation worked, else why not: no_answer, not_a_relay, wrong_secret, refused_N, blocks_public, unreachable. */
    fun allocate(host: String, port: Int, username: String, password: String): String?
}

object NetworkProber : Prober {
    override fun binding(host: String, port: Int) = RelayProbe.binding(host, port)
    override fun allocate(host: String, port: Int, username: String, password: String) = RelayProbe.allocate(host, port, username, password)
}

/**
 * What a check of the own relay from this gateway found: the addresses it could allocate on, the round trip, and
 * what was wrong when none worked. [publicOk] is whether the address phones away from home use answered from here;
 * a gateway in the relay's own network often cannot reach that one (the router does not turn a connection round),
 * and then only a phone outside can tell.
 */
data class RelayCheck(val atMs: Long, val reachable: List<String>, val publicOk: Boolean, val rttMs: Long?, val problem: String?) {
    val ok get() = reachable.isNotEmpty()
}

/**
 * The owner's own relay on this gateway (RELAY_SPLIT.md 17): added from its setup page's code, checked from here, and
 * turned into credentials for this gateway's end of a call and for the gateway's phones. Which relays a call may use
 * is the owner's signed choice ([mode], from the sharing policy), applied here whatever the service hands out.
 */
class OwnRelayKeeper(
    private val store: RelayStore,
    private val mode: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
    private val prober: Prober = NetworkProber,
    private val log: (String) -> Unit = {},
) {
    @Volatile var relay: OwnRelay? = runCatching { store.load() }.getOrNull()
        private set
    @Volatile var lastCheck: RelayCheck? = null
        private set
    @Volatile var checking = false
        private set

    /** The owner's choice as it stands; anything this version does not know counts as ours only. */
    val choice: String get() = mode().takeIf { it == OWN || it == OWN_FIRST } ?: OFFICIAL

    /** Takes the code from the relay's setup page; false when the text is not one. */
    fun add(code: String): Boolean {
        val parsed = OwnRelay.parse(code) ?: return false
        store.save(parsed)
        relay = parsed
        lastCheck = null
        log("own relay added (package ${parsed.version.ifEmpty { "?" }})")
        return true
    }

    fun remove() {
        store.clear()
        relay = null
        lastCheck = null
        log("own relay removed")
    }

    private fun tag() = ByteArray(4).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    /** Tries every address of the relay from here, with a credential made for the try. Blocking; null without a relay. */
    fun check(): RelayCheck? {
        val r = relay ?: return null
        checking = true
        try {
            val (user, pass) = r.credential("check" + tag(), now() / 1000 + 300)
            var problem: String? = null
            var rtt: Long? = null
            val reachable = (listOf(r.host) + r.lan).distinct().filter { host ->
                val t = prober.binding(host, r.port)
                val why = if (t == null) "no_answer" else prober.allocate(host, r.port, user, pass)
                if (why == null) rtt = minOf(rtt ?: Long.MAX_VALUE, t!!) else if (host == r.host || problem == null) problem = why
                why == null
            }
            val result = RelayCheck(now(), reachable, r.host in reachable, rtt, problem.takeIf { reachable.isEmpty() })
            lastCheck = result
            // Never the address or the secret.
            log("own relay check: ${if (result.ok) "ok over ${reachable.size} address(es), ${rtt} ms, public ${result.publicOk}" else "failed, $problem"}")
            return result
        } finally {
            checking = false
        }
    }

    private fun entries(ice: JSONObject?): List<JSONObject> {
        if (ice == null) return emptyList()
        val list = ice.optJSONArray("relays") ?: return if (ice.has("urls")) listOf(ice) else emptyList()
        return (0 until list.length()).mapNotNull(list::optJSONObject)
    }

    /**
     * This gateway's relays for its end of a call, as the audio bridge takes them, from what the service handed out
     * ([official]) and the owner's choice. Ours only: as handed out. Own relay only: never ours, whatever the service
     * sent. Own first: the own relay while it can be reached from here, else ours. A relay of any other kind (one
     * shared by another user) is dropped in every case: no choice allows it.
     */
    fun ice(official: JSONObject?): JSONObject? {
        val all = entries(official)
        val ours = all.filter { it.optString("kind", OFFICIAL_KIND) == OFFICIAL_KIND }
        if (ours.size != all.size) log("relay candidates of a kind the owner did not allow were dropped: ${all.size - ours.size}")
        val choice = choice
        if (choice == OFFICIAL) return if (ours.size == all.size) official else ours.takeIf { it.isNotEmpty() }?.let { JSONObject().put("relays", JSONArray(it)) }
        val r = relay
        val known = lastCheck?.takeIf { now() - it.atMs < CHECK_FRESH_MS }
        val own = r?.let {
            // Not checked lately: every address is tried. Checked and none answered: still tried when it is the only
            // relay allowed, as there is nothing to lose; left out when ours may stand in.
            val hosts = known?.reachable?.takeIf { it.isNotEmpty() } ?: if (known == null || choice == OWN) (listOf(it.host) + it.lan).distinct() else emptyList()
            if (hosts.isEmpty()) null else it.candidate(hosts, tag(), now() + CALL_TTL_MS)
        }
        val list = when {
            own != null -> listOf(own)
            choice == OWN_FIRST -> ours
            else -> emptyList()
        }
        log("relays for this call: ${if (list.isEmpty()) "none" else list.joinToString(",") { it.optString("id") }} (choice ${choice.ifEmpty { "official" }})")
        return list.takeIf { it.isNotEmpty() }?.let { JSONObject().put("relays", JSONArray(it)) }
    }

    /**
     * What a phone of this gateway is told (sealed for it): the owner's choice, and, when calls use the own relay,
     * its address for phones away from home with credentials good for [PHONE_TTL_MS]. A phone must have them before
     * it dials, so they outlast one call; they open this relay only, and never carry the secret.
     */
    fun forPhone(): JSONObject {
        val out = JSONObject().put("mode", choice).put("configured", relay != null)
        val r = relay
        if (r != null && choice != OFFICIAL) out.put("relay", r.candidate(listOf(r.host), tag(), now() + PHONE_TTL_MS))
        lastCheck?.let { c -> out.put("check", JSONObject().put("ok", c.ok).put("public_ok", c.publicOk).put("at_ms", c.atMs).apply { c.rttMs?.let { put("rtt_ms", it) } }.apply { c.problem?.let { put("problem", it) } }) }
        return out
    }

    companion object {
        const val OFFICIAL = ""
        const val OWN = "own"
        const val OWN_FIRST = "own_first"
        const val OFFICIAL_KIND = "official"
        /** As long as the service's: coturn checks the expiry again whenever the allocation is refreshed. */
        const val CALL_TTL_MS = 3 * 3600_000L
        const val PHONE_TTL_MS = 12 * 3600_000L
        const val CHECK_EVERY_MS = 10 * 60_000L
        /** A check older than this says nothing about now. */
        const val CHECK_FRESH_MS = 30 * 60_000L
    }
}
