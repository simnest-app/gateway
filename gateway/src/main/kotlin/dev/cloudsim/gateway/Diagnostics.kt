package dev.cloudsim.gateway

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.cloudsim.gateway.adb.LocalAdb
import dev.cloudsim.gateway.identity.KeystoreSigner
import dev.cloudsim.gateway.net.ControlClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater

/**
 * "发送诊断日志" (DIAGNOSTICS.md): this app's own log lines and a few facts about the phone's setup, masked, packed and
 * sent signed to the service, only when the user taps. An app may read its own log without any permission.
 */
object Diagnostics {
    private val digits = Regex("(?<![0-9A-Fa-f])[0-9]{7,}(?![0-9A-Fa-f])")
    private val longId = Regex("(?<![0-9A-Za-z_-])([0-9A-Fa-f]{8})(?:-?[0-9A-Fa-f]){12,}(?![0-9A-Za-z_-])")
    private val ipv4 = Regex("(?<![0-9.])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9.])")
    private val ipv6 = Regex("(?<![0-9A-Za-z:.])[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7}(?:%[0-9A-Za-z]+)?(?![0-9A-Za-z:])")

    /**
     * A standalone run of seven or more digits is hidden, in case a number slipped into a log line; digits inside a hex
     * id stay. IP addresses go too, and whole device or call ids are cut to their first 8 characters, enough to match
     * one call across the phone's and the gateway's logs (LOG_LEVELS.md). "12:34:56" is a time, not an address.
     */
    fun mask(text: String): String {
        var out = digits.replace(text, "***")
        out = longId.replace(out, "$1…")
        out = ipv4.replace(out, "<ipv4>")
        return ipv6.replace(out) { m -> if (m.value.contains("::") || m.value.count { it == ':' } == 7) "<ipv6>" else m.value }
    }

    /** What one upload may carry once unpacked; the service takes up to 4 MB. */
    private const val UPLOAD_CHARS = 3_600_000
    private const val BRIDGE_BYTES = 512 * 1024

    /** Raw deflate, as the service unpacks it. */
    fun pack(text: String): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(text.toByteArray())
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    private fun ownLog(): String = try {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid", Process.myPid().toString(), "-t", "5000").redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().also { p.waitFor() }
    } catch (e: Exception) {
        "logcat unavailable: ${e.javaClass.simpleName}"
    }

    /** The last part of the shell audio bridge's log: the shell owns the file, so it is read over wireless debugging. */
    private fun bridgeLog(context: Context): String = try {
        runBlocking {
            withTimeout(15_000) { LocalAdb(context).shell("tail -c $BRIDGE_BYTES /data/local/tmp/cloudsim-bridge.log") }
        }
    } catch (e: Exception) {
        "bridge log unavailable: ${e.javaClass.simpleName}"
    }

    /** Basic: this process's lines still in the system's ring. Full: the app's own files, then the bridge's log. */
    private fun log(context: Context): String {
        if (!GatewayLog.isFull(context)) return ownLog()
        val bridge = "=== bridge ===\n" + bridgeLog(context)
        val own = GatewayLog.text(context, UPLOAD_CHARS - bridge.length).ifEmpty { ownLog() }
        return "=== gateway ===\n" + own + "\n" + bridge
    }

    private fun facts(context: Context): String {
        val roles = context.getSystemService(RoleManager::class.java)
        return JSONObject()
            .put("event", "diagnostics_facts")
            .put("build", BuildInfo.summary())
            .put("default_sms", roles?.isRoleHeld(RoleManager.ROLE_SMS) == true)
            .put("default_dialer", roles?.isRoleHeld(RoleManager.ROLE_DIALER) == true)
            .put("paired", context.getSharedPreferences("pairing", Context.MODE_PRIVATE).getString("phone_id", null) != null)
            .put("log_level", if (GatewayLog.isFull(context)) "full" else "basic")
            .toString()
    }

    /** Returns the code to quote; throws with a message to show. */
    fun send(context: Context, note: String): String {
        // The gateway's last self-test, when there is one: what it found of itself, next to what it logged.
        val test = dev.cloudsim.gateway.selftest.SelfTests.last(context)?.let { "=== self-test at ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(it.atMs))} ===\n" + it.text() + "\n" } ?: ""
        val text = mask(facts(context) + "\n" + test + log(context))
        val body = JSONObject()
            .put("app", "gateway")
            .put("version", BuildInfo.summary())
            .put("system", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("note", mask(note.trim()))
            .put("log", Base64.getEncoder().encodeToString(pack(text)))
        val r = ControlClient(BuildInfo.service, KeystoreSigner()).call("POST", "/v1/diagnostics", body, readTimeoutMs = 30_000)
        return r.optString("code").ifEmpty { throw IllegalStateException(L.t("服务没有回日志编号")) }
    }
}

@Composable
fun DiagnosticsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(L.t("发给开发者"), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = note, onValueChange = { note = it.take(500) }, label = { Text(L.t("遇到了什么问题（可不填）")) }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(enabled = !sending, onClick = {
            sending = true
            scope.launch {
                result = withContext(Dispatchers.IO) {
                    try {
                        L.t("已发送，日志编号：") + Diagnostics.send(context, note)
                    } catch (e: Exception) {
                        L.t("没能发送：") + (e.message ?: e.javaClass.simpleName)
                    }
                }
                if (result?.startsWith(L.t("已发送")) == true) note = ""
                sending = false
            }
        }) { Text(if (sending) L.t("正在发送…") else L.t("发送诊断日志")) }
        result?.let { Text(it) }
        Text(L.t("只在你点按时发送。日志记的是网关做了什么，不含短信内容；万一混进长串数字也会先遮掉。服务端保存 30 天。"), style = MaterialTheme.typography.bodySmall)
        LogLevelChoice()
    }
}

/** LOG_LEVELS.md: this phone's own choice, basic unless changed. */
@Composable
private fun LogLevelChoice() {
    val context = LocalContext.current
    var full by remember { mutableStateOf(GatewayLog.isFull(context)) }
    Text(L.t("日志详细程度"), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !full, onClick = { full = false; GatewayLog.setFull(context, false) }, label = { Text(L.t("基础")) })
        FilterChip(selected = full, onClick = { full = true; GatewayLog.setFull(context, true) }, label = { Text(L.t("完整")) })
    }
    Text(
        if (full) L.t("完整：把网关的日志另存在这台手机上（不怕重启和冲刷），多记收到的每条命令和电话状态变化，发送时连同音频桥的日志一起发；留 7 天，最多占 3 MB。")
        else L.t("基础：只发系统里还留着的网关日志，忙的时候可能只剩最近一两个小时。遇到问题想让开发者查清楚，可以先改成“完整”，再复现一次后发送。"),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(L.t("两种都不记电话号码、短信内容和通讯录，只在点“发送诊断日志”或主人在 iPhone 上发送时才发出。"), style = MaterialTheme.typography.bodySmall)
}
