package dev.cloudsim.gateway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cloudsim.gateway.calls.CellularPath
import dev.cloudsim.gateway.calls.GatewayService
import dev.cloudsim.gateway.calls.GatewayStatus
import dev.cloudsim.gateway.calls.PortMapping
import dev.cloudsim.gateway.pairing.PairingViewModel
import dev.cloudsim.gateway.selftest.Level
import dev.cloudsim.gateway.selftest.SelfTestScreen
import dev.cloudsim.gateway.selftest.SelfTests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The gateway's pages once it is paired: the first shows how it stands, the rest hold what used to be one long page. */
enum class Page { HOME, SELF_TEST, NETWORK, FEATURES, MCP, OWNER, DIAGNOSTICS, ABOUT }

internal fun pageTitle(page: Page) = when (page) {
    Page.HOME -> ""
    Page.SELF_TEST -> L.t("自检")
    Page.NETWORK -> L.t("网络与中继")
    Page.FEATURES -> L.t("权限与功能")
    Page.MCP -> L.t("外部工具（MCP）")
    Page.OWNER -> L.t("主人与配对")
    Page.DIAGNOSTICS -> L.t("诊断与反馈")
    Page.ABOUT -> L.t("关于")
}

/** What the first page reads again every few seconds, off the main thread. */
private data class HomeFacts(
    val lines: List<String> = emptyList(), val power: String = "", val broken: Int = 0, val direct: String = "", val test: String = "", val testBad: Boolean = false,
)

private fun homeFacts(context: android.content.Context): HomeFacts {
    val lines = GatewayStatus.shown(GatewayService.liveNumbers?.let { runCatching(it).getOrNull() }.orEmpty()).map { n ->
        val (state, signal) = GatewayStatus.line(context, n)
        val text = when (state) {
            "in_service" -> L.t("有服务") + (signal?.let { g -> L.t("，信号 %1\$s/4", g.bars) + g.detail().let { if (it.isEmpty()) "" else " · $it" } } ?: "")
            "no_sim" -> L.t("没插卡，已停用")
            "no_service" -> L.t("无服务")
            "emergency_only" -> L.t("只能拨紧急电话（卡可能停机或欠费）")
            "radio_off" -> L.t("飞行模式或卡被关闭")
            else -> L.t("状态未知")
        }
        (if (state == "in_service") "✓ " else if (state == "no_sim") "– " else "✗ ") + L.t("卡 %1\$s", n.slot + 1) + (n.carrier.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "") + "：" + text
    }
    val (freezes, _, _) = dev.cloudsim.gateway.selftest.Freezes.facts()
    val frozen = if (freezes > 0) L.t("✗ 系统在过去 24 小时冻结过网关 %1\$s 次，冻结时来电转不出去。详见自检", freezes) else ""
    val power = GatewayStatus.battery(context)?.let { (level, charging) -> L.t("电量 %1\$s%%", level) + if (charging) L.t("，在充电") else L.t("，没在充电") }.orEmpty()
    val c = CellularPath.current?.condition()
    val direct = when {
        c == null -> ""
        c.optBoolean("wifi_v6") -> L.t("在外能直连，经%1\$s", L.t("家里 Wi-Fi 的 IPv6"))
        (c.optString("mapping").ifEmpty { PortMapping.condition(context) }) == "mapped" -> L.t("在外能直连，经%1\$s", L.t("路由器开放的端口"))
        c.optString("cellular") == "ready" || c.optString("cellular") == "standby" -> L.t("在外能直连，经%1\$s", L.t("这台手机的流量"))
        else -> L.t("在外经中继通话")
    }
    val last = SelfTests.last(context)
    val test = if (last == null) L.t("还没测过") else {
        val at = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(last.atMs))
        val bad = last.count(Level.FAIL)
        val warn = last.count(Level.WARN)
        at + " · " + when {
            bad > 0 -> L.t("%1\$s 项有问题", bad)
            warn > 0 -> L.t("%1\$s 项要留意", warn)
            else -> L.t("全部正常")
        }
    }
    return HomeFacts(listOfNotNull(frozen.takeIf { it.isNotEmpty() }) + lines, power, Readiness.items(context).count { !it.ok }, direct, test, last?.worst == Level.FAIL)
}

/** The first page of a paired gateway: on or off, each SIM, power, and one row for each of the other pages with its state in a line. */
@Composable
internal fun GatewayHome(phoneId: String, open: (Page) -> Unit) {
    val context = LocalContext.current
    var facts by remember { mutableStateOf(HomeFacts()) }
    LaunchedEffect(Unit) {
        while (true) {
            facts = withContext(Dispatchers.Default) { runCatching { homeFacts(context) }.getOrDefault(facts) }
            delay(3_000)
        }
    }
    GatewaySwitch()
    if (facts.lines.isNotEmpty() || facts.power.isNotEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                facts.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (it.startsWith("✗")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
                if (facts.power.isNotEmpty()) Text(facts.power, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column {
            PageRow(Page.SELF_TEST, facts.test, facts.testBad, open)
            HorizontalDivider()
            PageRow(Page.NETWORK, facts.direct, false, open)
            HorizontalDivider()
            PageRow(Page.FEATURES, if (facts.broken == 0) L.t("所有功能可用") else L.t("%1\$s 项功能用不了", facts.broken), facts.broken > 0, open)
            HorizontalDivider()
            PageRow(Page.MCP, L.t("让你自己的程序读这台网关的状态、通话和短信"), false, open)
            HorizontalDivider()
            PageRow(Page.OWNER, L.t("已配对管理手机：%1\$s", phoneId.take(8)), false, open)
            HorizontalDivider()
            PageRow(Page.DIAGNOSTICS, L.t("发送诊断日志、日志详细程度"), false, open)
            HorizontalDivider()
            val update = (dev.cloudsim.gateway.update.Updater.current.collectAsState().value as? dev.cloudsim.gateway.update.Updater.State.Available)?.release
            PageRow(Page.ABOUT, BuildInfo.summary() + (update?.let { " · " + L.t("有新版本 %1\$s（%2\$s）", it.versionName, it.versionCode) } ?: ""), false, open)
        }
    }
}

@Composable
private fun PageRow(page: Page, note: String, bad: Boolean, open: (Page) -> Unit) {
    TextButton(onClick = { open(page) }, modifier = Modifier.fillMaxWidth(), shape = androidx.compose.ui.graphics.RectangleShape) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pageTitle(page), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One of the pages behind the first: a way back on top, then the page's own parts as they were on the long page. */
@Composable
internal fun SubPage(page: Page, model: PairingViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val on = GatewayService.isEnabled(context)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = back) { Text(L.t("‹ 返回")) }
        // The external tools' part carries its own title.
        if (page != Page.MCP) Text(pageTitle(page), style = MaterialTheme.typography.titleLarge)
    }
    when (page) {
        Page.HOME -> {}
        Page.SELF_TEST -> SelfTestScreen()
        Page.NETWORK -> {
            if (on) {
                DirectPaths()
                OwnRelayCard()
            } else Text(L.t("网关开启后，这里显示直连方式和自建中继。"))
        }
        Page.FEATURES -> {
            if (on) QuietSwitch()
            FeatureStatus()
            SetupGuide()
        }
        Page.MCP -> dev.cloudsim.gateway.mcp.McpSection()
        Page.OWNER -> {
            Text(L.t("这台网关由一台 iPhone 管理。主人的 iPhone 丢了或想换人时，在这里处理。"), style = MaterialTheme.typography.bodySmall)
            ReplaceOwner(model)
            ClearOwner(model)
        }
        Page.DIAGNOSTICS -> DiagnosticsSection()
        Page.ABOUT -> {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
            Text(BuildInfo.summary() + " · " + BuildConfig.BUILD_COMMIT)
            dev.cloudsim.gateway.update.UpdateCard()
            LegalLinks()
            OpenSourceLicenses()
            Text(L.t("网关的源码以 GPL-3.0 公开：github.com/simnest-app/gateway"), style = MaterialTheme.typography.bodySmall)
        }
    }
}
