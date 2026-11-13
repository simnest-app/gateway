package dev.cloudsim.gateway.mcp

import android.app.Activity
import android.app.KeyguardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.cloudsim.gateway.L
import dev.cloudsim.gateway.pairing.qrBitmap
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "External tools (MCP)" on the gateway's own screen (GATEWAY_MCP.md 2.4, 4): the switch, where it listens, the tools
 * let in and what each may use, taking one off, and the log. Nothing of this is on the iPhone.
 */
@Composable
fun McpSection() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(Mcp.on(context)) }
    var explain by remember { mutableStateOf(false) }
    var lan by remember { mutableStateOf(Mcp.lan(context)) }
    var plain by remember { mutableStateOf(Mcp.plainLan(context)) }
    var askPlain by remember { mutableStateOf(false) }
    // Bumped whenever a grant changes, and every few seconds for "last active" and where it listens.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3_000); tick++ } }

    HorizontalDivider()
    Text(L.t("外部工具（MCP）"), style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(L.t("让你自己的程序连接网关"))
            Text(L.t("打开后，你接入的程序可以按你勾选的能力读网关上的短信、通话记录等。默认关闭。"), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = on, onCheckedChange = { want ->
            if (want && !Mcp.explained(context)) explain = true else { on = want; Mcp.setOn(context, want) }
        })
    }
    if (explain) AlertDialog(
        onDismissRequest = { explain = false },
        title = { Text(L.t("打开外部工具？")) },
        text = { Text(L.t("打开后，你接入的程序可以按你勾选的能力读网关上的短信和通话记录。内容交给它以后，怎么保存、会不会发给云端的模型，取决于那个程序。短信和来电人说的话是别人写的，里面可能带着骗模型的指令。只接入你信得过的程序；不用的时候关掉。")) },
        confirmButton = { TextButton(onClick = { explain = false; Mcp.setExplained(context); on = true; Mcp.setOn(context, true) }) { Text(L.t("我已了解，打开")) } },
        dismissButton = { TextButton(onClick = { explain = false }) { Text(L.t("取消")) } },
    )
    if (!on) return

    androidx.compose.runtime.key(tick) {
        val l = Mcp.listening
        if (l == null) Text(L.t("网关服务没在运行，外部工具连不上。先开启网关。"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        else {
            Text(L.t("网关本机上的程序：%1\$s", "http://127.0.0.1:${l.port}/mcp"), style = MaterialTheme.typography.bodySmall)
            if (lan) Text(if (l.lanAddress != null) L.t("家里网络上的程序：%1\$s", "${if (l.tls) "https" else "http"}://${l.lanAddress}:${l.port}/mcp") else L.t("没连上 Wi-Fi，家里网络上的程序暂时连不上。"),
                style = MaterialTheme.typography.bodySmall)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(L.t("允许家里网络上的设备连接"))
            Text(L.t("关掉后只有网关本机上的程序能连。不会在移动数据网络上开放。"), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = lan, onCheckedChange = { lan = it; Mcp.setLan(context, it) })
    }
    if (lan) Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(L.t("允许不加密的连接"))
            Text(L.t("给不认网关自己证书的程序用。打开后，同一个 Wi-Fi 上的其他设备能看到内容和令牌。"), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = plain, onCheckedChange = { want -> if (want) askPlain = true else { plain = false; Mcp.setPlainLan(context, false) } })
    }
    if (askPlain) AlertDialog(
        onDismissRequest = { askPlain = false },
        title = { Text(L.t("允许不加密的连接？")) },
        text = { Text(L.t("打开后，家里网络上的连接不再加密：同一个 Wi-Fi 上的其他设备能看到短信内容和工具的令牌。只在你信得过的网络里用。")) },
        confirmButton = { TextButton(onClick = { askPlain = false; plain = true; Mcp.setPlainLan(context, true) }) { Text(L.t("打开")) } },
        dismissButton = { TextButton(onClick = { askPlain = false }) { Text(L.t("取消")) } },
    )

    val grants = Mcp.grants(context)
    var editing by remember { mutableStateOf<Grant?>(null) }
    var adding by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<Pair<Grant, String>?>(null) }
    var logOf by remember { mutableStateOf<Grant?>(null) }
    var revoking by remember { mutableStateOf<Grant?>(null) }
    val confirm = rememberDeviceCheck()

    androidx.compose.runtime.key(tick) {
        val list = grants.all()
        if (list.isEmpty()) Text(L.t("还没有接入任何工具。"), style = MaterialTheme.typography.bodySmall)
        for (g in list) {
            HorizontalDivider()
            Text(g.name, style = MaterialTheme.typography.titleSmall)
            Text(capsLine(g), style = MaterialTheme.typography.bodySmall)
            Text(if (g.lastSeen == 0L) L.t("还没连接过") else L.t("最近一次活动：%1\$s", time(g.lastSeen)), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { editing = g }) { Text(L.t("改能力")) }
                TextButton(onClick = { confirm { grants.renew(g.id)?.let { shown = g to it }; tick++ } }) { Text(L.t("换令牌")) }
                TextButton(onClick = { logOf = g }) { Text(L.t("日志")) }
                TextButton(onClick = { revoking = g }) { Text(L.t("撤销"), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    Button(onClick = { adding = true }) { Text(L.t("接入新工具")) }

    if (adding) GrantDialog(null, onDismiss = { adding = false }) { name, caps, hide ->
        adding = false
        confirm { shown = grants.add(name, caps, hideCodes = hide); tick++ }
    }
    editing?.let { g ->
        GrantDialog(g, onDismiss = { editing = null }) { _, caps, hide ->
            editing = null
            // Taking something away needs no proof of who is holding the phone; giving more does.
            if (g.caps.containsAll(caps) && (hide || !g.hideCodes)) { grants.change(g.id, caps, g.numbers, hide, g.since); tick++ }
            else confirm { grants.change(g.id, caps, g.numbers, hide, g.since); tick++ }
        }
    }
    revoking?.let { g ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text(L.t("撤销“%1\$s”？", g.name)) },
            text = { Text(L.t("它的令牌马上失效，再也连不上。要再用，得重新接入。")) },
            confirmButton = { TextButton(onClick = { grants.revoke(g.id); revoking = null; tick++ }) { Text(L.t("撤销"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text(L.t("取消")) } },
        )
    }
    shown?.let { (g, token) -> TokenDialog(g, token, lan) { shown = null } }
    logOf?.let { g -> LogDialog(g) { logOf = null } }
}

private fun capName(c: Cap) = when (c) {
    Cap.STATUS -> L.t("看状态和号码")
    Cap.CALLS -> L.t("看通话记录和来电助手的对话")
    Cap.SMS -> L.t("看短信（包括验证码）")
    Cap.CONTACTS -> L.t("看通讯录")
    Cap.SCREENING -> L.t("看来电助手的设置")
}

private fun capsLine(g: Grant): String {
    val caps = if (g.caps.isEmpty()) L.t("没有任何能力") else Cap.entries.filter { it in g.caps }.joinToString("、") { capName(it) }
    return if (g.hideCodes && Cap.SMS in g.caps) caps + L.t("；验证码类短信对它隐藏") else caps
}

private fun time(at: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))

/**
 * Before a tool is let in, given more, or shown a new token: the phone's own lock (PIN, pattern, fingerprint), so that
 * whoever picks up an unlocked gateway cannot open a way to the messages in a few taps (the user's 2026-11-11 decision).
 * A phone with no lock set has nothing to ask.
 */
@Composable
private fun rememberDeviceCheck(): ((() -> Unit) -> Unit) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val then = pending
        pending = null
        if (result.resultCode == Activity.RESULT_OK) then?.invoke()
    }
    return { then ->
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        @Suppress("DEPRECATION")
        val intent = if (keyguard?.isDeviceSecure == true) keyguard.createConfirmDeviceCredentialIntent(L.t("确认是你本人"), L.t("接入或更改外部工具前，先验证这台手机的锁屏密码或指纹。")) else null
        if (intent == null) then() else { pending = then; launcher.launch(intent) }
    }
}

@Composable
private fun GrantDialog(existing: Grant?, onDismiss: () -> Unit, onDone: (String, Set<Cap>, Boolean) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var caps by remember { mutableStateOf(existing?.caps ?: emptySet()) }
    var hide by remember { mutableStateOf(existing?.hideCodes ?: false) }
    val context = LocalContext.current
    val secure = remember { context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) L.t("接入新工具") else L.t("“%1\$s”能用什么", existing.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (existing == null) OutlinedTextField(name, { name = it.take(Grants.MAX_NAME) }, label = { Text(L.t("给它起个名字")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(L.t("勾上它能用的。没勾的，对它来说就不存在。"), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { caps = if (caps.size == Cap.entries.size) emptySet() else Cap.entries.toSet() }) { Text(if (caps.size == Cap.entries.size) L.t("全不选") else L.t("全选")) }
                for (c in Cap.entries) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = c in caps, onCheckedChange = { caps = if (it) caps + c else caps - c })
                    Text(capName(c))
                }
                if (Cap.SMS in caps) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = hide, onCheckedChange = { hide = it })
                    Column {
                        Text(L.t("验证码类短信对它隐藏"))
                        Text(L.t("按短信的样子判断，不保证每一条都认得出。"), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!secure) Text(L.t("这台手机没有设锁屏密码，谁拿到它都能接入工具。建议先设一个。"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onDone(name, caps, hide) }, enabled = existing != null || name.isNotBlank()) { Text(if (existing == null) L.t("接入") else L.t("保存")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.t("取消")) } },
    )
}

/** The one time a token can be seen: as text to copy, as what goes into an MCP client's settings, and as a code to scan. */
@Composable
private fun TokenDialog(g: Grant, token: String, lan: Boolean, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val l = Mcp.listening
    val url = if (lan && l?.lanAddress != null) "${if (l.tls) "https" else "http"}://${l.lanAddress}:${l.port}/mcp" else "http://127.0.0.1:${Mcp.PORT}/mcp"
    val info = JSONObject().put("url", url).put("token", token).apply { if (l != null && l.tls && l.fingerprint.isNotEmpty() && url.startsWith("https")) put("cert_sha256", l.fingerprint) }
    val config = JSONObject().put("mcpServers", JSONObject().put("simnest", JSONObject().put("type", "http").put("url", url)
        .put("headers", JSONObject().put("Authorization", "Bearer $token")))).toString(2)
    AlertDialog(
        onDismissRequest = {},
        title = { Text(L.t("“%1\$s”的连接信息", g.name)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L.t("令牌只显示这一次，关掉后看不到了；丢了就点“换令牌”。"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text(L.t("地址"), style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Text(url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                Text(L.t("令牌"), style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Text(token, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                if (info.has("cert_sha256")) {
                    Text(L.t("网关证书的指纹（SHA-256）"), style = MaterialTheme.typography.labelMedium)
                    SelectionContainer { Text(info.getString("cert_sha256"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    Text(L.t("网关用的是自己的证书。连接程序按这个指纹认它；不认自签证书的程序，可以打开“允许不加密的连接”。"), style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(token)) }) { Text(L.t("复制令牌")) }
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(config)) }) { Text(L.t("复制配置")) }
                }
                val bitmap = remember(token, url) { qrBitmap(info.toString()).asImageBitmap() }
                Image(bitmap, contentDescription = L.t("连接信息二维码"), modifier = Modifier.size(220.dp), filterQuality = FilterQuality.None)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(L.t("我已经存好了")) } },
    )
}

@Composable
private fun LogDialog(g: Grant, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lines = remember(g.id) { Mcp.audit(context).recent(g.id, 100) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L.t("“%1\$s”的日志", g.name)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(L.t("只记它要了什么、结果如何，不记内容。保留最近 30 天。"), style = MaterialTheme.typography.bodySmall)
                if (lines.isEmpty()) Text(L.t("还没有记录。"))
                for (e in lines) {
                    val what = listOfNotNull(e.optString("what").ifEmpty { null } ?: e.optString("method"), e.optString("result"),
                        if (e.has("items")) L.t("%1\$s 条", e.optInt("items")) else null).joinToString(" · ")
                    Text("${time(e.optLong("at"))}  $what", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(L.t("关闭")) } },
    )
}
