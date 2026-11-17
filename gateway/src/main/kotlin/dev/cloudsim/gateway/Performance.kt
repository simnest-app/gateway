package dev.cloudsim.gateway

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cloudsim.gateway.adb.LocalAdb
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.lang.ref.WeakReference

/**
 * Full speed when the gateway has heavy work (the user's 2026-11-11 rule, SCREENING_ASSISTANT.md): some phones let an
 * app that is not on screen run on a few small cores only, a foreground service included. On a Mate 40 Pro+ with its
 * screen off the gateway had cores 0-3 of eight, and a line of a dozen characters took 55 s to speak instead of some
 * ten. So whoever needs the processor holds it here; while anyone does and this process is kept off some cores, the
 * gateway lights the screen and shows its own page, which gives it all of them, and lets go a little after the last
 * holder is done. On a phone that does not hold it back nothing shows.
 */
class PerformanceGate(
    /** Whether this process is kept off some of the phone's cores right now. */
    private val limited: () -> Boolean,
    /** Shows the gateway's page with the screen on; called again while still limited. */
    private val front: (reason: String) -> Unit,
    /** Takes the page away. */
    private val back: () -> Unit,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
    /** Looks again by itself every half second while held; off in tests, which step by hand. */
    private val watching: Boolean = true,
) {
    private val holders = LinkedHashMap<Long, Pair<String, Long>>()
    private var next = 0L
    private var idleSince = 0L
    private var shown = false
    private var lastFront: Long? = null
    private var watcher: Thread? = null

    /** Why the processor is wanted now, the newest first; empty when nobody holds it. */
    fun reasons(): List<String> = synchronized(this) { holders.values.map { it.first }.reversed().distinct() }

    /**
     * Holds the processor for [reason] until closed. With [wait], returns once every core is this process's, or after
     * [WAIT_MS] at most: work started a moment late beats work done on the small cores.
     */
    fun hold(reason: String, wait: Boolean = true): AutoCloseable {
        val id = synchronized(this) {
            holders[next] = reason to now()
            next++
        }
        step()
        watch()
        if (wait) {
            val until = now() + WAIT_MS
            while (limited() && now() < until) sleep(200)
            if (limited()) log("full speed for $reason: still on a part of the cores")
        }
        return AutoCloseable { synchronized(this) { if (holders.remove(id) != null && holders.isEmpty()) idleSince = now() } }
    }

    /** One look: bring the page up while someone holds and the cores are limited; take it away once nobody has for a while. */
    fun step(): Boolean = synchronized(this) {
        // A holder that never let go (a call that ended without a word) does not keep the screen on for ever.
        holders.entries.removeAll { now() - it.value.second > MAX_HOLD_MS }.also { if (it && holders.isEmpty()) idleSince = now() }
        if (holders.isNotEmpty()) {
            if (limited() && (lastFront?.let { now() - it >= RETRY_MS } ?: true)) {
                lastFront = now()
                if (!shown) log("full speed for ${holders.values.last().first}: bringing the gateway to the front")
                shown = true
                front(holders.values.last().first)
            }
            true
        } else {
            if (shown && now() - idleSince >= LINGER_MS) {
                shown = false
                lastFront = null
                back()
                log("full speed no longer needed")
            }
            shown
        }
    }

    private fun watch() = synchronized(this) {
        if (!watching || watcher?.isAlive == true) return@synchronized
        watcher = Thread({
            while (step()) sleep(500)
        }, "performance").apply { isDaemon = true; start() }
    }

    companion object {
        const val WAIT_MS = 5_000L
        /** Another try at showing the page when the first did not give the cores. */
        const val RETRY_MS = 4_000L
        /** Between two lines of a set the page stays, instead of going and coming back. */
        const val LINGER_MS = 3_000L
        const val MAX_HOLD_MS = 20 * 60_000L

        /** How many cores a list like "0-3,6" names. */
        fun count(list: String): Int = list.trim().split(',').filter { it.isNotBlank() }.sumOf { part ->
            val ends = part.trim().split('-')
            val a = ends.first().toIntOrNull() ?: return@sumOf 0
            val b = ends.last().toIntOrNull() ?: return@sumOf 0
            if (b >= a) b - a + 1 else 0
        }

        /** From /proc/self/status and /sys/devices/system/cpu/possible; false when either cannot be read. */
        fun limited(status: String?, possible: String?): Boolean {
            val allowed = status?.lineSequence()?.firstOrNull { it.startsWith("Cpus_allowed_list:") }?.substringAfter(':')?.let(::count) ?: return false
            val all = possible?.let(::count) ?: return false
            return allowed in 1 until all
        }
    }
}

/** The gateway's own gate: what limits it is read from the system, and the page is [PerformanceActivity]. */
object Performance {
    private const val TAG = "CloudSimGateway"
    @Volatile private var app: Context? = null
    @Volatile internal var page: WeakReference<PerformanceActivity>? = null

    val gate = PerformanceGate(
        limited = ::limited,
        front = { reason -> app?.let { show(it, reason) } },
        back = { page?.get()?.let { a -> a.runOnUiThread { a.finish() } } },
        log = { Log.i(TAG, it) },
    )

    fun limited(): Boolean = PerformanceGate.limited(
        runCatching { File("/proc/self/status").readText() }.getOrNull(),
        runCatching { File("/sys/devices/system/cpu/possible").readText() }.getOrNull())

    /** Holds the processor for [reason] ("speech", "transcribe"); close it when the work is done. */
    fun hold(context: Context, reason: String, wait: Boolean = true): AutoCloseable {
        app = context.applicationContext
        return gate.hold(reason, wait)
    }

    /**
     * Full speed for call [callId] from its ring to its end (the user's 2026-11-16 decision, ANSWER_DELAY.md 14.4),
     * without waiting for the cores. Closing it lets go and logs one `call_perf` line: the cores this process had, how
     * long it was held back, and how busy the processor and the app were, to tell a slow network from a slow phone.
     */
    fun callHold(context: Context, callId: String): AutoCloseable {
        val held = hold(context, CALL, wait = false)
        val perf = CallPerf(read = { runCatching { File(it).readText() }.getOrNull() })
        val sampler = Thread({
            try { while (true) { Thread.sleep(1_000); perf.sample() } } catch (_: InterruptedException) {}
        }, "call-perf").apply { isDaemon = true; start() }
        var closed = false
        return AutoCloseable {
            synchronized(perf) { if (closed) return@AutoCloseable; closed = true }
            sampler.interrupt()
            held.close()
            Log.i(TAG, perf.line(callId.take(8)))
        }
    }

    const val CALL = "call"

    /**
     * The page over whatever is on screen, lock screen included. First the plain way; where the system keeps an app
     * from bringing up a screen by itself, through this phone's own debugging, as the audio bridge is started.
     */
    private fun show(context: Context, reason: String) {
        // During a call nothing may cover the call screen (ANSWER_DELAY.md 14.4): as the default phone app the
        // gateway's own call screen is brought up instead, which puts this app in front just the same; otherwise the
        // system's call screen is in front and stays there.
        if (CALL in gate.reasons()) return showCall(context)
        val intent = Intent(context, PerformanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { context.startActivity(intent) }.onFailure { Log.i(TAG, "full speed: the page could not be shown directly: ${it.javaClass.simpleName}") }
        Thread({
            Thread.sleep(1_500)
            if (!limited() || gate.reasons().isEmpty()) return@Thread
            runCatching {
                runBlocking {
                    withTimeout(8_000) {
                        LocalAdb(context).shell("input keyevent KEYCODE_WAKEUP; am start -n ${context.packageName}/${PerformanceActivity::class.java.name}")
                    }
                }
            }.onFailure { Log.i(TAG, "full speed: not through the shell either: ${it.message}") }
        }, "performance-front").apply { isDaemon = true; start() }
    }

    @Volatile private var toldNoScreen = 0L

    private fun showCall(context: Context) {
        if (!dev.cloudsim.gateway.calls.CallHub.isDefault(context)) {
            // Asked again every few seconds while held back: said once a minute.
            if (System.currentTimeMillis() - toldNoScreen > 60_000) {
                toldNoScreen = System.currentTimeMillis()
                Log.i(TAG, "full speed for a call: the system's call screen is in front, nothing shown over it")
            }
            return
        }
        runCatching { context.startActivity(Intent(context, dev.cloudsim.gateway.calls.InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.i(TAG, "full speed: the call screen could not be shown directly: ${it.javaClass.simpleName}") }
        // The call screen shows over the lock screen and turns it on itself; where the screen stays off, it is woken.
        Thread({
            Thread.sleep(1_500)
            if (!limited() || CALL !in gate.reasons()) return@Thread
            runCatching { runBlocking { withTimeout(8_000) { LocalAdb(context).shell("input keyevent KEYCODE_WAKEUP") } } }
                .onFailure { Log.i(TAG, "full speed: could not wake the screen: ${it.message}") }
        }, "performance-front").apply { isDaemon = true; start() }
    }

    fun text(reason: String): String = when (reason) {
        "speech" -> L.t("正在用主人的声音合成要说的话")
        "transcribe" -> L.t("正在把对方说的话转成文字")
        else -> L.t("正在处理")
    }
}

/**
 * How one call went for the processor (ANSWER_DELAY.md 14.4): sampled every second from the ring to the end, logged
 * as one line. The cores this process may use (`Cpus_allowed_list`), how long it had fewer than all, the share of time
 * the whole processor was busy (/proc/stat) and the share this app took (/proc/self/stat, all cores counted as one).
 * The audio bridge is a process of its own and not in the app's share. [read] gives a file's text, null if unreadable.
 */
class CallPerf(private val read: (String) -> String?, private val now: () -> Long = System::currentTimeMillis) {
    private val startedAt = now()
    private val all = read("/sys/devices/system/cpu/possible")?.let(PerformanceGate::count) ?: 0
    private val firstCores = cores()
    private var minCores = firstCores
    private var lastCores = firstCores
    private var limitedMs = 0L
    private var lastSample = startedAt
    private val cpuStart = read("/proc/stat")?.let(::cpu)
    private val appStart = read("/proc/self/stat")?.let(::appTicks)

    private fun cores(): Int = read("/proc/self/status")?.lineSequence()?.firstOrNull { it.startsWith("Cpus_allowed_list:") }
        ?.substringAfter(':')?.let(PerformanceGate::count) ?: 0

    @Synchronized fun sample() {
        val c = cores()
        val t = now()
        if (lastCores in 1 until all) limitedMs += t - lastSample
        lastSample = t
        lastCores = c
        if (c in 1 until minCores) minCores = c
    }

    @Synchronized fun line(call: String): String {
        sample()
        val ms = now() - startedAt
        val cpuEnd = read("/proc/stat")?.let(::cpu)
        val busy = if (cpuStart != null && cpuEnd != null) busyPercent(cpuStart, cpuEnd) else null
        val appEnd = read("/proc/self/stat")?.let(::appTicks)
        val total = if (cpuStart != null && cpuEnd != null) cpuEnd.second - cpuStart.second else 0L
        // The app's ticks against the whole processor's, as a share of one core's time: total ticks / cores.
        val app = if (appStart != null && appEnd != null && total > 0 && all > 0) (appEnd - appStart) * 100.0 * all / total else null
        return "call_perf call=$call ms=$ms cores=$firstCores/$minCores/$lastCores of $all limited_ms=$limitedMs" +
            " cpu_busy=${busy?.let { "%.0f%%".format(java.util.Locale.ROOT, it) } ?: "?"}" +
            " app_cpu=${app?.let { "%.0f%%".format(java.util.Locale.ROOT, it) } ?: "?"}"
    }

    companion object {
        /** From /proc/stat's first line: (busy ticks, all ticks). Idle and iowait count as not busy. */
        fun cpu(stat: String): Pair<Long, Long>? {
            val f = stat.lineSequence().firstOrNull { it.startsWith("cpu ") }?.trim()?.split(Regex("\\s+"))?.drop(1)?.mapNotNull { it.toLongOrNull() } ?: return null
            if (f.size < 4) return null
            val total = f.take(8).sum()
            val idle = f[3] + (f.getOrNull(4) ?: 0)
            return (total - idle) to total
        }

        fun busyPercent(a: Pair<Long, Long>, b: Pair<Long, Long>): Double? {
            val total = b.second - a.second
            return if (total <= 0) null else (b.first - a.first) * 100.0 / total
        }

        /** utime + stime of this process from /proc/self/stat (fields 14 and 15; the name in parentheses may hold spaces). */
        fun appTicks(stat: String): Long? {
            val rest = stat.substringAfterLast(')').trim().split(' ')
            return (rest.getOrNull(11)?.toLongOrNull() ?: return null) + (rest.getOrNull(12)?.toLongOrNull() ?: return null)
        }
    }
}

/** Shown while the gateway needs every core: over the lock screen, the screen kept on; goes by itself. */
class PerformanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Performance.page = WeakReference(this)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.CenterVertically)) {
                        Text(L.t("SimNest 网关 · 全速工作中"), style = MaterialTheme.typography.titleLarge)
                        Text(Performance.gate.reasons().firstOrNull()?.let(Performance::text) ?: L.t("正在处理"), style = MaterialTheme.typography.bodyLarge)
                        Text(L.t("屏幕熄灭或网关不在前台时，这台手机只让它用一部分处理器，会慢好几倍。所以需要用力时网关会自己点亮屏幕，做完自动收起，不用管它。"),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Brought up when nothing needs it any more (a late start, or opened by hand): nothing to show.
        if (Performance.gate.reasons().isEmpty()) finish()
    }

    override fun onDestroy() {
        if (Performance.page?.get() === this) Performance.page = null
        super.onDestroy()
    }
}
