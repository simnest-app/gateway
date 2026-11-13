package dev.cloudsim.gateway.calls

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import org.json.JSONObject

/**
 * The gateway phone's own cellular data, for direct calls with an iPhone on cellular (CELLULAR_DIRECT_RESEARCH.md):
 * both have public IPv6 there, where Wi-Fi behind a NAT and the carriers' shared IPv4 leave only the relay. The shell
 * audio bridge cannot ask Android for networks (ConnectivityManager hangs in app_process), so this app keeps the
 * cellular network requested and tells the bridge, with each offer and self-test, which network and interface it is;
 * the bridge offers that interface's IPv6 to WebRTC, bound to the network. Used only for media, and only when on.
 */
class CellularPath(private val context: Context, private val log: (String) -> Unit = {}) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var network: Network? = null
    @Volatile private var iface: String? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            network = n
            iface = cm?.getLinkProperties(n)?.interfaceName
            log("cellular path available: ${iface ?: "?"}")
        }
        override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
            if (n == network) iface = lp.interfaceName
        }
        override fun onLost(n: Network) {
            if (n == network) {
                network = null
                iface = null
                log("cellular path lost")
            }
        }
    }

    /** Keeps cellular data up beside Wi-Fi while the gateway runs (it costs nothing until media uses it). */
    fun start() {
        current = this
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm?.requestNetwork(request, callback) }.onFailure { log("cellular path not requested: ${it.message}") }
    }

    fun stop() {
        if (current === this) current = null
        runCatching { cm?.unregisterNetworkCallback(callback) }
    }

    /** The network for the bridge, or null when off or there is none. */
    fun describe(): JSONObject? {
        if (!on(context)) return null
        val n = network ?: return null
        val name = iface ?: return null
        return JSONObject().put("handle", n.networkHandle).put("iface", name)
    }

    /**
     * The direct-path conditions for the self-test and the check before going out (DIRECT_PATHS.md 3.3): whether the
     * Wi-Fi has a public IPv6, and whether this phone's own data is offered: "off" (switched off), "none" (no cellular
     * network), "no_v6" (no public IPv6 on it), "standby" (offered, but the Wi-Fi's IPv6 or a router port goes first)
     * or "ready" (the direct path from outside); and the router port mapping's state. No address leaves the phone.
     */
    fun condition(): JSONObject {
        val wifiV6 = runCatching {
            cm?.allNetworks?.any { n ->
                cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && publicV6(cm.getLinkProperties(n))
            }
        }.getOrNull() ?: false
        val n = network
        val own = when {
            !on(context) -> "off"
            n == null -> "none"
            !publicV6(runCatching { cm?.getLinkProperties(n) }.getOrNull()) -> "no_v6"
            wifiV6 || PortMapping.condition(context) == "mapped" -> "standby"
            else -> "ready"
        }
        return JSONObject().put("wifi_v6", wifiV6).put("cellular", own).put("mapping", PortMapping.condition(context))
    }

    /** A call's offer or a self-test carries the cellular network along. */
    fun annotate(command: JSONObject): JSONObject {
        if (command.optString("type") !in setOf("offer", "probe", "assist")) return command
        return describe()?.let { command.put("cellular", it) } ?: command
    }

    companion object {
        /** The running gateway's, for the screen's live direct-path state; null when the gateway is off. */
        @Volatile var current: CellularPath? = null

        /** A global unicast IPv6 address (2000::/3), as the audio bridge counts it. */
        fun publicV6(lp: LinkProperties?): Boolean = lp?.linkAddresses?.any { la ->
            val a = la.address.address
            a.size == 16 && (a[0].toInt() and 0xE0) == 0x20
        } == true

        /** On unless the owner of this phone turns it off on the gateway screen. */
        fun on(context: Context) = context.getSharedPreferences("gateway", Context.MODE_PRIVATE).getBoolean("cellular_direct", true)
        fun set(context: Context, on: Boolean) = context.getSharedPreferences("gateway", Context.MODE_PRIVATE).edit().putBoolean("cellular_direct", on).apply()
    }
}
