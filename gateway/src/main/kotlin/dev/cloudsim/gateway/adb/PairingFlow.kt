package dev.cloudsim.gateway.adb

import dev.cloudsim.gateway.L

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Pairing with this phone's wireless debugging. The system's pairing dialog closes (and its code expires) as soon as the
 * user leaves it, so the code is typed into a notification's reply field instead. The dialog opens a new loopback port;
 * the flow notes the open ports first, then watches for the new one (mDNS does not show a phone its own service on MIUI).
 */
object PairingFlow {
    private const val CHANNEL = "pairing"
    private const val ID = 2
    const val KEY_CODE = "code"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watching: Job? = null
    @Volatile private var pairingPort: Int? = null

    sealed interface Step {
        data object Idle : Step
        data object WaitingForDialog : Step
        data object WaitingForCode : Step
        data object Pairing : Step
        data object Done : Step
        data class Failed(val message: String) : Step
    }

    private val _step = MutableStateFlow<Step>(Step.Idle)
    val step: StateFlow<Step> = _step

    /** Call right before sending the user to the pairing dialog. */
    fun start(context: Context) {
        val app = context.applicationContext
        watching?.cancel()
        pairingPort = null
        _step.value = Step.WaitingForDialog
        notify(app, L.t("点“使用配对码配对设备”。弹出 6 位配对码后，下拉通知栏，在这里点“输入配对码”"), null)
        watching = scope.launch {
            val adb = LocalAdb(app)
            val before = adb.listeningPorts()
            // Three minutes for the user to open the dialog; it adds exactly one new port.
            repeat(90) {
                delay(2_000)
                val added = adb.listeningPorts() - before
                if (added.isNotEmpty()) {
                    pairingPort = added.first()
                    _step.value = Step.WaitingForCode
                    notify(app, L.t("配对窗口已打开。在弹出的输入框里填入窗口里的 6 位数字（窗口不要关）"), askCode(app))
                    PairingOverlay.show(app) { code -> submit(app, code, null) }
                    return@launch
                }
            }
            fail(app, L.t("3 分钟内没有看到配对窗口，请回到网关重新开始"))
        }
    }

    private fun fail(context: Context, message: String) {
        _step.value = Step.Failed(message)
        notify(context, message, null)
    }

    private fun askCode(context: Context): Notification.Action {
        val input = RemoteInput.Builder(KEY_CODE).setLabel(L.t("6 位配对码")).build()
        val reply = PendingIntent.getBroadcast(context, 3, Intent(context, CodeReceiver::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(null, L.t("输入配对码"), reply).addRemoteInput(input).build()
    }

    fun notify(context: Context, text: String, action: Notification.Action?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, L.t("无线调试配对"), NotificationManager.IMPORTANCE_HIGH))
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(L.t("SimNest 网关：设置音频服务"))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
        action?.let { builder.addAction(it) }
        manager.notify(ID, builder.build())
    }

    /** Pairs with a code from the floating box or the notification's reply field. */
    private fun submit(app: Context, code: String, done: BroadcastReceiver.PendingResult?) {
        val port = pairingPort
        if (port == null || code.length != 6) {
            notify(app, L.t("配对码要 6 位数字，请重新输入"), askCode(app))
            PairingOverlay.show(app) { submit(app, it, null) }
            done?.finish()
            return
        }
        PairingOverlay.hide(app)
        _step.value = Step.Pairing
        notify(app, L.t("正在配对…"), null)
        scope.launch {
            try {
                LocalAdb(app).pair(port, code)
                notify(app, L.t("配对成功，正在启动音频服务…"), null)
                ShellLauncher(app).afterPairing()
                _step.value = Step.Done
                notify(app, L.t("音频服务已启动。以后重启或换 Wi-Fi，网关会自己恢复。"), null)
            } catch (e: Exception) {
                fail(app, L.t("配对没有成功（%1\$s）。请关掉配对窗口，回到网关重新开始", e.message ?: e.javaClass.simpleName))
            } finally {
                done?.finish()
            }
        }
    }

    class CodeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()?.filter(Char::isDigit).orEmpty()
            submit(context.applicationContext, code, goAsync())
        }
    }
}
