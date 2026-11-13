package dev.cloudsim.gateway

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File

/**
 * How much the gateway keeps (LOG_LEVELS.md), chosen on this phone's own screen, basic unless changed. Basic: the app's
 * log as the system keeps it, a shared ring that busy hours or a restart wash out. Full: the same lines also copied,
 * masked, into this app's own files (two of 1.5 MB, 7 days at most), extra detail lines, and on sending, the last part
 * of the shell audio bridge's log.
 */
object GatewayLog {
    const val TAG = "CloudSimGateway"
    private const val PREFS = "logging"
    private const val KEY = "level"
    const val PART_BYTES = 1_500_000L
    private const val KEEP_MS = 7 * 86_400_000L

    @Volatile private var full = false
    private var reader: Thread? = null
    private var process: java.lang.Process? = null

    fun isFull(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "basic") == "full"

    fun setFull(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, if (on) "full" else "basic").apply()
        Log.i(TAG, "log level ${if (on) "full" else "basic"}")
        if (on) start(context) else stop(context)
    }

    /** Only at the full level; for steps worth having when chasing a problem, not every day. */
    fun detail(message: String) {
        if (full) Log.i(TAG, message)
    }

    /** At the full level, follows this process's own log into the files; started with the service, idempotent. */
    @Synchronized
    fun start(context: Context) {
        full = isFull(context)
        if (!full || reader?.isAlive == true) return
        val dir = dir(context)
        reader = Thread({
            try {
                // This process's lines only: those still in the ring from its start, then each as it comes.
                val p = ProcessBuilder("logcat", "-v", "threadtime", "--pid", Process.myPid().toString()).redirectErrorStream(true).start()
                process = p
                p.inputStream.bufferedReader().useLines { lines ->
                    var out = File(dir, "gateway.log")
                    var size = out.length()
                    var writer = java.io.FileWriter(out, true)
                    for (line in lines) {
                        if (!full) break
                        val masked = Diagnostics.mask(line) + "\n"
                        writer.write(masked)
                        writer.flush()
                        size += masked.length
                        if (size > PART_BYTES) {
                            writer.close()
                            out.renameTo(File(dir, "gateway.1.log"))
                            out = File(dir, "gateway.log")
                            size = 0
                            writer = java.io.FileWriter(out, true)
                        }
                    }
                    writer.close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "log copy stopped: ${e.javaClass.simpleName}")
            }
        }, "gateway-log").apply { isDaemon = true; start() }
        prune(dir)
    }

    @Synchronized
    private fun stop(context: Context) {
        full = false
        process?.destroy()
        process = null
        reader = null
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /** The kept files, oldest first, without parts older than 7 days. */
    fun text(context: Context, maxBytes: Int): String {
        val dir = dir(context)
        prune(dir)
        val all = listOf(File(dir, "gateway.1.log"), File(dir, "gateway.log")).filter { it.exists() }.joinToString("") { it.readText() }
        return if (all.length > maxBytes) all.substring(all.length - maxBytes).substringAfter('\n') else all
    }

    private fun prune(dir: File) {
        val old = File(dir, "gateway.1.log")
        if (old.exists() && System.currentTimeMillis() - old.lastModified() > KEEP_MS) old.delete()
    }

    private fun dir(context: Context) = File(context.filesDir, "logs").apply { mkdirs() }
}
