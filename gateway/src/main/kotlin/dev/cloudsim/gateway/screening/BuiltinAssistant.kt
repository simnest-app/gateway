package dev.cloudsim.gateway.screening

import dev.cloudsim.gateway.calls.Scheduler
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.log10
import kotlin.math.sqrt

/** What the assistant needs from the call it is in; the gateway carries each out. */
interface ScreenHost {
    /** Plays [pcm] (16 kHz mono) into the call; [said] comes back with [id] once it has gone out. */
    fun say(id: String, pcm: ShortArray)
    /** Stops whatever is still being said. */
    fun hush()
    /** Ends the cellular call. */
    fun hangUp()
    /** Where the screening is now, for the phones: greeting, listening, holding, replying, closing. */
    fun stage(stage: String)
}

/** The clips one number's screening plays, 16 kHz mono. */
interface ScreenClips {
    fun greeting(): ShortArray?
    fun hold(): ShortArray?
    fun closing(): ShortArray?
    fun reply(index: Int): ShortArray?
}

/**
 * The gateway's built-in assistant for one screened call (SCREENING_ASSISTANT.md 2.3), with no model: plays the
 * greeting, listens until the caller has finished (by level: 2 s of quiet after speech, 8 s with no speech at all,
 * 30 s at most), puts the caller on hold while someone listens in, says goodbye and hangs up otherwise. The owner can
 * play a reply, ask the caller to hold, take the call over or hang up at any time. Everything runs on the relay's
 * thread: the gateway calls in, timers come back through [scheduler].
 */
class BuiltinAssistant(
    private val host: ScreenHost,
    private val clips: ScreenClips,
    private val scheduler: Scheduler,
    private val now: () -> Long,
    private val log: (String) -> Unit = {},
) {
    var stage = "idle"
        private set
    /** How it ended: taken (someone took over), unheard (the uplink failed and nobody answered the ring), owner_hangup, message (said goodbye), caller_left, caller_left_early, failed. */
    var result: String? = null
        private set
    private var started = 0L
    private var speaking: String? = null
    private var quietUntil = 0L
    private val timers = mutableListOf<() -> Unit>()
    private var listenTimers = mutableListOf<() -> Unit>()
    /** Someone is listening in: after the caller has spoken they are asked to hold rather than told goodbye. */
    var listening = false
    /**
     * Someone has the screened call's page open on a phone (the user's 2026-11-10 test: alone with two phones, the
     * call was over before they could act). Counts as listening: the caller is asked to hold, not told goodbye.
     */
    var watching = false
    private val attended get() = listening || watching
    private var callerSpoke = false
    private var replies = 0
    private val events = JSONArray()

    // Level-based end of speech, per 20 ms frame.
    private var floorDb = -60.0
    private var run = 0
    private var inSpeech = false
    private var lastSpeech = 0L
    private var speechMs = 0L
    private var utterances = 0

    fun start() {
        started = now()
        timers += scheduler.after(MAX_MS) { if (result == null) atLimit() }
        val greeting = clips.greeting()
        if (greeting == null) return fail("no_greeting")
        stage("greeting")
        // A little silence first, so the first word is never clipped (S1-0 measured none; this is margin).
        play("greeting", ShortArray(LEAD_SAMPLES) + greeting)
    }

    /** 100 ms of the caller, 16 kHz mono. */
    fun onFar(pcm: ShortArray) {
        if (result != null || stage != "listening" && stage != "holding" && stage != "replying") return
        val t = now()
        if (speaking != null || t < quietUntil) return
        var i = 0
        while (i + FRAME <= pcm.size) {
            frame(pcm, i, t)
            i += FRAME
        }
    }

    private fun frame(pcm: ShortArray, off: Int, t: Long) {
        var sum = 0.0
        for (k in off until off + FRAME) sum += pcm[k].toDouble() * pcm[k]
        val rms = sqrt(sum / FRAME)
        val db = if (rms < 1) -96.0 else 20 * log10(rms / 32768)
        val loud = db > floorDb + 12 && db > -50
        // The floor follows quiet at once and creeps up with steady noise, never with speech.
        floorDb = if (db < floorDb) db else if (!loud) floorDb + 0.05 * (db - floorDb) else floorDb
        run = if (loud) run + 1 else 0
        if (run >= 3) {
            if (!inSpeech) { inSpeech = true; callerSpoke = true; utterances++; event("speech") }
            lastSpeech = t
            speechMs += FRAME_MS
        }
        if (inSpeech && !loud && t - lastSpeech >= END_QUIET_MS && stage == "listening") {
            inSpeech = false
            event("speech_end")
            heard()
        }
    }

    fun onSaid(id: String, cut: Boolean) {
        if (id != speaking) return
        speaking = null
        quietUntil = now() + ECHO_GUARD_MS
        if (result != null || cut) return
        when (id) {
            "greeting" -> listen(NO_SPEECH_MS)
            "closing" -> finish("message", hangUp = true)
            // Still attended: ask again every HOLD_MS until someone acts or the time is up; otherwise goodbye.
            "hold" -> timers += scheduler.after(HOLD_MS) {
                if (stage == "holding" && result == null) {
                    if (attended && now() - started < limitMs) clips.hold()?.let { play("hold", it) } ?: closing() else closing()
                }
            }
            else -> if (id.startsWith("reply")) listen(AFTER_REPLY_MS)
        }
    }

    // The owner's actions, from a phone allowed to answer this call.

    fun ownerReply(index: Int) {
        if (result != null) return
        val clip = clips.reply(index) ?: return log("screening: no reply $index")
        replies++
        event("reply", index)
        stop()
        cancelListen()
        stage("replying")
        play("reply$index", clip)
    }

    /** A phone opened this call's page: hold the caller for whoever is looking. */
    fun ownerWatching() {
        if (result != null) return
        watching = true
        event("watching")
    }

    fun ownerHold() {
        if (result != null || stage == "holding") return
        stop()
        cancelListen()
        hold()
    }

    /** Someone took the call over: the assistant stops at once and leaves the call to them. */
    fun takenOver() {
        if (result != null) return
        stop()
        finish("taken", hangUp = false)
    }

    /**
     * What it says does not reach the caller (the uplink failed): it stops without hanging up, and the phones are rung
     * instead. [answeredAfter] when one of them answers.
     */
    fun unheard() {
        if (result != null) return
        stop()
        finish("unheard", hangUp = false)
    }

    fun answeredAfter() {
        if (result != "unheard") return
        result = "taken"
        event("end", "taken")
    }

    fun ownerHangUp() {
        if (result != null) return
        stop()
        finish("owner_hangup", hangUp = true)
    }

    /** The call ended on its own (the caller hung up, or the line dropped). */
    fun ended() {
        if (result != null) return
        finish(if (callerSpoke || stage != "greeting") "caller_left" else "caller_left_early", hangUp = false)
    }

    /** A record of the screening for the call log: how it ended, how long, what was said and done. */
    fun summary(): JSONObject = JSONObject().put("result", result ?: "unfinished").put("ms", now() - started)
        .put("caller_spoke", callerSpoke).put("speech_ms", speechMs).put("replies", replies).put("events", events)

    private fun listen(noSpeechMs: Long) {
        stage("listening")
        cancelListen()
        floorDb = -60.0
        run = 0
        inSpeech = false
        val before = utterances
        listenTimers += scheduler.after(noSpeechMs) { if (stage == "listening" && !inSpeech && utterances == before && result == null) { event("no_speech"); heard() } }
        listenTimers += scheduler.after(MAX_LISTEN_MS) { if (stage == "listening" && result == null) { event("listen_cap"); heard() } }
    }

    /** The caller has finished (or did not speak): hold for whoever listens in, otherwise goodbye. */
    private fun heard() {
        cancelListen()
        if (attended) hold() else closing()
    }

    private fun hold() {
        val clip = clips.hold() ?: return closing()
        stage("holding")
        play("hold", clip)
    }

    private fun closing() {
        cancelListen()
        val clip = clips.closing() ?: return finish("message", hangUp = true)
        stage("closing")
        play("closing", clip)
    }

    /**
     * How long the assistant keeps the call: 90 s, or 3 minutes once someone looks at it or listens in (the user's
     * 2026-11-10 request: the call was ended under the owner watching it). The screening page counts down to it.
     */
    private val limitMs get() = if (attended) MAX_ATTENDED_MS else MAX_MS

    /** The 90 s mark: over, unless someone is looking at the call, then at the longer limit. */
    private fun atLimit() {
        val left = limitMs - (now() - started)
        if (left > 0) timers += scheduler.after(left) { if (result == null) atLimit() } else timeUp()
    }

    private fun timeUp() {
        event("time_up")
        if (stage == "closing") return finish("message", hangUp = true)
        stop()
        closing()
    }

    private fun play(id: String, pcm: ShortArray) {
        speaking = id
        host.say(id, pcm)
    }

    private fun stop() {
        if (speaking != null) { host.hush(); speaking = null }
    }

    private fun stage(s: String) {
        if (stage == s) return
        stage = s
        event("stage", s)
        host.stage(s)
    }

    private fun cancelListen() {
        listenTimers.forEach { it() }
        listenTimers.clear()
    }

    private fun fail(why: String) {
        log("screening failed: $why")
        finish("failed", hangUp = true)
    }

    private fun finish(r: String, hangUp: Boolean) {
        result = r
        event("end", r)
        cancelListen()
        timers.forEach { it() }
        timers.clear()
        if (hangUp) host.hangUp()
    }

    private fun event(what: String, detail: Any? = null) {
        events.put(JSONObject().put("t", now() - started).put("e", what).apply { if (detail != null) put("d", detail) })
    }

    companion object {
        const val FRAME = 320
        const val FRAME_MS = 20L
        const val LEAD_SAMPLES = 16000 * 3 / 10
        const val END_QUIET_MS = 2_000L
        const val NO_SPEECH_MS = 8_000L
        const val AFTER_REPLY_MS = 6_000L
        const val MAX_LISTEN_MS = 30_000L
        const val HOLD_MS = 20_000L
        const val MAX_MS = 90_000L
        const val MAX_ATTENDED_MS = 180_000L
        const val ECHO_GUARD_MS = 300L
    }
}
