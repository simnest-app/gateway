package dev.cloudsim.gateway.calls

import android.content.Context
import org.json.JSONObject

/**
 * The owner's switch for opening a port on the home router (docs/architecture/DIRECT_PATHS.md 4), off unless turned
 * on on this phone's screen. The audio bridge does the mapping (libplum) and reports whether the router can:
 * checking, mapped, unsupported or not_public (the broadband has no public IPv4, so a mapping would not help).
 */
object PortMapping {
    const val KEY = "port_mapping"

    @Volatile var state: String = "off"

    fun on(context: Context) = context.getSharedPreferences("gateway", Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun set(context: Context, on: Boolean) = context.getSharedPreferences("gateway", Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()

    /** What the bridge is told when it connects and whenever the switch changes. */
    fun command(context: Context): JSONObject = JSONObject().put("type", "mapping").put("on", on(context))

    /** For the self-test's direct-path conditions: off when switched off, else what the bridge found. */
    fun condition(context: Context): String = if (!on(context)) "off" else state.takeIf { it != "off" } ?: "checking"
}
