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
     * The page over whatever is on screen, lock screen included. First the plain way; where the system keeps an app
     * from bringing up a screen by itself, through this phone's own debugging, as the audio bridge is started.
     */
    private fun show(context: Context, reason: String) {
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

    fun text(reason: String): String = when (reason) {
        "speech" -> L.t("正在用主人的声音合成要说的话")
        "transcribe" -> L.t("正在把对方说的话转成文字")
        else -> L.t("正在处理")
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
