package dev.cloudsim.gateway

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.cloudsim.gateway.adb.LocalAdb
import dev.cloudsim.gateway.adb.PairingFlow
import dev.cloudsim.gateway.adb.PairingOverlay
import dev.cloudsim.gateway.adb.ShellLauncher
import dev.cloudsim.gateway.calls.GatewayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Step-by-step setup of the audio service on this phone, each step checked live where the system allows it, with a
 * button straight to the right settings page. Written from walking the flow on the Redmi (MIUI, 2026-11-04).
 * A phone without wireless debugging (Huawei's HarmonyOS, 2026-11-10) reaches its shell over adbd's classic TCP port
 * instead: a computer opens the port once, and the user allows this app's key in the system's own dialog.
 */
@Composable
fun SetupGuide() {
    val context = LocalContext.current
    // Settings change outside the app; re-read them every two seconds while this screen is shown.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2_000); tick++ } }
    val prefs = remember { context.getSharedPreferences("setup", Context.MODE_PRIVATE) }
    var autostartConfirmed by remember { mutableStateOf(prefs.getBoolean("autostart", false)) }
    val pairing by PairingFlow.step.collectAsState()
    val bridge by ShellLauncher.current.collectAsState()

    tick.let { }
    val paired = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    val permissions = GatewayService.missingPermissions(context).isEmpty() && GatewayService.isEnabled(context)
    val unrestricted = !context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
    val developer = Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    val wifi = context.getSystemService(ConnectivityManager::class.java).let { cm ->
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    val wireless = Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
    val running = bridge == ShellLauncher.Status.Running
    val miui = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
    val huawei = Build.MANUFACTURER.uppercase() in setOf("HUAWEI", "HONOR")
    var tcp by remember { mutableStateOf(LocalAdb.usesTcp(context)) }
    val usbDebugging = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    // A socket may not be opened on the main thread, loopback included.
    var tcpOpen by remember { mutableStateOf(false) }
    LaunchedEffect(tick, tcp) { if (tcp) tcpOpen = withContext(Dispatchers.IO) { LocalAdb(context).tcpOpen() } }
    val scope = rememberCoroutineScope()
    var allowing by remember { mutableStateOf(false) }
    var allowError by remember { mutableStateOf<String?>(null) }
    var n = 0

    Text(L.t("设置音频服务"), style = MaterialTheme.typography.titleMedium)
    Text(L.t("按顺序完成。打勾的步骤网关已自动确认。"), style = MaterialTheme.typography.bodySmall)

    Step(++n, L.t("开启网关"), permissions, if (!miui) L.t("上面点“开启网关”，在弹窗里都选允许：电话、通知、通话记录（只用来让 iPhone 看到来电号码）、拨打电话（让 iPhone 经网关拨出）、短信（收、发，结果不明时核实，以及历史短信）。开启后上方的“功能状态”会逐项列出还缺什么。") else L.t("上面点“开启网关”，在弹窗里都选允许：电话、通知、通话记录（只用来让 iPhone 看到来电号码）、拨打电话（让 iPhone 经网关拨出）、短信（收、发，结果不明时核实，以及历史短信）。开启后上方的“功能状态”会逐项列出还缺什么，包括 MIUI 权限里被单独关掉的项。MIUI 在重启或更新网关后会收回“接听电话”，配对完成后网关会自己补回。"))
    if (tcp) {
        Step(++n, L.t("打开开发者选项和 USB 调试"), developer && usbDebugging, L.t("到 设置 → 关于手机，连续点“版本号”7 次。再到 设置 → 系统和更新 → 开发人员选项，打开“USB 调试”。")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(L.t("打开“关于手机”")) { context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
                if (developer) Button(L.t("打开开发者选项")) { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            }
        }
        Step(++n, L.t("连电脑打开调试端口"), tcpOpen, L.t("这台手机没有“无线调试”，要借电脑开一次端口：用数据线连上电脑，在电脑上运行 adb tcpip 5555。手机每次重启后都要再做一次这一步，否则通话没有声音。")) {
            Button(L.t("这台手机有“无线调试”")) { LocalAdb.setUsesTcp(context, false); tcp = false }
        }
        Step(++n, L.t("允许网关调试本机（只需一次）"), paired, allowError ?: if (allowing) L.t("正在等你点允许…手机会弹出“是否允许 USB 调试”，勾上“始终允许”，再点“确定”。") else L.t("点“连接并允许”，手机会弹出“是否允许 USB 调试”。勾上“始终允许”，再点“确定”。网关只用它在本机启动音频服务。")) {
            if (tcpOpen && !allowing) Button(L.t("连接并允许")) {
                allowing = true; allowError = null
                scope.launch {
                    val failure = withContext(Dispatchers.IO) { runCatching { ShellLauncher(context).authorizeTcp() }.exceptionOrNull() }
                    allowError = failure?.let { it.message ?: it.javaClass.simpleName }
                    allowing = false
                    tick++
                }
            }
        }
    } else {
        Step(++n, L.t("打开开发者选项"), developer, L.t("到 设置 → 我的设备 → 全部参数与信息，连续点“OS 版本”（或“MIUI 版本”）7 次，看到“已处于开发者模式”即可。")) {
            Button(L.t("打开“我的设备”")) { context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
        }
        Step(++n, L.t("连上 Wi-Fi"), wifi, L.t("无线调试只能在 Wi-Fi 下工作。网关平时请一直连着家里的 Wi-Fi。")) {
            Button(L.t("打开 WLAN 设置")) { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        }
        Step(++n, L.t("打开无线调试"), wireless || paired, L.t("在开发者选项里找到“无线调试”，打开开关，弹出“允许吗”时点“允许”。配对完成后，网关会自己保持它开着。")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(L.t("打开“无线调试”")) { openWirelessDebugging(context) }
                Button(L.t("没有“无线调试”？")) { LocalAdb.setUsesTcp(context, true); tcp = true }
            }
        }
        val overlay = PairingOverlay.allowed(context)
        Step(++n, L.t("允许显示悬浮窗（配对时用）"), overlay || paired, L.t("配对时网关要在系统的配对窗口上方弹一个小输入框。找到“SimNest 网关”，打开“显示悬浮窗”（或“允许显示在其他应用的上层”）。只在配对时用。")) {
            Button(L.t("打开悬浮窗设置")) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}")))
            }
        }
        val pairingText = when (val s = pairing) {
            PairingFlow.Step.Idle -> L.t("点“开始配对”，网关会跳到开发者选项。点“无线调试”，再点“使用配对码配对设备”。窗口里出现 6 位配对码时，上方会弹出 SimNest 的输入框，把配对码填进去点“配对”。")
            PairingFlow.Step.WaitingForDialog -> L.t("正在等你打开“使用配对码配对设备”的窗口…")
            PairingFlow.Step.WaitingForCode -> L.t("窗口已打开。在上方 SimNest 的输入框里填入窗口里的 6 位数字，点“配对”。")
            PairingFlow.Step.Pairing -> L.t("正在配对…")
            PairingFlow.Step.Done -> L.t("配对成功。")
            is PairingFlow.Step.Failed -> s.message
        }
        Step(++n, L.t("配对无线调试（只需一次）"), paired, pairingText) {
            if (wireless && overlay) Button(L.t("开始配对")) { PairingFlow.start(context); openWirelessDebugging(context) }
        }
    }
    if (huawei) {
        Step(++n, L.t("允许自启动"), autostartConfirmed, L.t("在“应用启动管理”里找到“SimNest 网关”，关掉“自动管理”，把“允许自启动”和“允许后台活动”都打开。不打开的话，手机重启后网关不会自己运行。设置好后回来点“已打开”。")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(L.t("打开应用启动管理")) { openHuaweiStartup(context) }
                Button(L.t("已打开")) { autostartConfirmed = true; prefs.edit().putBoolean("autostart", true).apply() }
            }
        }
    }
    if (miui) {
        Step(++n, L.t("允许自启动"), autostartConfirmed, L.t("在“自启动管理”里找到“SimNest 网关”并打开。不打开的话，手机重启后网关不会自己运行。设置好后回来点“已打开”。")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(L.t("打开自启动管理")) {
                    runCatching {
                        context.startActivity(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
                    }
                }
                Button(L.t("已打开")) { autostartConfirmed = true; prefs.edit().putBoolean("autostart", true).apply() }
            }
        }
    }
    Step(++n, L.t("省电策略设为“无限制”"), unrestricted, L.t("系统限制了网关后台运行，重启后网关起不来。在省电策略里把“SimNest 网关”设为“无限制”。")) {
        Button(L.t("打开省电策略")) { openBatterySettings(context, miui) }
    }
    // Default SMS app (SMS_4B.md): MIUI asks for confirmation of every SMS a third-party app sends.
    val smsDefault = dev.cloudsim.gateway.sms.DefaultSms.isDefault(context)
    val askRole = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { tick++ }
    Step(++n, L.t("设为默认短信应用"), smsDefault, if (!miui) L.t("这样网关替 iPhone 发短信时，系统不会每条都弹“是否允许发送”。系统自带短信仍能打开看历史，新短信由网关存进系统并转给 iPhone；彩信暂不转发。以后可在系统设置的“默认应用”里改回。") else L.t("这样网关替 iPhone 发短信时，系统不会每条都弹“是否允许发送”。小米自带短信仍能打开看历史，新短信由网关存进系统并转给 iPhone；彩信暂不转发。以后可在 设置 → 应用设置 → 默认应用 → 短信 里改回。")) {
        Button(L.t("设为默认短信应用")) { dev.cloudsim.gateway.sms.DefaultSms.roleIntent(context)?.let { askRole.launch(it) } }
    }
    // Default phone app (DIALER_3F.md): call objects with the far end's answer, no answer-permission games with MIUI.
    val dialerDefault = dev.cloudsim.gateway.calls.CallHub.isDefault(context)
    Step(++n, L.t("设为默认电话应用"), dialerDefault, if (!miui) L.t("网关接管这台手机的来电和拨号界面，能知道对方何时接起，接听也不再依赖“接听电话”权限。日常仍在 iPhone 上打电话；本机上可以直接接听、拒接、拨号，紧急号码照常可拨。可在系统设置的“默认应用”里改回。") else L.t("网关接管这台手机的来电和拨号界面，能知道对方何时接起，接听也不再依赖“接听电话”权限。日常仍在 iPhone 上打电话；本机上可以直接接听、拒接、拨号，紧急号码照常可拨。可在 设置 → 应用设置 → 默认应用 → 电话 改回。")) {
        Button(L.t("设为默认电话应用")) { dev.cloudsim.gateway.calls.CallHub.roleIntent(context)?.let { askRole.launch(it) } }
    }
    // The running gateway starts the audio service and connects to it; before pairing and "开启网关" nothing does.
    val gatewayOn = GatewayService.isEnabled(context)
    Step(++n, L.t("音频服务运行中"), running, when (val s = bridge) {
        ShellLauncher.Status.Running -> L.t("通话声音由音频服务传送，本机听筒保持无声。")
        else -> if (!gatewayOn) L.t("配对并开启网关后会自动启动。") else when (s) {
        ShellLauncher.Status.Started -> L.t("正在启动…")
        is ShellLauncher.Status.NotRunning -> L.t("还没运行：%1\$s。完成上面的步骤后，网关会在 30 秒内自动启动它。", s.reason)
        ShellLauncher.Status.Unknown -> L.t("检查中…")
        ShellLauncher.Status.Running -> ""
        }
    })
}

@Composable
private fun Step(number: Int, title: String, done: Boolean, help: String, actions: (@Composable () -> Unit)? = null) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text((if (done) "✓ " else "$number. ") + title, style = MaterialTheme.typography.titleSmall,
                color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            if (!done) {
                Text(help, style = MaterialTheme.typography.bodySmall)
                actions?.invoke()
            }
        }
    }
}

@Composable
private fun Button(label: String, onClick: () -> Unit) = OutlinedButton(onClick = onClick) { Text(label) }

/** MIUI's per-app battery page; elsewhere the app's details page (battery is one tap from there). */
private fun openBatterySettings(context: Context, miui: Boolean) {
    val miuiPage = Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
        .putExtra("package_name", context.packageName)
        .putExtra("package_label", context.applicationInfo.loadLabel(context.packageManager).toString())
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
    if (!miui || runCatching { context.startActivity(miuiPage) }.isFailure) context.startActivity(details)
}

/** Huawei's and Honor's list of apps allowed to start themselves; the app's details page if neither opens. */
private fun openHuaweiStartup(context: Context) {
    val pages = listOf(
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
    )
    if (pages.none { runCatching { context.startActivity(Intent().setComponent(it)) }.isSuccess }) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")))
    }
}

/** Developer options scrolled to the wireless-debugging entry (the fragment key works on MIUI 14 / Android 13). */
private fun openWirelessDebugging(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(":settings:fragment_args_key", "toggle_adb_wireless"))
}
