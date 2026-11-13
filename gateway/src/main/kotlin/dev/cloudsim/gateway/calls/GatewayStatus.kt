package dev.cloudsim.gateway.calls

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import dev.cloudsim.gateway.Readiness
import dev.cloudsim.gateway.numbers.GatewayNumber
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the check before going out shows of this gateway phone (docs/architecture/PRE_DEPARTURE_7.md): battery, each
 * SIM's service, and which features are not set up. Sent with a self-test reply, sealed for the asking phone alone.
 * A family phone gets only the numbers it has a right on, and only the problems that affect what it may do.
 */
object GatewayStatus {
    /** Which right a feature serves; the rest (remote access, notices, the default phone app) concern everyone. */
    private val serves = mapOf(
        "calls" to "answer", "caller_id" to "answer", "answer" to "answer", "dial" to "dial",
        "sms_in" to "sms", "sms_read" to "sms", "sms_out" to "send", "sms_app" to "send",
        "contacts" to "contacts", "numbers" to "owner", "notice_sms" to "owner",
    )

    fun collect(context: Context, numbers: List<GatewayNumber>, owner: String?, policy: SharingPolicy?, to: String, direct: JSONObject? = null): JSONObject {
        val member = if (to == owner) null else policy?.members?.firstOrNull { it.deviceId == to }
        val held = mutableSetOf<String>()
        val lines = JSONArray()
        for (n in numbers.sortedBy { it.slot }) {
            if (member != null) {
                val r = member.on(n.numberId)
                if (r.answer) held += "answer"
                if (r.dial) held += "dial"
                if (r.smsView) held += "sms"
                if (r.smsSend) held += "send"
                if (!(r.answer || r.dial || r.smsView || r.smsSend)) continue
            }
            val line = JSONObject().put("number_id", n.numberId).put("slot", n.slot + 1)
            val (state, signal) = line(context, n)
            line.put("state", state)
            signal?.let { line.put("signal", it) }
            lines.put(line)
        }
        if (member?.contacts == true) held += "contacts"
        val problems = JSONArray()
        Readiness.items(context).filter { !it.ok && it.id.isNotEmpty() }.forEach { item ->
            val need = serves[item.id]
            if (member == null || need == null || need in held) problems.put(item.id)
        }
        val out = JSONObject().put("lines", lines).put("problems", problems)
        battery(context)?.let { (level, charging) -> out.put("battery", level).put("charging", charging) }
        direct?.let { out.put("direct", it) }
        return out
    }

    /**
     * The numbers as this phone's own screen lists them: the cards in it now, and for a slot with none a single line
     * saying so (cards taken out stay known to the gateway, several for one slot over time).
     */
    fun shown(numbers: List<GatewayNumber>): List<GatewayNumber> {
        val active = numbers.filter { it.active }
        val empty = numbers.filter { n -> !n.active && active.none { it.slot == n.slot } }.distinctBy { it.slot }
        return (active + empty).sortedBy { it.slot }
    }

    /** One number's service state and signal bars (0–4, when in service); a slot with no card in it is "no_sim". */
    fun line(context: Context, n: GatewayNumber): Pair<String, Int?> {
        if (!n.active) return "no_sim" to null
        val tm = runCatching { context.getSystemService(TelephonyManager::class.java).createForSubscriptionId(n.subscriptionId) }.getOrNull()
        // The best of the card's cells, as the status bar shows it; the overall level reads low on some phones (2026-11-13).
        val signal = runCatching { tm?.signalStrength?.let { s -> s.cellSignalStrengths.maxOfOrNull { it.level } ?: s.level } }.getOrNull()
        var state = state(tm)
        // Some phones give no service state without the location permission (the Redmi on Android 13, 2026-11-13):
        // a card registered on a network names that network's operator, one that is not names none.
        if (state == "unknown" && tm != null) {
            val operator = runCatching { tm.networkOperator }.getOrNull()
            if (!operator.isNullOrEmpty()) state = "in_service" else if (operator != null && runCatching { tm.simState }.getOrNull() == TelephonyManager.SIM_STATE_READY) state = "no_service"
        }
        return state to signal.takeIf { state == "in_service" }
    }

    /**
     * Without location data: the plain getter needs the location permission as well, which the gateway does not have
     * and does not ask for.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun state(tm: TelephonyManager?): String = when (runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 33) tm?.getServiceState(TelephonyManager.INCLUDE_LOCATION_DATA_NONE)?.state else tm?.serviceState?.state
    }.onFailure { android.util.Log.w("CloudSimGateway", "service state not read: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()) {
        ServiceState.STATE_IN_SERVICE -> "in_service"
        ServiceState.STATE_OUT_OF_SERVICE -> "no_service"
        ServiceState.STATE_EMERGENCY_ONLY -> "emergency_only"
        ServiceState.STATE_POWER_OFF -> "radio_off"
        else -> "unknown"
    }

    fun battery(context: Context): Pair<Int, Boolean>? {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return (level * 100 / scale) to charging
    }
}
