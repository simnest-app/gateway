package dev.cloudsim.gateway.selftest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class SelfTestTest {
    private val saved = Locale.getDefault()

    @Before fun chinese() = Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
    @After fun restore() = Locale.setDefault(saved)

    @Test
    fun theNetworkIsJudgedByWhatItCanReach() {
        assertEquals(Level.FAIL, Judge.network(NetFacts("none", false, false, null)).level)
        assertEquals(Level.FAIL, Judge.network(NetFacts("wifi", false, false, 20)).level)
        assertEquals(Level.FAIL, Judge.network(NetFacts("wifi", true, false, null)).level)
        assertEquals(Level.OK, Judge.network(NetFacts("wifi", true, false, 20)).level)
        // Named by address: nothing to look up, and nothing said of it.
        assertEquals("Wi-Fi", Judge.network(NetFacts("wifi", true, false, -1)).detail)
        assertEquals(Level.WARN, Judge.network(NetFacts("wifi", true, true, 20)).level)
        assertEquals(Level.WARN, Judge.network(NetFacts("cellular", true, false, 20)).level)
    }

    @Test
    fun theServiceAndItsStandingConnection() {
        val down = ServiceFacts(null, "timeout", true, 200_000, 3)
        assertEquals(Level.FAIL, Judge.service(down).level)
        assertEquals(Level.FAIL, Judge.link(down).level)
        val fine = ServiceFacts(80, null, true, 10_000, 0)
        assertEquals(Level.OK, Judge.service(fine).level)
        assertEquals(Level.OK, Judge.link(fine).level)
        assertEquals(Level.WARN, Judge.service(fine.copy(rttMs = 2000)).level)
        assertEquals(Level.WARN, Judge.link(fine.copy(drops = 30)).level)
        // A clock a few seconds off is ordinary; one far off is said, either way round.
        assertEquals(Level.OK, Judge.service(fine.copy(skewMs = -2_000)).level)
        assertEquals(Level.WARN, Judge.service(fine.copy(skewMs = 12_000)).level)
        assertTrue(Judge.service(fine.copy(skewMs = -12_000)).detail.contains("慢了 12 秒"))
        // Never heard from the service although switched on: down.
        assertEquals(Level.FAIL, Judge.link(fine.copy(heardAgoMs = null)).level)
        assertEquals(Level.SKIP, Judge.link(fine.copy(running = false)).level)
    }

    @Test
    fun aRelayThatOnlyWorksOverTcpIsAWarningNotAFailure() {
        val good = RelayFacts("a", 30, null, 40)
        val udpDead = RelayFacts("b", null, null, 40)
        val dead = RelayFacts("c", null, null, null)
        val refused = RelayFacts("d", 30, "wrong_secret", -1)
        assertEquals(Level.OK, Judge.relays(listOf(good), null).level)
        assertEquals(Level.WARN, Judge.relays(listOf(good, dead), null).level)
        assertEquals(Level.WARN, Judge.relays(listOf(udpDead), null).level)
        assertEquals(Level.FAIL, Judge.relays(listOf(dead), null).level)
        // Answering a round trip is not enough: it must hand out an address.
        assertEquals(Level.FAIL, Judge.relays(listOf(refused), null).level)
        assertEquals(Level.FAIL, Judge.relays(emptyList(), "NETWORK").level)
        assertTrue(Judge.relays(listOf(good, udpDead), null).detail.lines().size == 3)
    }

    @Test
    fun anEmptySlotIsNotAProblemButNoServiceIs() {
        assertEquals(Level.OK, Judge.lines(listOf(LineFacts(1, "in_service", 3), LineFacts(2, "no_sim", null))).level)
        assertEquals(Level.WARN, Judge.lines(listOf(LineFacts(1, "in_service", 3), LineFacts(2, "emergency_only", null))).level)
        assertEquals(Level.WARN, Judge.lines(listOf(LineFacts(1, "in_service", 0))).level)
        assertEquals(Level.OK, Judge.lines(listOf(LineFacts(1, "in_service", 1))).level)
        assertEquals(Level.FAIL, Judge.lines(listOf(LineFacts(1, "no_service", null), LineFacts(2, "no_sim", null))).level)
        assertEquals(Level.FAIL, Judge.lines(emptyList()).level)
        assertEquals(Level.SKIP, Judge.lines(null).level)
    }

    @Test
    fun theRestOfTheItems() {
        assertEquals(Level.OK, Judge.direct(DirectFacts(true, "off", "")).level)
        assertEquals(Level.OK, Judge.direct(DirectFacts(false, "mapped", "none")).level)
        assertEquals(Level.OK, Judge.direct(DirectFacts(false, "off", "standby")).level)
        assertEquals(Level.WARN, Judge.direct(DirectFacts(false, "unsupported", "no_v6")).level)
        assertEquals(Level.SKIP, Judge.direct(null).level)
        assertEquals(Level.OK, Judge.bridge(true, "running", null).level)
        assertEquals(Level.FAIL, Judge.bridge(true, "not_running", "x").level)
        assertEquals(Level.SKIP, Judge.bridge(false, "running", null).level)
        assertEquals(Level.OK, Judge.keep(KeepFacts(true, false, 0)).level)
        assertEquals(Level.WARN, Judge.keep(KeepFacts(false, false, 0)).level)
        assertEquals(Level.FAIL, Judge.keep(KeepFacts(true, false, 2)).level)
        assertEquals(Level.OK, Judge.device(DeviceFacts(90, true, 20_000)).level)
        assertEquals(Level.WARN, Judge.device(DeviceFacts(90, false, 20_000)).level)
        assertEquals(Level.FAIL, Judge.device(DeviceFacts(5, false, 20_000)).level)
        assertEquals(Level.FAIL, Judge.device(DeviceFacts(90, true, 100)).level)
        assertEquals(Level.OK, Judge.ownRelay(true, 12, null).level)
        assertEquals(Level.WARN, Judge.ownRelay(false, null, "no_answer").level)
    }

    @Test
    fun aResultSaysItsWorstAndGoesToTheLogInALineEach() {
        val r = SelfTestResult(1, listOf(
            Check("network", "本机网络", Level.OK, "Wi-Fi"), Check("relays", "中继", Level.WARN, "a\nb"), Check("link", "长连接", Level.SKIP, "x")))
        assertEquals(Level.WARN, r.worst)
        assertEquals("ok network: Wi-Fi\nwarn relays: a b\nskip link: x", r.text())
        assertEquals(Level.OK, SelfTestResult(1, listOf(Check("link", "", Level.SKIP, ""))).worst)
    }

    @Test
    fun dropsAreCountedOnceEachAndForgottenAfterADay() {
        var now = 1_000_000_000L
        val link = ServiceLink { now }
        assertNull(link.heardAgoMs())
        // Switched on a moment ago: not yet a dead connection.
        link.began()
        assertEquals(0L, link.heardAgoMs())
        link.heard()
        now += 5_000
        assertEquals(5_000L, link.heardAgoMs())
        link.failed(); link.failed(); link.failed()
        assertEquals(1, link.drops24h())
        link.heard()
        link.failed()
        assertEquals(2, link.drops24h())
        now += ServiceLink.DAY_MS + 1
        assertEquals(0, link.drops24h())
    }

    @Test
    fun relayAddressesAreTakenApart() {
        assertEquals(RelayUrl("relay.example", 3478, false), RelayUrl.parse("turn:relay.example:3478?transport=udp"))
        assertEquals(RelayUrl("relay.example", 443, true), RelayUrl.parse("turn:relay.example:443?transport=tcp"))
        assertEquals(RelayUrl("relay.example", 3478, false), RelayUrl.parse("turn:relay.example"))
        assertEquals(RelayUrl("2001:db8::1", 3478, false), RelayUrl.parse("turn:[2001:db8::1]:3478"))
        assertNull(RelayUrl.parse("turns:relay.example:5349?transport=tcp"))
        assertNull(RelayUrl.parse("stun:relay.example:3478"))
    }
}
