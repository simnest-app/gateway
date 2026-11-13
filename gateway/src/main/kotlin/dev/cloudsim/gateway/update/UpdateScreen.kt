package dev.cloudsim.gateway.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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

/** "软件更新" on the About page: look for a new version, install it with a tap, or let the gateway do it by itself. */
@Composable
fun UpdateCard() {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val state by Updater.current.collectAsState()
    var auto by remember { mutableStateOf(Updater.auto(context)) }
    val working = state is Updater.State.Checking || state is Updater.State.Downloading || state is Updater.State.Installing
    val offered = (state as? Updater.State.Available)?.release ?: (state as? Updater.State.Failed)?.release

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(L.t("软件更新"), style = MaterialTheme.typography.titleSmall)
            if (!Updater.configured) {
                Text(L.t("这个版本没有设置更新来源。"), style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            when (val s = state) {
                Updater.State.Idle -> Text(L.t("点“检查更新”看有没有新版本。"), style = MaterialTheme.typography.bodyMedium)
                Updater.State.Checking -> Text(L.t("正在检查…"), style = MaterialTheme.typography.bodyMedium)
                Updater.State.UpToDate -> Text(L.t("✓ 已是最新版本"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                is Updater.State.Available -> {
                    Text(L.t("有新版本 %1\$s（%2\$s）", s.release.versionName, s.release.versionCode), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    if (s.release.notes.isNotBlank()) Text(s.release.notes, style = MaterialTheme.typography.bodySmall)
                }
                is Updater.State.Downloading -> {
                    Text(L.t("正在从 %1\$s 下载 %2\$s… %3\$s%%", if (s.release.source == "gitee") "Gitee" else "GitHub", s.release.versionName, s.percent), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
                is Updater.State.Installing -> Text(L.t("正在安装 %1\$s，网关会自己重新启动…", s.release.versionName), style = MaterialTheme.typography.bodyMedium)
                is Updater.State.Failed -> Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !working, onClick = { scope.launch { withContext(Dispatchers.IO) { runCatching { Updater.check(app) } } } }) { Text(L.t("检查更新")) }
                if (offered != null) {
                    Button(enabled = !working, onClick = { scope.launch { withContext(Dispatchers.IO) { runCatching { Updater.install(app, offered) } } } }) { Text(L.t("下载并安装")) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(L.t("自动更新"))
                    Text(L.t("打开后，网关每天看两次有没有新版本，没有通话时自己装好并重新启动，不用到手机跟前。"), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = auto, onCheckedChange = { auto = it; Updater.setAuto(context, it) })
            }
            Text(L.t("新版本从 GitHub 和 Gitee 下载。安装前会核对：是这个应用、版本更新、签名和现在的一样；不一样就不装。通话中不更新。"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
