package dev.cloudsim.gateway.screening

import dev.cloudsim.gateway.calls.ManualScheduler
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.sin

class ScreeningRulesTest {
    private var t = ZonedDateTime.of(2026, 11, 9, 23, 0, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
    private val rules = ScreeningRules(now = { t }, zone = { ZoneId.of("Asia/Shanghai") })
    private val direct = ScreenSettings(ScreenSettings.DIRECT, assets = "a")

    private fun decide(s: ScreenSettings? = direct, caller: String? = "13800138000", known: Known = Known.UNKNOWN, emergency: Boolean = false,
                       remote: Boolean = true, bridge: Boolean = true, assets: Boolean = true) =
        rules.decide(RingContext(s, caller, known, emergency, remote, bridge, assets))

    @Test
    fun screensUnknownCallersOnly() {
        assertEquals("direct", decide().mode)
        assertEquals("known", decide(caller = "13900000000", known = Known.KNOWN).why)
        assertEquals("allowed", decide(direct.copy(who = ScreenSettings.ALL), caller = "13700000000", known = Known.ALLOWED).why)
        assertTrue(decide(direct.copy(who = ScreenSettings.ALL), caller = "13600000000", known = Known.KNOWN).screen)
    }

    @Test
    fun hiddenCallersFollowTheirSwitch() {
        assertTrue(decide(caller = null).screen)
        assertEquals("hidden_not_screened", decide(direct.copy(hidden = false), caller = "").why)
    }

    @Test
    fun neverScreened() {
        assertEquals("off", decide(s = null).why)
        assertEquals("emergency_caller", decide(caller = "110", emergency = true).why)
        assertEquals("permissions_unconfirmed", decide(caller = "1", remote = false).why)
        assertEquals("no_assets", decide(caller = "2", assets = false).why)
        assertEquals("no_bridge", decide(caller = "3", bridge = false).why)
        rules.lastEmergency = t - 29 * 60_000
        assertEquals("after_emergency", decide(caller = "4").why)
        rules.lastEmergency = t - 31 * 60_000
        assertTrue(decide(caller = "5").screen)
    }

    @Test
    fun aSecondCallWithinThreeMinutesRings() {
        assertTrue(decide(caller = "13811112222").screen)
        t += 2 * 60_000
        assertEquals("repeat", decide(caller = "13811112222").why)
        t += 4 * 60_000
        assertTrue(decide(caller = "13811112222").screen)
    }

    /** "Rings through" off: every call goes to the assistant, counted, so the owner's notice can say it. */
    @Test
    fun repeatedCallsCanStillGoToTheAssistant() {
        val s = direct.copy(repeats = true)
        assertEquals(0, decide(s, caller = "13822223333").repeatCount)
        t += 60_000
        val second = decide(s, caller = "13822223333")
        assertTrue(second.screen)
        assertEquals("repeat_screened", second.why)
        assertEquals(2, second.repeatCount)
        t += 2 * 60_000
        assertEquals(3, decide(s, caller = "13822223333").repeatCount)
        t += 10 * 60_000
        assertEquals(0, decide(s, caller = "13822223333").repeatCount)
        assertTrue(ScreenSettings.parse(org.json.JSONObject().put("mode", "direct").put("repeats", true))!!.repeats)
    }

    @Test
    fun hoursWrapPastMidnight() {
        val night = direct.copy(hours = 22 * 60 to 8 * 60)
        assertTrue(decide(night, caller = "1").screen)
        t += 10 * 3600_000L // 09:00 the next day
        assertEquals("outside_hours", decide(night, caller = "2").why)
    }

    @Test
    fun settingsParseStrictly() {
        assertEquals(ScreenSettings(ScreenSettings.AFTER_RING, 25, ScreenSettings.ALL, false, 1320 to 480, false, true, "d"),
            ScreenSettings.parse(JSONObject("""{"mode":"after_ring","ring_s":25,"who":"all","hidden":false,"hours":[1320,480],"record":false,"known_contacts":true,"assets":"d"}""")))
        assertNull(ScreenSettings.parse(JSONObject("""{"mode":"always"}""")))
        assertNull(ScreenSettings.parse(JSONObject("""{"mode":"direct","ring_s":2}""")))
    }

    @Test
    fun numbersInInternationalForm() {
        assertEquals("+8613800138000", e164("138 0013 8000"))
        assertEquals("+862712345678", e164("027-12345678"))
        assertEquals("+14155550100", e164("+1 415 555 0100"))
        assertEquals("+447700900000", e164("00447700900000"))
        assertEquals("95588", e164("95588"))
    }
}

class BuiltinAssistantTest {
    private val clock = ManualScheduler()
    private val said = mutableListOf<String>()
    private val stages = mutableListOf<String>()
    private var hushes = 0
    private var hangUps = 0
    private val host = object : ScreenHost {
        override fun say(id: String, pcm: ShortArray) { said += id }
        override fun hush() { hushes++ }
        override fun hangUp() { hangUps++ }
        override fun stage(stage: String) { stages += stage }
    }
    private val clips = object : ScreenClips {
        override fun greeting() = ShortArray(16000)
        override fun hold() = ShortArray(8000)
        override fun closing() = ShortArray(8000)
        override fun reply(index: Int) = if (index == 1) ShortArray(4000) else null
    }
    private val a = BuiltinAssistant(host, clips, clock, { clock.nowMs })

    /** [ms] of the caller: a 300 Hz tone when [loud], faint noise otherwise, fed in 100 ms blocks as the bridge does. */
    private fun caller(ms: Int, loud: Boolean) {
        repeat(ms / 100) {
            val block = ShortArray(1600) { i -> if (loud) (8000 * sin(2 * Math.PI * 300 * i / 16000)).toInt().toShort() else ((i * 7919) % 41 - 20).toShort() }
            a.onFar(block)
            clock.advance(100)
        }
    }

    private fun greeted() {
        a.start()
        a.onSaid("greeting", false)
        clock.advance(400)
    }

    @Test
    fun greetsListensAndSaysGoodbye() {
        greeted()
        assertEquals("listening", a.stage)
        caller(1_000, loud = false)
        caller(3_000, loud = true)
        caller(1_500, loud = false)
        assertEquals("listening", a.stage)
        caller(800, loud = false)
        assertEquals(listOf("greeting", "closing"), said)
        a.onSaid("closing", false)
        assertEquals(1, hangUps)
        assertEquals("message", a.result)
        assertTrue(a.summary().getBoolean("caller_spoke"))
    }

    @Test
    fun aListenerGetsTheCallerOnHold() {
        greeted()
        a.listening = true
        caller(2_000, loud = true)
        caller(2_500, loud = false)
        assertEquals("holding", a.stage)
        assertEquals("hold", said.last())
        a.onSaid("hold", false)
        clock.advance(BuiltinAssistant.HOLD_MS)
        // Still listened to: asked to hold again; once the listener has gone, goodbye.
        assertEquals(listOf("greeting", "hold", "hold"), said)
        a.listening = false
        a.onSaid("hold", false)
        clock.advance(BuiltinAssistant.HOLD_MS)
        assertEquals("closing", said.last())
    }

    /** The page open on the owner's phone: no goodbye while they look, the caller is asked to hold again and again. */
    @Test
    fun someoneLookingKeepsTheCallerOnHold() {
        greeted()
        a.ownerWatching()
        clock.advance(BuiltinAssistant.NO_SPEECH_MS)
        assertEquals("holding", a.stage)
        a.onSaid("hold", false)
        clock.advance(BuiltinAssistant.HOLD_MS)
        assertEquals(listOf("greeting", "hold", "hold"), said)
        a.onSaid("hold", false)
        // Watched, it keeps the call past 90 s, up to 3 minutes (the user's 2026-11-10 request).
        clock.advance(BuiltinAssistant.MAX_MS)
        assertEquals("hold", said.last())
        clock.advance(BuiltinAssistant.MAX_ATTENDED_MS - BuiltinAssistant.MAX_MS)
        assertEquals("closing", said.last())
    }

    @Test
    fun takingOverStopsItAtOnce() {
        greeted()
        caller(1_000, loud = true)
        a.ownerReply(1)
        assertEquals("reply1", said.last())
        a.takenOver()
        assertEquals(1, hushes)
        assertEquals("taken", a.result)
        assertEquals(0, hangUps)
        // Nothing more after it is over.
        clock.advance(BuiltinAssistant.MAX_MS)
        assertEquals(0, hangUps)
    }

    @Test
    fun aCallerWhoLeavesDuringTheGreetingLeftEarly() {
        a.start()
        clock.advance(2_000)
        a.ended()
        assertEquals("caller_left_early", a.result)
    }

    @Test
    fun theWholeScreeningIsCapped() {
        greeted()
        a.listening = true
        repeat(10) { caller(2_000, loud = true); caller(500, loud = false) }
        a.onSaid("hold", false)
        // Listened to, capped at 3 minutes rather than 90 s.
        clock.advance(BuiltinAssistant.MAX_ATTENDED_MS)
        assertEquals("closing", said.last())
        assertFalse(a.result == null && hangUps > 0)
    }
}

class ScreenAssetStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The same vectors as the iPhone's Tests/ScreeningChecks.swift: both sides must compute the same digests. */
    @Test
    fun digestsMatchTheIphone() {
        assertEquals("9470914d2821a02af4f9ac7fc118dcef39c152a4e98fbc3c484ab4bdc00ce889",
            ScreenAssetStore.digest(mapOf("reply0" to byteArrayOf(4), "greeting" to byteArrayOf(1, 2, 3)), listOf(ByteArray(16) { 9 }), emptyList()))
        assertEquals("R6H1TF/tgbU7ktDguNZHzg==", ScreenAssetStore.numberDigest(ByteArray(32) { 3 }, "+8613800138000"))
        assertEquals("d1c258537415a058095349dd5fabb05b1bea3b2be0d4322136608d903e505d4a",
            ScreenAssetStore.textDigest("abc", mapOf("hold" to "请稍候", "greeting" to "您好"), listOf(ByteArray(16) { 9 }), emptyList()))
    }

    @Test
    fun usesOnlyTheSetThePolicyNames() {
        val store = ScreenAssetStore(tmp.root)
        val greeting = ShortArray(100) { it.toShort() }
        val digest = store.install("sim1", mapOf("greeting" to byteArrayOf(1, 2, 3)), mapOf("greeting" to greeting), listOf(ByteArray(16) { 7 }), emptyList())
        assertTrue(store.ready("sim1", digest))
        assertFalse(store.ready("sim1", "something else"))
        assertEquals(greeting.toList(), ScreenAssetStore(tmp.root).clips("sim1").greeting()!!.toList())
        val allowed = java.util.Base64.getEncoder().encodeToString(ByteArray(16) { 7 })
        assertEquals(Known.ALLOWED, store.known("sim1", allowed, contacts = false))
        assertNull(store.known("sim1", "other", contacts = true))
    }
}

/** Calls only the assistant spoke on are not someone the owner deals with (2026-11-12 14:40, 14:41). */
class ScreenHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun callsOnlyTheAssistantSpokeOnAreMatchedByWhenTheyRang() {
        val h = ScreenHistory(tmp.newFile("history.jsonl"))
        // Rang at 1_000_000, answered 5 s later, a message left after 18 s.
        h.add(JSONObject().put("call_id", "a").put("result", "message").put("ms", 18_000).put("at", 1_023_000))
        // Taken over by the owner: they spoke, so not among them.
        h.add(JSONObject().put("call_id", "b").put("result", "taken").put("ms", 10_000).put("at", 2_015_000))
        val only = h.assistantOnly()
        assertEquals(1, only.size)
        assertTrue(1_000_000L in only[0])
        assertFalse(2_000_000L in only[0])
        assertFalse(1_006_000L in only[0])
    }
}
