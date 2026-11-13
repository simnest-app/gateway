package dev.cloudsim.gateway.numbers

import dev.cloudsim.gateway.calls.Sealer
import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ServiceException
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** One SIM the system has in use: its subscription, slot, carrier name and own number if the system knows it. */
data class Sim(val subscriptionId: Int, val slot: Int, val carrier: String, val number: String?)

/** The SIMs now in use (SubscriptionManager on the phone). */
fun interface SimSource { fun active(): List<Sim> }

/** Where the subscription → number ID bindings are kept across restarts. */
interface Bindings {
    fun load(): JSONObject
    fun save(all: JSONObject)
}

/** A number as the gateway knows it: its random ID, the SIM it is bound to, and whether that SIM is in now. */
data class GatewayNumber(val numberId: String, val subscriptionId: Int, val slot: Int, val carrier: String, val active: Boolean, val number: String?)

/**
 * The gateway's numbers (docs/architecture/DUAL_SIM.md). Each SIM subscription gets a random number ID the first time
 * it is seen, kept for good: the service and the policy only ever see that ID. A different SIM is a different
 * subscription and so a new number, inheriting nothing; a SIM taken out stays known, marked not active.
 */
class NumberBook(
    private val client: ControlClient,
    private val sims: SimSource,
    private val bindings: Bindings,
    private val sealer: Sealer,
    private val owner: () -> String?,
    private val log: (String) -> Unit = {},
) {
    private val random = SecureRandom()

    @Synchronized
    fun current(): List<GatewayNumber> {
        val all = bindings.load()
        val now = sims.active()
        var changed = false
        for (sim in now) {
            val key = sim.subscriptionId.toString()
            val entry = all.optJSONObject(key) ?: JSONObject().put("number_id", newId()).also { changed = true }
            if (entry.optInt("slot", -1) != sim.slot || entry.optString("carrier") != sim.carrier) {
                entry.put("slot", sim.slot).put("carrier", sim.carrier); changed = true
            }
            all.put(key, entry)
        }
        if (changed) bindings.save(all)
        val active = now.associateBy { it.subscriptionId.toString() }
        return all.keys().asSequence().map { key ->
            val e = all.getJSONObject(key)
            GatewayNumber(e.getString("number_id"), key.toInt(), e.optInt("slot"), e.optString("carrier"), key in active, active[key]?.number?.takeIf { it.isNotBlank() })
        }.sortedWith(compareBy({ !it.active }, { it.slot })).toList()
    }

    fun numberIdFor(subscriptionId: Int): String? = current().find { it.subscriptionId == subscriptionId }?.numberId

    fun subscriptionFor(numberId: String): Int? = current().find { it.numberId == numberId && it.active }?.subscriptionId

    /** Tells the service the number list, and the owner (sealed) the numbers read off the SIMs. */
    fun report() {
        val list = current()
        try {
            client.call("POST", "/v1/gateways/${client.deviceId}/numbers", JSONObject().put("numbers", JSONArray(list.map {
                JSONObject().put("number_id", it.numberId).put("slot", it.slot).put("active", it.active).put("carrier", it.carrier)
            })))
            log("numbers reported: ${list.size} (${list.count { it.active }} active, ${list.count { it.number != null }} with a known number)")
            val o = owner() ?: return
            val known = list.filter { it.number != null }
            if (known.isEmpty()) return
            val plain = JSONObject().put("numbers", JSONArray(known.map { JSONObject().put("number_id", it.numberId).put("number", it.number) })).toString().toByteArray()
            val env = sealer.seal("numbers", o, "numbers", plain) ?: return log("numbers: owner has no recipient key yet")
            client.call("POST", "/v1/gateways/${client.deviceId}/labels", JSONObject().put("items", JSONArray().put(JSONObject().put("to", o).put("envelope", env.toString()))))
        } catch (e: ServiceException) {
            log("numbers: ${e.code} ${e.message}")
        }
    }

    private fun newId() = "num-" + ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
}
