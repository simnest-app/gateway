package dev.cloudsim.gateway.selftest

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.os.StatFs
import dev.cloudsim.gateway.BuildInfo
import dev.cloudsim.gateway.L
import dev.cloudsim.gateway.Readiness
import dev.cloudsim.gateway.adb.ShellLauncher
import dev.cloudsim.gateway.calls.CellularPath
import dev.cloudsim.gateway.calls.GatewayService
import dev.cloudsim.gateway.calls.GatewayStatus
import dev.cloudsim.gateway.calls.PortMapping
import dev.cloudsim.gateway.identity.KeystoreSigner
import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.relay.OwnRelays
import dev.cloudsim.gateway.relay.RelayMeasurer
import dev.cloudsim.gateway.relay.RelayProbe
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Measures what [Judge] judges, one item after the other, on the caller's (background) thread: this phone's network,
 * the service, each relay, the direct paths, the audio bridge, the SIMs, what keeps the gateway alive, battery and
 * space. Takes no part in calls: it places none, opens no media, and holds a relay's port only for the moment of the
 * check. [onProgress] gets the list so far after each item, with the one being measured shown as running.
 */
class SelfTestRunner(private val context: Context) {
    fun run(onProgress: (done: List<Check>, running: String?) -> Unit): SelfTestResult {
        val done = mutableListOf<Check>()
        fun step(title: String, check: () -> List<Check>) {
            onProgress(done.toList(), title)
            done += runCatching(check).getOrElse { listOf(Check("error", title, Level.WARN, L.t("没能检查：%1\$s", it.message ?: it.javaClass.simpleName))) }
        }
        val running = GatewayService.isEnabled(context) && GatewayService.liveNumbers != null
        var relays: JSONObject? = null
        var relayError: String? = null

        step(L.t("本机网络")) { listOf(Judge.network(network())) }
        step(L.t("核心服务")) {
            val client = ControlClient(BuildInfo.service, KeystoreSigner())
            var rtt: Long? = null
            var error: String? = null
            // Twice: the first try pays for the connection, the second is the round trip a waiting gateway sees.
            repeat(2) {
                val from = System.nanoTime()
                runCatching { client.call("GET", "/v1/relays", readTimeoutMs = 8_000) }
                    .onSuccess { relays = it; rtt = minOf(rtt ?: Long.MAX_VALUE, (System.nanoTime() - from) / 1_000_000) }
                    .onFailure { e -> if (rtt == null) error = e.message ?: e.javaClass.simpleName }
            }
            // A service from before the relay roster answers this with an error although it is reachable.
            if (rtt == null && error?.contains("NOT_FOUND") == true) rtt = 0
            relayError = error
            val f = ServiceFacts(rtt, error, running, ServiceLink.shared.heardAgoMs(), ServiceLink.shared.drops24h(), ControlClient.lastSkewMs.takeIf { rtt != null })
            listOf(Judge.service(f), Judge.link(f))
        }
        step(L.t("中继（兜底线路）")) {
            val list = relays?.optJSONArray("relays")
            val facts = (0 until (list?.length() ?: 0)).mapNotNull { i -> list?.optJSONObject(i)?.let { relay(it, i + 1) } }
            listOf(Judge.relays(facts, relayError))
        }
        OwnRelays.get(context).takeIf { it.relay != null }?.let { keeper ->
            step(L.t("自建中继")) { keeper.check()?.let { listOf(Judge.ownRelay(it.ok, it.rttMs, it.problem)) } ?: emptyList() }
        }
        step(L.t("在外直连")) {
            val c = CellularPath.current?.condition()
            listOf(Judge.direct(c?.takeIf { running }?.let {
                DirectFacts(it.optBoolean("wifi_v6"), it.optString("mapping").ifEmpty { PortMapping.condition(context) }, it.optString("cellular"))
            }))
        }
        step(L.t("语音通道")) {
            val (status, reason) = when (val s = ShellLauncher.status.value) {
                ShellLauncher.Status.Running -> "running" to null
                ShellLauncher.Status.Started -> "started" to null
                is ShellLauncher.Status.NotRunning -> "not_running" to s.reason
                else -> "unknown" to null
            }
            listOf(Judge.bridge(running, status, reason))
        }
        step(L.t("SIM 卡")) {
            listOf(Judge.lines(GatewayService.liveNumbers?.invoke()?.let(GatewayStatus::shown)?.map { n ->
                val (state, signal) = GatewayStatus.line(context, n)
                LineFacts(n.slot + 1, state, signal)
            }))
        }
        step(L.t("后台保活和权限")) {
            val power = context.getSystemService(PowerManager::class.java)
            listOf(Judge.keep(KeepFacts(power.isIgnoringBatteryOptimizations(context.packageName), power.isPowerSaveMode, Readiness.items(context).count { !it.ok })))
        }
        step(L.t("电量和空间")) {
            val battery = GatewayStatus.battery(context)
            val free = runCatching { StatFs(context.filesDir.path).availableBytes / (1024 * 1024) }.getOrDefault(Long.MAX_VALUE)
            listOf(Judge.device(DeviceFacts(battery?.first, battery?.second ?: false, free)))
        }
        val result = SelfTestResult(System.currentTimeMillis(), done.toList())
        onProgress(result.checks, null)
        SelfTests.save(context, result)
        return result
    }

    private fun network(): NetFacts {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities) ?: return NetFacts("none", false, false, null)
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val host = BuildInfo.service.address.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        // An address needs no looking up.
        val named = host.any { it.isLetter() } && !host.contains(':')
        val dns = if (!named) -1L else {
            val from = System.nanoTime()
            runCatching { InetAddress.getByName(host) }.getOrNull()?.let { (System.nanoTime() - from) / 1_000_000 }
        }
        return NetFacts(transport, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN), dns)
    }

    /** One relay of the roster: over UDP, a round trip and then a real allocation with its credentials; over TCP, whether its port opens. */
    private fun relay(entry: JSONObject, index: Int): RelayFacts? {
        val urls = entry.optJSONArray("urls") ?: return null
        val parsed = (0 until urls.length()).mapNotNull { RelayUrl.parse(urls.optString(it)) }
        val udp = parsed.firstOrNull { !it.tcp }
        val tcp = parsed.firstOrNull { it.tcp }
        if (udp == null && tcp == null) return null
        val rtt = udp?.let { u -> listOfNotNull(RelayProbe.binding(u.host, u.port), RelayProbe.binding(u.host, u.port)).minOrNull() }
        val allocate = if (udp != null && rtt != null) RelayProbe.allocate(udp.host, udp.port, entry.optString("username"), entry.optString("credential")) else null
        val tcpMs = if (tcp == null) -1L else runCatching {
            val from = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress(tcp.host, tcp.port), 3_000) }
            (System.nanoTime() - from) / 1_000_000
        }.getOrNull()
        // The roster's own name for it when it gives one; never the address.
        val name = entry.optString("id").takeIf { it.isNotEmpty() && it.length <= RelayMeasurer.MAX * 2 } ?: L.t("中继 %1\$s", index)
        return RelayFacts(name, rtt, allocate, tcpMs)
    }
}

/** The last finished run, kept across restarts. */
object SelfTests {
    @Volatile private var cached: SelfTestResult? = null
    private val running = java.util.concurrent.atomic.AtomicBoolean()
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "self-test").apply { isDaemon = true } }

    /**
     * For the owner's iPhone, with the gateway's state (the self-test there shows it): the last result, and a new run
     * started behind it when that one is older than [FRESH_MS], so asking again a little later gets today's. Never
     * during a call, and one at a time.
     */
    fun forOwner(context: Context, busy: () -> Boolean): JSONObject? {
        val last = last(context)
        if ((last == null || System.currentTimeMillis() - last.atMs > FRESH_MS) && !busy() && running.compareAndSet(false, true)) {
            worker.execute { try { runCatching { SelfTestRunner(context.applicationContext).run { _, _ -> } } } finally { running.set(false) } }
        }
        // Before the first run there is nothing to show yet, only that one is under way.
        return (last?.json() ?: JSONObject().put("at_ms", 0).put("checks", org.json.JSONArray())).put("running", running.get())
    }

    const val FRESH_MS = 120_000L

    fun save(context: Context, r: SelfTestResult) {
        cached = r
        context.getSharedPreferences("selftest", Context.MODE_PRIVATE).edit().putString("last", r.json().toString()).apply()
    }

    fun last(context: Context): SelfTestResult? = cached ?: runCatching {
        val o = JSONObject(context.getSharedPreferences("selftest", Context.MODE_PRIVATE).getString("last", null) ?: return null)
        val a = o.getJSONArray("checks")
        SelfTestResult(o.getLong("at_ms"), (0 until a.length()).map { i ->
            a.getJSONObject(i).let { Check(it.getString("id"), it.optString("title"), Level.valueOf(it.getString("level").uppercase()), it.getString("detail")) }
        })
    }.getOrNull()?.also { cached = it }
}
