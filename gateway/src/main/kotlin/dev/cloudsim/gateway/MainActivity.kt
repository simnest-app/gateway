package dev.cloudsim.gateway

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.cloudsim.gateway.calls.GatewayService
import dev.cloudsim.gateway.pairing.PairingUi
import dev.cloudsim.gateway.pairing.PairingViewModel
import dev.cloudsim.gateway.pairing.qrBitmap
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val pairing: PairingViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PairingViewModel(application, BuildInfo.service) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A gateway the user had turned on keeps running whatever this screen shows first (the terms only gate new
        // pairing and settings, not a gateway already in use).
        if (GatewayService.isEnabled(this) && GatewayService.missingPermissions(this).isEmpty()) GatewayService.start(this)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var agreed by remember { mutableStateOf(Legal.accepted(this@MainActivity)) }
                    // A paired gateway shows its state on the first page and keeps everything else a tap away
                    // (the user's 2026-11-13 decision: one page held too much). Unpaired, there is only that page.
                    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(Page.HOME) }
                    val ui by pairing.ui.collectAsState()
                    val shown = if (agreed && ui is PairingUi.Paired) page else Page.HOME
                    androidx.activity.compose.BackHandler(shown != Page.HOME) { page = Page.HOME }
                    // Each page scrolls from its own top.
                    androidx.compose.runtime.key(shown) {
                        Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Before pairing or any permission: the terms and the privacy policy.
                            if (!agreed) {
                                LegalConsent { agreed = true }
                            } else if (shown == Page.HOME) {
                                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                                PairingSection(pairing) { page = it }
                                if (ui !is PairingUi.Paired) {
                                    DiagnosticsSection()
                                    LegalLinks()
                                    Text(BuildInfo.summary(), style = MaterialTheme.typography.bodySmall)
                                }
                            } else {
                                SubPage(shown, pairing) { page = Page.HOME }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PairingSection(model: PairingViewModel, open: (Page) -> Unit) {
    val ui by model.ui.collectAsState()
    when (val state = ui) {
        PairingUi.Idle -> {
            Text(L.t("还没有管理手机。用要管理这台网关的 iPhone 扫码配对。"))
            Button(onClick = model::open) { Text(L.t("出码配对")) }
            PrepareSection()
        }
        PairingUi.Working -> Text(L.t("请稍候…"))
        is PairingUi.ShowingCode -> {
            if (state.code.replace) {
                Text(L.t("更换主人手机"), style = MaterialTheme.typography.titleMedium)
                Text(L.t("用新的 iPhone 打开 SimNest，在“从备份恢复”里选“在网关手机上确认”，扫描这个码。"))
            } else Text(L.t("用 iPhone 上的 SimNest 扫描这个码"))
            val bitmap = remember(state.code.link) { qrBitmap(state.code.link).asImageBitmap() }
            Image(bitmap, contentDescription = L.t("配对二维码"), modifier = Modifier.size(280.dp), filterQuality = FilterQuality.None)
            Countdown(state.code.expiresAtMs)
            if (state.code.replace) {
                OutlinedButton(onClick = { model.open(true) }) { Text(L.t("重新出码")) }
                OutlinedButton(onClick = model::keepOwner) { Text(L.t("不换了")) }
            } else OutlinedButton(onClick = model::open) { Text(L.t("重新出码")) }
        }
        is PairingUi.Compare -> {
            Text(L.t("核对两台手机上的数字是否一致"))
            Text(state.claimant.code.chunked(3).joinToString(" "), fontSize = 40.sp, style = MaterialTheme.typography.displaySmall)
            Text(L.t("对方设备：%1\$s", state.claimant.phoneId.take(8)), style = MaterialTheme.typography.bodySmall)
            if (state.code.replace) Text(L.t("确认后，这台 iPhone 成为主人，原来的主人手机立即失去管理权；家人和他们的权限保留。"), color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { model.confirm(state.code, state.claimant) }) { Text(if (state.code.replace) L.t("一致，更换主人") else L.t("一致，确认配对")) }
                OutlinedButton(onClick = { model.reject(state.code) }) { Text(L.t("不一致")) }
            }
        }
        is PairingUi.Paired -> {
            GatewayHome(state.phoneId, open)
        }
        is PairingUi.Problem -> {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            Button(onClick = model::open) { Text(L.t("重新出码")) }
            OutlinedButton(onClick = model::keepOwner) { Text(L.t("返回")) }
        }
    }
}

/**
 * Changing the owner phone in person (BACKUP_RESTORE_6.md): for when the owner's iPhone is lost and there is neither the
 * old phone nor a recovery credential. Being at this phone is the proof.
 */
@Composable
internal fun ReplaceOwner(model: PairingViewModel) {
    var asking by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { asking = true }) { Text(L.t("更换主人手机")) }
    if (asking) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(L.t("更换主人手机？")) },
            text = { Text(L.t("只在主人的 iPhone 丢了、旧手机和恢复凭据都没有时使用。旧手机还在的话，在旧手机上转交更方便。新 iPhone 扫码并在这里确认后，旧 iPhone 立即失去管理权；家人和他们的权限保留。")) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { asking = false; model.open(true) }) { Text(L.t("出码更换")) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { asking = false }) { Text(L.t("取消")) } },
        )
    }
}

/**
 * Forgetting the owner and family on this phone (GATEWAY_RESET.md): for a gateway whose owner unpaired it on the iPhone,
 * or whose owner phone is gone. Being at this phone is the proof; the service tells everyone who had it.
 */
@Composable
internal fun ClearOwner(model: PairingViewModel) {
    var asking by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { asking = true }) { Text(L.t("清除主人关联")) }
    if (asking) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(L.t("清除主人关联？")) },
            text = { Text(L.t("主人和所有家人会立即失去这台网关，并收到通知；来电和短信不再转给任何 iPhone，直到重新配对。这台手机上的短信、通话记录和本页的设置保留。清除后可以马上出码，用新的 iPhone 配对。")) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { asking = false; model.clearOwner() }) { Text(L.t("清除"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { asking = false }) { Text(L.t("取消")) } },
        )
    }
}

@Composable
private fun Countdown(expiresAtMs: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAtMs) {
        while (now < expiresAtMs) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val left = ((expiresAtMs - now) / 1000).coerceAtLeast(0)
    Text(L.t("剩余 %1\$s:%2\$s", left / 60, "%02d".format(left % 60)))
}

@Composable
internal fun GatewaySwitch() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(GatewayService.isEnabled(context)) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        denied = result.values.any { !it }
        if (!denied) {
            GatewayService.start(context)
            enabled = true
        }
    }
    // A permission can be taken back in system settings, or by the system after an update.
    val missing = GatewayService.missingPermissions(context)
    LaunchedEffect(Unit) {
        // Picks up where the user left it; the service itself never restarts after being switched off.
        if (enabled && missing.isEmpty()) GatewayService.start(context)
        if (enabled && missing.isNotEmpty()) GatewayService.stop(context).also { enabled = false; denied = true }
    }
    if (enabled) {
        Text(L.t("网关运行中：来电会转给已配对的 iPhone。"))
        OutlinedButton(onClick = { GatewayService.stop(context); enabled = false }) { Text(L.t("关闭网关")) }
        OutlinedButton(onClick = { context.startActivity(android.content.Intent(context, dev.cloudsim.gateway.calls.DialActivity::class.java)) }) { Text(L.t("在本机拨号")) }
    } else {
        Text(L.t("网关已关闭，来电不会转给 iPhone。"))
        Button(onClick = { ask.launch(GatewayService.permissions.toTypedArray()) }) { Text(L.t("开启网关")) }
    }
    if (denied) Text(L.t("需要允许“电话”（含接听电话）和“通知”权限才能转接来电。请点“开启网关”重新允许；如果没有弹窗，到系统设置的应用权限里打开。"), color = MaterialTheme.colorScheme.error)
}

/** Redmi silent for incoming calls: they still go to the iPhones and show on this screen, without ringing here. */
@Composable
internal fun QuietSwitch() {
    val context = LocalContext.current
    var quiet by remember { mutableStateOf(dev.cloudsim.gateway.calls.Quiet.on(context)) }
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(L.t("来电时本机不响铃"))
            Text(L.t("来电照样转给 iPhone，这台手机上只显示、不出声也不振动。需要网关是默认电话应用。"), style = MaterialTheme.typography.bodySmall)
        }
        androidx.compose.material3.Switch(checked = quiet, onCheckedChange = { quiet = it; dev.cloudsim.gateway.calls.Quiet.set(context, it) })
    }
}

/**
 * Before pairing: the permissions and default apps can be set up on this phone already, so the owner can do it while
 * next to it and pair later from wherever the iPhone is. Nothing runs until pairing and "开启网关".
 */
@Composable
private fun PrepareSection() {
    val context = LocalContext.current
    var asked by remember { mutableStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { asked++ }
    androidx.compose.material3.HorizontalDivider()
    Text(L.t("先准备这台手机"), style = MaterialTheme.typography.titleMedium)
    Text(L.t("可以先在这里把权限和默认应用设好，之后再配对。配对并开启网关前，来电和短信都不会转出。"), style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { ask.launch(GatewayService.permissions.toTypedArray()) }) { Text(L.t("允许网关所需权限")) }
    // Read again after each round of the system prompts.
    androidx.compose.runtime.key(asked) {
        FeatureStatus()
        SetupGuide()
    }
}
