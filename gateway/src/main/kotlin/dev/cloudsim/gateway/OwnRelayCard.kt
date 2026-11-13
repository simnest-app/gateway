package dev.cloudsim.gateway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.cloudsim.gateway.relay.OwnRelayKeeper
import dev.cloudsim.gateway.relay.OwnRelays
import dev.cloudsim.gateway.relay.RelayCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "自建中继" (docs/architecture/RELAY_SPLIT.md 17.1): the owner adds a relay they run themselves by pasting the text
 * from its setup page, the gateway checks it from here at once, and shows how it stands. Which relays calls use is
 * chosen on the owner's iPhone, as that choice is signed; this card only says what it is now.
 */
@Composable
fun OwnRelayCard() {
    val context = LocalContext.current
    val keeper = remember { OwnRelays.get(context) }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var code by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    // The service checks in the background too: what is shown follows it.
    LaunchedEffect(Unit) { while (true) { delay(1_000); tick++ } }
    val relay = remember(tick) { keeper.relay }
    val check = remember(tick) { keeper.lastCheck }
    val checking = remember(tick) { keeper.checking }
    val choice = remember(tick) { keeper.choice }
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    fun recheck() { scope.launch { withContext(Dispatchers.IO) { runCatching { keeper.check() } }; tick++ } }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(L.t("自建中继"), style = MaterialTheme.typography.titleSmall)
            if (relay == null) {
                Text(L.t("通话连不通时，默认经官方中继转发。你也可以在自己的云主机或 NAS 上部署一台中继，只给自己和家人用。中继听不到通话内容。"),
                    style = MaterialTheme.typography.bodySmall, color = quiet)
                OutlinedTextField(value = code, onValueChange = { code = it; bad = false }, modifier = Modifier.fillMaxWidth(), maxLines = 4,
                    label = { Text(L.t("粘贴中继设置页面上的文字")) }, isError = bad)
                if (bad) Text(L.t("这不是自建中继设置页面上的文字，请重新复制。"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Button(enabled = code.isNotBlank(), onClick = {
                    if (keeper.add(code)) { code = ""; tick++; recheck() } else bad = true
                }) { Text(L.t("添加")) }
            } else {
                Text(L.t("已添加：%1\$s", relay.host), style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (checking) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                    Text(if (checking || check == null) L.t("正在检查…") else checkText(check), style = MaterialTheme.typography.bodySmall,
                        color = if (!checking && check?.ok == true) MaterialTheme.colorScheme.primary else if (checking || check == null) quiet else MaterialTheme.colorScheme.tertiary)
                }
                Text(when (choice) {
                    OwnRelayKeeper.OWN -> L.t("中继方式：只用自建中继。连不上时不会改走官方中继。")
                    OwnRelayKeeper.OWN_FIRST -> L.t("中继方式：自建优先，连不上时用官方中继。")
                    else -> L.t("现在通话仍走官方中继。要用这台中继，请在主人的 iPhone 上把这台网关的“中继方式”改成“只用自建”或“自建优先”。")
                }, style = MaterialTheme.typography.bodySmall, color = quiet)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !checking, onClick = { recheck() }) { Text(L.t("重新检查")) }
                    TextButton(onClick = { removing = true }) { Text(L.t("移除")) }
                }
            }
        }
    }
    if (removing) {
        AlertDialog(onDismissRequest = { removing = false },
            title = { Text(L.t("移除自建中继？")) },
            text = { Text(if (choice == OwnRelayKeeper.OWN) L.t("中继方式是“只用自建”：移除后，连不通的通话没有中继可用，直到在 iPhone 上改回官方中继。") else L.t("移除后，通话不再经这台中继。")) },
            confirmButton = { TextButton(onClick = { keeper.remove(); removing = false; tick++ }) { Text(L.t("移除")) } },
            dismissButton = { TextButton(onClick = { removing = false }) { Text(L.t("取消")) } })
    }
}

private fun checkText(c: RelayCheck): String = when {
    c.ok && c.publicOk -> L.t("✓ 能连上，往返 %1\$s 毫秒", c.rttMs ?: 0)
    c.ok -> L.t("✓ 在家里的网络内能连上，往返 %1\$s 毫秒。在外面能不能连，请在 iPhone 上做一次自检。", c.rttMs ?: 0)
    c.problem == "wrong_secret" -> L.t("密钥不对：中继可能重新生成过密钥。请移除后重新添加。")
    c.problem == "no_answer" || c.problem == "unreachable" -> L.t("连不上：中继没有应答。请确认它在运行，防火墙和路由器放行了端口。")
    c.problem == "not_a_relay" -> L.t("这个地址上不是中继。请确认地址和端口。")
    c.problem == "blocks_public" -> L.t("中继拒绝转发到公网地址，通话会连不通。请检查中继配置里的禁止规则。")
    else -> L.t("中继拒绝了请求（%1\$s）。", c.problem.orEmpty())
}
