package dev.cloudsim.gateway.numbers

import dev.cloudsim.gateway.calls.Member
import dev.cloudsim.gateway.calls.Rights
import dev.cloudsim.gateway.calls.Ring
import dev.cloudsim.gateway.calls.Sealer
import dev.cloudsim.gateway.calls.SharingPolicy
import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ServiceConfig
import dev.cloudsim.gateway.pairing.SoftwareSigner
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberBookTest {
    private class Sent : ControlClient(ServiceConfig("127.0.0.1:1", ByteArray(32), "svc"), SoftwareSigner()) {
        val calls = mutableListOf<Pair<String, JSONObject?>>()
        override fun call(method: String, path: String, body: JSONObject?, readTimeoutMs: Int): JSONObject { calls += path to body; return JSONObject() }
    }
    private val stored = object : Bindings { var all = JSONObject(); override fun load() = JSONObject(all.toString()); override fun save(all: JSONObject) { this.all = all } }
    private var sims = listOf(Sim(3, 0, "中国联通", "+8613800138000"), Sim(4, 1, "中国联通", null))
    private val client = Sent()
    private val sealer = object : Sealer {
        override fun seal(kind: String, to: String, id: String, plaintext: ByteArray) = JSONObject().put("kind", kind).put("to", to).put("plain", plaintext.decodeToString())
        override fun open(envelope: JSONObject, kind: String, senderKey: ByteArray): ByteArray? = null
    }
    private val book = NumberBook(client, { sims }, stored, sealer, { "owner" })

    @Test
    fun eachSimGetsAStableRandomIdAndANewSimInheritsNothing() {
        val first = book.current()
        assertEquals(2, first.size)
        assertNotEquals(first[0].numberId, first[1].numberId)
        assertEquals(first, book.current())
        // A different SIM in slot 2 is a new number; the one taken out stays known, not active.
        sims = listOf(Sim(3, 0, "中国联通", "+8613800138000"), Sim(9, 1, "中国移动", null))
        val after = book.current()
        assertEquals(3, after.size)
        assertEquals(first[0].numberId, book.numberIdFor(3))
        assertFalse(after.single { it.subscriptionId == 4 }.active)
        assertNull(book.subscriptionFor(first[1].numberId))
        assertTrue(after.none { it.subscriptionId == 9 && it.numberId == first[1].numberId })
    }

    @Test
    fun reportsIdsToTheServiceAndOnlyKnownNumbersSealedForTheOwner() {
        book.report()
        val list = client.calls.first { it.first.endsWith("/numbers") }.second!!.getJSONArray("numbers")
        assertEquals(2, list.length())
        assertFalse("no real number in the plain list", list.toString().contains("138"))
        val label = client.calls.first { it.first.endsWith("/labels") }.second!!.getJSONArray("items").getJSONObject(0)
        assertEquals("owner", label.getString("to"))
        val plain = JSONObject(JSONObject(label.getString("envelope")).getString("plain")).getJSONArray("numbers")
        assertEquals(1, plain.length())
        assertEquals("+8613800138000", plain.getJSONObject(0).getString("number"))
    }
}

class PerNumberPolicyTest {
    private val member = Member("fam", ByteArray(0), answer = true, smsView = true, perNumber = mapOf("work" to Rights(answer = true), "home" to Rights(smsView = true)))
    private val policy = SharingPolicy(3, listOf(member), listOf("owner"), "simultaneous", 15, null, false, listOf("work", "home"),
        mapOf("work" to Ring(listOf("owner"), "assigned", 20, "fam", true)))

    @Test
    fun rightsAndRingingFollowTheNumber() {
        val work = policy.forNumber("work")
        assertEquals(listOf("owner", "fam"), work.answerers("owner"))
        assertEquals("assigned", work.mode)
        assertEquals(20, work.waitS)
        assertFalse(work.members.single().smsView)
        val home = policy.forNumber("home")
        assertEquals(listOf("owner"), home.answerers("owner"))
        assertTrue(home.members.single().smsView)
        assertEquals("simultaneous", home.mode)
        // A number the member has no entry for gives nothing.
        assertEquals(listOf("owner"), policy.forNumber("other").answerers("owner"))
    }
}
