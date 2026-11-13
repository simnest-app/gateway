package dev.cloudsim.gateway.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps the shell audio bridge running without a computer. After pairing it grants this app WRITE_SECURE_SETTINGS
 * (the user's 2026-11-04 decision), used only to switch wireless debugging back on after a reboot or a Wi-Fi change.
 * MIUI also takes back "answer phone calls" on every reboot and update; the user decided (2026-11-04) that the gateway
 * grants that one permission back to itself over the shell. Nothing else is granted.
 */
class ShellLauncher(private val context: Context) {
    private val adb by lazy { LocalAdb(context) }

    fun canEnableWirelessDebugging() =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** On a phone without wireless debugging: has the user allow this app's key, then carries on as after pairing. */
    suspend fun authorizeTcp() {
        adb.authorizeTcp()
        afterPairing()
    }

    /** After pairing: grant the secure-settings permission, then start the bridge. */
    suspend fun afterPairing() {
        adb.session { it.shell("pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}") }
        delay(500)
        startBridge()
    }

    fun canAnswer() =
        context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED

    /**
     * Over one adb session: grants "answer phone calls" back if MIUI took it, and starts (or restarts) the bridge from
     * this app's current APK when [restartBridge].
     */
    suspend fun startBridge(restartBridge: Boolean = true) {
        if (!LocalAdb.usesTcp(context) && canEnableWirelessDebugging() && Settings.Global.getInt(context.contentResolver, ADB_WIFI, 0) != 1) {
            Settings.Global.putInt(context.contentResolver, ADB_WIFI, 1)
        }
        val info = context.applicationInfo
        val uid = info.uid
        // Two shell commands on one connection: a launch line naming BridgeMain in the same shell would match pkill and
        // kill that shell itself (the bracket only keeps the pattern from matching its own text).
        adb.session { kadb ->
            if (!canAnswer()) {
                kadb.shell("pm grant ${context.packageName} ${Manifest.permission.ANSWER_PHONE_CALLS}; " +
                    "appops set ${context.packageName} ANSWER_PHONE_CALLS allow")
            }
            if (!restartBridge) return@session
            kadb.shell("pkill -f dev.cloudsim.gateway.shell.[B]ridgeMain; true")
            kadb.shell("CLASSPATH=${info.sourceDir} nohup app_process /system/bin " +
                "dev.cloudsim.gateway.shell.BridgeMain $uid ${info.nativeLibraryDir} /sdcard/Android/data/${context.packageName}/files/bridge-token " +
                "> /data/local/tmp/cloudsim-bridge.log 2>&1 &")
        }
        if (restartBridge) status.value = Status.Started
    }

    sealed interface Status {
        data object Unknown : Status
        data object Running : Status
        data object Started : Status
        data class NotRunning(val reason: String) : Status
    }

    companion object {
        /** Settings.Global.ADB_WIFI_ENABLED, hidden from the SDK. */
        private const val ADB_WIFI = "adb_wifi_enabled"
        val status = MutableStateFlow<Status>(Status.Unknown)
        val current: StateFlow<Status> get() = status
    }
}
