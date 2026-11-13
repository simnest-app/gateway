package dev.cloudsim.gateway.screening

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * One number's call screening switches, as the owner signed them in the policy (SCREENING_ASSISTANT.md 10.1). Only
 * switches: no caller numbers and no audio, since the service can read the policy.
 */
data class ScreenSettings(
    val mode: String,
    /** How long the phones ring first, in [AFTER_RING]. */
    val ringS: Int = 20,
    /** [UNKNOWN]: callers not known on this phone; [ALL]: everyone but the allow list. */
    val who: String = UNKNOWN,
    /** Calls with no number shown. */
    val hidden: Boolean = true,
    /** When screening applies, minutes after midnight [from, to) in the gateway's time zone; null all day. */
    val hours: Pair<Int, Int>? = null,
    /** Keep what the caller says (the user's 2026-11-09 decision: on unless the owner turns it off). */
    val record: Boolean = true,
    /** Numbers in the owner's iPhone contacts count as known (their digests, sent with the assets). */
    val knownContacts: Boolean = false,
    /** Digest of the clips and lists this number's screening uses; the gateway uses only a set that matches. */
    val assets: String = "",
    /**
     * The same number again within 3 minutes still goes to the assistant (the owner turned "rings through" off,
     * 2026-11-10); the owner's notice then says it is a repeated call.
     */
    val repeats: Boolean = false,
) {
    val on get() = mode == DIRECT || mode == AFTER_RING

    companion object {
        const val OFF = "off"
        const val DIRECT = "direct"
        const val AFTER_RING = "after_ring"
        const val UNKNOWN = "unknown"
        const val ALL = "all"

        /** Null for anything the gateway does not understand: then the number is not screened at all. */
        fun parse(o: JSONObject): ScreenSettings? = runCatching {
            val mode = o.getString("mode").also { require(it in setOf(OFF, DIRECT, AFTER_RING)) }
            val ring = o.optInt("ring_s", 20).also { require(it in 5..60) }
            val who = o.optString("who", UNKNOWN).also { require(it in setOf(UNKNOWN, ALL)) }
            val hours = o.optJSONArray("hours")?.let { a ->
                require(a.length() == 2)
                (a.getInt(0) to a.getInt(1)).also { (f, t) -> require(f in 0 until 1440 && t in 0 until 1440 && f != t) }
            }
            ScreenSettings(mode, ring, who, o.optBoolean("hidden", true), hours, o.optBoolean("record", true),
                o.optBoolean("known_contacts"), o.optString("assets"), o.optBoolean("repeats"))
        }.getOrNull()
    }
}

/** Who the caller is to this gateway, for screening. */
enum class Known {
    /** On the owner's allow list: never screened. */
    ALLOWED,
    /** In this phone's contacts or call log, or the owner's contacts when that is on. */
    KNOWN,
    UNKNOWN,
}

/** What happens to an incoming call. [why] goes to the gateway log, never with the number. */
data class Decision(val mode: String, val ringS: Int = 0, val why: String, val repeatCount: Int = 0) {
    val screen get() = mode == ScreenSettings.DIRECT || mode == ScreenSettings.AFTER_RING

    companion object {
        fun ring(why: String) = Decision(ScreenSettings.OFF, why = why)
    }
}

/** What the gateway knows when a call starts ringing. */
data class RingContext(
    val settings: ScreenSettings?,
    /** The caller's number; null or blank when hidden. */
    val caller: String?,
    val known: Known,
    val emergencyCaller: Boolean,
    /** The latest permissions confirmed within 5 minutes (REVOKE_LEASE_5.md). */
    val remote: Boolean,
    /** The audio bridge answers and the uplink is ready. */
    val bridgeReady: Boolean,
    /** The clips this number's screening needs are here and match the policy. */
    val assetsReady: Boolean,
)

/**
 * Decides whether the assistant takes an incoming call (SCREENING_ASSISTANT.md 2.1). Never screened, whatever the
 * owner set: emergency numbers, any call within 30 minutes of an emergency call from this phone, the same number
 * calling again within 3 minutes, the allow list. And not when screening could not work: permissions unconfirmed,
 * no audio bridge, no clips. Then the call rings as it would without screening.
 */
class ScreeningRules(private val now: () -> Long = System::currentTimeMillis, private val zone: () -> ZoneId = ZoneId::systemDefault) {
    /** When this phone last dialed an emergency number. */
    @Volatile var lastEmergency = 0L
    /** Each caller's last ring, by number, and how many calls in a row came within [REPEAT_MS] of each other. */
    private val lastRing = LinkedHashMap<String, Pair<Long, Int>>()

    @Synchronized
    fun decide(c: RingContext): Decision {
        val t = now()
        // This call's place in a run of calls from the number, each within 3 minutes of the one before (1: not a repeat).
        val count = c.caller?.takeIf { it.isNotBlank() }?.let { n ->
            val before = lastRing.remove(n)
            val k = if (before != null && t - before.first < REPEAT_MS) before.second + 1 else 1
            lastRing[n] = t to k
            while (lastRing.size > MAX_REMEMBERED) lastRing.remove(lastRing.keys.first())
            k
        } ?: 1
        val repeat = count > 1
        val s = c.settings?.takeIf { it.on } ?: return Decision.ring("off")
        val hidden = c.caller.isNullOrBlank()
        return when {
            c.emergencyCaller -> Decision.ring("emergency_caller")
            lastEmergency > 0 && t - lastEmergency < EMERGENCY_MS -> Decision.ring("after_emergency")
            !c.remote -> Decision.ring("permissions_unconfirmed")
            !inHours(s.hours, t) -> Decision.ring("outside_hours")
            hidden && !s.hidden -> Decision.ring("hidden_not_screened")
            !hidden && c.known == Known.ALLOWED -> Decision.ring("allowed")
            !hidden && s.who == ScreenSettings.UNKNOWN && c.known == Known.KNOWN -> Decision.ring("known")
            repeat && !s.repeats -> Decision.ring("repeat")
            !c.assetsReady -> Decision.ring("no_assets")
            !c.bridgeReady -> Decision.ring("no_bridge")
            else -> Decision(s.mode, if (s.mode == ScreenSettings.AFTER_RING) s.ringS else 0,
                if (repeat) "repeat_screened" else if (hidden) "hidden" else s.who, if (repeat) count else 0)
        }
    }

    private fun inHours(hours: Pair<Int, Int>?, t: Long): Boolean {
        val (from, to) = hours ?: return true
        val local = Instant.ofEpochMilli(t).atZone(zone())
        val m = local.hour * 60 + local.minute
        // An overnight window (22:00–08:00) wraps past midnight.
        return if (from < to) m in from until to else m >= from || m < to
    }

    companion object {
        const val REPEAT_MS = 3 * 60_000L
        const val EMERGENCY_MS = 30 * 60_000L
        private const val MAX_REMEMBERED = 200
    }
}
