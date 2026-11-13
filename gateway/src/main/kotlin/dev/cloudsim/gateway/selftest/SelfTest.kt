package dev.cloudsim.gateway.selftest

import dev.cloudsim.gateway.L
import org.json.JSONArray
import org.json.JSONObject

/**
 * The gateway's own self-test (the user's 2026-11-13 decision): what this phone can find out about itself without an
 * iPhone, said in a line each. This file is the part that knows nothing of Android: what was measured comes in as
 * plain facts, and each function here says what they mean for calls. The measuring is in [SelfTestRunner].
 */
enum class Level { OK, WARN, FAIL, SKIP }

/** One line of the result. [id] is for the log; [title] and [detail] are in this phone's language. */
data class Check(val id: String, val title: String, val level: Level, val detail: String) {
    /** For the diagnostics log: the id and level only say enough with the detail in Chinese as shown. */
    fun json(): JSONObject = JSONObject().put("id", id).put("title", title).put("level", level.name.lowercase()).put("detail", detail)
}

data class NetFacts(
    /** "wifi", "cellular", "ethernet", "other" or "none". */
    val transport: String,
    /** The system reached the internet over it. */
    val validated: Boolean,
    val vpn: Boolean,
    /** Looking the service's name up: the time it took, null when it failed; -1 when the service is named by address. */
    val dnsMs: Long?,
)

data class ServiceFacts(
    /** A signed request's round trip, null when it failed. */
    val rttMs: Long?,
    val error: String?,
    /** Whether the gateway is switched on: the lasting connection only exists then. */
    val running: Boolean,
    /** How long ago the lasting connection last heard from the service; null when never. */
    val heardAgoMs: Long?,
    /** How many times it lost the service in the last 24 hours. */
    val drops: Int,
    /** This phone's clock ahead of the service's (behind when negative); null when not known. */
    val skewMs: Long? = null,
)

data class RelayFacts(
    val name: String,
    /** The round trip to it over UDP; null when it did not answer. */
    val udpMs: Long?,
    /** null when it handed out a relayed address, else why not ([dev.cloudsim.gateway.relay.RelayProbe.allocate]). */
    val allocate: String?,
    /** Whether it can be reached over TCP at all: the time to connect, null when not; -1 when it offers none. */
    val tcpMs: Long?,
)

data class DirectFacts(val wifiV6: Boolean, val mapping: String, val cellular: String)

data class LineFacts(val slot: Int, val state: String, val signal: Int?)

data class KeepFacts(
    val batteryExempt: Boolean, val powerSave: Boolean, val broken: Int,
    /** Times the system stopped this app's process in the last 24 hours, the last one's age and length; see [FreezeWatch]. */
    val freezes: Int = 0, val lastFreezeAgoMs: Long? = null, val lastFreezeMs: Long = 0,
    /** A Huawei or Honor phone: its own list of apps allowed in the background is where this is set. */
    val huawei: Boolean = false,
)

data class DeviceFacts(val battery: Int?, val charging: Boolean, val freeMb: Long)

object Judge {
    fun network(f: NetFacts): Check {
        val name = when (f.transport) {
            "wifi" -> "Wi-Fi"
            "cellular" -> L.t("移动数据")
            "ethernet" -> L.t("有线网络")
            "none" -> ""
            else -> L.t("其他网络")
        }
        val title = L.t("本机网络")
        if (f.transport == "none") return Check("network", title, Level.FAIL, L.t("没有网络：来电和短信转不出去"))
        if (!f.validated) return Check("network", title, Level.FAIL, L.t("%1\$s 已连接，但上不了网", name))
        if (f.dnsMs == null) return Check("network", title, Level.FAIL, L.t("%1\$s 能上网，但查不到服务的地址（DNS 有问题）", name))
        val notes = mutableListOf(name)
        if (f.dnsMs >= 0) notes += L.t("域名解析 %1\$s ms", f.dnsMs)
        if (f.vpn) return Check("network", title, Level.WARN, notes.joinToString(" · ") + L.t("；开着 VPN，通话可能绕远或连不上，建议在网关上关掉"))
        if (f.transport == "cellular") return Check("network", title, Level.WARN, notes.joinToString(" · ") + L.t("；没连 Wi-Fi，通话和短信都走这张卡的流量"))
        return Check("network", title, Level.OK, notes.joinToString(" · "))
    }

    fun service(f: ServiceFacts): Check {
        val title = L.t("核心服务")
        if (f.rttMs == null) return Check("service", title, Level.FAIL, L.t("连不上：%1\$s", f.error ?: L.t("没有回应")))
        val rtt = L.t("往返 %1\$s ms", f.rttMs)
        // Call times and what the phones are told to do by time go wrong with a clock far off (2026-11-11: an iPhone 12 s fast had silent calls).
        if (f.skewMs != null && kotlin.math.abs(f.skewMs) > MAX_SKEW_MS) {
            val s = kotlin.math.abs(f.skewMs) / 1000
            return Check("service", title, Level.WARN, rtt + (if (f.skewMs > 0) L.t("；这台手机的时间快了 %1\$s 秒，请在系统设置里打开自动校时", s) else L.t("；这台手机的时间慢了 %1\$s 秒，请在系统设置里打开自动校时", s)))
        }
        return if (f.rttMs > SLOW_SERVICE_MS) Check("service", title, Level.WARN, rtt + L.t("，偏慢：来电响铃会晚一点")) else Check("service", title, Level.OK, rtt)
    }

    fun link(f: ServiceFacts): Check {
        val title = L.t("和服务的长连接")
        if (!f.running) return Check("link", title, Level.SKIP, L.t("网关没开启"))
        val drops = if (f.drops == 0) L.t("过去 24 小时没断过") else L.t("过去 24 小时断过 %1\$s 次", f.drops)
        if (f.heardAgoMs == null || f.heardAgoMs > STALE_LINK_MS) return Check("link", title, Level.FAIL, L.t("现在断着：来电通知发不出去") + "；" + drops)
        return Check("link", title, if (f.drops > MANY_DROPS) Level.WARN else Level.OK, L.t("在线") + "；" + drops)
    }

    /** One line for the relays the service names; they are the fallback every call can take. */
    fun relays(list: List<RelayFacts>, error: String?): Check {
        val title = L.t("中继（兜底线路）")
        if (list.isEmpty()) return Check("relays", title, Level.FAIL, error?.let { L.t("拿不到中继：%1\$s", it) } ?: L.t("服务没有给中继"))
        val lines = list.map { r ->
            val udp = when {
                r.udpMs == null -> L.t("UDP 不通")
                r.allocate == null -> L.t("UDP 可用 %1\$s ms", r.udpMs)
                else -> L.t("UDP 有回应但用不了（%1\$s）", r.allocate)
            }
            val tcp = when {
                r.tcpMs == null -> L.t("TCP 不通")
                r.tcpMs < 0 -> null
                else -> L.t("TCP 可连 %1\$s ms", r.tcpMs)
            }
            "· ${r.name}：" + listOfNotNull(udp, tcp).joinToString("，")
        }
        val udpOk = list.count { it.udpMs != null && it.allocate == null }
        val tcpOk = list.count { it.tcpMs != null && it.tcpMs >= 0 }
        val (level, head) = when {
            udpOk == list.size -> Level.OK to L.t("%1\$s 个中继都可用", list.size)
            udpOk > 0 -> Level.WARN to L.t("%1\$s 个中继里 %2\$s 个可用", list.size, udpOk)
            tcpOk > 0 -> Level.WARN to L.t("UDP 中继都用不了，只剩 TCP：直连不通时声音可能稍慢")
            else -> Level.FAIL to L.t("中继都连不上：直连不通时，通话会打不通")
        }
        return Check("relays", title, level, (listOf(head) + lines).joinToString("\n"))
    }

    /** The owner's own relay; no line without one. */
    fun ownRelay(ok: Boolean, rttMs: Long?, problem: String?): Check {
        val title = L.t("自建中继")
        return if (ok) Check("own_relay", title, Level.OK, rttMs?.let { L.t("可用 %1\$s ms", it) } ?: L.t("可用"))
        else Check("own_relay", title, Level.WARN, L.t("用不了（%1\$s）：通话改用官方中继", problem ?: "no_answer"))
    }

    fun direct(f: DirectFacts?): Check {
        val title = L.t("在外直连")
        if (f == null) return Check("direct", title, Level.SKIP, L.t("网关没开启"))
        val via = when {
            f.wifiV6 -> L.t("家里 Wi-Fi 的 IPv6")
            f.mapping == "mapped" -> L.t("路由器开放的端口")
            f.cellular == "ready" || f.cellular == "standby" -> L.t("这台手机的流量")
            else -> null
        }
        return if (via != null) Check("direct", title, Level.OK, L.t("iPhone 在外面能直连，经%1\$s", via))
        else Check("direct", title, Level.WARN, L.t("iPhone 在外面时通话经中继：照样能打，延迟稍高"))
    }

    fun bridge(running: Boolean, status: String, reason: String?): Check {
        val title = L.t("语音通道")
        if (!running) return Check("bridge", title, Level.SKIP, L.t("网关没开启"))
        return when (status) {
            "running" -> Check("bridge", title, Level.OK, L.t("音频服务在运行"))
            "started" -> Check("bridge", title, Level.WARN, L.t("音频服务刚启动，还没连上；过一会儿再测"))
            "not_running" -> Check("bridge", title, Level.FAIL, L.t("音频服务没在运行：通话会没有声音。%1\$s", reason.orEmpty()))
            else -> Check("bridge", title, Level.WARN, L.t("还不知道音频服务的状态；过一会儿再测"))
        }
    }

    fun lines(list: List<LineFacts>?): Check {
        val title = L.t("SIM 卡")
        if (list == null) return Check("sim", title, Level.SKIP, L.t("网关没开启"))
        if (list.isEmpty()) return Check("sim", title, Level.FAIL, L.t("没有读到 SIM 卡"))
        val text = list.joinToString("\n") { l ->
            val state = when (l.state) {
                "in_service" -> L.t("有服务") + (l.signal?.let { L.t("，信号 %1\$s/4", it) } ?: "")
                "no_sim" -> L.t("没插卡，已停用")
                "no_service" -> L.t("无服务")
                "emergency_only" -> L.t("只能拨紧急电话（卡可能停机或欠费）")
                "radio_off" -> L.t("飞行模式或卡被关闭")
                else -> L.t("状态未知")
            }
            L.t("· 卡 %1\$s：", l.slot) + state
        }
        val inService = list.count { it.state == "in_service" }
        // A slot with no card in it is stopped on purpose (2026-11-11) and is not a problem.
        val bad = list.count { it.state != "in_service" && it.state != "no_sim" }
        // Only none at all: Android's own scale is stricter than the bars a phone draws (a Redmi at -99 dBm on 5G
        // shows nearly full bars and reports 1 of 4, 2026-11-13), so a low number alone is not a warning.
        val weak = list.any { it.state == "in_service" && it.signal == 0 }
        val level = when {
            inService == 0 -> Level.FAIL
            bad > 0 || weak -> Level.WARN
            else -> Level.OK
        }
        return Check("sim", title, level, text)
    }

    fun keep(f: KeepFacts): Check {
        val title = L.t("后台保活和权限")
        val notes = mutableListOf<String>()
        if (f.freezes > 0) {
            val ago = (f.lastFreezeAgoMs ?: 0) / 60_000
            notes += L.t("系统在过去 24 小时冻结过网关 %1\$s 次（最近一次 %2\$s 分钟前，停了 %3\$s 秒）。冻结期间来电和短信转不出去", f.freezes, ago, f.lastFreezeMs / 1000) +
                if (f.huawei) L.t("。请到“应用启动管理”里找到网关，关掉“自动管理”，把“允许自启动”“允许关联启动”“允许后台活动”都打开")
                else L.t("。请在系统的电池和后台设置里允许网关自启动和后台运行")
        }
        if (f.broken > 0) notes += L.t("%1\$s 项功能用不了，见“权限与功能”", f.broken)
        if (!f.batteryExempt) notes += L.t("系统可能为省电停掉网关：把网关的电池优化设为“不限制”")
        if (f.powerSave) notes += L.t("开着省电模式，网关可能被限速或断网")
        return if (notes.isEmpty()) Check("keep", title, Level.OK, L.t("正常")) else Check("keep", title, if (f.broken > 0 || f.freezes > 0) Level.FAIL else Level.WARN, notes.joinToString("；"))
    }

    fun device(f: DeviceFacts): Check {
        val title = L.t("电量和空间")
        val power = f.battery?.let { L.t("电量 %1\$s%%", it) + if (f.charging) L.t("，在充电") else L.t("，没在充电") } ?: L.t("电量未知")
        val space = if (f.freeMb >= 1024) L.t("剩余空间 %1\$s GB", f.freeMb / 1024) else L.t("剩余空间 %1\$s MB", f.freeMb)
        val level = when {
            f.freeMb < LOW_SPACE_MB || (f.battery != null && f.battery < 10 && !f.charging) -> Level.FAIL
            f.freeMb < 1024 || !f.charging -> Level.WARN
            else -> Level.OK
        }
        val advice = when {
            f.freeMb < LOW_SPACE_MB -> L.t("；空间快满了，录音和短信可能存不下")
            !f.charging -> L.t("；网关要一直插着电")
            else -> ""
        }
        return Check("device", title, level, "$power · $space$advice")
    }

    const val SLOW_SERVICE_MS = 1500L
    const val MAX_SKEW_MS = 5_000L
    /** A long poll answers within 35 s even when nothing happens; twice that without a word is a dead connection. */
    const val STALE_LINK_MS = 75_000L
    const val MANY_DROPS = 20
    const val LOW_SPACE_MB = 200L
}

/** A finished run, kept for the home screen and sent along with the diagnostics log. */
data class SelfTestResult(val atMs: Long, val checks: List<Check>) {
    val worst: Level get() = checks.map { it.level }.filter { it != Level.SKIP }.maxByOrNull { it.ordinal } ?: Level.OK
    fun count(level: Level) = checks.count { it.level == level }

    fun json(): JSONObject = JSONObject().put("at_ms", atMs).put("checks", JSONArray(checks.map { it.json() }))

    /** One line a check for the log; never an address or a number. */
    fun text(): String = checks.joinToString("\n") { c ->
        val mark = when (c.level) { Level.OK -> "ok"; Level.WARN -> "warn"; Level.FAIL -> "FAIL"; Level.SKIP -> "skip" }
        "$mark ${c.id}: ${c.detail.replace("\n", " ")}"
    }
}

/**
 * How the lasting connection to the service has held (the gateway's long poll): when it last heard from the service
 * and how often it lost it. Kept in memory: a gateway restarted starts counting again.
 */
class ServiceLink(private val now: () -> Long = System::currentTimeMillis) {
    private var heardAt = 0L
    private var down = false
    private val drops = ArrayDeque<Long>()

    @Synchronized fun heard() { heardAt = now(); down = false }

    /** The connection is being made (the gateway switched on): the first answer may take as long as a quiet wait does. */
    @Synchronized fun began() { heardAt = now(); down = false }

    /** Counted once for each time it goes down, not for every failed try while it stays down. */
    @Synchronized fun failed() {
        if (down) return
        down = true
        drops.addLast(now())
        while (drops.size > KEEP) drops.removeFirst()
    }

    @Synchronized fun heardAgoMs(): Long? = if (heardAt == 0L) null else now() - heardAt
    @Synchronized fun drops24h(): Int { val from = now() - DAY_MS; return drops.count { it >= from } }

    companion object {
        const val DAY_MS = 24 * 3600_000L
        const val KEEP = 1000
        val shared = ServiceLink()
    }
}

/** "turn:host:port?transport=tcp" apart; null for anything else (TLS relays are not probed). */
data class RelayUrl(val host: String, val port: Int, val tcp: Boolean) {
    companion object {
        fun parse(url: String): RelayUrl? {
            if (!url.startsWith("turn:")) return null
            val rest = url.removePrefix("turn:")
            val address = rest.substringBefore('?')
            val tcp = rest.substringAfter('?', "").split('&').any { it.equals("transport=tcp", ignoreCase = true) }
            val at = address.lastIndexOf(':')
            val host = (if (at > 0 && !address.endsWith("]")) address.substring(0, at) else address).removePrefix("[").removeSuffix("]")
            val port = if (at > 0 && !address.endsWith("]")) address.substring(at + 1).toIntOrNull() ?: return null else 3478
            if (host.isEmpty() || port !in 1..65535) return null
            return RelayUrl(host, port, tcp)
        }
    }
}

/**
 * Notices when the system has stopped this app's process although the phone itself kept running: what Huawei's power
 * manager does to an app a few seconds after the screen goes off (frozen and cut off the network, 2026-11-12 and
 * 2026-11-13), and nothing in Android's own settings shows it. A tick is expected every few seconds; one that comes
 * much later, by the clock that only counts while the processor runs, means this process was not let run in between.
 * A phone asleep stops that clock too, so sleeping is not taken for freezing.
 */
class FreezeWatch(private val wall: () -> Long = System::currentTimeMillis, private val keep: Int = 200) {
    /** One stop: when it ended (wall clock) and how long it lasted. */
    data class Freeze(val atMs: Long, val lastedMs: Long)

    private var lastUptime = -1L
    private val list = ArrayDeque<Freeze>()

    /** Called every [TICK_MS] with the processor's running time; true when this tick shows a stop. */
    @Synchronized
    fun tick(uptimeMs: Long): Boolean {
        val gap = if (lastUptime < 0) 0 else uptimeMs - lastUptime
        lastUptime = uptimeMs
        if (gap < STOPPED_MS) return false
        list.addLast(Freeze(wall(), gap))
        while (list.size > keep) list.removeFirst()
        return true
    }

    @Synchronized fun last24h(): List<Freeze> { val from = wall() - ServiceLink.DAY_MS; return list.filter { it.atMs >= from } }

    @Synchronized fun restore(saved: List<Freeze>) { list.clear(); list.addAll(saved.takeLast(keep)) }
    @Synchronized fun all(): List<Freeze> = list.toList()

    companion object {
        const val TICK_MS = 5_000L
        /** Six ticks missed in a row: a pause in ordinary running does not last that long. */
        const val STOPPED_MS = 30_000L
        val shared = FreezeWatch()
    }
}
