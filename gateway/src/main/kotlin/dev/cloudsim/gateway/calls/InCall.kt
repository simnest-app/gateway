package dev.cloudsim.gateway.calls

import dev.cloudsim.gateway.L

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.TelephonyManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the Redmi's minimal call screen shows of the current call (docs/architecture/DIALER_3F.md). */
data class CallView(val number: String, val state: Int, val incoming: Boolean, val emergency: Boolean, val remote: Boolean, val speaker: Boolean, val muted: Boolean)

/**
 * The gateway as Redmi's default phone app (DIALER_3F.md, the user's 2026-11-05 decision). Telecom hands every call to
 * [GatewayInCallService]; this object passes them on to the relay and keeps what the minimal call screen shows.
 * Emergency calls are flagged so the relay leaves them entirely alone.
 */
object CallHub {
    const val TAG = "CloudSimGateway"

    fun isDefault(context: Context) = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage == context.packageName

    fun roleIntent(context: Context): Intent? = context.getSystemService(RoleManager::class.java)
        ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_DIALER) && !it.isRoleHeld(RoleManager.ROLE_DIALER) }
        ?.createRequestRoleIntent(RoleManager.ROLE_DIALER)

    @Volatile var service: GatewayInCallService? = null
    private val calls = mutableListOf<Call>()
    private val view = MutableStateFlow<CallView?>(null)
    val current: StateFlow<CallView?> get() = view

    /**
     * Set by the gateway service: the relay's side of a call. [onState] gets the TelephonyManager-style state with the
     * number and whether it is an emergency call; [onFarAnswered] fires when a dialed call's far end picks up.
     */
    @Volatile var onState: ((state: Int, number: String?, emergency: Boolean) -> Unit)? = null
    @Volatile var onFarAnswered: (() -> Unit)? = null
    /** Whether a paired phone, not the Redmi, is talking in the current call; set by the gateway service. */
    @Volatile var remote = false
        set(v) { field = v; refresh() }

    private var dialing = false

    @Synchronized fun active(): Call? = calls.firstOrNull { it.details.state != Call.STATE_DISCONNECTED }
    @Synchronized fun ringing(): Call? = calls.firstOrNull { it.details.state == Call.STATE_RINGING }

    fun number(call: Call): String = call.details.handle?.schemeSpecificPart ?: ""

    /** A SIM that can place calls, named by slot and carrier ("卡 1 · 中国联通"); never by the phone's model. */
    data class Card(val handle: PhoneAccountHandle, val label: String)

    @android.annotation.SuppressLint("MissingPermission")
    fun cards(context: Context): List<Card> = runCatching {
        val telecom = context.getSystemService(TelecomManager::class.java)
        val tm = context.getSystemService(TelephonyManager::class.java)
        val subs = context.getSystemService(android.telephony.SubscriptionManager::class.java)
        telecom.callCapablePhoneAccounts.mapIndexed { i, h ->
            val sub = runCatching { if (android.os.Build.VERSION.SDK_INT >= 30) tm.getSubscriptionId(h) else null }.getOrNull()
                ?: h.id?.toIntOrNull()
            val slot = sub?.let { id -> runCatching { subs.getActiveSubscriptionInfo(id)?.simSlotIndex }.getOrNull() }?.takeIf { it >= 0 } ?: i
            val name = runCatching { telecom.getPhoneAccount(h)?.label?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
            Card(h, listOfNotNull(L.t("卡 %1\$d", slot + 1), name).joinToString(" · "))
        }
    }.getOrDefault(emptyList())

    /**
     * A call placed without saying which SIM, on a phone with two and no default for calls: the system waits for the
     * phone app to ask. Without this the call stayed waiting, unseen, until hung up.
     */
    @Synchronized fun choose(handle: PhoneAccountHandle): Boolean =
        calls.firstOrNull { it.details.state == Call.STATE_SELECT_PHONE_ACCOUNT }?.let { it.phoneAccountSelected(handle, false); true } ?: false

    fun emergency(context: Context, call: Call): Boolean {
        val n = number(call)
        val tm = context.getSystemService(TelephonyManager::class.java)
        return call.details.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE) || (n.isNotEmpty() && runCatching { tm.isEmergencyNumber(n) }.getOrDefault(false))
    }

    @Synchronized fun added(context: Context, call: Call) {
        calls += call
        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) = changed(context, call)
        })
        changed(context, call)
    }

    @Synchronized fun removed(call: Call) {
        calls -= call
        if (calls.isEmpty()) {
            dialing = false
            remote = false
            onState?.invoke(TelephonyManager.CALL_STATE_IDLE, null, false)
        }
        refresh()
    }

    private fun changed(context: Context, call: Call) {
        val number = number(call)
        val emergency = emergency(context, call)
        dev.cloudsim.gateway.GatewayLog.detail("telecom state=${call.details.state} dialing=$dialing emergency=$emergency")
        when (call.details.state) {
            Call.STATE_RINGING -> {
                // The user's choice: Redmi stays quiet and the call rings on the iPhones only (an emergency callback still rings).
                if (Quiet.on(context) && !emergency) runCatching { context.getSystemService(TelecomManager::class.java)?.silenceRinger() }
                onState?.invoke(TelephonyManager.CALL_STATE_RINGING, number, emergency)
            }
            Call.STATE_DIALING, Call.STATE_CONNECTING -> if (!dialing) {
                dialing = true
                onState?.invoke(TelephonyManager.CALL_STATE_OFFHOOK, number, emergency)
            }
            Call.STATE_ACTIVE -> if (dialing) onFarAnswered?.invoke() else onState?.invoke(TelephonyManager.CALL_STATE_OFFHOOK, number, emergency)
            Call.STATE_DISCONNECTED -> Unit
        }
        when (call.details.state) {
            Call.STATE_RINGING -> Screens.ringing(context, number)
            Call.STATE_SELECT_PHONE_ACCOUNT -> Screens.choosing(context, number)
            else -> Screens.ongoing(context, active())
        }
        refresh()
    }

    fun refresh() {
        val c = active() ?: return run { view.value = null }
        val audio = service?.callAudioState
        val ctx = service
        view.value = CallView(number(c), c.details.state, c.details.callDirection == Call.Details.DIRECTION_INCOMING,
            ctx != null && emergency(ctx, c), remote, audio?.route == CallAudioState.ROUTE_SPEAKER, audio?.isMuted == true)
    }

    fun answer(): Boolean = ringing()?.let { it.answer(VideoProfile.STATE_AUDIO_ONLY); true } ?: false

    /** Rejects a ringing call, or hangs up the current one. */
    fun end(): Boolean {
        ringing()?.let { it.reject(false, null); return true }
        active()?.let { it.disconnect(); return true }
        return false
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** One key in the active cellular call: the tone for a moment, as a press on the keypad (as default phone app). */
    fun dtmf(digit: Char): Boolean {
        val call = active()?.takeIf { it.details.state == Call.STATE_ACTIVE } ?: return false
        call.playDtmfTone(digit)
        main.postDelayed({ call.stopDtmfTone() }, DTMF_TONE_MS)
        return true
    }

    private const val DTMF_TONE_MS = 160L

    fun speaker(on: Boolean) = service?.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE)
    fun mute(on: Boolean) = service?.setMuted(on)
}

/** Whether Redmi itself rings for incoming calls; the owner switches it off on the gateway's screen. */
object Quiet {
    private fun prefs(context: Context) = context.getSharedPreferences("calls", Context.MODE_PRIVATE)
    fun on(context: Context) = prefs(context).getBoolean("redmi_silent", false)
    fun set(context: Context, quiet: Boolean) = prefs(context).edit().putBoolean("redmi_silent", quiet).apply()
}

/** Telecom binds this as the call screen while the gateway is the default phone app. */
class GatewayInCallService : InCallService() {
    override fun onCreate() { super.onCreate(); CallHub.service = this }
    override fun onDestroy() { CallHub.service = null; super.onDestroy() }
    override fun onCallAdded(call: Call) = CallHub.added(this, call)
    override fun onCallRemoved(call: Call) { CallHub.removed(call); if (CallHub.active() == null) Screens.clear(this) }
    override fun onCallAudioStateChanged(audioState: CallAudioState?) = CallHub.refresh()
}

/** Notifications for the minimal call screen: full-screen for a ringing call, ongoing with hang-up otherwise. */
object Screens {
    private const val CHANNEL = "calls"
    private const val ID = 7

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java).also {
        it.createNotificationChannel(NotificationChannel(CHANNEL, L.t("通话"), NotificationManager.IMPORTANCE_HIGH))
    }

    private fun action(context: Context, what: String) = PendingIntent.getBroadcast(context, what.hashCode(),
        Intent(context, CallActionReceiver::class.java).setAction(what), PendingIntent.FLAG_IMMUTABLE)

    private fun screen(context: Context) = PendingIntent.getActivity(context, 0, Intent(context, InCallActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)

    fun ringing(context: Context, number: String) {
        manager(context).notify(ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call).setContentTitle(L.t("来电")).setContentText(number.ifEmpty { L.t("未知号码") })
            .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setFullScreenIntent(screen(context), true).setContentIntent(screen(context))
            .addAction(Notification.Action.Builder(null, L.t("接听"), action(context, "answer")).build())
            .addAction(Notification.Action.Builder(null, L.t("拒接"), action(context, "end")).build()).build())
    }

    /**
     * A dialed call waiting for a SIM. Each SIM is a button on the notification itself: MIUI keeps the gateway from
     * bringing up a screen on its own ("后台弹出界面"), which the user may allow, and then the call screen asks at once.
     */
    fun choosing(context: Context, number: String) {
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call).setContentTitle(L.t("选择用哪张卡拨打")).setContentText(number.ifEmpty { L.t("未知号码") })
            .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setFullScreenIntent(screen(context), true).setContentIntent(screen(context))
        CallHub.cards(context).take(2).forEachIndexed { i, card -> builder.addAction(Notification.Action.Builder(null, card.label, action(context, "card$i")).build()) }
        manager(context).notify(ID, builder.addAction(Notification.Action.Builder(null, L.t("取消"), action(context, "end")).build()).build())
        runCatching { context.startActivity(Intent(context, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(CallHub.TAG, "card choice screen", it) }
    }

    fun ongoing(context: Context, call: Call?) {
        if (call == null) return clear(context)
        manager(context).notify(ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call).setContentTitle(L.t("通话中")).setContentText(CallHub.number(call).ifEmpty { L.t("未知号码") })
            .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setContentIntent(screen(context))
            .addAction(Notification.Action.Builder(null, L.t("挂断"), action(context, "end")).build()).build())
    }

    fun clear(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID)
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "answer" -> CallHub.answer()
            "end" -> CallHub.end()
            "card0", "card1" -> CallHub.cards(context).getOrNull(intent.action!!.last() - '0')?.let { CallHub.choose(it.handle) }
        }
    }
}

/** The Redmi's call screen: who, state, answer/reject or hang up, speaker, mute. Calls are meant for the iPhones. */
class InCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                val call by CallHub.current.collectAsState()
                val c = call
                androidx.compose.runtime.LaunchedEffect(c == null) { if (c == null) finish() }
                if (c == null) return@MaterialTheme
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(c.number.ifEmpty { L.t("未知号码") }, style = MaterialTheme.typography.headlineMedium)
                    Text(when (c.state) {
                        Call.STATE_RINGING -> L.t("来电")
                        Call.STATE_DIALING, Call.STATE_CONNECTING -> L.t("正在呼叫…")
                        Call.STATE_ACTIVE -> if (c.remote) L.t("通话由 iPhone 进行") else L.t("通话中")
                        Call.STATE_HOLDING -> L.t("保持中")
                        Call.STATE_SELECT_PHONE_ACCOUNT -> L.t("选择用哪张卡拨打")
                        else -> L.t("正在结束…")
                    } + if (c.emergency) L.t("（紧急呼叫）") else "")
                    if (c.state == Call.STATE_SELECT_PHONE_ACCOUNT) {
                        val cards = remember { CallHub.cards(this@InCallActivity) }
                        cards.forEach { card -> Button({ CallHub.choose(card.handle) }, Modifier.fillMaxWidth()) { Text(L.t("用 %1\$s 拨打", card.label)) } }
                        OutlinedButton({ CallHub.end() }, Modifier.fillMaxWidth()) { Text(L.t("取消")) }
                    } else if (c.state == Call.STATE_RINGING) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button({ CallHub.answer() }) { Text(L.t("接听")) }
                            OutlinedButton({ CallHub.end() }) { Text(L.t("拒接")) }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button({ CallHub.end() }) { Text(L.t("挂断")) }
                            if (!c.remote) {
                                OutlinedButton({ CallHub.speaker(!c.speaker) }) { Text(if (c.speaker) L.t("关免提") else L.t("免提")) }
                                OutlinedButton({ CallHub.mute(!c.muted) }) { Text(if (c.muted) L.t("取消静音") else L.t("静音")) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The dial screen Android requires of a default phone app. Any number, emergency numbers included, goes straight to the
 * system; the gateway never relays or touches an emergency call (DIALER_3F.md).
 */
class DialActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preset = intent?.data?.schemeSpecificPart ?: ""
        setContent {
            MaterialTheme {
                var number by remember { mutableStateOf(preset) }
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(L.t("SimNest 网关 · 拨号"), style = MaterialTheme.typography.titleMedium)
                    Text(L.t("日常请在 iPhone 上打电话。这里用于在本机直接拨号，紧急号码（110、119、120、112）也在这里拨。"), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(number, { number = it.filter { ch -> ch.isDigit() || ch in "+*#" } }, label = { Text(L.t("号码")) }, modifier = Modifier.fillMaxWidth())
                    // Two SIMs: one button for each, so the call never waits for a choice.
                    val cards = remember { CallHub.cards(this@DialActivity) }
                    fun dial(card: CallHub.Card?) = runCatching {
                        val extras = Bundle().apply { card?.let { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it.handle) } }
                        @Suppress("MissingPermission")
                        getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), extras)
                    }.onFailure { Log.w(CallHub.TAG, "local dial failed", it) }
                    if (cards.size > 1) {
                        cards.forEach { card -> Button({ dial(card) }, Modifier.fillMaxWidth(), enabled = number.isNotBlank()) { Text(L.t("用 %1\$s 拨打", card.label)) } }
                    } else {
                        Button({ dial(null) }, enabled = number.isNotBlank()) { Text(L.t("拨打")) }
                    }
                }
            }
        }
    }
}
