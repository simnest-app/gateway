package dev.cloudsim.gateway

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.cloudsim.gateway.calls.CallHub
import dev.cloudsim.gateway.sms.DefaultSms

/**
 * What each gateway feature needs, checked against both layers a permission has on MIUI: the system grant, and the
 * per-app switch MIUI keeps on top of it (an app op it sets to "ignore" while the system still says granted, as with
 * SEND_SMS in 4b). Nothing is granted here; every fix opens the system dialog or the right settings page for the user.
 */
object Readiness {
    enum class Fix { ASK, MIUI_PAGE, SMS_ROLE, DIALER_ROLE, CONFIRM_NOTICE_SMS }

    /** [id] names the feature to the phones (the check before going out); the texts are this phone's language. */
    data class Item(val title: String, val ok: Boolean, val problem: String, val fix: Fix?, val ask: List<String> = emptyList(), val id: String = "")

    val miui: Boolean get() = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

    enum class State { GRANTED, NOT_GRANTED, BLOCKED_BY_MIUI }

    fun state(context: Context, permission: String): State {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return State.NOT_GRANTED
        val op = AppOpsManager.permissionToOp(permission) ?: return State.GRANTED
        val mode = runCatching {
            context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName)
        }.getOrDefault(AppOpsManager.MODE_ALLOWED)
        return if (mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED) State.BLOCKED_BY_MIUI else State.GRANTED
    }

    /** "读取通知类短信" has no public check; the user confirms it, as with MIUI's autostart switch. */
    fun noticeSmsConfirmed(context: Context) = context.getSharedPreferences("setup", Context.MODE_PRIVATE).getBoolean("notice_sms", false)
    fun confirmNoticeSms(context: Context) = context.getSharedPreferences("setup", Context.MODE_PRIVATE).edit().putBoolean("notice_sms", true).apply()

    fun items(context: Context): List<Item> {
        val paired = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        val dialer = CallHub.isDefault(context)
        val smsApp = DefaultSms.isDefault(context)
        fun need(id: String, title: String, effect: String, vararg perms: String, satisfied: Boolean = false): Item {
            if (satisfied) return Item(title, true, "", null, id = id)
            val states = perms.associateWith { state(context, it) }
            val blocked = states.filterValues { it == State.BLOCKED_BY_MIUI }.keys
            val missing = states.filterValues { it == State.NOT_GRANTED }.keys
            return when {
                missing.isNotEmpty() -> Item(title, false, L.t("还没允许“%1\$s”：%2\$s", missing.joinToString("”“") { label(it) }, effect), Fix.ASK, missing.toList(), id)
                blocked.isNotEmpty() -> Item(title, false, L.t("系统已允许，但 MIUI 权限里“%1\$s”被关了：%2\$s", blocked.joinToString("”“") { label(it) }, effect), Fix.MIUI_PAGE, id = id)
                else -> Item(title, true, "", null, id = id)
            }
        }
        return buildList {
            dev.cloudsim.gateway.calls.Lease.current?.let { lease ->
                add(Item(L.t("已确认最新权限"), lease.valid(), L.t("5 分钟内没能向服务确认最新权限，来电、短信等远程访问已暂停（本机电话不受影响）。连上服务后会自动恢复。"), null, id = "lease"))
            }
            add(need("calls", L.t("来电转给 iPhone"), L.t("来电不会转给 iPhone。"), Manifest.permission.READ_PHONE_STATE))
            if (Build.VERSION.SDK_INT >= 33) add(need("notify", L.t("网关通知"), L.t("网关的常驻通知和来电界面可能不显示。"), Manifest.permission.POST_NOTIFICATIONS))
            // As the default phone app the call objects carry the number and answering; otherwise these two are needed.
            add(need("caller_id", L.t("来电号码"), L.t("iPhone 来电只显示“未知号码”。"), Manifest.permission.READ_CALL_LOG, satisfied = dialer))
            add(need("answer", L.t("在本机接听"), L.t("网关不能替 iPhone 接起来电。"), Manifest.permission.ANSWER_PHONE_CALLS, satisfied = dialer || paired))
            add(need("dial", L.t("经网关拨出"), L.t("iPhone 不能经网关打电话。"), Manifest.permission.CALL_PHONE))
            add(need("sms_in", L.t("收短信"), L.t("新短信不会转给 iPhone。"), Manifest.permission.RECEIVE_SMS))
            add(need("sms_out", L.t("发短信"), L.t("iPhone 发不出短信。"), Manifest.permission.SEND_SMS))
            add(need("sms_read", L.t("核实发送结果、历史短信"), L.t("结果不明的短信无法核实，iPhone 也加载不了历史短信。"), Manifest.permission.READ_SMS))
            add(need("numbers", L.t("读取本机号码"), L.t("两张卡的号码要主人在 iPhone 上手动填写。"), Manifest.permission.READ_PHONE_NUMBERS))
            add(need("contacts", L.t("网关通讯录"), L.t("iPhone 上看不到这台手机里的联系人。"), Manifest.permission.READ_CONTACTS))
            add(need("contacts_write", L.t("存到网关通讯录"), L.t("iPhone 不能把号码存进这台手机的通讯录。"), Manifest.permission.WRITE_CONTACTS))
            add(Item(L.t("默认短信应用"), smsApp, if (miui) L.t("MIUI 会给每条外发短信弹确认框，家里没人时发不出去。") else L.t("不设的话，系统可能给每条外发短信弹确认框，家里没人时发不出去。"), Fix.SMS_ROLE, id = "sms_app"))
            if (miui) add(Item(L.t("读取通知类短信（MIUI）"), noticeSmsConfirmed(context),
                L.t("网关接管前收到的服务号短信（验证码、通知）读不到，历史短信里会缺这些。在 MIUI 权限页的“读取通知类短信”打开后回来点“已打开”。"), Fix.CONFIRM_NOTICE_SMS, id = "notice_sms"))
            add(Item(L.t("默认电话应用"), dialer, if (miui) L.t("拨出时从线路接通就开始计时；接听要靠“接听电话”权限，MIUI 更新后会收回。") else L.t("设了以后，拨出从线路接通就开始计时，接听也不再依赖“接听电话”权限。"), Fix.DIALER_ROLE, id = "dialer"))
        }
    }

    private fun label(permission: String) = when (permission) {
        Manifest.permission.READ_PHONE_STATE -> L.t("电话")
        Manifest.permission.POST_NOTIFICATIONS -> L.t("通知")
        Manifest.permission.READ_CALL_LOG -> L.t("通话记录")
        Manifest.permission.ANSWER_PHONE_CALLS -> L.t("接听电话")
        Manifest.permission.CALL_PHONE -> L.t("拨打电话")
        Manifest.permission.RECEIVE_SMS -> L.t("接收短信")
        Manifest.permission.SEND_SMS -> L.t("发送短信")
        Manifest.permission.READ_SMS -> L.t("读取短信")
        Manifest.permission.READ_PHONE_NUMBERS -> L.t("读取本机号码")
        Manifest.permission.READ_CONTACTS -> L.t("通讯录")
        Manifest.permission.WRITE_CONTACTS -> L.t("写入通讯录")
        else -> permission.substringAfterLast('.')
    }

    /** MIUI's own permission page for this app; elsewhere the app's details page. */
    fun openPermissionPage(context: Context) {
        val miuiPage = Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", context.packageName)
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        if (!miui || runCatching { context.startActivity(miuiPage) }.isFailure) context.startActivity(details)
    }
}

/** One line per feature, at the top while the gateway runs: what does not work now, why, and the one tap to fix it. */
@Composable
fun FeatureStatus() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2_000); tick++ } }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    tick.let { }
    val items = Readiness.items(context)
    val broken = items.filter { !it.ok }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (broken.isEmpty()) L.t("✓ 所有功能可用") else L.t("%1\$s 项功能用不了", broken.size), style = MaterialTheme.typography.titleSmall,
                color = if (broken.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            for (item in items) {
                Text((if (item.ok) "✓ " else "✗ ") + item.title, style = MaterialTheme.typography.bodyMedium,
                    color = if (item.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                if (item.ok) continue
                Text(item.problem, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (item.fix) {
                        Readiness.Fix.ASK -> {
                            OutlinedButton(onClick = { ask.launch(item.ask.toTypedArray()) }) { Text(L.t("允许")) }
                            // Once refused twice the system no longer shows a dialog; the settings page always works.
                            OutlinedButton(onClick = { Readiness.openPermissionPage(context) }) { Text(L.t("没弹窗？去权限页")) }
                        }
                        Readiness.Fix.MIUI_PAGE -> OutlinedButton(onClick = { Readiness.openPermissionPage(context) }) { Text(L.t("打开 MIUI 权限页")) }
                        Readiness.Fix.SMS_ROLE -> OutlinedButton(onClick = { DefaultSms.roleIntent(context)?.let { role.launch(it) } }) { Text(L.t("设为默认短信应用")) }
                        Readiness.Fix.DIALER_ROLE -> OutlinedButton(onClick = { CallHub.roleIntent(context)?.let { role.launch(it) } }) { Text(L.t("设为默认电话应用")) }
                        Readiness.Fix.CONFIRM_NOTICE_SMS -> {
                            OutlinedButton(onClick = { Readiness.openPermissionPage(context) }) { Text(L.t("打开 MIUI 权限页")) }
                            OutlinedButton(onClick = { Readiness.confirmNoticeSms(context); tick++ }) { Text(L.t("已打开")) }
                        }
                        null -> {}
                    }
                }
            }
        }
    }
}
