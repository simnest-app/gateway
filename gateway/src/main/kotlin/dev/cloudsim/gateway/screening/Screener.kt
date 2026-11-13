package dev.cloudsim.gateway.screening

import org.json.JSONObject

/**
 * What the call relay needs for screening (SCREENING_ASSISTANT.md 10.3), so the relay stays free of Android and of
 * where the clips and lists live. [decide] runs when a call starts ringing; the rest while the assistant has it.
 */
interface Screener {
    fun decide(settings: ScreenSettings?, numberId: String?, caller: String?, emergencyCaller: Boolean, remote: Boolean): Decision
    fun clips(numberId: String?): ScreenClips
    /** This phone dialed an emergency number: nothing is screened for the next 30 minutes. */
    fun emergency()
    /** Keep what the caller says, as a recording of this call (V7 storage). */
    fun recordMessage(callId: String)
    /** The screening is over: its summary goes with the call's record. Never with the caller's number in the log. */
    fun finished(callId: String, numberId: String?, summary: JSONObject)

    // What was said, written down (SCREENING_ASSISTANT.md 13).
    /** The assistant may answer [callId] in a few seconds: get ready to write down what the caller says. */
    fun prepare(callId: String) {}
    /** The caller's audio while the assistant has the call, 16 kHz mono. */
    fun heard(callId: String, pcm: ShortArray) {}
    /** The assistant played clip [clip] of [numberId]'s set. */
    fun said(callId: String, numberId: String?, clip: String) {}
    /** The assistant no longer has the call ([over]: the call has ended too). */
    fun stopTranscribing(callId: String, over: Boolean) {}
}
