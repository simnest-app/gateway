package dev.cloudsim.gateway.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a reboot (and the first unlock), starts the gateway again if the user had switched it on and its permissions
 * still hold (the user's 2026-11-04 decision). The service then brings the audio bridge back; nothing dials or plays.
 * "Answer phone calls" is not required once paired: MIUI takes it back at boot, and the service grants it again.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Also after the gateway updated itself (GATEWAY_UPDATE.md): nobody is at the phone to open it again.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val granted = GatewayService.missingPermissions(context).isEmpty()
        if (GatewayService.isEnabled(context) && granted) GatewayService.start(context)
    }
}
