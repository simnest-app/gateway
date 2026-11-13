package dev.cloudsim.gateway.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import android.util.Log
import dev.cloudsim.gateway.BuildConfig
import dev.cloudsim.gateway.L
import dev.cloudsim.gateway.adb.LocalAdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The gateway updating itself (docs/architecture/GATEWAY_UPDATE.md, the user's 2026-11-13 decision): looks for a newer
 * release on GitHub and Gitee, downloads it, checks that it is this app signed with this app's key, and installs it
 * over this phone's own shell (the one the audio bridge is started with), so nobody has to be at the phone. By
 * default it only says a new version is there and waits for a tap; "自动更新" lets it install by itself when no call
 * is on. Never during a call.
 */
object Updater {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        data class Installing(val release: Release) : State
        data class Failed(val message: String, val release: Release? = null) : State
    }

    private const val TAG = "CloudSimGateway"
    private val state = MutableStateFlow<State>(State.Idle)
    val current: StateFlow<State> get() = state
    private val busy = AtomicBoolean()
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "update").apply { isDaemon = true } }
    @Volatile private var scheduled = false

    fun auto(context: Context) = prefs(context).getBoolean("auto", false)
    fun setAuto(context: Context, on: Boolean) = prefs(context).edit().putBoolean("auto", on).apply()
    private fun prefs(context: Context) = context.getSharedPreferences("update", Context.MODE_PRIVATE)

    /** Whether any place to look is set in this build. */
    val configured: Boolean get() = sources().isNotEmpty()

    private fun sources() = listOf("github" to BuildConfig.UPDATE_GITHUB, "gitee" to BuildConfig.UPDATE_GITEE)
        .mapNotNull { (s, repo) -> Releases.latestUrl(s, repo)?.let { s to it } }

    /** While the gateway runs: a look twice a day, and with "自动更新" on, the install as well. */
    fun schedule(context: Context) {
        if (scheduled || !configured) return
        scheduled = true
        val app = context.applicationContext
        worker.scheduleWithFixedDelay({
            runCatching {
                val found = check(app) ?: return@runCatching
                if (auto(app)) install(app, found)
            }.onFailure { Log.w(TAG, "update check failed: ${it.message}") }
        }, 10, 12 * 60, TimeUnit.MINUTES)
    }

    /** Asks both sites; blocking. The newer release, or null when there is none (the state says which). */
    fun check(context: Context): Release? {
        if (!busy.compareAndSet(false, true)) return (state.value as? State.Available)?.release
        try {
            state.value = State.Checking
            var reached = false
            var problem: String? = null
            val found = sources().mapNotNull { (source, url) ->
                runCatching { get(url) }.onFailure { problem = it.message }.getOrNull()?.let { (status, text) ->
                    // 404: the repository has published nothing yet, which is an answer.
                    if (status == 200 || status == 404) reached = true else problem = "HTTP $status"
                    if (status == 200) runCatching { Releases.parse(source, JSONObject(text)) }.getOrNull() else null
                }
            }
            val newest = Releases.newest(found, BuildConfig.VERSION_CODE)
            state.value = when {
                newest != null -> State.Available(newest)
                reached -> State.UpToDate
                else -> State.Failed(L.t("查不了更新：%1\$s", problem ?: L.t("没有回应")))
            }
            Log.i(TAG, "update check: ${newest?.let { "${it.versionName} (${it.versionCode}) on ${it.source}" } ?: if (reached) "up to date" else "unreachable"}")
            return newest
        } finally {
            busy.set(false)
        }
    }

    /** Downloads, checks and installs [release]; blocking. On success this process ends with the old version. */
    fun install(context: Context, release: Release) {
        if (!busy.compareAndSet(false, true)) return
        try {
            if (inCall(context)) return fail(L.t("正在通话，挂断后再更新"), release)
            state.value = State.Downloading(release, 0)
            val file = runCatching { download(context, release) }.getOrElse { return fail(L.t("下载没成功：%1\$s", it.message ?: it.javaClass.simpleName), release) }
            verify(context, file)?.let { why ->
                file.delete()
                return fail(L.t("下载到的安装包不能用：%1\$s", why), release)
            }
            if (inCall(context)) return fail(L.t("正在通话，挂断后再更新"), release)
            state.value = State.Installing(release)
            Log.i(TAG, "installing ${release.versionName} (${release.versionCode}) from ${release.source}")
            // Through the shell's stdin: the package service cannot read this app's own files by path.
            val out = runCatching {
                runBlocking(Dispatchers.IO) { LocalAdb(context).shell("cat '${file.absolutePath}' | pm install -r -S ${file.length()} 2>&1") }
            }.getOrElse { return fail(L.t("装不上：%1\$s", it.message ?: it.javaClass.simpleName), release) }
            // Reached only when the install did not replace this process.
            if (!out.contains("Success")) fail(L.t("装不上：%1\$s", out.trim().takeLast(200)), release)
        } finally {
            busy.set(false)
        }
    }

    private fun fail(message: String, release: Release?) {
        Log.w(TAG, "update: $message")
        state.value = State.Failed(message, release)
    }

    private fun inCall(context: Context) = runCatching {
        context.getSystemService(TelephonyManager::class.java).callState != TelephonyManager.CALL_STATE_IDLE
    }.getOrDefault(false)

    /** Null when [file] is this app, newer, and signed with the key this app is signed with; else why not. */
    fun verify(context: Context, file: File): String? {
        val pm = context.packageManager
        val theirs = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: return L.t("不是安装包")
        val mine = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (theirs.packageName != context.packageName) return L.t("不是这个应用")
        if (theirs.longVersionCode <= mine.longVersionCode) return L.t("版本不比现在的新")
        val a = signers(theirs)
        if (a.isEmpty() || a != signers(mine)) return L.t("签名和现在的不一样")
        return null
    }

    private fun signers(p: PackageInfo): Set<String> =
        p.signingInfo?.apkContentsSigners.orEmpty().map { java.util.Base64.getEncoder().encodeToString(it.toByteArray()) }.toSet()

    /** Into this app's own external files, where the shell can read it; older downloads are removed first. */
    private fun download(context: Context, release: Release): File {
        val dir = File(context.getExternalFilesDir(null), "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "simnest-gateway-${release.versionCode}.apk")
        val c = open(release.url, "application/octet-stream")
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: release.size
            if (total > Releases.MAX_BYTES) error("too large")
            var done = 0L
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        done += n
                        if (done > Releases.MAX_BYTES) error("too large")
                        out.write(buffer, 0, n)
                        if (total > 0) state.value = State.Downloading(release, (done * 100 / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            return file
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    private fun get(url: String): Pair<Int, String> {
        val c = open(url, "application/json")
        try {
            val status = c.responseCode
            val text = (if (status < 400) c.inputStream else c.errorStream)?.use { s -> String(s.readNBytes(512 * 1024)) }.orEmpty()
            return status to text
        } finally {
            c.disconnect()
        }
    }

    /** Follows redirects by hand, so each hop is checked to be one of the two sites over HTTPS. */
    private fun open(url: String, accept: String): HttpURLConnection {
        var at = url
        repeat(6) {
            if (!Releases.allowed(at)) error("not an allowed address")
            val c = (URL(at).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "SimNest-Gateway/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", accept)
            }
            val status = c.responseCode
            if (status !in 300..399) return c
            val next = c.getHeaderField("Location")
            c.disconnect()
            at = URL(URL(at), next ?: error("redirect without an address")).toString()
        }
        error("too many redirects")
    }
}
