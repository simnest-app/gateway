package dev.cloudsim.gateway.records

import dev.cloudsim.gateway.calls.Member
import dev.cloudsim.gateway.calls.Sealer
import dev.cloudsim.gateway.calls.SharingPolicy
import dev.cloudsim.gateway.pairing.SoftwareSigner
import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ServiceConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class Client : ControlClient(ServiceConfig("127.0.0.1:1", ByteArray(32), "svc"), SoftwareSigner()) {
    val sent = mutableListOf<JSONObject>()
    override fun call(method: String, path: String, body: JSONObject?, readTimeoutMs: Int): JSONObject {
        if (path == "/v1/records/deliver") sent += body!!
        return JSONObject()
    }
}

private class Wrap : Sealer {
    override fun seal(kind: String, to: String, id: String, plaintext: ByteArray) =
        JSONObject().put("kind", kind).put("to", to).put("id", id).put("plain", plaintext.decodeToString())
    /** Opens what a test sealed as {kind, id, plain}, if the kind and sender key match. */
    override fun open(envelope: JSONObject, kind: String, senderKey: ByteArray): ByteArray? =
        if (envelope.optString("kind") == kind && senderKey.decodeToString() == envelope.optString("from")) envelope.optString("plain").toByteArray() else null
}

private class Book(val n: Int, val name: String = "联系人") : RecordSource {
    var callsAsked: Pair<Long, Long>? = null
    override fun contacts() = (0 until n).map { JSONObject().put("name", "$name$it").put("numbers", org.json.JSONArray().put(JSONObject().put("label", "手机").put("number", "1380000%04d".format(it)))) }
    override fun calls(from: Long, to: Long): List<JSONObject> { callsAsked = from to to; return listOf(JSONObject().put("number", "10086").put("at", from + 1).put("type", "incoming")) }
    val added = mutableListOf<NewContact>()
    val appended = mutableListOf<Pair<String, List<ContactNumber>>>()
    var canWrite = true
    override fun addContact(contact: NewContact) {
        if (!canWrite) throw MissingPermission("WRITE_CONTACTS")
        added += contact
    }
    override fun addNumbers(contactId: String, numbers: List<ContactNumber>) {
        if (contactId == "gone") throw ContactGone()
        appended += contactId to numbers
    }
}

class RecordsRelayTest {
    private val client = Client()
    private val owner = "owner-device"
    private val family = "family-device"
    private var policy: SharingPolicy? = null
    private var lease = true

    private fun relay(book: RecordSource) = RecordsRelay(client, Wrap(), { owner }, { policy }, book, { lease })
    private fun ask(r: RecordsRelay, kind: String, from: String, id: String = "req-00000001") =
        r.onRequest(JSONObject().put("kind", kind).put("device_id", from).put("request_id", id).put("from", 100).put("to", 200))
    private fun plain(i: Int) = JSONObject(JSONObject(client.sent[i].getString("envelope")).getString("plain"))
    private fun withFamily(contacts: Boolean) {
        policy = SharingPolicy(1, listOf(Member(family, ByteArray(0), answer = true, contacts = contacts)), listOf(owner), "simultaneous", 15, null, false)
    }

    @Test
    fun withoutThePermissionThePhoneIsToldWhyInsteadOfWaiting() {
        val r = relay(object : RecordSource {
            override fun contacts(): List<JSONObject> = throw MissingPermission("READ_CONTACTS")
            override fun calls(from: Long, to: Long): List<JSONObject> = throw IllegalStateException("provider gone")
        })
        ask(r, "contacts", owner)
        assertEquals(1, client.sent.size)
        assertEquals(RecordsRelay.NO_PERMISSION, plain(0).getString("unavailable"))
        assertEquals(0, plain(0).getJSONArray("items").length())
        assertEquals(1, plain(0).getInt("count"))
        ask(r, "calllog", owner, "req-00000002")
        assertEquals(RecordsRelay.UNREADABLE, plain(1).getString("unavailable"))
    }

    @Test
    fun theOwnerGetsContactsAndTheCallLogSealedForItAlone() {
        val book = Book(3)
        val r = relay(book)
        ask(r, "contacts", owner)
        assertEquals(1, client.sent.size)
        assertEquals(owner, client.sent[0].getString("to"))
        assertEquals(owner, JSONObject(client.sent[0].getString("envelope")).getString("to"))
        assertEquals(3, plain(0).getJSONArray("items").length())
        ask(r, "calllog", owner, "req-00000002")
        assertEquals(100L to 200L, book.callsAsked)
        assertEquals("calllog", client.sent[1].getString("kind"))
    }

    @Test
    fun familyGetsContactsOnlyWithTheRightAndNeverTheCallLog() {
        val r = relay(Book(2))
        withFamily(contacts = false)
        ask(r, "contacts", family)
        assertTrue(client.sent.isEmpty())
        withFamily(contacts = true)
        ask(r, "contacts", family)
        assertEquals(1, client.sent.size)
        ask(r, "calllog", family, "req-00000002")
        ask(r, "contacts", "stranger")
        ask(r, "photos", owner)
        assertEquals(1, client.sent.size)
    }

    @Test
    fun nothingGoesOutWhilePermissionsAreUnconfirmed() {
        lease = false
        ask(relay(Book(2)), "contacts", owner)
        assertTrue(client.sent.isEmpty())
    }

    @Test
    fun aLongListGoesInPartsThatTogetherHoldEveryone() {
        ask(relay(Book(2_000, name = "很长的联系人名字用来测分段")), "contacts", owner)
        assertTrue(client.sent.size > 1)
        val count = client.sent[0].getInt("count")
        assertEquals(client.sent.size, count)
        var total = 0
        client.sent.forEachIndexed { i, p ->
            assertEquals(i, p.getInt("index"))
            assertTrue(p.getString("envelope").toByteArray().size < 48 * 1024)
            total += plain(i).getJSONArray("items").length()
        }
        assertEquals(2_000, total)
    }

    private var clock = 1_000_000L
    private fun addRelay(book: Book) = RecordsRelay(client, Wrap(), { owner }, { policy }, book, { lease }, now = { clock })
    private fun addContact(r: RecordsRelay, from: String, id: String = "add-00000001", sealedBy: String = from, innerId: String = id, at: Long = clock,
                           plain: JSONObject = JSONObject().put("name", "王师傅")
                               .put("numbers", org.json.JSONArray().put(JSONObject().put("label", "手机").put("number", "137 0000 4321")))) {
        plain.put("request_id", innerId)
        val env = JSONObject().put("kind", "contactadd").put("id", id).put("from", sealedBy).put("plain", plain.toString())
        r.onRequest(JSONObject().put("kind", "contact.add").put("device_id", from).put("request_id", id).put("_at_ms", at)
            .put("public_key", java.util.Base64.getEncoder().encodeToString(from.toByteArray())).put("envelope", env.toString()))
    }

    @Test
    fun aContactIsAddedOnceForThoseWhoMaySeeTheContacts() {
        val book = Book(0)
        val r = addRelay(book)
        addContact(r, owner)
        assertEquals(listOf(NewContact("王师傅", "", "", "", listOf(ContactNumber("mobile", "手机", "137 0000 4321")))), book.added)
        assertEquals("contact.add", client.sent[0].getString("kind"))
        assertTrue(!plain(0).has("unavailable"))
        // The same request again: answered, not written twice.
        addContact(r, owner)
        assertEquals(1, book.added.size)
        assertEquals(2, client.sent.size)
        // Family only with the contacts right.
        withFamily(contacts = false)
        addContact(r, family, "add-00000002")
        assertEquals(2, client.sent.size)
        withFamily(contacts = true)
        addContact(r, family, "add-00000003")
        assertEquals(2, book.added.size)
    }

    @Test
    fun aContactNotSealedByItsSenderOrStaleOrMismatchedIsIgnored() {
        val book = Book(0)
        val r = addRelay(book)
        addContact(r, owner, sealedBy = "someone-else")
        addContact(r, owner, "add-00000002", innerId = "add-00000099")
        addContact(r, owner, "add-00000003", at = clock - 200_000)
        lease = false
        addContact(r, owner, "add-00000004")
        assertTrue(book.added.isEmpty())
        assertTrue(client.sent.isEmpty())
    }

    @Test
    fun familyGivenCompanyAndLabelsAreKept() {
        val book = Book(0)
        val numbers = org.json.JSONArray()
            .put(JSONObject().put("type", "work").put("label", "工作").put("number", "+86 755 1234 5678"))
            .put(JSONObject().put("type", "custom").put("label", "老家").put("number", "+86 137 0000 4321"))
        addContact(addRelay(book), owner, plain = JSONObject().put("name", "王建国").put("family", "王").put("given", "建国").put("company", "某某公司").put("numbers", numbers))
        assertEquals(NewContact("王建国", "王", "建国", "某某公司", listOf(ContactNumber("work", "工作", "+86 755 1234 5678"), ContactNumber("custom", "老家", "+86 137 0000 4321"))), book.added.single())
    }

    @Test
    fun aCompanyAloneIsEnoughButNothingAtAllIsNot() {
        val book = Book(0)
        val r = addRelay(book)
        val number = { org.json.JSONArray().put(JSONObject().put("type", "main").put("number", "95588")) }
        addContact(r, owner, plain = JSONObject().put("company", "银行").put("numbers", number()))
        addContact(r, owner, "add-00000002", plain = JSONObject().put("numbers", number()))
        assertEquals(listOf("银行"), book.added.map { it.company })
        assertEquals(1, client.sent.size)
    }

    @Test
    fun numbersGoOntoAnExistingContactOrThePhoneIsToldItIsGone() {
        val book = Book(0)
        val r = addRelay(book)
        val number = { org.json.JSONArray().put(JSONObject().put("type", "home").put("label", "住宅").put("number", "+86 10 1234 5678")) }
        addContact(r, owner, plain = JSONObject().put("contact_id", "42").put("numbers", number()))
        assertEquals(listOf("42" to listOf(ContactNumber("home", "住宅", "+86 10 1234 5678"))), book.appended)
        assertTrue(book.added.isEmpty())
        assertTrue(!plain(0).has("unavailable"))
        addContact(r, owner, "add-00000002", plain = JSONObject().put("contact_id", "gone").put("numbers", number()))
        assertEquals(RecordsRelay.NOT_FOUND, plain(1).getString("unavailable"))
    }

    @Test
    fun withoutTheWritePermissionThePhoneIsToldWhy() {
        val book = Book(0).apply { canWrite = false }
        addContact(addRelay(book), owner)
        assertEquals(RecordsRelay.NO_PERMISSION, plain(0).getString("unavailable"))
    }
}
