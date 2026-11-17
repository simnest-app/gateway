package dev.cloudsim.gateway.calls

import dev.cloudsim.gateway.identity.Signer
import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ControlClient.Companion.b64
import dev.cloudsim.gateway.net.ControlClient.Companion.unb64
import dev.cloudsim.gateway.net.ServiceException
import dev.cloudsim.gateway.protocol.Protocol
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Runs a task on the relay's thread after a delay; the returned function cancels it. */
/** A call phones talk in (RECORDING_V7.md): connected, and over. */
interface CallWatcher {
    /** [assisted]: the screening assistant answered; such a call is recorded without the notice (RECORDING_NOTICE.md 4). */
    fun started(callId: String, numberId: String?, peer: String?, outgoing: Boolean, assisted: Boolean = false)
    fun ended(callId: String)
}

fun interface Scheduler {
    fun after(delayMs: Long, task: () -> Unit): () -> Unit
}

/** What the gateway does to the cellular call; the system Telecom implementation in the app, a fake in tests. */
interface Telephony {
    fun answer(): Boolean
    fun end(): Boolean
    /** Places a cellular call to [number]; false if the system refused. */
    fun dial(number: String): Boolean
    /** Dials on the SIM of gateway number [numberId] (DUAL_SIM.md); null uses the system's default. */
    fun dial(number: String, numberId: String?): Boolean = dial(number)
    /** Which gateway number the ringing call is on, when the system tells; null otherwise. */
    fun ringingNumber(): String? = null
    /** Whether [numberId] is one of this gateway's SIMs, in now. */
    fun hasNumber(numberId: String): Boolean = true
    /** Whether the system has no call at all, whatever this relay believes. */
    fun idle(): Boolean = true
    /** Whether [number] is an emergency number; those are never dialed through the gateway (DIALER_3F.md). */
    fun isEmergency(number: String): Boolean = false
    /** Whether the far end's answer of a dialed call will be reported (the gateway is the default phone app). */
    fun precise(): Boolean = false
    /** Sounds one key (0-9, *, #) in the call in progress, as pressing it on the phone would; false if it cannot. */
    fun dtmf(digit: Char): Boolean = false
}

/** End-to-end sealing for one device (CALLER_DIAL_3E.md); the app uses HPKE, tests a fake. */
interface Sealer {
    /** Sealed for [to], or null when that device has no recipient key yet. */
    fun seal(kind: String, to: String, id: String, plaintext: ByteArray): JSONObject?
    /** The plaintext if [envelope] is for this gateway, of [kind], and signed by [senderKey]; otherwise null. */
    fun open(envelope: JSONObject, kind: String, senderKey: ByteArray): ByteArray?
}

/**
 * Gateway side of call control 3a (docs/architecture/CALL_CONTROL_3A.md), media signaling 3b (MEDIA_3B.md) and
 * staged ringing 3d (FAMILY_RING_3D.md). The gateway is the only authority on the cellular call: it reports what the
 * system shows, rings each stage of the owner-signed policy frozen at the call's start, and decides which phone's
 * answer wins under the current policy. Media offers must come from the phone that answered, signed by its key, before
 * they reach the shell audio bridge, and the bridge's answers are signed. Not thread-safe: the service feeds phone
 * states, service events, bridge messages and timers from one thread.
 */
class CallRelay(
    private val client: ControlClient,
    private val telephony: Telephony,
    private val bridge: MediaBridge,
    private val signer: Signer,
    /** The managing phone's device ID from pairing: a hash of its public key. */
    private val pairedPhone: () -> String?,
    /** The current verified sharing policy; [SharingPolicy.none] before the owner set one. */
    private val policy: () -> SharingPolicy?,
    private val scheduler: Scheduler,
    private val sealer: Sealer,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    /** Whether remote access is allowed now: the latest permissions confirmed within 5 minutes (REVOKE_LEASE_5.md). */
    private val remote: () -> Boolean = { true },
    /** Told when a call a phone talks in is connected, who is in it, and when it is over (RECORDING_V7.md). */
    private val watcher: CallWatcher? = null,
    /** Call screening (SCREENING_ASSISTANT.md); null: every call rings as before. */
    private val screener: dev.cloudsim.gateway.screening.Screener? = null,
    /**
     * Turns the relays the service handed out into the ones this call may use, by the owner's signed choice and with
     * the owner's own relay (RELAY_SPLIT.md 17); as handed out when the gateway has no such choice.
     */
    private val relays: (JSONObject?) -> JSONObject? = { it },
    /**
     * Holds the processor at full speed for call [callId] from its ring (or a phone's dial request) to its end (the
     * user's 2026-11-16 decision, ANSWER_DELAY.md 14.4, 14.10); closing it lets go and logs the call's cores and load.
     */
    private val fullSpeed: (callId: String) -> AutoCloseable = { AutoCloseable {} },
) {
    private enum class Phase { IDLE, RINGING, DIALING, ACTIVE, EMERGENCY }

    private var phase = Phase.IDLE
    /** A call is under way (ringing, dialling or connected): no self-test then (SELF_TEST.md). */
    val busy: Boolean get() = phase != Phase.IDLE
    private var callId: String? = null
    /** The phone whose answer we passed to the system, until the system reports off-hook. */
    private var answering: String? = null
    private var reported = false
    private var answeredBy: String? = null
    private var ice: JSONObject? = null
    /** Whether the service was asked for relays this call, when an offer came without them. */
    private var iceAsked = false
    private var bridgeStarted = false
    /** The full speed held for the current relayed call, incoming or dialed for a phone; see [fullSpeed]. */
    private var speed: AutoCloseable? = null
    /**
     * The owner's phone connected with the bridge while the call rings, without audio (ANSWER_DELAY.md 14): its audio
     * opens if it answers; any other end of the ringing closes the connection.
     */
    private var ringMember: String? = null

    private fun letGoOfSpeed() {
        val s = speed ?: return
        speed = null
        runCatching { s.close() }.onFailure { log("full speed: ${it.javaClass.simpleName}") }
    }

    /** The owner's connection made while it rang is no longer wanted: the bridge closes it. */
    private fun dropRingMember(reason: String) {
        val m = ringMember ?: return
        ringMember = null
        bridge.send(JSONObject().put("type", "leave").put("call_id", callId).put("member", m).put("reason", reason))
        log("connection made while ringing with ${m.take(8)} closed: $reason")
    }

    // Ringing of the current call: the policy's stages frozen at the start, which devices were offered, confirmed
    // ringing or declined, and the timers of the current stage.
    private var frozen: SharingPolicy? = null
    private var stages: List<List<String>> = emptyList()
    private var stage = -1
    private val offered = mutableSetOf<String>()
    private val ringing = mutableSetOf<String>()
    private val declined = mutableSetOf<String>()
    /** A strict assignment ran out: no phone may answer any more, though the cellular call still rings. */
    private var exhausted = false
    private var timers = mutableListOf<() -> Unit>()
    /** The caller's number while it rings, sealed for each device as it is offered; null if hidden or unknown. */
    private var callerNumber: String? = null

    val currentCall: String? get() = callId

    /**
     * The phones talking in the current call now. Read from the recording threads too, while this one may be changing
     * the map: a read that collides is tried again rather than taken for "nobody".
     */
    val participants: Set<String> get() {
        repeat(3) { runCatching { return inCall().toSet() } }
        return emptySet()
    }

    /** The number at the other end of the current call, when known; never sent anywhere but sealed. */
    private var peerNumber: String? = null
    /** The watcher was told this call started, so it is told when it ends. */
    private var watched = false

    private fun startWatch(outgoing: Boolean, assisted: Boolean = false) {
        if (watched) return
        val id = callId ?: return
        watched = true
        runCatching { watcher?.started(id, numberId, peerNumber, outgoing, assisted) }.onFailure { log("recording: ${it.javaClass.simpleName}") }
    }

    /** A paired phone, not the Redmi, is talking in the current call. */
    val remoteParty: Boolean get() = phase == Phase.ACTIVE && answeredBy != null && answeredBy != LOCAL

    private var farPending = false
    /** The last key message carried out in this call: an older or repeated one is ignored. */
    private var dtmfSeq = 0L

    /** The far end of a dialed call picked up (only known as the default phone app): now the call counts as connected. */
    fun onFarAnswered() {
        if (phase != Phase.ACTIVE || !farPending) return
        farPending = false
        state("active", answeredBy = answeredBy!!, reason = "connected")
        startWatch(outgoing = true)
        log("dialed call ${callId?.take(8)} answered at the far end")
    }

    /** The gateway number the current call is on, when known (DUAL_SIM.md). */
    private var numberId: String? = null

    /** The number call [id] is on while it goes on ("" for the default one), or null if it is not the current call. */
    fun numberOf(id: String): String? = if (callId == id) numberId ?: "" else null

    /**
     * The phones in this call (MULTIPARTY_TRANSFER_V6.md): the one that answered or dialed, and those invited or
     * transferred in, while they ring and once they joined. Each joined phone has its own media with the bridge.
     */
    private class Phone(val kind: String, val invitedBy: String = "", var joined: Boolean = false, var showing: Boolean = false, var connected: Boolean = false)
    private val phones = mutableMapOf<String, Phone>()
    private val phoneTimers = mutableMapOf<String, MutableList<() -> Unit>>()

    /** The phones talking in the call now. */
    private fun inCall(): Set<String> = phones.filterValues { it.joined }.keys

    /** The policy as it applies to the current call's number: that number's rights and ringing. */
    private fun current(): SharingPolicy? = pairedPhone()?.let { owner -> (policy() ?: SharingPolicy.none(owner)).let { p -> numberId?.let(p::forNumber) ?: p } }

    /** May this device answer now: offered in this call, not declined, and allowed by the current policy. */
    private fun mayAnswer(device: String): Boolean {
        val owner = pairedPhone() ?: return false
        val now = current() ?: return false
        return !exhausted && device in offered && device !in declined && device in now.answerers(owner)
    }

    private fun cancelTimers() {
        timers.forEach { it() }
        timers.clear()
    }

    /** Starts stage [index]: its devices ring as well, and the stage's timers start. */
    private fun startStage(index: Int) {
        cancelTimers()
        stage = index
        val targets = stages.getOrNull(index) ?: return
        val added = targets.filter { offered.add(it) }
        if (index > 0 && added.isNotEmpty()) {
            report { client.call("POST", "/v1/calls/$callId/offer", JSONObject().put("targets", JSONArray(added)).put("sealed", sealCaller(added, ringMedia = true))) }
            log("stage ${index + 1}: offered ${added.map { it.take(8) }}")
        }
        if (!timed()) return
        val call = callId
        // No phone of this stage showed the call in time: it counts as unavailable (P-03 candidate, 10 s).
        timers += scheduler.after(PREPARE_MS) {
            if (callId == call && stage == index && targets.none { it in ringing }) {
                log("stage ${index + 1}: nobody confirmed ringing")
                nextStage()
            }
        }
        // Already confirmed by a device carried over from an earlier stage.
        if (targets.any { it in ringing && it !in declined }) startWait(index)
    }

    /** Whether the current stage has somewhere to go when its wait ends. */
    private fun timed() = stage + 1 < stages.size || frozen?.strict == true

    private var waiting = -1

    private fun startWait(index: Int) {
        if (waiting == index || !timed()) return
        waiting = index
        val call = callId
        timers += scheduler.after(frozen!!.waitS * 1000L) {
            if (callId == call && stage == index) {
                log("stage ${index + 1}: wait over")
                nextStage()
            }
        }
    }

    private fun nextStage() {
        if (stage + 1 < stages.size) return startStage(stage + 1)
        cancelTimers()
        if (frozen?.strict == true && !exhausted) {
            exhausted = true
            withdraw((offered - declined).toList(), "no_answer")
            log("strict assignment ran out; the cellular call keeps ringing")
        }
    }

    private fun withdraw(devices: List<String>, reason: String) {
        if (devices.isEmpty()) return
        ringMember?.let { if (it in devices) dropRingMember(reason) }
        report { client.call("POST", "/v1/calls/$callId/withdraw", JSONObject().put("targets", JSONArray(devices)).put("reason", reason)) }
    }

    /**
     * The caller's number sealed for each of [devices] that has a recipient key; the service only passes it along.
     * [ringMedia]: a ringing stage; the owner's phone is told it may connect while it rings (ANSWER_DELAY.md 14), which
     * no other phone is, and the service cannot add, as it cannot read this.
     */
    private fun sealCaller(devices: List<String>, ringMedia: Boolean = false): JSONObject {
        val out = JSONObject()
        val owner = pairedPhone()
        for (d in devices) {
            // A run of calls from the same number that still went to the assistant: the owner's notice says which call it is.
            val plain = JSONObject().put("number", callerNumber ?: "").apply {
                if (callerRepeat > 1) put("repeat", callerRepeat)
                if (ringMedia && d == owner) put("ring_media", 1)
            }.toString().toByteArray()
            runCatching { sealer.seal("caller", d, callId!!, plain) }.getOrNull()?.let { out.put(d, it.toString()) }
        }
        return out
    }

    /** A newer policy arrived: a removed member stops ringing at once, and one already talking is cut off. */
    fun onPolicyChanged() {
        val owner = pairedPhone() ?: return
        val allowed = (current() ?: return).answerers(owner)
        if (phase == Phase.RINGING) {
            val removed = offered.filter { it !in allowed && it !in declined }
            declined += removed
            withdraw(removed, "removed")
        }
        val dialers = (current() ?: return).dialers(owner)
        if (phase == Phase.DIALING && answeredBy != null && answeredBy !in dialers) {
            log("the dialing member was removed; ending the call")
            telephony.end()
        }
        if (phase == Phase.ACTIVE) {
            // Each phone keeps its place only while the policy still lets it answer (or, the one that dialed, dial).
            for ((device, p) in phones.toMap()) {
                val keeps = device in allowed || (device == answeredBy && device in dialers)
                if (keeps) continue
                if (p.joined) dropPhone(device, "removed") else dropGuest(device, "removed")
            }
        }
    }

    /**
     * The latest permissions went unconfirmed for 5 minutes: no phone rings any more, and a call carried on a phone is
     * hung up, as nobody is left in it (the Redmi's earpiece is silent; the user's 2026-11-05 decision). A call on the
     * Redmi itself goes on.
     */
    fun onLeaseExpired() {
        when {
            phase == Phase.RINGING -> {
                val stop = (offered - declined).toList()
                declined += stop
                exhausted = true
                cancelTimers()
                withdraw(stop, "lease")
                log("permissions unconfirmed for 5 minutes: phones stop ringing, the Redmi keeps ringing")
            }
            phase in setOf(Phase.ACTIVE, Phase.DIALING) && (inCall().isNotEmpty() || (answeredBy != null && answeredBy != LOCAL)) -> {
                log("permissions unconfirmed for 5 minutes: ending the call carried on ${answeredBy!!.take(8)}")
                telephony.end()
            }
        }
    }

    /** System phone state: one of the TelephonyManager CALL_STATE_* values. */
    fun onPhoneState(state: Int, number: String? = null, emergency: Boolean = false) {
        // An emergency call is the Redmi's own: not reported, not relayed, earpiece and microphone untouched.
        if (phase == Phase.EMERGENCY) {
            if (state == IDLE) { phase = Phase.IDLE; warmAfterOwnCall(); log("emergency call over") }
            return
        }
        if (emergency && phase == Phase.IDLE) {
            phase = Phase.EMERGENCY
            runCatching { screener?.emergency() }
            log("emergency call: left to the system")
            return
        }
        when (state) {
            RINGING -> if (phase == Phase.IDLE) {
                phase = Phase.RINGING
                callId = UUID.randomUUID().toString()
                callerNumber = number?.takeIf { it.isNotBlank() }
                answering = null
                numberId = telephony.ringingNumber()
                frozen = current()
                stages = frozen?.stages() ?: emptyList()
                // Full speed from the ring to the end, whoever answers (ANSWER_DELAY.md 14.4); not for a call left
                // to this phone alone (below), and never for an emergency call (above).
                if (remote()) speed = runCatching { fullSpeed(callId!!) }.onFailure { log("full speed: ${it.javaClass.simpleName}") }.getOrNull()
                if (!remote()) {
                    // Remote access paused: the Redmi rings as an ordinary phone, nobody else is told.
                    exhausted = true
                    stages = emptyList()
                    log("incoming call ${callId!!.take(8)}: permissions unconfirmed for 5 minutes, not relayed")
                    return
                }
                val decision = screener?.let { sc ->
                    runCatching { sc.decide(frozen?.screenFor(numberId), numberId, callerNumber, emergency, remote()) }
                        .onFailure { log("screening: ${it.javaClass.simpleName}; rings as usual") }.getOrNull()
                }
                screen = decision?.takeIf { it.screen }?.let { frozen?.screenFor(numberId) }
                callerRepeat = decision?.takeIf { it.screen }?.repeatCount ?: 0
                if (decision != null && decision.why != "off") log("screening: ${decision.mode} (${decision.why})")
                if (decision?.mode == dev.cloudsim.gateway.screening.ScreenSettings.DIRECT) {
                    // The assistant answers at once: nothing rings, everyone who may answer is told (SCREENING_ASSISTANT.md 10.2).
                    val all = pairedPhone()?.let { o -> frozen?.answerers(o) } ?: emptyList()
                    offered += all
                    stages = emptyList()
                    reported = report { client.call("POST", "/v1/calls", JSONObject().put("call_id", callId).put("number_id", numberId ?: "").put("targets", JSONArray(all)).put("sealed", sealCaller(all)).put("screening", "direct")) }
                    log("incoming call ${callId!!.take(8)} on ${numberId?.take(12) ?: "the default number"} reported=$reported: the assistant answers in ${DIRECT_WAIT_MS / 1000}s")
                    // Not at once (the user's 2026-11-10 request): the phones' notice goes now, the caller hears a few
                    // rings, and meanwhile the greeting is read into memory and the iPhone readies its media, so
                    // listening in right after the answer is quick. 旁听 in this time answers at once and listens.
                    runCatching { screener?.clips(numberId)?.greeting() }
                    // And the transcription models (SCREENING_ASSISTANT.md 13), in those same seconds.
                    runCatching { screener?.prepare(callId!!) }
                    transcribing = true
                    directPending = true
                    val call = callId
                    screenTimer = scheduler.after(DIRECT_WAIT_MS) {
                        if (callId == call && phase == Phase.RINGING && answering == null) {
                            directPending = false
                            answerWithAssistant()
                        }
                    }
                    return
                }
                val first = stages.firstOrNull() ?: emptyList()
                offered += first
                reported = report { client.call("POST", "/v1/calls", JSONObject().put("call_id", callId).put("number_id", numberId ?: "").put("targets", JSONArray(first)).put("sealed", sealCaller(first, ringMedia = true))) }
                log("incoming call ${callId!!.take(8)} on ${numberId?.take(12) ?: "the default number"} reported=$reported mode=${frozen?.mode} stage 1: ${first.map { it.take(8) }}")
                if (reported) startStage(0)
                if (decision?.mode == dev.cloudsim.gateway.screening.ScreenSettings.AFTER_RING) {
                    // Rings first; nobody answered in time, so the assistant does (the user's own voicemail).
                    val call = callId
                    screenTimer = scheduler.after(decision.ringS * 1000L) {
                        if (callId == call && phase == Phase.RINGING && answering == null) {
                            cancelTimers()
                            withdraw((offered - declined).toList(), "assistant")
                            answerWithAssistant()
                        }
                    }
                }
            }
            OFFHOOK -> if (phase == Phase.DIALING) {
                // Off-hook only means the line is up; ordinary apps cannot see when the other side picks up.
                phase = Phase.ACTIVE
                cancelTimers()
                quietAgain()
                // As the default phone app the far end's answer follows; until then the phone only plays ringback.
                farPending = telephony.precise()
                phones[answeredBy!!] = Phone("answer", joined = true)
                state("active", answeredBy = answeredBy!!, reason = if (farPending) "alerting" else "")
                if (!farPending) startWatch(outgoing = true)
                log("dialed call ${callId?.take(8)} is off-hook${if (farPending) ", ringing at the far end" else ""}")
            } else if (phase == Phase.RINGING) {
                phase = Phase.ACTIVE
                cancelTimers()
                // Off-hook without a remote answer in flight means someone answered on this phone.
                val by = answering ?: LOCAL
                answeredBy = by
                if (by != LOCAL) quietAgain()
                // The owner's connection made while it rang: its audio opens now if it answered, else it goes first,
                // before the assistant or another phone takes the bridge (ANSWER_DELAY.md 14).
                if (ringMember == by) {
                    ringMember = null
                    bridgeStarted = bridge.send(JSONObject().put("type", "open").put("call_id", callId).put("member", by)) || bridgeStarted
                    log("connection made while ringing with ${by.take(8)}: audio open")
                } else dropRingMember("answered_elsewhere")
                // Answered on this phone itself: its audio does not pass the gateway, nothing to hold full speed for.
                if (by == LOCAL) letGoOfSpeed()
                screenTimer?.invoke()
                screenTimer = null
                if (by == ASSISTANT) return startAssistant()
                // Answered by a phone during the rings: nothing for the assistant, so nothing to write down.
                callId?.let { id -> if (transcribing) runCatching { screener?.stopTranscribing(id, over = false) } }
                if (by != LOCAL) phones[by] = Phone("answer", joined = true)
                state("active", answeredBy = by)
                peerNumber = callerNumber
                // Answered on this phone itself: its audio does not pass the bridge, so nothing to record.
                if (by != LOCAL) startWatch(outgoing = false)
                log("answered by ${by.take(8)}")
            } else if (phase == Phase.IDLE) {
                // A call dialled on this phone itself: not relayed, but it takes the uplink's warm-up with it.
                ownCall = true
            }
            IDLE -> if (phase != Phase.IDLE) {
                state("ended", reason = when (phase) { Phase.RINGING -> "not_answered"; Phase.DIALING -> "failed"; else -> "ended" })
                log("call ${callId?.take(8)} ended")
                ownCall = false
                reset()
            } else if (ownCall) {
                ownCall = false
                warmAfterOwnCall()
                log("a call dialled on this phone is over: uplink warmed again")
            }
        }
    }

    /**
     * The earpiece silenced at the answer (or the dial) again as the call goes off-hook: the Mate 40 Pro+ puts its
     * earpiece volume back to the user's level at that moment, and the caller was heard there until the media started
     * (measured 2026-11-13: 0 at the answer, 12 again 0.26 s later as the call went off-hook, for 6.5 s). The bridge
     * also re-asserts it every 200 ms until then; this closes the gap at once.
     */
    private fun quietAgain() {
        bridge.send(JSONObject().put("type", "quiet").put("call_id", callId))
    }

    /** A call dialled on this phone while no call was being relayed; see [warmAfterOwnCall]. */
    private var ownCall = false

    /**
     * The uplink only reaches the far end in a call if Telephony TX was written to since the call before it (V1). The
     * bridge does that after every call it carried; a call it did not carry (dialled on this phone, an emergency
     * call) left the next one cold: the track showed no route, the bridge ended the session after 2 s with
     * route_unknown, and the gateway hung up on a caller the assistant had just answered (2026-11-11, twice after a
     * call dialled on the phone).
     */
    private fun warmAfterOwnCall() {
        bridge.send(JSONObject().put("type", "warm"))
    }

    private fun reset() {
        callId?.let { id -> if (transcribing) runCatching { screener?.stopTranscribing(id, over = true) } }
        transcribing = false
        assistant?.let { a ->
            a.ended()
            val id = callId
            if (id != null) runCatching { screener?.finished(id, numberId, a.summary()) }
            log("screening ${id?.take(8)} over: ${a.result}")
        }
        assistant = null
        screen = null
        screenTimer?.invoke()
        screenTimer = null
        directPending = false
        pendingListen = null
        ringingInstead = false

        if (watched) callId?.let { id -> runCatching { watcher?.ended(id) } }
        watched = false
        peerNumber = null
        bridge.send(JSONObject().put("type", "stop").put("call_id", callId))
        bridge.send(JSONObject().put("type", "warm"))
        phase = Phase.IDLE
        callId = null
        answering = null
        answeredBy = null
        ice = null
        iceAsked = false
        bridgeStarted = false
        ringMember = null
        letGoOfSpeed()
        cancelTimers()
        frozen = null
        stages = emptyList()
        stage = -1
        waiting = -1
        offered.clear()
        ringing.clear()
        declined.clear()
        exhausted = false
        callerNumber = null
        farPending = false
        numberId = null
        dtmfSeq = 0
        phoneTimers.values.forEach { list -> list.forEach { it() } }
        phoneTimers.clear()
        phones.clear()
    }

    /** One event from the service's queue. */
    fun onEvent(type: String, data: JSONObject) {
        val id = data.optString("call_id")
        if (!remote() && type in setOf("call.dial", "call.answer", "call.media", "call.dtmf", "call.invite")) {
            log("$type for ${id.take(8)} refused: permissions unconfirmed for 5 minutes")
            if (type == "call.dial") report { client.call("POST", "/v1/calls/$id/state", JSONObject().put("state", "ended").put("answered_by", "").put("reason", "lease")) }
            return
        }
        if (type == "call.dial") return dial(data)
        if (id != callId) return
        when (type) {
            "call.answer" -> if (phase == Phase.ACTIVE && screening) screenJoin(data.optString("device_id"), data.optBoolean("listen"))
            // 旁听 while the assistant is about to answer: it answers now, and this phone listens once it has.
            else if (phase == Phase.RINGING && directPending && answering == null && data.optBoolean("listen") && mayAnswer(data.optString("device_id"))) {
                screenTimer?.invoke()
                screenTimer = null
                directPending = false
                pendingListen = data.optString("device_id")
                log("${pendingListen!!.take(8)} listens in before the assistant answered: answering now")
                answerWithAssistant()
            }
            else if (phase == Phase.ACTIVE) joinGuest(data.optString("device_id"))
            else if (phase == Phase.RINGING && answering == null) {
                val device = data.optString("device_id")
                if (!mayAnswer(device)) {
                    log("answer from ${device.take(8)} refused: not ringing for it")
                    return
                }
                answering = device
                // Earpiece silent from the answer on, before media starts (the bridge restores it when the call ends).
                bridge.send(JSONObject().put("type", "quiet").put("call_id", callId))
                if (!telephony.answer()) {
                    log("system refused to answer")
                    answering = null
                }
            }
            "call.end" -> if (phase != Phase.IDLE) {
                val a = assistant
                if (screening && a != null) a.ownerHangUp()
                else if (!telephony.end()) log("system refused to end the call")
            }
            "call.screen" -> screenAction(data)
            "call.media" -> if (data.optString("kind") == "offer") offer(data)
            "call.dtmf" -> dtmf(data)
            "call.invite" -> invite(data)
            "call.leave" -> if (phase == Phase.ACTIVE) data.optString("device_id").let { if (it in inCall()) dropPhone(it, "left") }
            "call.remove" -> if (phase == Phase.ACTIVE) remove(data)
            "call.ringing" -> if (phase == Phase.ACTIVE) guestShowing(data.optString("device_id"))
            else if (phase == Phase.RINGING) {
                val device = data.optString("device_id")
                if (device in offered && ringing.add(device)) {
                    log("ringing on ${device.take(8)}")
                    if (device in stages.getOrElse(stage) { emptyList() }) startWait(stage)
                }
            }
            "call.decline" -> if (phase == Phase.ACTIVE) data.optString("device_id").let { if (phones[it]?.joined == false) dropGuest(it, "declined") }
            else if (phase == Phase.RINGING) {
                val device = data.optString("device_id")
                if (device in offered && declined.add(device)) {
                    log("declined on ${device.take(8)}")
                    if (device == ringMember) dropRingMember("declined")
                    // The whole stage said no: move on early. Devices carried over from earlier stages count too.
                    if (offered.all { it in declined }) nextStage()
                }
            }
        }
    }

    /**
     * Keys pressed on the phone in the call (IOS_UI_REDESIGN.md 3.5): sealed by that phone for this gateway, in order,
     * each sounded on the cellular call for a moment, one after the other. Only from the phone talking in this call.
     */
    private fun dtmf(data: JSONObject) {
        val from = data.optString("device_id")
        // The service lets only the phone holding the keys send them; here it must at least be talking in the call.
        if (phase != Phase.ACTIVE || from !in inCall()) return log("keys from ${from.take(8)} refused: not in this call")
        // The key must be that phone's own: its device ID is a hash of it, so the service cannot swap in another.
        val key = unb64(data.optString("public_key"))?.takeIf { Protocol.deviceId(it) == from } ?: return log("keys from ${from.take(8)} refused: wrong key")
        val plain = sealer.open(JSONObject(data.optString("sealed", "{}")), "dtmf", key)
            ?.let { runCatching { JSONObject(it.decodeToString()) }.getOrNull() } ?: return log("keys from ${from.take(8)} refused: not sealed by it")
        val digits = plain.optString("digits")
        val seq = plain.optLong("seq")
        if (plain.optString("call_id") != callId || !DTMF.matches(digits) || seq <= dtmfSeq) return log("keys from ${from.take(8)} ignored: stale or malformed")
        dtmfSeq = seq
        val id = callId
        digits.forEachIndexed { i, d ->
            if (i == 0) telephony.dtmf(d)
            else timers += scheduler.after(i * DTMF_GAP_MS) { if (callId == id && phase == Phase.ACTIVE) telephony.dtmf(d) }
        }
        log("${digits.length} key(s) from ${from.take(8)}")
    }

    /**
     * A phone asks this gateway to place a call (CALLER_DIAL_3E.md). The number is sealed for this gateway by that phone,
     * which the current policy must let dial. One call at a time: anything else in progress means busy.
     */
    private fun dial(data: JSONObject) {
        val id = data.optString("call_id")
        val from = data.optString("device_id")
        val endNow = { reason: String ->
            report { client.call("POST", "/v1/calls/$id/state", JSONObject().put("state", "ended").put("answered_by", "").put("reason", reason)) }
        }
        // A request is only good for a moment: never act on one replayed or delayed (the phone gave up long ago).
        val at = data.optLong("_at_ms")
        if (at > 0 && now() - at > DIAL_FRESH_MS) {
            log("dial request ${id.take(8)} is ${(now() - at) / 1000}s old, ignored")
            return
        }
        if (phase != Phase.IDLE || !telephony.idle()) {
            if (id != callId) endNow("busy")
            return
        }
        // The number to call from (DUAL_SIM.md): the policy's rights on that number decide, and its SIM must be in.
        val via = data.optString("number_id").ifEmpty { null }
        if (via != null && !telephony.hasNumber(via)) {
            log("dial request from ${from.take(8)} refused: that SIM is not in")
            endNow("no_sim")
            return
        }
        val owner = pairedPhone()
        val rules = owner?.let { o -> (policy() ?: SharingPolicy.none(o)).let { p -> via?.let(p::forNumber) ?: p } }
        val allowed = owner != null && from in (rules?.dialers(owner) ?: emptyList())
        val key = unb64(data.optString("public_key"))
        val number = if (allowed && key != null) {
            sealer.open(JSONObject(data.optString("sealed", "{}")), "dial", key)?.let { runCatching { JSONObject(it.decodeToString()).getString("number") }.getOrNull() }
        } else null
        if (number != null && telephony.isEmergency(number)) {
            log("dial request from ${from.take(8)} refused: emergency number")
            endNow("emergency")
            return
        }
        if (number == null || !NUMBER.matches(number)) {
            log("dial request from ${from.take(8)} refused: ${if (!allowed) "no dial right" else "not sealed by it"}")
            endNow("refused")
            return
        }
        phase = Phase.DIALING
        callId = id
        numberId = via
        peerNumber = number
        answeredBy = from
        reported = true
        // Earpiece silent before the line opens; the bridge restores it when the call ends.
        bridge.send(JSONObject().put("type", "quiet").put("call_id", id))
        if (!telephony.dial(number, via)) {
            log("system refused to dial")
            endNow("failed")
            reset()
            return
        }
        log("dialing for ${from.take(8)}")
        // Full speed from the dial request to the end, as for an incoming call from its ring (the user's 2026-11-16
        // addition: a dialed call the same way, ANSWER_DELAY.md 14.10).
        speed = runCatching { fullSpeed(id) }.onFailure { log("full speed: ${it.javaClass.simpleName}") }.getOrNull()
        timers += scheduler.after(DIAL_MS) {
            if (callId == id && phase == Phase.DIALING) {
                log("dialed call never went off-hook")
                endNow("failed")
                telephony.end()
                reset()
            }
        }
    }

    /** An offer must come from the phone that answered, which the current policy lets answer, signed by its key. */
    private fun offer(data: JSONObject) {
        val from = data.optString("from")
        val key = unb64(data.optString("public_key"))
        val gen = data.optInt("gen", -1)
        val sdp = data.optString("sdp")
        // The answerer of an incoming call, or the phone that dialed an outgoing one; either must still be allowed.
        val allowed = pairedPhone()?.let { owner -> current()?.let { it.answerers(owner) + it.dialers(owner) } } ?: emptyList()
        val signed = key != null && Protocol.deviceId(key) == from && Protocol.verify(key, Protocol.mediaMessage(callId!!, "offer", gen, sdp), unb64(data.optString("signature")))
        // While it rings, only the owner's phone, rung for this call and still allowed to answer it, and before anyone
        // else's answer is under way: connected without audio until it answers (ANSWER_DELAY.md 14).
        val ring = phase == Phase.RINGING && signed && from == pairedPhone() && mayAnswer(from) && !directPending && (answering == null || answering == from)
        val trusted = ring || (phase == Phase.ACTIVE && from in inCall() && from in allowed && signed)
        if (!trusted) {
            log("media offer refused: not signed by an allowed phone in the call")
            return
        }
        // This gateway's relays come with the offer (RELAY_SPLIT.md 18.3), fresh ones with each restart; a service
        // from before that is asked once a call.
        if (data.has("relays")) {
            ice = RelayCandidates.carried(data)
        } else if (!iceAsked) {
            iceAsked = true
            ice = RelayCandidates.fetch(client, log)
        }
        // Each phone is its own member of the bridge's call, named by its device ID.
        val message = JSONObject().put("type", "offer").put("call_id", callId).put("member", from).put("gen", gen).put("sdp", sdp).put("ice", relays(ice))
        if (ring) message.put("ring", true)
        val sent = bridge.send(message)
        if (ring) {
            if (sent) ringMember = from
            log("connecting with ${from.take(8)} while it rings gen=$gen${if (sent) "" else ": audio bridge not running"}")
            return
        }
        bridgeStarted = bridgeStarted || sent
        if (!sent) log("audio bridge not running; the call has no audio")
    }

    // Multi-party (MULTIPARTY_TRANSFER_V6.md): invites, transfers, leaving and removal. The service checks who is in
    // the call; this gateway checks the owner-signed rights on the call's number and carries them out.

    /**
     * A phone in the call asks for [target] to be rung, to join (`invite`) or to take the call over (`transfer`). Both
     * must hold the rights on this call's number, and the call holds MAX_PHONES phones at most.
     */
    private fun invite(data: JSONObject) {
        val inviter = data.optString("device_id")
        val target = data.optString("target")
        val kind = data.optString("kind")
        if (phase != Phase.ACTIVE || inviter !in inCall() || phones[target] != null) return log("invite from ${inviter.take(8)} ignored: not in a call, or ${target.take(8)} already is")
        val owner = pairedPhone() ?: return
        val rules = current() ?: return
        val may = when (kind) { "invite" -> inviter in rules.inviters(owner); "transfer" -> inviter in rules.transferers(owner); else -> false }
        val refusal = when {
            !may -> "not_allowed"
            target !in rules.answerers(owner) -> "target_not_allowed"
            phones.size >= MAX_PHONES -> "full"
            else -> null
        }
        if (refusal != null) {
            log("$kind of ${target.take(8)} by ${inviter.take(8)} refused: $refusal")
            report { member(target, "left", kind = kind, invitedBy = inviter, reason = refusal) }
            return
        }
        phones[target] = Phone(kind, inviter)
        val sealed = sealCaller(listOf(target)).optString(target)
        if (!report { member(target, "ringing", kind = kind, invitedBy = inviter, sealed = sealed) }) {
            phones.remove(target)
            return
        }
        log("$kind: ringing ${target.take(8)} for ${inviter.take(8)}")
        val call = callId
        // As for a ringing stage: the phone must show the call within PREPARE_MS, then has the wait to answer.
        phoneTimer(target, PREPARE_MS) { if (callId == call && phones[target]?.showing == false) dropGuest(target, "unavailable") }
    }

    private fun guestShowing(device: String) {
        val p = phones[device] ?: return
        if (p.joined || p.showing) return
        p.showing = true
        val call = callId
        // The transfer wait counts from the target's actual ringing (DESIGN_DECISIONS.md D-03); invites use the same.
        phoneTimer(device, (frozen?.waitS ?: DEFAULT_WAIT_S) * 1000L) { if (callId == call && phones[device]?.joined == false) dropGuest(device, "no_answer") }
    }

    private fun joinGuest(device: String) {
        val p = phones[device] ?: return log("answer from ${device.take(8)} ignored: not invited")
        val owner = pairedPhone() ?: return
        if (p.joined) return
        if (device !in (current() ?: return).answerers(owner)) return dropGuest(device, "removed")
        p.joined = true
        cancelPhoneTimers(device)
        report { member(device, "joined") }
        log("${device.take(8)} joined (${p.kind}) phones=${inCall().size}")
        if (ringingInstead) {
            // The first to answer has the call, as with answering and taking over; the others stop ringing.
            ringingInstead = false
            assistant?.answeredAfter()
            answeredBy = device
            state("active", answeredBy = device, reason = "screen:taken")
            phones.keys.filter { it != device }.forEach { dropGuest(it, "answered") }
        }
    }

    /** The bridge has audio with [device]: a transfer is complete, so the phone that handed it on leaves. */
    private fun connected(device: String) {
        val p = phones[device] ?: return
        if (p.connected) return
        p.connected = true
        if (p.kind == "transfer" && p.joined && p.invitedBy in inCall()) {
            log("transfer to ${device.take(8)} complete")
            dropPhone(p.invitedBy, "transferred", controller = device)
        }
    }

    private fun remove(data: JSONObject) {
        // The service lets only managers remove; this gateway's own owner is the one it trusts.
        if (data.optString("device_id") != pairedPhone()) return log("remove refused: not the owner")
        val target = data.optString("target")
        val p = phones[target] ?: return
        if (p.joined) dropPhone(target, "removed") else dropGuest(target, "removed")
    }

    /** A phone stops ringing for an invite: declined, not answered in time, removed. */
    private fun dropGuest(device: String, reason: String) {
        if (phones[device]?.joined != false) return
        phones.remove(device)
        cancelPhoneTimers(device)
        report { member(device, "left", reason = reason) }
        log("invite of ${device.take(8)} over: $reason")
        if (ringingInstead && phones.isEmpty()) {
            ringingInstead = false
            log("nobody answered for the assistant: ending the call")
            telephony.end()
        }
    }

    /** A phone leaves the call; with none left, the cellular call ends rather than leave the caller talking to nobody. */
    private fun dropPhone(device: String, reason: String, controller: String? = null, bridgeKnows: Boolean = false) {
        if (phones.remove(device) == null) return
        cancelPhoneTimers(device)
        if (!bridgeKnows) bridge.send(JSONObject().put("type", "leave").put("call_id", callId).put("member", device).put("reason", reason))
        report { member(device, "left", reason = reason, controller = controller) }
        log("${device.take(8)} left: $reason phones=${inCall().size}")
        assistant?.listening = inCall().isNotEmpty()
        if (inCall().isEmpty() && screening) return
        if (inCall().isEmpty()) {
            // Nobody is talking any more: invites still ringing stop too, and the cellular call ends.
            phones.keys.toList().forEach { dropGuest(it, "ended") }
            telephony.end()
        }
    }

    private fun member(device: String, state: String, kind: String = "", invitedBy: String = "", reason: String = "", sealed: String = "", controller: String? = null) {
        client.call("POST", "/v1/calls/$callId/member", JSONObject().put("device_id", device).put("state", state).put("kind", kind)
            .put("invited_by", invitedBy).put("reason", reason).put("sealed", sealed).put("controller", controller ?: ""))
    }

    private fun phoneTimer(device: String, delayMs: Long, task: () -> Unit) {
        phoneTimers.getOrPut(device) { mutableListOf() } += scheduler.after(delayMs, task)
    }

    private fun cancelPhoneTimers(device: String) {
        phoneTimers.remove(device)?.forEach { it() }
    }

    /** A message from the shell audio bridge. */
    fun onBridge(message: JSONObject) {
        val id = message.optString("call_id")
        when (message.optString("type")) {
            "answer" -> if (id == callId && (phase == Phase.ACTIVE || phase == Phase.RINGING && message.optString("member") == ringMember && ringMember != null)) {
                val gen = message.getInt("gen")
                val sdp = message.getString("sdp")
                val to = message.optString("member").takeIf { it.isNotEmpty() && it != "phone" }
                // Timed with the bridge's own steps (its "answer" line): which part of an answer is slow (ANSWER_DELAY.md 8.6).
                val started = System.nanoTime()
                val posted = report {
                    client.call("POST", "/v1/calls/$callId/media", JSONObject().put("kind", "answer").put("gen", gen).put("sdp", sdp).put("to", to ?: "")
                        .put("signature", b64(signer.sign(Protocol.mediaMessage(callId!!, "answer", gen, sdp)))))
                }
                log("media answer gen=$gen to ${to?.take(8) ?: "phone"} ${if (posted) "posted" else "not posted"} in ${(System.nanoTime() - started) / 1_000_000} ms")
            }
            "member_connected" -> if (id == callId && phase == Phase.ACTIVE) connected(message.optString("member"))
            // One phone's media is gone (it dropped and did not come back); the others carry on.
            "member_ended" -> if (id == callId && phase == Phase.RINGING && message.optString("member") == ringMember) {
                // Its connection made while ringing went (lost, or failed): the answer sets up anew, as before.
                ringMember = null
                log("connection made while ringing ended: ${message.optString("reason")}")
            } else if (id == callId && phase == Phase.ACTIVE) {
                val member = message.optString("member")
                if (member in inCall()) dropPhone(member, message.optString("reason").ifEmpty { "dropped" }, bridgeKnows = true)
            }
            // The only remote member is gone: end the cellular call rather than leave the caller talking to silence.
            "ended" -> if (id == callId && phase == Phase.ACTIVE) {
                log("media ended: ${message.optString("reason")}")
                val reason = message.optString("reason")
                val uplink = reason == "route_unknown" || reason.startsWith("route_not_telephony") || reason.startsWith("inject_")
                // The assistant alone on the call and not heard: the caller is not hung up on, the phones ring.
                if (uplink && screening && inCall().isEmpty()) ringInstead(reason)
                else if (uplink || reason in setOf("ice_lost", "offer_failed", "start_failed")) telephony.end()
            }
            "far" -> if (id == callId && screening) {
                val far = pcm(message.optString("data"))
                assistant?.onFar(far)
                runCatching { screener?.heard(id, far) }
            }
            "said" -> if (id == callId) assistant?.onSaid(message.optString("id"), message.optBoolean("cut"))
            "assisting" -> if (id == callId && screening && !message.optBoolean("on")) {
                log("assistant could not start: ${message.optString("error")}")
                assistant?.ownerHangUp()
            }
            "state" -> log("media ${message.optString("ice")} downlink=${message.optString("downlink")} uplink=${message.optString("uplink")} injected=${message.optLong("injected")} dropped=${message.optLong("dropped")} ${message.optString("silence")}")
            "disconnected" -> if (bridgeStarted && phase == Phase.ACTIVE) {
                log("audio bridge went away during the call")
                telephony.end()
            }
        }
    }

    // Call screening (SCREENING_ASSISTANT.md 10.3): the assistant answers, phones listen in or take over.

    private var assistant: dev.cloudsim.gateway.screening.BuiltinAssistant? = null
    /** This call's screening switches, when the assistant may take it. */
    private var screen: dev.cloudsim.gateway.screening.ScreenSettings? = null
    private var screenTimer: (() -> Unit)? = null
    /** A direct screening waiting its few rings before the assistant answers; a phone that listened in meanwhile. */
    private var directPending = false
    private var pendingListen: String? = null
    /** This call's place in a run of calls from the same number that went to the assistant (0: not a repeat). */
    private var callerRepeat = 0
    /** The transcriber was asked to get ready for this call. */
    private var transcribing = false
    /** The assistant has the call and nobody has taken it over. */
    private val screening get() = phase == Phase.ACTIVE && answeredBy == ASSISTANT && assistant?.result == null

    /** The phones are ringing because the assistant could not be heard; see [ringInstead]. */
    private var ringingInstead = false

    /**
     * The assistant answered and its uplink failed: the caller hears nothing. Hanging up, as for a phone's call, looks
     * like being hung up on, and the owner only finds a line in Recents (2026-11-11, twice with real callers). The
     * assistant stops and every phone that may answer this number rings, as for an invite; the first to answer has the
     * call, with a new media session and so a new try at the uplink. Nobody answering in the usual wait ends the call.
     * (The user's 2026-11-11 decision; calls a phone answered itself are still ended.)
     */
    private fun ringInstead(reason: String) {
        val a = assistant
        val owner = pairedPhone()
        val targets = if (a == null || owner == null) emptyList() else (current()?.answerers(owner) ?: emptyList()).filter { phones[it] == null }.take(MAX_PHONES)
        a?.unheard()
        callId?.let { id -> runCatching { screener?.stopTranscribing(id, over = false) } }
        bridgeStarted = false
        val rung = targets.filter { target ->
            phones[target] = Phone("assistant")
            val sealed = sealCaller(listOf(target)).optString(target)
            report { member(target, "ringing", kind = "assistant", sealed = sealed) }.also { if (!it) phones.remove(target) }
        }
        if (rung.isEmpty()) {
            log("assistant not heard ($reason) and no phone to ring: ending the call")
            telephony.end()
            return
        }
        ringingInstead = true
        // The bridge gave the earpiece and the microphone back when its session ended: silent again while it rings.
        bridge.send(JSONObject().put("type", "quiet").put("call_id", callId))
        state("active", answeredBy = ASSISTANT, reason = "screen:unheard")
        log("assistant not heard ($reason): ringing ${rung.size} phone(s) instead")
        val call = callId
        for (target in rung) phoneTimer(target, PREPARE_MS) { if (callId == call && phones[target]?.showing == false) dropGuest(target, "unavailable") }
    }

    private fun answerWithAssistant() {
        answering = ASSISTANT
        bridge.send(JSONObject().put("type", "quiet").put("call_id", callId))
        if (!telephony.answer()) {
            log("system refused to answer for the assistant")
            answering = null
        }
    }

    private fun startAssistant() {
        peerNumber = callerNumber
        state("active", answeredBy = ASSISTANT, reason = "screen:greeting")
        startWatch(outgoing = false, assisted = true)
        val id = callId!!
        val id0 = id
        // Ringing first, the models are loading already; answered after ringing (先响后代接), they load now.
        if (!transcribing) runCatching { screener?.prepare(id) }
        transcribing = true
        val host = object : dev.cloudsim.gateway.screening.ScreenHost {
            override fun say(id: String, pcm: ShortArray) {
                sayInto(id, pcm)
                runCatching { screener?.said(id0, numberId, id) }
            }
            override fun hush() { bridge.send(JSONObject().put("type", "hush").put("call_id", callId)) }
            override fun hangUp() { if (!telephony.end()) log("system refused to end the screened call") }
            override fun stage(stage: String) { if (callId == id && answeredBy == ASSISTANT) state("active", answeredBy = ASSISTANT, reason = "screen:$stage") }
        }
        val a = dev.cloudsim.gateway.screening.BuiltinAssistant(host, screener!!.clips(numberId), scheduler, now, log)
        assistant = a
        if (!bridge.send(JSONObject().put("type", "assist").put("call_id", id).put("on", true))) {
            log("audio bridge not reachable for the assistant")
            a.ownerHangUp()
            return
        }
        bridgeStarted = true
        if (screen?.record != false) runCatching { screener?.recordMessage(id) }
        log("assistant answered ${id.take(8)}")
        a.start()
        pendingListen?.let { screenJoin(it, listen = true) }
        pendingListen = null
    }

    /** Hands a clip to the bridge in 100 ms pieces; the last names it, so "said" comes back when it has been played. */
    private fun sayInto(id: String, pcm: ShortArray) {
        val call = callId ?: return
        var i = 0
        do {
            val n = minOf(SAY_CHUNK, pcm.size - i)
            val piece = dev.cloudsim.gateway.screening.ScreenAssetStore.bytes(pcm.copyOfRange(i, i + n))
            val m = JSONObject().put("type", "say").put("call_id", call).put("data", b64(piece))
            i += n
            if (i >= pcm.size) m.put("end", id)
            bridge.send(m)
        } while (i < pcm.size)
    }

    private fun pcm(data: String): ShortArray = unb64(data)?.let(dev.cloudsim.gateway.screening.ScreenAssetStore::pcm) ?: ShortArray(0)

    /**
     * A phone that may answer this call joins the screened call: to listen in (heard by nobody until it takes over), or
     * to take it over at once. The first to take over has it, as with answering.
     */
    private fun screenJoin(device: String, listen: Boolean) {
        val owner = pairedPhone() ?: return
        if (device !in (current() ?: return).answerers(owner)) return log("screening join from ${device.take(8)} refused: not allowed")
        if (phones[device] == null) {
            if (phones.size >= MAX_PHONES) return log("screening join from ${device.take(8)} refused: full")
            if (listen) bridge.send(JSONObject().put("type", "listen").put("call_id", callId).put("member", device).put("on", true))
            phones[device] = Phone(if (listen) "listen" else "answer", joined = true)
            report { member(device, "joined", kind = if (listen) "listen" else "answer") }
            assistant?.listening = true
            log("${device.take(8)} ${if (listen) "listens in" else "joins"} the screened call")
        }
        if (!listen) takeOver(device)
    }

    private fun takeOver(device: String) {
        val a = assistant ?: return
        if (a.result != null) return
        a.takenOver()
        // Only what was said to the assistant is written down; the owner's own talk is not (SCREENING_ASSISTANT.md 13).
        callId?.let { id -> runCatching { screener?.stopTranscribing(id, over = false) } }
        bridge.send(JSONObject().put("type", "listen").put("call_id", callId).put("member", device).put("on", false))
        bridge.send(JSONObject().put("type", "assist").put("call_id", callId).put("on", false))
        answeredBy = device
        phones[device] = phones[device] ?: Phone("answer", joined = true)
        state("active", answeredBy = device, reason = "screen:taken")
        log("${device.take(8)} took the screened call over")
    }

    /**
     * The owner's or a family member's action on a screened call (SCREENING_ASSISTANT.md 10.2): sealed by that phone for
     * this gateway, like keys, from a phone that may answer this call. take over, play a reply, ask the caller to hold.
     */
    private fun screenAction(data: JSONObject) {
        val from = data.optString("device_id")
        val a = assistant
        if (!screening || a == null) return log("screening action from ${from.take(8)} ignored: not screening")
        val owner = pairedPhone() ?: return
        if (from !in (current() ?: return).answerers(owner)) return log("screening action from ${from.take(8)} refused: not allowed")
        val key = unb64(data.optString("public_key"))?.takeIf { Protocol.deviceId(it) == from } ?: return log("screening action from ${from.take(8)} refused: wrong key")
        val plain = sealer.open(JSONObject(data.optString("sealed", "{}")), "screen", key)
            ?.let { runCatching { JSONObject(it.decodeToString()) }.getOrNull() } ?: return log("screening action from ${from.take(8)} refused: not sealed by it")
        if (plain.optString("call_id") != callId) return
        when (plain.optString("action")) {
            "takeover" -> if (phones[from]?.joined == true) takeOver(from) else screenJoin(from, listen = false)
            "reply" -> a.ownerReply(plain.optInt("index", -1))
            "hold" -> a.ownerHold()
            "watch" -> a.ownerWatching()
            else -> log("screening action from ${from.take(8)} unknown")
        }
    }

    private fun state(state: String, answeredBy: String = "", reason: String = "") {
        if (!reported) return
        report {
            client.call("POST", "/v1/calls/$callId/state", JSONObject().put("state", state).put("answered_by", answeredBy).put("reason", reason))
        }
    }

    private fun report(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: ServiceException) {
        log("report failed: ${e.code} ${e.message}")
        false
    }

    companion object {
        const val IDLE = 0
        const val RINGING = 1
        const val OFFHOOK = 2
        const val LOCAL = "gateway"
        /** The screening assistant answered: no phone is in the call (SCREENING_ASSISTANT.md 10.2). */
        const val ASSISTANT = "assistant"
        /** 100 ms at 16 kHz: one piece of a clip handed to the bridge. */
        /** How long a directly screened call rings before the assistant answers (the user's 2026-11-10 request). */
        const val DIRECT_WAIT_MS = 5_000L
        const val SAY_CHUNK = 1600
        /** How long a stage's phones have to show the call before the stage counts as unavailable. */
        const val PREPARE_MS = 10_000L
        /** How long a dialed call may take to go off-hook before it counts as failed. */
        const val DIAL_MS = 15_000L
        /** A dial request older than this when it reaches the gateway is ignored. */
        const val DIAL_FRESH_MS = 20_000L
        private val NUMBER = Regex("^\\+?[0-9*#]{2,32}$")
        /** One key message: up to 32 keys, held and spaced as a phone's keypad would. */
        private val DTMF = Regex("^[0-9*#]{1,32}$")
        const val DTMF_GAP_MS = 250L
        /** Phones in one call at most: with the cellular party, four ends (MULTIPARTY_TRANSFER_V6.md). */
        const val MAX_PHONES = 3
        /** How long an invited phone rings when the policy says nothing (CURRENT_BASELINE.md: 15 s). */
        const val DEFAULT_WAIT_S = 15
    }
}
