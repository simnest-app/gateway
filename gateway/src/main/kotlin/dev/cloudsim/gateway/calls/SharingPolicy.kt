package dev.cloudsim.gateway.calls

import dev.cloudsim.gateway.net.ControlClient.Companion.unb64
import dev.cloudsim.gateway.protocol.Protocol
import org.json.JSONObject

/**
 * A member's call recording rights on a number (RECORDING_V7.md 15), all off unless the owner turns them on: play,
 * export, start and stop (only while in the call), and [all], the number's whole recordings instead of only the
 * stretches this phone was in, for those started from [allFrom] (0: every one).
 */
data class RecRights(val play: Boolean = false, val export: Boolean = false, val control: Boolean = false, val all: Boolean = false, val allFrom: Long = 0) {
    val any get() = play || export

    companion object {
        fun of(o: JSONObject) = RecRights(o.optBoolean("rec_play"), o.optBoolean("rec_export"), o.optBoolean("rec_control"), o.optBoolean("rec_all"), o.optLong("rec_all_from"))
    }
}

/** One member's rights on one of the gateway's numbers (DUAL_SIM.md). */
data class Rights(val answer: Boolean = false, val dial: Boolean = false, val smsView: Boolean = false, val smsSend: Boolean = false,
                  val historyFrom: Long = 0, val historyTo: Long = 0,
                  /** Turned off by the owner (MULTIPARTY_TRANSFER_V6.md); absent, answering brings both. */
                  val noTransfer: Boolean = false, val noInvite: Boolean = false, val rec: RecRights = RecRights()) {
    val transfer get() = answer && !noTransfer
    val invite get() = answer && !noInvite
}

/**
 * One family device in the owner's policy. With per-number rights the flags here are their union (what it may do on
 * at least one number), so code that does not yet look at numbers stays as strict as before and no stricter.
 */
data class Member(val deviceId: String, val publicKey: ByteArray, val answer: Boolean, val dial: Boolean = false, val smsView: Boolean = false, val smsSend: Boolean = false,
                  val historyFrom: Long = 0, val historyTo: Long = 0, val perNumber: Map<String, Rights>? = null,
                  /** May see this phone's contacts (IOS_UI_REDESIGN.md 9.2); per device, not per number. */
                  val contacts: Boolean = false,
                  val noTransfer: Boolean = false, val noInvite: Boolean = false, val rec: RecRights = RecRights()) {
    /** This member's rights on number [n]; without per-number rights, the flags. */
    fun on(n: String): Rights = perNumber?.let { it[n] ?: Rights() } ?: Rights(answer, dial, smsView, smsSend, historyFrom, historyTo, noTransfer, noInvite, rec)
}

/** How one number rings, in the policy's top-level terms. */
/** The owner this gateway was handed from, and the last policy revision that owner's signature still covers. */
data class PreviousOwner(val id: String, val revision: Int)

data class Ring(val ownerRing: List<String>, val mode: String, val waitS: Int, val assignee: String?, val fallback: Boolean)

/**
 * The owner-signed sharing policy (docs/architecture/FAMILY_RING_3D.md): who besides the owner may answer, and how
 * incoming calls ring. The gateway accepts only what the phone it paired with signed, so the service cannot add anyone.
 */
data class SharingPolicy(
    val revision: Int,
    val members: List<Member>,
    val ownerRing: List<String>,
    val mode: String,
    val waitS: Int,
    val assignee: String?,
    val fallback: Boolean,
    /** The gateway's numbers the owner has seen, and how each rings when set apart (DUAL_SIM.md). */
    val numbers: List<String> = emptyList(),
    val ringing: Map<String, Ring> = emptyMap(),
    /** The owner's call screening switches per number (SCREENING_ASSISTANT.md 10.1); "*" for calls on an unknown number. */
    val screening: Map<String, dev.cloudsim.gateway.screening.ScreenSettings> = emptyMap(),
    /**
     * Which media relays calls may use (RELAY_SPLIT.md 17.2): "" for the service's, "own" for the owner's own relay
     * only, "own_first" for the owner's with the service's when it cannot be reached.
     */
    val relay: String = "",
) {
    /** How calls on number [n] are screened; off when the owner set nothing. */
    fun screenFor(n: String?): dev.cloudsim.gateway.screening.ScreenSettings? = (n?.let { screening[it] } ?: screening["*"])?.takeIf { it.on }

    /**
     * The policy as it applies to calls and messages on number [n]: each member's rights on that number, and that
     * number's ringing. Without per-number settings, the policy itself.
     */
    fun forNumber(n: String): SharingPolicy {
        val r = ringing[n]
        return copy(
            members = members.map { m -> m.on(n).let { x -> m.copy(answer = x.answer, dial = x.dial, smsView = x.smsView, smsSend = x.smsSend, historyFrom = x.historyFrom, historyTo = x.historyTo,
                noTransfer = x.noTransfer, noInvite = x.noInvite, rec = x.rec) } },
            ownerRing = r?.ownerRing ?: ownerRing, mode = r?.mode ?: mode, waitS = r?.waitS ?: waitS,
            assignee = if (r != null) r.assignee else assignee, fallback = r?.fallback ?: fallback,
        )
    }

    /** The owner and the members with the answer right. */
    fun answerers(owner: String): List<String> = listOf(owner) + members.filter { it.answer }.map { it.deviceId }

    /** The owner and the members allowed to see this phone's contacts. */
    fun contactReaders(owner: String): List<String> = listOf(owner) + members.filter { it.contacts }.map { it.deviceId }

    /** Who may hand a call on, and who may ask someone into it (MULTIPARTY_TRANSFER_V6.md): the owner, and members
     *  who may answer unless the owner turned it off. On a policy for one number ([forNumber]). */
    fun transferers(owner: String): List<String> = listOf(owner) + members.filter { it.answer && !it.noTransfer }.map { it.deviceId }
    fun inviters(owner: String): List<String> = listOf(owner) + members.filter { it.answer && !it.noInvite }.map { it.deviceId }

    /** The owner and the members with the dial right (CALLER_DIAL_3E.md). */
    fun dialers(owner: String): List<String> = listOf(owner) + members.filter { it.dial }.map { it.deviceId }

    private val family get() = members.filter { it.answer }.map { it.deviceId }

    /** Ringing stages in order; later stages add to the earlier ones (the user's 2026-11-04 decision). */
    fun stages(): List<List<String>> = when (mode) {
        "owner_first" -> listOf(ownerRing, family)
        "assigned" -> listOfNotNull(listOfNotNull(assignee), if (fallback) family - setOfNotNull(assignee) else null)
        else -> listOf((ownerRing + family).distinct())
    }.filter { it.isNotEmpty() }

    /** This policy with [old] owner's place taken by [new], for a policy the previous owner signed. */
    fun replacingOwner(old: String, new: String): SharingPolicy {
        fun swap(id: String) = if (id == old) new else id
        return copy(ownerRing = ownerRing.map(::swap).distinct(), assignee = assignee?.let(::swap),
            ringing = ringing.mapValues { (_, r) -> r.copy(ownerRing = r.ownerRing.map(::swap).distinct(), assignee = r.assignee?.let(::swap)) })
    }

    /** A strict assignment stops reminding everyone when its wait runs out. */
    val strict get() = mode == "assigned" && !fallback

    companion object {
        /** No policy yet: only the owner rings, as in 3a and 3b. */
        fun none(owner: String) = SharingPolicy(0, emptyList(), listOf(owner), "simultaneous", 15, null, false)

        /**
         * Checks a policy as the service hands it out: signed by the owner this gateway paired with, for this gateway,
         * and newer than [after]. Returns null for anything else.
         */
        fun verify(response: JSONObject, gatewayId: String, owner: String, after: Int, previous: PreviousOwner? = null): SharingPolicy? = runCatching {
            if (response.optInt("revision") == 0) return null
            val raw = unb64(response.getString("policy"))!!
            val key = unb64(response.getString("signer_public_key"))!!
            val doc = JSONObject(raw.decodeToString())
            val revision = doc.getInt("revision")
            val signer = Protocol.deviceId(key)
            // The owner this gateway was handed from still counts for the policies it signed before (BACKUP_RESTORE_6.md).
            val trusted = signer == owner || (previous != null && signer == previous.id && revision <= previous.revision)
            require(trusted && doc.getInt("v") == 1 && doc.getString("gateway_id") == gatewayId && revision > after)
            require(Protocol.verify(key, Protocol.policyMessage(gatewayId, revision, raw), unb64(response.getString("signature"))))
            parse(doc)
        }.getOrNull()

        private fun parse(doc: JSONObject): SharingPolicy {
            val members = doc.optJSONArray("members")?.let { a ->
                (0 until a.length()).map { i ->
                    val m = a.getJSONObject(i)
                    val key = unb64(m.getString("public_key"))!!
                    require(Protocol.deviceId(key) == m.getString("device_id"))
                    val per = m.optJSONObject("per_number")?.let { o ->
                        o.keys().asSequence().associateWith { n -> rights(o.getJSONObject(n)) }
                    }
                    val any = per?.values?.fold(Rights()) { a, x -> Rights(a.answer || x.answer, a.dial || x.dial, a.smsView || x.smsView, a.smsSend || x.smsSend) }
                    if (any != null) Member(m.getString("device_id"), key, any.answer, any.dial, any.smsView, any.smsSend, perNumber = per, contacts = m.optBoolean("contacts"))
                    else Member(m.getString("device_id"), key, m.optBoolean("answer"), m.optBoolean("dial"), m.optBoolean("sms_view"), m.optBoolean("sms_send"),
                        m.optLong("sms_history_from"), m.optLong("sms_history_to"), contacts = m.optBoolean("contacts"),
                        noTransfer = m.optBoolean("no_transfer"), noInvite = m.optBoolean("no_invite"), rec = RecRights.of(m))
                }
            } ?: emptyList()
            val ring = doc.optJSONArray("owner_ring")?.let { a -> (0 until a.length()).map(a::getString) } ?: emptyList()
            val mode = doc.getString("mode")
            require(mode in setOf("simultaneous", "owner_first", "assigned"))
            val wait = doc.getInt("wait_s")
            require(wait in 5..60)
            val numbers = doc.optJSONArray("numbers")?.let { a -> (0 until a.length()).map(a::getString) } ?: emptyList()
            val ringing = doc.optJSONObject("ringing")?.let { o ->
                o.keys().asSequence().associateWith { n ->
                    val r = o.getJSONObject(n)
                    val m = r.getString("mode").also { require(it in setOf("simultaneous", "owner_first", "assigned")) }
                    Ring(r.optJSONArray("owner_ring")?.let { a -> (0 until a.length()).map(a::getString) } ?: emptyList(), m,
                        r.getInt("wait_s").also { require(it in 5..60) }, r.optString("assignee").ifEmpty { null }, r.optBoolean("fallback"))
                }
            } ?: emptyMap()
            val screening = doc.optJSONObject("screening")?.let { o ->
                o.keys().asSequence().mapNotNull { n -> dev.cloudsim.gateway.screening.ScreenSettings.parse(o.getJSONObject(n))?.let { n to it } }.toMap()
            } ?: emptyMap()
            return SharingPolicy(doc.getInt("revision"), members, ring, mode, wait, doc.optString("assignee").ifEmpty { null }, doc.optBoolean("fallback"), numbers, ringing, screening,
                doc.optString("relay").takeIf { it == "own" || it == "own_first" }.orEmpty())
        }

        private fun rights(o: JSONObject) = Rights(o.optBoolean("answer"), o.optBoolean("dial"), o.optBoolean("sms_view"), o.optBoolean("sms_send"),
            o.optLong("sms_history_from"), o.optLong("sms_history_to"), o.optBoolean("no_transfer"), o.optBoolean("no_invite"), RecRights.of(o))
    }
}
