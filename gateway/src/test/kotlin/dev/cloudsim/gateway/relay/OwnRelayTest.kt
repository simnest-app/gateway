package dev.cloudsim.gateway.relay

import dev.cloudsim.gateway.calls.Member
import dev.cloudsim.gateway.calls.Sealer
import dev.cloudsim.gateway.calls.SharingPolicy
import dev.cloudsim.gateway.pairing.SoftwareSigner
import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ServiceConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val SECRET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG"

private class MemoryStore(var kept: OwnRelay? = null) : RelayStore {
    override fun load() = kept
    override fun save(relay: OwnRelay) { kept = relay }
    override fun clear() { kept = null }
}

/** Which addresses answer, and with which secret. */
private class FakeProber(val up: MutableMap<String, Long> = mutableMapOf(), var secret: String = SECRET) : Prober {
    override fun binding(host: String, port: Int) = up[host]
    override fun allocate(host: String, port: Int, username: String, password: String): String? {
        if (host !in up) return "no_answer"
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA1")) }
        return if (Base64.getEncoder().encodeToString(mac.doFinal(username.toByteArray())) == password) null else "wrong_secret"
    }
}

class OwnRelayTest {
    private val code = """{"t":"cloudsim-relay","v":1,"host":"203.0.113.7","port":3478,"lan":["192.168.1.10"],"secret":"$SECRET","ver":"0.1.0"}"""
    private var mode = ""
    private var nowMs = 1_800_000_000_000L
    private val store = MemoryStore()
    private val prober = FakeProber(mutableMapOf("203.0.113.7" to 30L, "192.168.1.10" to 2L))
    private val keeper = OwnRelayKeeper(store, { mode }, { nowMs }, prober)

    private fun official(vararg ids: String) = JSONObject().put("relays", JSONArray(ids.map {
        JSONObject().put("id", it).put("kind", if (it.startsWith("shared")) "shared" else "official").put("urls", JSONArray(listOf("turn:192.0.2.1:3478"))).put("username", "u-$it").put("credential", "p")
    }))

    private fun ids(ice: JSONObject?) = ice?.getJSONArray("relays")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } } ?: emptyList()

    /** The setup page's code, as the relay's helper writes it; nothing else is taken for a relay. */
    @Test
    fun readsTheSetupPagesCodeAndNothingElse() {
        val r = OwnRelay.parse(code)!!
        assertEquals(OwnRelay("203.0.113.7", 3478, listOf("192.168.1.10"), SECRET, "0.1.0"), r)
        assertEquals(listOf("turn:203.0.113.7:3478", "turn:203.0.113.7:3478?transport=tcp"), r.urls(r.host))
        assertEquals("turn:[2001:db8::1]:3478", r.urls("2001:db8::1")[0])
        assertEquals(r, OwnRelay.parse(r.toJson().toString()))
        for (bad in listOf(
            "", "hello", "https://example.com/?k=1", "{}",
            code.replace("cloudsim-relay", "something-else"), code.replace("\"v\":1", "\"v\":2"),
            code.replace(SECRET, "short"), code.replace("203.0.113.7", "evil.example/path?x"), code.replace("3478", "0"),
            code.replace("192.168.1.10", "a b"),
        )) assertNull(bad, OwnRelay.parse(bad))
    }

    /** The coturn vector the service's and the helper's tests use: all three must agree. */
    @Test
    fun credentialsAreCoturns() {
        val (user, pass) = OwnRelay("h", 1, emptyList(), "north", "").credential("username", 1433895918)
        assertEquals("1433895918:username", user)
        assertEquals("Lnvy4u5dznX7KxCwfYaMn6dzmXQ=", pass)
    }

    /** Ours only (no choice signed): what the service handed out goes on as it is, and the own relay is not used. */
    @Test
    fun withoutAChoiceTheServicesRelaysGoOnUntouched() {
        keeper.add(code)
        val handed = official("a", "b")
        assertTrue(handed === keeper.ice(handed))
        val old = JSONObject().put("urls", JSONArray(listOf("turn:192.0.2.1:3478"))).put("username", "u").put("credential", "p")
        assertTrue(old === keeper.ice(old))
        assertNull(keeper.ice(null))
        mode = "something-new"
        assertEquals(listOf("a", "b"), ids(keeper.ice(handed)))
    }

    /** "Own relay only": never the service's, whatever it sent, and still nothing of the service's with no relay added. */
    @Test
    fun ownOnlyNeverUsesTheServices() {
        mode = "own"
        assertNull(keeper.ice(official("a")))
        keeper.add(code)
        val ice = keeper.ice(official("a"))
        assertEquals(listOf("own"), ids(ice))
        val own = ice!!.getJSONArray("relays").getJSONObject(0)
        // Not checked yet: every address is tried, the public one and the one in the relay's own network.
        assertEquals(4, own.getJSONArray("urls").length())
        assertEquals("own", own.getString("kind"))
        // The credential is one coturn accepts for this secret, and outlasts a long call.
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(SECRET.toByteArray(), "HmacSHA1")) }
        assertEquals(Base64.getEncoder().encodeToString(mac.doFinal(own.getString("username").toByteArray())), own.getString("credential"))
        assertEquals((nowMs + OwnRelayKeeper.CALL_TTL_MS) / 1000, own.getString("username").substringBefore(":").toLong())
        assertFalse(ice.toString().contains(SECRET))
        // The relay does not answer: it is still the only one allowed, so it is still tried, and ours never stand in.
        prober.up.clear()
        assertFalse(keeper.check()!!.ok)
        assertEquals(listOf("own"), ids(keeper.ice(official("a"))))
    }

    /** "Own first": the own relay while it answers from here, the service's when it does not or none was added. */
    @Test
    fun ownFirstFallsBackToTheServices() {
        mode = "own_first"
        assertEquals(listOf("a"), ids(keeper.ice(official("a"))))
        keeper.add(code)
        assertEquals(listOf("own"), ids(keeper.ice(official("a"))))
        // Only the address in the relay's own network answers: that one is used.
        prober.up.remove("203.0.113.7")
        val check = keeper.check()!!
        assertTrue(check.ok && !check.publicOk && check.rttMs == 2L)
        val own = keeper.ice(official("a"))!!.getJSONArray("relays").getJSONObject(0)
        assertEquals(listOf("turn:192.168.1.10:3478", "turn:192.168.1.10:3478?transport=tcp"), (0 until 2).map { own.getJSONArray("urls").getString(it) })
        prober.up.clear()
        keeper.check()
        assertEquals(listOf("a"), ids(keeper.ice(official("a"))))
        // A check from long ago says nothing about now: the own relay is tried again.
        nowMs += OwnRelayKeeper.CHECK_FRESH_MS + 1
        assertEquals(listOf("own"), ids(keeper.ice(official("a"))))
    }

    /** A relay shared by another user is dropped under every choice: none allows it (RELAY_SPLIT.md 15). */
    @Test
    fun sharedRelaysAreDroppedWhateverTheChoice() {
        keeper.add(code)
        for (m in listOf("", "own", "own_first")) {
            mode = m
            assertFalse(m, "shared-1" in ids(keeper.ice(official("shared-1", "a"))))
        }
        mode = ""
        assertEquals(listOf("a"), ids(keeper.ice(official("shared-1", "a"))))
        assertNull(keeper.ice(official("shared-1")))
    }

    @Test
    fun aCheckSaysWhatIsWrong() {
        assertNull(keeper.check())
        keeper.add(code)
        val ok = keeper.check()!!
        assertTrue(ok.ok && ok.publicOk && ok.rttMs == 2L && ok.problem == null)
        prober.secret = "another-secret-another-secret-another"
        assertEquals("wrong_secret", keeper.check()!!.problem)
        prober.up.clear()
        assertEquals("no_answer", keeper.check()!!.problem)
        keeper.remove()
        assertNull(store.kept)
        assertNull(keeper.lastCheck)
    }

    /** What a phone gets: the choice, and the relay only when calls use it, at its public address, never the secret. */
    @Test
    fun aPhoneGetsCredentialsNotTheSecret() {
        keeper.add(code)
        assertFalse(keeper.forPhone().has("relay"))
        assertEquals("", keeper.forPhone().getString("mode"))
        mode = "own_first"
        keeper.check()
        val told = keeper.forPhone()
        assertEquals("own_first", told.getString("mode"))
        val relay = told.getJSONObject("relay")
        assertEquals(listOf("turn:203.0.113.7:3478", "turn:203.0.113.7:3478?transport=tcp"), (0 until relay.getJSONArray("urls").length()).map { relay.getJSONArray("urls").getString(it) })
        assertEquals(nowMs + OwnRelayKeeper.PHONE_TTL_MS, relay.getLong("expires_at_ms"))
        assertTrue(told.getJSONObject("check").getBoolean("ok"))
        assertFalse(told.toString().contains(SECRET))
        assertFalse(told.toString().contains("192.168.1.10"))
        // Two phones do not share a user name on the relay, and neither carries a device's ID.
        assertFalse(relay.getString("username") == keeper.forPhone().getJSONObject("relay").getString("username"))
    }

    private class Sent : ControlClient(ServiceConfig("127.0.0.1:1", ByteArray(32), "svc"), SoftwareSigner()) {
        val sent = mutableListOf<Pair<String, JSONObject?>>()
        override fun call(method: String, path: String, body: JSONObject?, readTimeoutMs: Int): JSONObject { sent += "$method $path" to body; return JSONObject() }
    }

    /** Only the owner and the family in the owner-signed policy are answered, sealed for the one who asked. */
    @Test
    fun onlyThisGatewaysPhonesAreAnswered() {
        keeper.add(code)
        mode = "own"
        val client = Sent()
        val sealed = mutableListOf<String>()
        val sealer = object : Sealer {
            override fun seal(kind: String, to: String, id: String, plaintext: ByteArray): JSONObject { sealed += to; return JSONObject().put("to", to).put("plain", plaintext.decodeToString()) }
            override fun open(envelope: JSONObject, kind: String, senderKey: ByteArray): ByteArray? = null
        }
        var lease = true
        val policy = SharingPolicy(1, listOf(Member("family", ByteArray(1), true)), listOf("owner"), "simultaneous", 15, null, false, relay = "own")
        val requests = OwnRelayRequests(client, sealer, { "owner" }, { policy }, keeper, { lease })
        fun ask(from: String) = requests.onRequest(JSONObject().put("kind", "relay").put("device_id", from).put("request_id", "relay-0001"))
        ask("owner"); ask("family"); ask("stranger")
        assertEquals(listOf("owner", "family"), sealed)
        val body = client.sent.last().second!!
        assertEquals("POST /v1/records/deliver", client.sent.last().first)
        assertEquals("family", body.getString("to"))
        val plain = JSONObject(JSONObject(body.getString("envelope")).getString("plain"))
        assertEquals("relay", plain.getString("kind"))
        assertEquals("own", plain.getJSONArray("items").getJSONObject(0).getString("mode"))
        assertNotNull(plain.getJSONArray("items").getJSONObject(0).optJSONObject("relay"))
        // Permissions unconfirmed for five minutes: nobody is answered (REVOKE_LEASE_5.md).
        lease = false
        ask("owner")
        assertEquals(2, client.sent.size)
    }

    /** A relay that checks credentials the way coturn does, on this machine. */
    private fun fakeRelay(secret: String, denyPeers: Boolean = false): DatagramSocket {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        Thread {
            val buf = ByteArray(2048)
            while (true) {
                val p = DatagramPacket(buf, buf.size)
                if (runCatching { socket.receive(p) }.isFailure) return@Thread
                val m = RelayProbe.decode(buf, p.length) ?: continue
                val user = m.get(0x0006)?.decodeToString()
                val reply = when {
                    m.type == 0x0001 -> RelayProbe.Message(0x0101, m.txid, emptyList())
                    user == null -> RelayProbe.Message(m.type or 0x0110, m.txid, listOf(0x0009 to byteArrayOf(0, 0, 4, 1), 0x0014 to "realm".toByteArray(), 0x0015 to "nonce".toByteArray()))
                    else -> {
                        val pass = Base64.getEncoder().encodeToString(Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA1")) }.doFinal(user.toByteArray()))
                        val key = MessageDigest.getInstance("MD5").digest("$user:realm:$pass".toByteArray())
                        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }.doFinal(buf.copyOf(p.length - 24))
                        if (!mac.contentEquals(m.get(0x0008))) RelayProbe.Message(m.type or 0x0110, m.txid, listOf(0x0009 to byteArrayOf(0, 0, 4, 31)))
                        else if (m.type == 0x0003) RelayProbe.Message(0x0103, m.txid, listOf(0x0016 to byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0)))
                        else if (m.type == 0x0008 && denyPeers) RelayProbe.Message(0x0118, m.txid, listOf(0x0009 to byteArrayOf(0, 0, 4, 3)))
                        else RelayProbe.Message(m.type or 0x0100, m.txid, emptyList())
                    }
                }
                val out = RelayProbe.encode(reply)
                socket.send(DatagramPacket(out, out.size, p.socketAddress))
            }
        }.apply { isDaemon = true }.start()
        return socket
    }

    /**
     * Against a real coturn, when one is named: CLOUDSIM_REAL_RELAY=host:port and CLOUDSIM_REAL_RELAY_SECRET, as the
     * deployment package's relay has them. Skipped otherwise.
     */
    @Test
    fun theProbeSpeaksToARealCoturn() {
        val at = System.getenv("CLOUDSIM_REAL_RELAY")?.let(RelayMeasurer::hostPort)
        val secret = System.getenv("CLOUDSIM_REAL_RELAY_SECRET")
        org.junit.Assume.assumeTrue(at != null && !secret.isNullOrEmpty())
        val (host, port) = at!!
        val own = OwnRelay(host, port, emptyList(), secret!!, "")
        assertNotNull(RelayProbe.binding(host, port))
        val (user, pass) = own.credential("check", System.currentTimeMillis() / 1000 + 60)
        assertNull(RelayProbe.allocate(host, port, user, pass))
        val (u2, p2) = own.copy(secret = "another-secret-another-secret-another").credential("check", System.currentTimeMillis() / 1000 + 60)
        assertEquals("wrong_secret", RelayProbe.allocate(host, port, u2, p2))
        // A credential that has run out is refused too.
        val (u3, p3) = own.credential("check", System.currentTimeMillis() / 1000 - 60)
        assertEquals("wrong_secret", RelayProbe.allocate(host, port, u3, p3))
        val keeper = OwnRelayKeeper(MemoryStore(own), { "own" })
        val check = keeper.check()!!
        assertTrue(check.ok && check.publicOk)
    }

    /** The check's own STUN and TURN: a binding, an allocation with the right secret, one with the wrong secret, and silence. */
    @Test
    fun theProbeSpeaksToARelay() {
        val relay = fakeRelay(SECRET)
        try {
            val own = OwnRelay("127.0.0.1", relay.localPort, emptyList(), SECRET, "")
            assertNotNull(RelayProbe.binding("127.0.0.1", relay.localPort, 1000))
            val (user, pass) = own.credential("check", System.currentTimeMillis() / 1000 + 60)
            assertNull(RelayProbe.allocate("127.0.0.1", relay.localPort, user, pass, 1000))
            val (u2, p2) = own.copy(secret = "another-secret-another-secret-another").credential("check", 1)
            assertEquals("wrong_secret", RelayProbe.allocate("127.0.0.1", relay.localPort, u2, p2, 1000))
        } finally {
            relay.close()
        }
        assertNull(RelayProbe.binding("127.0.0.1", 9, 200))
        assertEquals("no_answer", RelayProbe.allocate("127.0.0.1", 9, "u", "p", 200))
        // A relay whose rules shut out ordinary public addresses hands out allocations all the same: said as that.
        val strict = fakeRelay(SECRET, denyPeers = true)
        try {
            val (user, pass) = OwnRelay("127.0.0.1", strict.localPort, emptyList(), SECRET, "").credential("check", System.currentTimeMillis() / 1000 + 60)
            assertEquals("blocks_public", RelayProbe.allocate("127.0.0.1", strict.localPort, user, pass, 1000))
        } finally {
            strict.close()
        }
    }
}
