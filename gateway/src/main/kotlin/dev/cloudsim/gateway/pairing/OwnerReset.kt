package dev.cloudsim.gateway.pairing

import android.content.Context
import dev.cloudsim.gateway.net.ControlClient
import org.json.JSONObject

/**
 * "清除主人关联" (GATEWAY_RESET.md): done on the gateway phone, being at it is the proof. The service forgets the owner
 * and family first, and tells them; only when it agreed is anything forgotten here, so the two never disagree about
 * whether this gateway has an owner. After it the gateway is as before its first pairing, keeping its own key, its
 * messages and call log, and its settings on this screen.
 */
object OwnerReset {
    /** Blocking. Throws ServiceException when the service could not be reached or refused; nothing is cleared then. */
    fun run(context: Context, client: ControlClient) {
        client.call("POST", "/v1/gateways/${client.deviceId}/reset", JSONObject())
        clearLocal(context)
    }

    /**
     * The owner (and the one before it), the sharing policy with its family and revision, where each phone's messages
     * had got to, and the key the owner's phone was given for number digests. The revision goes too: the new owner's
     * first policy starts again from 1, which this gateway would otherwise refuse as older than the last one it had.
     */
    fun clearLocal(context: Context) {
        for (name in listOf("pairing", "sharing", "sms_since")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        context.getSharedPreferences("screening", Context.MODE_PRIVATE).edit().remove("digest_key").commit()
    }
}
