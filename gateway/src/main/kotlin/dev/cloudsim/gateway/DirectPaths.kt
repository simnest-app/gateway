package dev.cloudsim.gateway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.cloudsim.gateway.calls.CellularPath
import dev.cloudsim.gateway.calls.PortMapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * "在外也能直连" (docs/architecture/DIRECT_PATHS.md): how an iPhone away from home reaches this phone, in the order the
 * gateway prefers them (the home Wi-Fi's IPv6, a port on the home router, this phone's own data), each with what it
 * needs, what it costs and how it stands now, refreshed every second; and one line on top saying what a call from
 * outside will use. With none of them a call still goes through, over the server's relay.
 */
@Composable
fun DirectPaths() {
    val context = LocalContext.current
    var mappingOn by remember { mutableStateOf(PortMapping.on(context)) }
    var cellularOn by remember { mutableStateOf(CellularPath.on(context)) }
    var wifiV6 by remember { mutableStateOf(false) }
    var cellular by remember { mutableStateOf("") }
    var mapping by remember { mutableStateOf("off") }
    LaunchedEffect(Unit) {
        while (true) {
            val c = withContext(Dispatchers.Default) { CellularPath.current?.condition() }
            wifiV6 = c?.optBoolean("wifi_v6") ?: false
            cellular = c?.optString("cellular").orEmpty()
            mapping = c?.optString("mapping") ?: PortMapping.condition(context)
            delay(1_000)
        }
    }
    val good = MaterialTheme.colorScheme.primary
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val via = when {
        wifiV6 -> L.t("家里 Wi-Fi 的 IPv6")
        mapping == "mapped" -> L.t("路由器开放的端口")
        cellular == "ready" -> L.t("这台手机的流量")
        else -> null
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(L.t("在外也能直连"), style = MaterialTheme.typography.titleSmall)
            if (via != null) {
                Text(L.t("✓ iPhone 在外面打接电话时能直连，经%1\$s", via), style = MaterialTheme.typography.bodyMedium, color = good)
                if (cellular == "standby") Text(L.t("这台手机的流量作备用：前面的方式连不通时才用。"), style = MaterialTheme.typography.bodySmall, color = quiet)
            } else {
                Text(L.t("iPhone 在外面时，通话经服务器中继：照样能打，延迟稍高一点。"), style = MaterialTheme.typography.bodyMedium)
            }
            Text(L.t("直连比经服务器中继更快、更稳。下面三种方式按顺序优先，哪种能用就用哪种；都用不了也不影响打电话。"),
                style = MaterialTheme.typography.bodySmall, color = quiet)

            HorizontalDivider()
            PathRow(
                title = L.t("① 家里 Wi-Fi 的 IPv6"),
                note = L.t("不耗流量，最优先。不用设置，有没有取决于宽带和光猫。"),
                status = if (wifiV6) L.t("✓ 有") else L.t("没有。可以问宽带运营商能不能开通 IPv6。"),
                ok = wifiV6,
                plain = !wifiV6,
            )

            HorizontalDivider()
            PathRow(
                title = L.t("② 让路由器开放端口"),
                note = L.t("通话时请家里的路由器临时开放一个端口，挂断就撤掉，不耗流量。需要路由器开着 UPnP 或 PCP，宽带有公网 IPv4。"),
                status = if (!mappingOn) null else when (mapping) {
                    "mapped" -> L.t("✓ 路由器可以开放端口")
                    "unsupported" -> L.t("路由器没有回应。可以在路由器管理页面打开 UPnP，再把这个开关关掉重开。")
                    "not_public" -> L.t("路由器能开放端口，但宽带没有公网 IPv4，外面连不进来。可以问宽带运营商申请公网 IP。")
                    else -> L.t("正在问路由器…")
                },
                ok = mappingOn && mapping == "mapped",
                busy = mappingOn && mapping != "mapped" && mapping != "unsupported" && mapping != "not_public",
                checked = mappingOn,
                onChange = { mappingOn = it; PortMapping.set(context, it) },
            )

            HorizontalDivider()
            PathRow(
                title = L.t("③ 用这台手机的流量直连"),
                note = L.t("前两种用不了或连不通时才用。用 SIM 卡的流量，每路通话一小时约 30–40 MB。"),
                status = if (!cellularOn) null else when (cellular) {
                    "ready" -> L.t("✓ 可用")
                    "standby" -> L.t("✓ 备用中：前面的方式能直连时不用流量")
                    "none" -> L.t("这台手机没开移动数据，用不了。")
                    "no_v6" -> L.t("这张卡的流量没有 IPv6，用不了。卡停机或欠费时也会这样。")
                    else -> null
                },
                ok = cellularOn && (cellular == "ready" || cellular == "standby"),
                checked = cellularOn,
                onChange = { cellularOn = it; CellularPath.set(context, it) },
            )
        }
    }
}

@Composable
private fun PathRow(
    title: String, note: String, status: String?, ok: Boolean,
    busy: Boolean = false,
    /** Just not there, nothing wrong: in grey rather than the colour that asks for attention. */
    plain: Boolean = false,
    checked: Boolean? = null, onChange: (Boolean) -> Unit = {},
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                    Text(status, style = MaterialTheme.typography.bodySmall,
                        color = when {
                            ok -> MaterialTheme.colorScheme.primary
                            busy || plain -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.tertiary
                        })
                }
            }
        }
        if (checked != null) Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.padding(start = 8.dp))
    }
}
