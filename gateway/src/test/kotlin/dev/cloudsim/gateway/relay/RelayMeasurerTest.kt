package dev.cloudsim.gateway.relay

import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ServiceConfig
import dev.cloudsim.gateway.pairing.SoftwareSigner
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayMeasurerTest {
    private class Service(var relays: JSONObject) : ControlClient(ServiceConfig("127.0.0.1:1", ByteArray(32), "svc"), SoftwareSigner()) {
        val sent = mutableListOf<Pair<String, JSONObject?>>()
        override fun call(method: String, path: String, body: JSONObject?, readTimeoutMs: Int): JSONObject {
            sent += "$method $path" to body
            return if (path == "/v1/relays") relays else JSONObject()
        }
    }

    private fun target(id: String, stun: String) = JSONObject().put("id", id).put("stun", stun)

    /** Each relay the service names is timed twice and the better try reported; one that never answers is reported as such. */
    @Test
    fun reportsTheBetterOfTwoTriesPerRelay() {
        val service = Service(JSONObject().put("relays", JSONArray()).put("probe", JSONArray()
            .put(target("south", "192.0.2.1:3478")).put(target("east", "[2001:db8::1]:3479")).put(target("down", "relay.example:3478")).put(target("bad", "nowhere"))))
        val tries = mutableListOf<String>()
        val answers = mutableMapOf("192.0.2.1" to mutableListOf<Long?>(40, 25), "2001:db8::1" to mutableListOf<Long?>(null, 80), "relay.example" to mutableListOf<Long?>(null, null))
        RelayMeasurer(service, { "wifi" }, { host, port -> tries += "$host:$port"; answers.getValue(host).removeAt(0) }).run()
        assertEquals(listOf("192.0.2.1:3478", "192.0.2.1:3478", "2001:db8::1:3479", "2001:db8::1:3479", "relay.example:3478", "relay.example:3478"), tries)
        val (what, body) = service.sent.last()
        assertEquals("POST /v1/relays/measurements", what)
        assertEquals("wifi", body!!.getString("network"))
        val rtts = body.getJSONObject("rtts")
        assertEquals(25, rtts.getLong("south"))
        assertEquals(80, rtts.getLong("east"))
        assertEquals(RelayMeasurer.UNREACHABLE, rtts.getLong("down"))
        assertTrue(!rtts.has("bad"))
        // Names and milliseconds only: no address goes to the service.
        assertTrue(!body.toString().contains("192.0.2.1") && !body.toString().contains("relay.example"))
    }

    /** A service that names nothing to time (an older one, or the owner chose their own relay only) is told nothing. */
    @Test
    fun nothingToTimeNothingReported() {
        for (answer in listOf(JSONObject(), JSONObject().put("relays", JSONArray()), JSONObject().put("probe", JSONArray()))) {
            val service = Service(answer)
            RelayMeasurer(service, binding = { _, _ -> 1L }).run()
            assertEquals(listOf("GET /v1/relays"), service.sent.map { it.first })
        }
    }

    @Test
    fun hostAndPort() {
        assertEquals("192.0.2.1" to 3478, RelayMeasurer.hostPort("192.0.2.1:3478"))
        assertEquals("2001:db8::1" to 3479, RelayMeasurer.hostPort("[2001:db8::1]:3479"))
        assertEquals("relay.example" to 3478, RelayMeasurer.hostPort("relay.example:3478"))
        for (bad in listOf("", "nowhere", ":3478", "host:0", "host:99999", "ho st:3478", "host/path:3478")) assertNull(bad, RelayMeasurer.hostPort(bad))
    }
}
