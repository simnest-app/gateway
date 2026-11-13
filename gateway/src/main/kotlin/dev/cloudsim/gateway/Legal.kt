package dev.cloudsim.gateway

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** The same terms and privacy policy as the iPhone app (docs/legal, copied by scripts/sync-legal.sh), agreed to first. */
object Legal {
    const val VERSION = 1
    val docs = listOf("PRIVACY_POLICY" to L.t("隐私政策"), "TERMS_OF_USE" to L.t("用户协议"), "DISCLAIMER" to L.t("免责声明"))

    fun accepted(context: Context) = context.getSharedPreferences("legal", Context.MODE_PRIVATE).getInt("accepted", 0) >= VERSION
    fun accept(context: Context) = context.getSharedPreferences("legal", Context.MODE_PRIVATE).edit().putInt("accepted", VERSION).apply()

    /** The Markdown as plain text: marks dropped, tables as lines. */
    /** The text in this app's language: English, Traditional Chinese, or the Simplified Chinese that prevails. */
    fun text(context: Context, name: String): String = runCatching {
        val l = java.util.Locale.getDefault()
        val suffix = when {
            l.language != "zh" -> "_EN"
            l.script == "Hant" || l.country in setOf("TW", "HK", "MO") -> "_ZH_HANT"
            else -> ""
        }
        runCatching { context.assets.open("legal/$name$suffix.md") }.getOrElse { context.assets.open("legal/$name.md") }.bufferedReader().readText()
    }.getOrDefault(L.t("（文件缺失）")).lines().filterNot { it.trim().matches(Regex("^\\|[-:| ]+\\|$")) }.joinToString("\n") { line ->
        line.replace("**", "").replace("`", "").replace(Regex("^#+ "), "").let { if (it.startsWith("|")) it.trim('|').split("|").joinToString("  ·  ") { c -> c.trim() } else it }
    }
}

@Composable
fun LegalConsent(onAgree: () -> Unit) {
    val context = LocalContext.current
    var reading by remember { mutableStateOf<Pair<String, String>?>(null) }
    var declined by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(L.t("欢迎使用 SimNest 网关"), style = MaterialTheme.typography.titleLarge)
        Text(L.t("使用前请阅读并同意《用户协议》和《隐私政策》。几件重要的事："))
        Text(L.t("• 这台手机会接收来电和短信，加密后只转给你配对的 iPhone 和你授权的家人；服务器看不到内容。"))
        Text(L.t("• 需要你在系统弹窗里允许电话、短信、通讯录等权限，并把网关设为默认短信和电话应用。"))
        Text(L.t("• 通话和短信用这台手机里你自己的 SIM 卡，费用由运营商收取。请合法使用，不得用于诈骗、骚扰或群发。"))
        Text(L.t("• 不能经网关拨打紧急电话。"))
        Legal.docs.forEach { doc -> TextButton(onClick = { reading = doc }) { Text(L.t("阅读《%1\$s》", doc.second)) } }
        if (declined) Text(L.t("不同意就无法使用网关转接来电和短信。"), color = MaterialTheme.colorScheme.error)
        Button(onClick = { Legal.accept(context); onAgree() }, modifier = Modifier.fillMaxWidth()) { Text(L.t("同意并继续")) }
        TextButton(onClick = { declined = true }, modifier = Modifier.fillMaxWidth()) { Text(L.t("不同意")) }
    }
    reading?.let { doc ->
        AlertDialog(
            onDismissRequest = { reading = null },
            confirmButton = { TextButton(onClick = { reading = null }) { Text(L.t("关闭")) } },
            title = { Text(doc.second) },
            text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) { Text(Legal.text(context, doc.first)) } },
        )
    }
}

/** 首页底部：随时可以再看条款。 */
@Composable
fun LegalLinks() {
    val context = LocalContext.current
    var reading by remember { mutableStateOf<Pair<String, String>?>(null) }
    Column {
        Legal.docs.forEach { doc -> TextButton(onClick = { reading = doc }) { Text(doc.second) } }
    }
    reading?.let { doc ->
        AlertDialog(
            onDismissRequest = { reading = null },
            confirmButton = { TextButton(onClick = { reading = null }) { Text(L.t("关闭")) } },
            title = { Text(doc.second) },
            text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) { Text(Legal.text(context, doc.first)) } },
        )
    }
}

/** "开源许可" on the About page: what the gateway is built from, and each licence in full (assets/licenses). */
@Composable
fun OpenSourceLicenses() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<String?>(null) }
    val files = remember { runCatching { context.assets.list("licenses")?.filter { it != "INDEX.txt" }?.sorted() }.getOrNull().orEmpty() }
    fun text(name: String) = runCatching { context.assets.open("licenses/$name").use { it.readBytes().decodeToString() } }.getOrDefault("")
    TextButton(onClick = { open = true }) { Text(L.t("开源许可")) }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text(L.t("关闭")) } },
            title = { Text(L.t("开源许可")) },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    Text(text("INDEX.txt"), style = MaterialTheme.typography.bodySmall)
                    files.forEach { name -> TextButton(onClick = { reading = name }) { Text(name.removeSuffix(".txt")) } }
                }
            },
        )
    }
    reading?.let { name ->
        AlertDialog(
            onDismissRequest = { reading = null },
            confirmButton = { TextButton(onClick = { reading = null }) { Text(L.t("关闭")) } },
            title = { Text(name.removeSuffix(".txt")) },
            text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) { Text(text(name), style = MaterialTheme.typography.bodySmall) } },
        )
    }
}
