package dev.cloudsim.gateway.selftest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.cloudsim.gateway.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "自检": the gateway checks itself when asked, a line an item, and keeps the last result on screen. Nothing runs by
 * itself and nothing is sent; the last result goes out only inside a diagnostics log the user or the owner sends.
 */
@Composable
fun SelfTestScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checks by remember { mutableStateOf(SelfTests.last(context)?.checks.orEmpty()) }
    var at by remember { mutableStateOf(SelfTests.last(context)?.atMs) }
    var running by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Text(L.t("检查这台网关自己的网络、和服务及中继的连接、SIM 卡和后台运行。不拨电话，不发短信，大约十几秒。"), style = MaterialTheme.typography.bodySmall)
    Button(enabled = !busy, onClick = {
        busy = true
        checks = emptyList()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { SelfTestRunner(context.applicationContext).run { done, now -> checks = done; running = now } }.getOrNull()
            }
            result?.let { checks = it.checks; at = it.atMs }
            running = null
            busy = false
        }
    }) { Text(if (busy) L.t("正在自检…") else if (checks.isEmpty()) L.t("开始自检") else L.t("再测一次")) }

    if (checks.isNotEmpty() || running != null) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (c in checks) {
                    val (mark, color) = when (c.level) {
                        Level.OK -> "✓" to MaterialTheme.colorScheme.primary
                        Level.WARN -> "!" to MaterialTheme.colorScheme.tertiary
                        Level.FAIL -> "✗" to MaterialTheme.colorScheme.error
                        Level.SKIP -> "–" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Column {
                        Text("$mark ${c.title}", style = MaterialTheme.typography.bodyLarge, color = color)
                        Text(c.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
                running?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(it, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
    val power = remember { context.getSystemService(android.os.PowerManager::class.java) }
    if (checks.any { it.id == "keep" } && !power.isIgnoringBatteryOptimizations(context.packageName)) {
        OutlinedButton(onClick = {
            runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }) { Text(L.t("打开电池优化设置")) }
        Text(L.t("在列表里找到“SimNest 网关”，选“不限制”或“不优化”。"), style = MaterialTheme.typography.bodySmall)
    }
    if (dev.cloudsim.gateway.selftest.Freezes.huawei && checks.any { it.id == "keep" && it.level == Level.FAIL } && Freezes.facts().first > 0) {
        OutlinedButton(onClick = { runCatching { dev.cloudsim.gateway.openHuaweiStartup(context) } }) { Text(L.t("打开应用启动管理")) }
    }
    if (!busy) at?.let {
        Text(L.t("上次自检：%1\$s", SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it))), style = MaterialTheme.typography.bodySmall)
    }
    Text(L.t("自检结果只留在这台手机上；发送诊断日志时会带上最近一次的结果，方便开发者对照。"), style = MaterialTheme.typography.bodySmall)
}
