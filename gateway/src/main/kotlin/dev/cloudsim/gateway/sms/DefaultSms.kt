package dev.cloudsim.gateway.sms

import dev.cloudsim.gateway.L

import android.app.Activity
import android.app.Service
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The gateway as Redmi's default SMS app (docs/architecture/SMS_4B.md, the user's 2026-11-05 decision): MIUI asks a
 * person to confirm every SMS a third-party app sends, which no one at home would do. The default SMS app is not asked,
 * but must itself write what it receives and sends to the system's SMS store, and must offer the entry points Android
 * requires: SMS and MMS delivery, a compose screen and the quick reply service. The compose screen is minimal; phones
 * are where messages are read and written.
 */
object DefaultSms {
    const val TAG = "CloudSimGateway"

    fun isDefault(context: Context) = Telephony.Sms.getDefaultSmsPackage(context) == context.packageName

    fun roleIntent(context: Context): Intent? = context.getSystemService(RoleManager::class.java)
        ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_SMS) && !it.isRoleHeld(RoleManager.ROLE_SMS) }
        ?.createRequestRoleIntent(RoleManager.ROLE_SMS)

    /** Set by the gateway service while it runs; otherwise messages wait in the gateway's store for the phones' sync. */
    /** [subscription] is the SIM it came in on (DUAL_SIM.md), -1 when unknown. */
    @Volatile var onIncoming: ((peer: String, body: String, at: Long, subscription: Int) -> Unit)? = null

    fun writeInbox(context: Context, peer: String, body: String, at: Long, subscription: Int = -1) = runCatching {
        context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
            put(Telephony.Sms.ADDRESS, peer); put(Telephony.Sms.BODY, body); put(Telephony.Sms.DATE, at); put(Telephony.Sms.READ, 0); put(Telephony.Sms.SEEN, 0)
            if (subscription >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subscription)
        })
    }.onFailure { Log.w(TAG, "inbox write failed", it) }

    /** A sent (or failed) message in the system store, so the Redmi's own history stays complete. */
    fun writeSent(context: Context, peer: String, body: String, at: Long, ok: Boolean, subscription: Int = -1): Uri? = runCatching {
        context.contentResolver.insert(Telephony.Sms.CONTENT_URI, ContentValues().apply {
            put(Telephony.Sms.ADDRESS, peer); put(Telephony.Sms.BODY, body); put(Telephony.Sms.DATE, at); put(Telephony.Sms.READ, 1)
            if (subscription >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subscription)
            put(Telephony.Sms.TYPE, if (ok) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED)
        })
    }.onFailure { Log.w(TAG, "sent write failed", it) }.getOrNull()

    fun deliver(context: Context, peer: String, body: String, at: Long, subscription: Int = -1) {
        writeInbox(context, peer, body, at, subscription)
        onIncoming?.invoke(peer, body, at, subscription) ?: SmsStore(context).add(java.util.UUID.randomUUID().toString(), peer, body, at)
    }

    /** The SIM a received-SMS broadcast is for. */
    fun subscriptionOf(intent: Intent) = intent.getIntExtra(android.telephony.SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, intent.getIntExtra("subscription", -1))
}

/** SMS_DELIVER: only the default SMS app gets it; parts of a long message come together and are joined per sender. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val now = System.currentTimeMillis()
        val sub = DefaultSms.subscriptionOf(intent)
        parts.filterNotNull().groupBy { it.displayOriginatingAddress ?: "" }.forEach { (peer, list) ->
            DefaultSms.deliver(context, peer, list.joinToString("") { it.displayMessageBody ?: "" }, now, sub)
        }
    }
}

/**
 * MMS notifications: M1 does not download or relay MMS (SMS_4B.md). The phones are told one arrived, without content;
 * reading it needs switching the default SMS app back for a moment.
 */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(DefaultSms.TAG, "mms notification received, not downloaded")
        DefaultSms.onIncoming?.invoke(L.t("彩信"), L.t("收到一条彩信。网关暂不支持转发彩信，内容未下载。"), System.currentTimeMillis(), DefaultSms.subscriptionOf(intent))
    }
}

/** "Reply with a message" from the system's incoming-call screen. */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val peer = intent?.data?.schemeSpecificPart?.substringBefore('?')
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!peer.isNullOrBlank() && !text.isNullOrBlank()) sendLocal(this, peer, text)
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

/** A message typed on the Redmi itself: sent directly and written to the system store. */
fun sendLocal(context: Context, peer: String, body: String) {
    runCatching {
        val sms = context.getSystemService(SmsManager::class.java)
        val parts = sms.divideMessage(body)
        if (parts.size > 1) sms.sendMultipartTextMessage(peer, null, parts, null, null) else sms.sendTextMessage(peer, null, body, null, null)
        DefaultSms.writeSent(context, peer, body, System.currentTimeMillis(), ok = true)
    }.onFailure { Log.w(DefaultSms.TAG, "local send failed", it) }
}

/** The compose screen Android requires of a default SMS app (sms:, smsto:, mms:, mmsto:). Minimal on purpose. */
class ComposeSmsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preset = intent?.data?.schemeSpecificPart?.substringBefore('?') ?: ""
        val presetBody = intent?.getStringExtra("sms_body") ?: intent?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        setContent {
            MaterialTheme {
                var peer by remember { mutableStateOf(preset) }
                var body by remember { mutableStateOf(presetBody) }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(L.t("SimNest 网关 · 发短信"), style = MaterialTheme.typography.titleMedium)
                    Text(L.t("短信主要在 iPhone 上收发。这里只用于在本机临时发一条。"), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(peer, { peer = it }, label = { Text(L.t("号码")) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(body, { body = it }, label = { Text(L.t("内容")) }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        sendLocal(this@ComposeSmsActivity, peer, body)
                        setResult(Activity.RESULT_OK)
                        finish()
                    }, enabled = peer.isNotBlank() && body.isNotBlank()) { Text(L.t("发送")) }
                }
            }
        }
    }
}
