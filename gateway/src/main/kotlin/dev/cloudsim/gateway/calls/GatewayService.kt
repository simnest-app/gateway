package dev.cloudsim.gateway.calls

import dev.cloudsim.gateway.L

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.cloudsim.gateway.BuildInfo
import dev.cloudsim.gateway.Diagnostics
import dev.cloudsim.gateway.GatewayLog
import dev.cloudsim.gateway.crypto.BoxKeys
import dev.cloudsim.gateway.crypto.Seal
import dev.cloudsim.gateway.sms.DefaultSms
import dev.cloudsim.gateway.sms.PrefsSince
import dev.cloudsim.gateway.sms.SmsRelay
import dev.cloudsim.gateway.sms.SmsStore
import dev.cloudsim.gateway.sms.SystemRadio
import org.json.JSONObject
import dev.cloudsim.gateway.adb.ShellLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import dev.cloudsim.gateway.MainActivity
import dev.cloudsim.gateway.identity.KeystoreSigner
import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ServiceException
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Keeps the gateway online while the user has it switched on: watches the system phone state, reports calls and
 * carries out answer/end requests. Visible as an ongoing notification with an off switch; never restarts on its own
 * after the user turns it off, and does not start at boot.
 */
class GatewayService : Service() {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val poller = Executors.newSingleThreadExecutor()
    private val supervisor = Executors.newSingleThreadScheduledExecutor()
    private val renewer = Executors.newSingleThreadScheduledExecutor()
    private val lease = Lease { android.os.SystemClock.elapsedRealtime() }
    private val launcher by lazy { ShellLauncher(this) }
    @Volatile private var running = false
    private lateinit var relay: CallRelay
    private var unregister: () -> Unit = {}
    private lateinit var smsRelay: SmsRelay
    /** SMS work is network-bound (sealing for each reader, delivering); keep it off the call thread. */
    private val smsWorker = Executors.newSingleThreadScheduledExecutor()
    /** Contacts and call log answers: reading and sealing a long list must not hold up calls or SMS. */
    private val recordsWorker = Executors.newSingleThreadExecutor()
    private lateinit var records: dev.cloudsim.gateway.records.RecordsRelay
    private lateinit var ownRelayRequests: dev.cloudsim.gateway.relay.OwnRelayRequests
    /** Checks the owner's own relay now and again; its own thread, as a check waits on the network. */
    private val relayChecker = Executors.newSingleThreadScheduledExecutor()
    /** Call recordings (RECORDING_V7.md): encoding and writing on their own thread. */
    private val recWorker = Executors.newSingleThreadScheduledExecutor()
    /** Settles, once, which numbers were here before the recording notice (RECORDING_NOTICE.md 5). */
    private var noticeMigration: () -> Unit = {}
    private lateinit var recorder: dev.cloudsim.gateway.recording.Recorder
    private lateinit var recordings: dev.cloudsim.gateway.recording.RecordingRelay
    private var unregisterSms: () -> Unit = {}

    /**
     * Received SMS (docs/architecture/SMS_4A.md), with the "receive SMS" permission. Parts of a long message arrive in one
     * broadcast and are joined per sender. The system keeps its own copy as usual; nothing here deletes or blocks it.
     */
    private fun watchSms() {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // The default SMS app gets the same message through SMS_DELIVER; handle it once.
                if (DefaultSms.isDefault(context)) return
                val parts = android.provider.Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
                val now = System.currentTimeMillis()
                val n = numbers.numberIdFor(DefaultSms.subscriptionOf(intent))
                parts.filterNotNull().groupBy { it.displayOriginatingAddress ?: "" }.forEach { (peer, list) ->
                    val body = list.joinToString("") { it.displayMessageBody ?: "" }
                    smsWorker.execute { smsRelay.onReceived(peer, body, now, n) }
                    mcpEvent("sms.new", dev.cloudsim.gateway.mcp.Cap.SMS, n, JSONObject().put("peer", peer).put("at", now))
                }
            }
        }
        val filter = android.content.IntentFilter(android.provider.Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        // The broadcast is sent by the system and guarded by BROADCAST_SMS, so only the system can deliver it.
        ContextCompat.registerReceiver(this, receiver, filter, Manifest.permission.BROADCAST_SMS, null, ContextCompat.RECEIVER_EXPORTED)
        unregisterSms = { runCatching { unregisterReceiver(receiver) } }
    }

    private var unregisterSubscriptions: () -> Unit = {}
    /** Made in onStartCommand: a Service has no context while it is constructed. */
    private var cellular: CellularPath? = null
    /** Held: SharedPreferences keeps listeners weakly. */
    private var mappingListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    private var mcp: dev.cloudsim.gateway.mcp.McpRuntime? = null
    /** Held: SharedPreferences keeps listeners weakly. */
    private var mcpListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** For tools waiting on MCP: that something happened, never what was said (they read that themselves, if allowed). */
    private fun mcpEvent(type: String, cap: dev.cloudsim.gateway.mcp.Cap, numberId: String?, data: JSONObject) =
        runCatching { dev.cloudsim.gateway.mcp.Mcp.events.add(type, cap, numberId, data) }

    private fun mcpCallState(state: Int, number: String?) = mcpEvent("call.state", dev.cloudsim.gateway.mcp.Cap.CALLS, null, JSONObject()
        .put("state", when (state) { TelephonyManager.CALL_STATE_RINGING -> "ringing"; TelephonyManager.CALL_STATE_OFFHOOK -> "in_call"; else -> "idle" })
        .apply { number?.takeIf { it.isNotEmpty() }?.let { put("number", it) } })

    private lateinit var screener: dev.cloudsim.gateway.screening.SystemScreener
    private lateinit var screeningRequests: dev.cloudsim.gateway.screening.ScreeningRequests

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY
        running = true
        Log.i(TAG, "gateway service starting ${dev.cloudsim.gateway.BuildConfig.VERSION_NAME}")
        GatewayLog.start(this)
        goForeground()
        keepForeground()
        val signer = KeystoreSigner()
        val client = ControlClient(BuildInfo.service, signer)
        val connection = BridgeConnection(File(getExternalFilesDir(null), "bridge-token"), { message ->
            when {
                message.optString("type") == "mapping_state" -> {
                    PortMapping.state = message.optString("state", "off")
                    Log.i(TAG, "port mapping: ${PortMapping.state}")
                }
                message.optString("type").startsWith("probe") -> probeWorker.execute { probes.onBridge(message) }
                // A recording's audio and its end (RECORDING_V7.md): on their own thread, so encoding never holds up calls.
                message.optString("type") == "pcm" -> recWorker.execute { recorder.onPcm(message.optString("call_id"), message.optString("data")) }
                message.optString("type") == "pcm_end" -> recWorker.execute { recorder.onPcmEnd(message.optString("call_id"), message.optString("reason")) }
                // The recording notice went out to the far party, or could not (RECORDING_NOTICE.md 8).
                message.optString("type") == "noticed" -> recWorker.execute {
                    recorder.onNoticed(message.optString("call_id"), message.optString("id"), message.optBoolean("ok"), message.optString("reason"))
                }
                message.optString("type") == "file_need" -> recordsWorker.execute { recordings.onFileNeed(message) }
                else -> worker.execute { relay.onBridge(message) }
            }
        }, { Log.i(TAG, it) }, { PortMapping.command(this) })
        // The owner's port-mapping switch reaches the bridge as soon as it changes (DIRECT_PATHS.md 4).
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        mappingListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PortMapping.KEY) worker.execute { connection.send(PortMapping.command(this)) }
        }.also { prefs.registerOnSharedPreferenceChangeListener(it) }
        worker.execute { connection.reachable() }
        // Offers and self-tests carry this phone's cellular network, for direct calls over IPv6 (CELLULAR_DIRECT_RESEARCH.md).
        val path = CellularPath(this) { Log.i(TAG, it) }.also { it.start() }
        cellular = path
        val bridge = object : MediaBridge { override fun send(command: JSONObject) = connection.send(path.annotate(command)) }
        val pairing = getSharedPreferences("pairing", MODE_PRIVATE)
        val owner = { pairing.getString("phone_id", null) }
        val ownerState = PrefsOwnerState(pairing)
        keeper = PolicyKeeper(client, owner, lease, { getSharedPreferences("sharing", MODE_PRIVATE).edit().putString("policy", it).apply() }, {
            // Carried out on the threads that use it, then reported, so "applied" means it is in force.
            val done = java.util.concurrent.CountDownLatch(2)
            worker.execute { relay.onPolicyChanged(); done.countDown() }
            smsWorker.execute { smsRelay.onPolicy(); done.countDown() }
            done.await(5, TimeUnit.SECONDS)
        }, { Log.i(TAG, it) }, { ownerState.previous })
        keeper.load(getSharedPreferences("sharing", MODE_PRIVATE).getString("policy", null))
        owners = OwnerKeeper(client, signer, ownerState, { keeper.policy?.revision ?: 0 }, log = { Log.i(TAG, it) })
        val scheduler = Scheduler { ms, task ->
            val f = worker.schedule(task, ms, TimeUnit.MILLISECONDS)
            return@Scheduler { f.cancel(false) }
        }
        val boxes = BoxKeys(this, client, signer)
        val sealer = object : Sealer {
            override fun seal(kind: String, to: String, id: String, plaintext: ByteArray) =
                boxes.recipient(to)?.let { Seal.seal(kind, client.deviceId, to, id, it, plaintext, signer) }
            override fun open(envelope: JSONObject, kind: String, senderKey: ByteArray) =
                Seal.open(envelope, kind, client.deviceId, senderKey, boxes.decrypter)
        }
        Lease.current = lease
        val recordingPrefs = getSharedPreferences("recording", MODE_PRIVATE)
        val recordingSettings = dev.cloudsim.gateway.recording.RecordingSettings({ recordingPrefs.getString("numbers", null) }, { recordingPrefs.edit().putString("numbers", it).apply() })
        val free = { filesDir.usableSpace }
        // The notice played to the far party before a recording (RECORDING_NOTICE.md 3): one clip a language, in the
        // app itself, 16 kHz mono PCM; read when first wanted.
        val noticeClips = java.util.concurrent.ConcurrentHashMap<String, ShortArray>()
        val noticeClip = { lang: String ->
            noticeClips[lang] ?: runCatching {
                val raw = assets.open("notice/$lang.pcm").use { it.readBytes() }
                val b = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                ShortArray(b.remaining()).also { b.get(it) }
            }.onFailure { Log.i(TAG, "recording notice clip $lang not readable: ${it.javaClass.simpleName}") }.getOrNull()?.also { noticeClips[lang] = it }
        }
        // The numbers this gateway had before the notice came keep recording without it (the user's 2026-11-11 choice);
        // a gateway not paired yet is a new one, and its numbers tell. Done once, when the numbers are known.
        if (!recordingPrefs.contains("notice_existing") && pairing.getString("phone_id", null) == null) recordingPrefs.edit().putBoolean("notice_existing", true).apply()
        val keepExisting = {
            if (!recordingPrefs.contains("notice_existing")) {
                val ids = numbers.current().map { it.numberId }
                if (ids.isNotEmpty()) {
                    recordingSettings.keepWithoutNotice(ids + recordingSettings.all().keys)
                    recordingPrefs.edit().putBoolean("notice_existing", true).apply()
                    Log.i(TAG, "recording notice: ${ids.size} number(s) from before keep recording without it")
                }
            }
        }
        recorder = dev.cloudsim.gateway.recording.Recorder(dev.cloudsim.gateway.recording.SqlRecordingStore(this), dev.cloudsim.gateway.recording.TinkVault(this),
            { dev.cloudsim.gateway.recording.MediaCodecAac() }, recordingSettings, free, bridge, log = { Log.i(TAG, it) },
            onState = { state -> recordsWorker.execute { recordings.broadcast(state) } },
            // The family phones in the call: each one's stretches are kept with the recording (RECORDING_V7.md 15).
            participants = { relay.participants - setOfNotNull(owner()) },
            notice = noticeClip,
            after = { ms, task ->
                val f = recWorker.schedule(task, ms, TimeUnit.MILLISECONDS)
                return@Recorder { f.cancel(false) }
            })
        // Call screening (SCREENING_ASSISTANT.md): the caller's message is kept as this call's recording, on the recorder's
        // thread after its start, so it is never lost to ordering.
        screener = dev.cloudsim.gateway.screening.SystemScreener(this, connection::reachable,
            { callId -> recWorker.execute { recorder.control(callId, true).onFailure { Log.i(TAG, "screening message not recorded: ${it.message}") } } }) { Log.i(TAG, it) }
        // The owner's own relay (RELAY_SPLIT.md 17): which relays a call may use is the owner's signed choice.
        dev.cloudsim.gateway.relay.OwnRelays.mode = { policy?.relay.orEmpty() }
        val ownRelay = dev.cloudsim.gateway.relay.OwnRelays.get(this) { Log.i(TAG, it) }
        ownRelayRequests = dev.cloudsim.gateway.relay.OwnRelayRequests(client, sealer, owner, { policy }, ownRelay, lease::valid) { Log.i(TAG, it) }
        relayChecker.scheduleWithFixedDelay({ if (ownRelay.relay != null) runCatching { ownRelay.check() } }, 20_000,
            dev.cloudsim.gateway.relay.OwnRelayKeeper.CHECK_EVERY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        // This gateway's round trip to each of the service's relays (RELAY_SPLIT.md 6.2), by which calls are handed the nearest.
        val measurer = dev.cloudsim.gateway.relay.RelayMeasurer(client, {
            val manager = getSystemService(android.net.ConnectivityManager::class.java)
            val caps = runCatching { manager?.activeNetwork?.let(manager::getNetworkCapabilities) }.getOrNull()
            when {
                caps == null -> ""
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> ""
            }
        }, log = { Log.i(TAG, it) })
        relayChecker.scheduleWithFixedDelay({ runCatching { measurer.run() } }, 45_000,
            dev.cloudsim.gateway.relay.OwnRelayKeeper.CHECK_EVERY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        relay = CallRelay(client, SystemTelephony(), bridge, signer, owner, { policy }, scheduler, sealer, log = { Log.i(TAG, it) }, remote = lease::valid, relays = ownRelay::ice,
            watcher = object : CallWatcher {
                override fun started(callId: String, numberId: String?, peer: String?, outgoing: Boolean, assisted: Boolean) =
                    recWorker.execute { runCatching { keepExisting() }; recorder.started(callId, numberId, peer, outgoing, assisted) }
                override fun ended(callId: String) = recWorker.execute { recorder.ended(callId) }
            }, screener = screener)
        recordings = dev.cloudsim.gateway.recording.RecordingRelay(client, sealer, owner, recorder, recordingSettings, { numbers.current().map { it.numberId } },
            { relay.participants }, { relay.busy }, free, { cmd -> bridge.send(cmd) }, lease::valid, log = { Log.i(TAG, it) },
            rights = { device, number -> policy?.members?.firstOrNull { it.deviceId == device }?.let { m -> if (number != null) m.on(number).rec else m.rec } ?: RecRights() })
        noticeMigration = keepExisting
        // Only while remote access is allowed, like calls and messages (REVOKE_LEASE_5.md).
        probes = ProbeRelay(client, signer, owner, { policy }, { cmd -> lease.valid() && bridge.send(cmd) }, { relay.busy }, { Log.i(TAG, it) }, sealer,
            { to ->
                GatewayStatus.collect(this, numbers.current(), owner(), policy, to, cellular?.condition()).apply {
                    // The gateway's own self-test, for the owner alone: what it found of its network, the service and the relays.
                    if (to == owner()) dev.cloudsim.gateway.selftest.SelfTests.forOwner(this@GatewayService) { relay.busy }?.let { put("selftest", it) }
                }
            }, recordings, relays = ownRelay::ice)
        // As the default phone app (DIALER_3F.md) Telecom's call objects drive the relay; otherwise the phone state does.
        CallHub.onState = { state, number, emergency ->
            worker.execute { relay.onPhoneState(state, number, emergency); CallHub.remote = relay.remoteParty }
            // Not for a call to or from an emergency number: tools hear nothing of those (GATEWAY_MCP.md 4.3).
            if (!emergency) mcpCallState(state, number)
        }
        CallHub.onFarAnswered = { worker.execute { relay.onFarAnswered() } }
        numbers = dev.cloudsim.gateway.numbers.NumberBook(client, dev.cloudsim.gateway.numbers.SystemSims(this) { Log.i(TAG, it) }, dev.cloudsim.gateway.numbers.PrefsBindings(this), sealer, owner) { Log.i(TAG, it) }
        liveNumbers = { numbers.current() }
        dev.cloudsim.gateway.update.Updater.schedule(this)
        dev.cloudsim.gateway.selftest.Freezes.start(this)
        val radio = SystemRadio(this, { numbers.subscriptionFor(it) }, { numbers.numberIdFor(it) }) { id, part, ok -> smsWorker.execute { smsRelay.onPart(id, part, ok) } }
        ContextCompat.registerReceiver(this, radio.receiver, android.content.IntentFilter("$packageName.SMS_SENT"), ContextCompat.RECEIVER_NOT_EXPORTED)
        val smsScheduler = Scheduler { ms, task ->
            val f = smsWorker.schedule(task, ms, TimeUnit.MILLISECONDS)
            return@Scheduler { f.cancel(false) }
        }
        smsRelay = SmsRelay(client, SmsStore(this), PrefsSince(this), sealer, radio, smsScheduler, owner, { policy }, trace = { Log.i(TAG, it) }, remote = lease::valid)
        smsWorker.execute { smsRelay.onStart() }
        // On MIUI a SIM's phone account id is its subscription id (DUAL_SIM.md).
        records = dev.cloudsim.gateway.records.RecordsRelay(client, sealer, owner, { policy },
            dev.cloudsim.gateway.screening.ScreenedCalls(dev.cloudsim.gateway.records.SystemRecords(this) { account -> account?.toIntOrNull()?.let(numbers::numberIdFor) }, screener.history),
            lease::valid) { Log.i(TAG, it) }
        // The MCP server for the user's own tools (GATEWAY_MCP.md): off until switched on on this phone's screen, and
        // apart from everything the iPhones do; a problem in it must never reach calls.
        mcp = dev.cloudsim.gateway.mcp.McpRuntime(this, dev.cloudsim.gateway.mcp.GatewayMcpData(this, { numbers.current() },
            dev.cloudsim.gateway.screening.ScreenedCalls(dev.cloudsim.gateway.records.SystemRecords(this) { account -> account?.toIntOrNull()?.let(numbers::numberIdFor) }, screener.history),
            SmsStore(this), screener.transcripts, { policy }, owner,
            { relay.currentCall?.let { id -> JSONObject().put("call_id", id).put("with", if (relay.remoteParty) "a paired phone" else "this phone or the assistant") } })) { Log.i(TAG, it) }
        mcpListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runCatching { mcp?.apply() } }
            .also { dev.cloudsim.gateway.mcp.Mcp.prefs(this).registerOnSharedPreferenceChangeListener(it) }
        runCatching { mcp?.apply() }.onFailure { Log.w(TAG, "mcp: ${it.message}") }
        // The owner's screening material (SCREENING_ASSISTANT.md 10.1).
        // The owner's own voice, cloned here (SCREENING_ASSISTANT.md 12.2): models fetched by this phone, never shipped.
        val clipDecoder = dev.cloudsim.gateway.screening.MediaCodecClipDecoder(cacheDir)
        val voiceModels = dev.cloudsim.gateway.screening.voice.VoiceModels(File(filesDir, "voice-models"), getExternalFilesDir("models")) { Log.i(TAG, it) }
        val cloner = dev.cloudsim.gateway.screening.voice.SherpaCloner(voiceModels)
        val voiceProfile = dev.cloudsim.gateway.screening.voice.VoiceProfile(File(filesDir, "screening/voice"))
        val voice = dev.cloudsim.gateway.screening.ScreeningRequests.VoiceSetup(
            voiceProfile,
            dev.cloudsim.gateway.screening.voice.SpeechJobs(voiceModels, voiceProfile, cloner,
                File(filesDir, "screening/voice-cache"), { relay.busy }, { Log.i(TAG, it) }, cloner::release,
                boost = { dev.cloudsim.gateway.Performance.hold(this, "speech") }),
            voiceModels) { clipDecoder.decodeAt(it, dev.cloudsim.gateway.screening.voice.VoiceProfile.RATE) }
        screeningRequests = dev.cloudsim.gateway.screening.ScreeningRequests(client, sealer, owner, screener.assets, { screener.digestKey },
            clipDecoder, lease::valid, log = { Log.i(TAG, it) }, voice = voice, transcripts = screener.transcripts, asr = screener.asrModels,
            policy = { policy }, numberOf = { id -> relay.numberOf(id) ?: screener.history.numberOf(id) })
        // Transcription models for a gateway already speaking in the owner's voice (SCREENING_ASSISTANT.md 13.1), in the
        // background; a side-loaded copy is taken at once.
        if (voiceModels.ready() && !screener.asrModels.ready()) Thread({ screener.asrModels.ensure() }, "asr-models").apply { priority = Thread.MIN_PRIORITY }.start()
        // As the default SMS app the system hands messages to SmsDeliverReceiver, which passes them on here.
        DefaultSms.onIncoming = { peer, body, at, sub ->
            val n = numbers.numberIdFor(sub)
            smsWorker.execute { smsRelay.onReceived(peer, body, at, n) }
            mcpEvent("sms.new", dev.cloudsim.gateway.mcp.Cap.SMS, n, JSONObject().put("peer", peer).put("at", at))
        }
        watchSms()
        // A SIM put in or taken out: report the list again.
        // Removed again when the service goes: left behind, it hands work to a stopped pool and the app crashes
        // (2026-11-08, the Mobile gateway offline from 17:51).
        val subscriptions = object : android.telephony.SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                if (!poller.isShutdown) runCatching { poller.execute { numbers.report() } }
            }
        }
        getSystemService(android.telephony.SubscriptionManager::class.java)?.let { sm ->
            sm.addOnSubscriptionsChangedListener(mainExecutor, subscriptions)
            unregisterSubscriptions = { runCatching { sm.removeOnSubscriptionsChangedListener(subscriptions) } }
        }
        poller.execute {
            runCatching { boxes.publish() }.onFailure { Log.w(TAG, "box key: ${it.message}") }
            // A handover sent while this gateway was off, and the owner's recovery key (BACKUP_RESTORE_6.md).
            if (owners.checkHandover()) onOwnerChanged()
            owners.refreshRecovery()
            keeper.refresh()
            numbers.report()
            // Fetch recipient keys now, so sealing the first caller number does not wait on the network.
            owner()?.let { o -> (listOf(o) + (policy?.members?.map { it.deviceId } ?: emptyList())).forEach { boxes.recipient(it) } }
        }
        watchPhoneState()
        poller.execute { poll(client) }
        supervisor.scheduleWithFixedDelay({ superviseBridge(connection) }, 3, 30, TimeUnit.SECONDS)
        // Confirm the latest permissions every minute; the long poll staying up does not count (REVOKE_LEASE_5.md).
        renewer.scheduleWithFixedDelay({ keeper.refresh() }, Lease.RENEW_MS, Lease.RENEW_MS, TimeUnit.MILLISECONDS)
        renewer.scheduleWithFixedDelay({
            keeper.watch(onPause = { worker.execute { relay.onLeaseExpired() } }, onResume = { smsWorker.execute { smsRelay.onResume() } })
        }, 5, 5, TimeUnit.SECONDS)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        liveNumbers = null
        dev.cloudsim.gateway.selftest.Freezes.stop()
        Log.w(TAG, "gateway service stopping")
        unregister()
        unregisterSubscriptions()
        unregisterSubscriptions = {}
        cellular?.stop()
        cellular = null
        mappingListener?.let { getSharedPreferences("gateway", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(it) }
        mappingListener = null
        mcpListener?.let { dev.cloudsim.gateway.mcp.Mcp.prefs(this).unregisterOnSharedPreferenceChangeListener(it) }
        mcpListener = null
        runCatching { mcp?.shutdown() }
        mcp = null
        CallHub.onState = null
        CallHub.onFarAnswered = null
        unregisterSms()
        DefaultSms.onIncoming = null
        smsWorker.shutdownNow()
        worker.shutdownNow()
        poller.shutdownNow()
        supervisor.shutdownNow()
        renewer.shutdownNow()
        relayChecker.shutdownNow()
        probeWorker.shutdownNow()
        // Last, so a recording in progress is saved before the service goes.
        if (::relay.isInitialized && ::recorder.isInitialized) relay.currentCall?.let { id -> runCatching { recWorker.execute { recorder.ended(id) } } }
        recWorker.shutdown()
        runCatching { recWorker.awaitTermination(3, TimeUnit.SECONDS) }
        Lease.current = null
        super.onDestroy()
    }

    /**
     * The legacy listener on every version: unlike TelephonyCallback it hands over the incoming number, given the
     * "read call log" permission (CALLER_DIAL_3E.md), which is used for nothing else.
     */
    @SuppressLint("MissingPermission")
    private fun watchPhoneState() {
        val tm = getSystemService(TelephonyManager::class.java)
        // The executor constructor is API 31; on Android 10 (the Huawei) the plain one delivers on the main looper.
        @Suppress("DEPRECATION")
        val listener = if (Build.VERSION.SDK_INT >= 31) object : PhoneStateListener(mainExecutor) {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                if (!CallHub.isDefault(this@GatewayService)) {
                    worker.execute { relay.onPhoneState(state, phoneNumber); CallHub.remote = relay.remoteParty }
                    if (!dev.cloudsim.gateway.calls.EmergencyNumbers.isEmergency(phoneNumber ?: "", null)) mcpCallState(state, phoneNumber)
                }
            }
        } else object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                if (!CallHub.isDefault(this@GatewayService)) {
                    worker.execute { relay.onPhoneState(state, phoneNumber); CallHub.remote = relay.remoteParty }
                    if (!dev.cloudsim.gateway.calls.EmergencyNumbers.isEmergency(phoneNumber ?: "", null)) mcpCallState(state, phoneNumber)
                }
            }
        }
        @Suppress("DEPRECATION")
        tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        @Suppress("DEPRECATION")
        unregister = { tm.listen(listener, PhoneStateListener.LISTEN_NONE) }
    }

    /**
     * Option B without a computer: if the shell audio bridge is gone (reboot, Wi-Fi change) or runs an older APK, start
     * it again over this phone's own wireless debugging. Only between calls; never dials or resumes media.
     */
    private fun superviseBridge(bridge: BridgeConnection) {
        if (relay.currentCall != null) return
        try {
            val up = bridge.reachable()
            val current = up && bridge.bridgeClasspath == applicationInfo.sourceDir
            if (current) ShellLauncher.status.value = ShellLauncher.Status.Running
            val answer = launcher.canAnswer()
            if (current && answer) return
            if (!answer) Log.i(TAG, "answer permission was taken back, granting it again")
            if (!current) Log.i(TAG, if (up) "bridge runs an older APK, restarting" else "bridge not running, starting")
            // On the IO pool, as pairing does: Kadb reads and writes one connection from several coroutines, and a
            // single-threaded runBlocking loop mixed up the plain and TLS reads (adbd: SSL_read failed, then reset).
            runBlocking(Dispatchers.IO) { launcher.startBridge(restartBridge = !current) }
        } catch (e: Exception) {
            ShellLauncher.status.value = ShellLauncher.Status.NotRunning(e.message ?: e.javaClass.simpleName)
            Log.w(TAG, "bridge supervision failed", e)
        }
    }

    private lateinit var keeper: PolicyKeeper
    private lateinit var numbers: dev.cloudsim.gateway.numbers.NumberBook
    /** The newest verified sharing policy, or null before the owner set one. */
    private val policy: SharingPolicy? get() = keeper.policy
    private lateinit var owners: OwnerKeeper
    private lateinit var probes: ProbeRelay
    private val probeWorker = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * A new owner phone: the relays read the owner afresh; this carries it out (ringing, readers), fetches the new
     * owner's recipient key, and seals the SIM numbers for it.
     */
    private fun onOwnerChanged() {
        worker.execute { relay.onPolicyChanged() }
        smsWorker.execute { smsRelay.onPolicy() }
        keeper.refresh()
        numbers.report()
        // The new owner publishes its recipient key moments after it takes over: seal the SIM numbers for it again.
        for (s in listOf(10L, 30L, 120L)) renewer.schedule({ numbers.report() }, s, TimeUnit.SECONDS)
    }

    /** Long-polls the service; events go to the same thread as phone states, so the relay sees one ordered stream. */
    private fun poll(client: ControlClient) {
        // Resume after the last event already handled: the service keeps events for 5 minutes, and a gateway restarted
        // within that time (an update, a crash) must not act on them again — above all, not dial again (2026-11-05).
        val progress = getSharedPreferences("events", MODE_PRIVATE)
        var after = progress.getLong("after", 0L)
        dev.cloudsim.gateway.selftest.ServiceLink.shared.began()
        while (running) {
            try {
                val list = client.call("GET", "/v1/events?after=$after", readTimeoutMs = 35_000).getJSONArray("events")
                dev.cloudsim.gateway.selftest.ServiceLink.shared.heard()
                for (i in 0 until list.length()) {
                    val e = list.getJSONObject(i)
                    after = maxOf(after, e.getLong("seq"))
                    progress.edit().putLong("after", after).commit()
                    e.getJSONObject("data").put("_at_ms", e.optLong("at_ms"))
                    GatewayLog.detail("event ${e.getString("type")} seq=${e.getLong("seq")}")
                    if (e.getString("type") == "sms.command") {
                        val d = e.getJSONObject("data")
                        val key = ControlClient.unb64(d.optString("public_key")) ?: continue
                        val env = runCatching { JSONObject(d.optString("envelope")) }.getOrNull() ?: continue
                        smsWorker.execute { smsRelay.onCommand(d.optString("device_id"), key, d.optString("command_id"), env, e.optLong("at_ms")) }
                        continue
                    }
                    if (e.getString("type") == "sms.history") {
                        val d = e.getJSONObject("data")
                        smsWorker.execute { smsRelay.onHistory(d.optString("device_id"), d.optLong("from"), d.optLong("to"), d.optString("number_id").ifEmpty { null }) }
                        continue
                    }
                    if (e.getString("type") == "sms.sync") {
                        val d = e.getJSONObject("data")
                        smsWorker.execute { smsRelay.onSync(d.optString("device_id"), d.optLong("after")) }
                        continue
                    }
                    if (e.getString("type") == "diagnostics.request") {
                        // The owner's phone sent its log and asks for this one too (LOG_LEVELS.md); the note names its code.
                        val code = e.getJSONObject("data").optString("code").take(6)
                        recordsWorker.execute {
                            runCatching { Diagnostics.send(this, "随 iPhone 日志 $code 一起发送") }
                                .onSuccess { Log.i(TAG, "diagnostics sent along $code as $it") }
                                .onFailure { Log.w(TAG, "diagnostics along $code failed: ${it.message}") }
                        }
                        continue
                    }
                    if (e.getString("type") == "records.request") {
                        val d = e.getJSONObject("data")
                        if (d.optString("kind") == dev.cloudsim.gateway.screening.ScreeningRequests.KIND) recordsWorker.execute { screeningRequests.onRequest(d) }
                        else if (d.optString("kind") == dev.cloudsim.gateway.recording.RecordingRelay.KIND) recordsWorker.execute { runCatching { noticeMigration() }; recordings.onRequest(d) }
                        else if (d.optString("kind") == dev.cloudsim.gateway.relay.OwnRelayRequests.KIND) recordsWorker.execute { ownRelayRequests.onRequest(d) }
                        else recordsWorker.execute { records.onRequest(d) }
                        continue
                    }
                    if (e.getString("type") == "policy.update") {
                        keeper.refresh()
                        continue
                    }
                    if (e.getString("type") == "probe.request") {
                        val d = e.getJSONObject("data")
                        probeWorker.execute { probes.onRequest(d) }
                        continue
                    }
                    if (e.getString("type") == "owner.handover") {
                        if (owners.checkHandover()) onOwnerChanged()
                        continue
                    }
                    if (e.getString("type") == "recovery.update") {
                        owners.refreshRecovery()
                        continue
                    }
                    // Also after an in-person change confirmed on the gateway's own screen.
                    if (e.getString("type") == "owner.changed") {
                        onOwnerChanged()
                        continue
                    }
                    worker.execute { relay.onEvent(e.getString("type"), e.getJSONObject("data")) }
                }
            } catch (e: ServiceException) {
                Log.w(TAG, "poll: ${e.code} ${e.message}")
                if (e.code == "NETWORK") dev.cloudsim.gateway.selftest.ServiceLink.shared.failed()
                runCatching { TimeUnit.SECONDS.sleep(5) }
            } catch (e: InterruptedException) {
                return
            }
        }
    }


    private inner class SystemTelephony : Telephony {
        private val telecom = getSystemService(TelecomManager::class.java)

        override fun precise() = CallHub.isDefault(this@GatewayService)

        /** The system's list for this SIM, and the same many-country table as the iPhone's. */
        override fun isEmergency(number: String): Boolean {
            val tm = getSystemService(TelephonyManager::class.java)
            return runCatching { tm.isEmergencyNumber(number) }.getOrDefault(false) ||
                EmergencyNumbers.isEmergency(number, runCatching { tm.simCountryIso }.getOrNull())
        }

        @SuppressLint("MissingPermission")
        override fun answer(): Boolean = CallHub.answer() || runCatching {
            @Suppress("DEPRECATION")
            telecom.acceptRingingCall()
        }.onFailure { Log.w(TAG, "answer", it) }.isSuccess

        @SuppressLint("MissingPermission")
        override fun idle(): Boolean = getSystemService(TelephonyManager::class.java).callState == TelephonyManager.CALL_STATE_IDLE

        @SuppressLint("MissingPermission")
        override fun dial(number: String): Boolean = dial(number, null)

        /**
         * A phone account's SIM subscription. MIUI can hide it from getSubscriptionId as it hides the subscription list
         * (DUAL_SIM.md); a SIM account's id is then its subscription id, which we check against the SIMs we know.
         */
        @SuppressLint("MissingPermission")
        private fun subscriptionOf(handle: android.telecom.PhoneAccountHandle): Int? {
            val tm = getSystemService(TelephonyManager::class.java)
            runCatching { tm.getSubscriptionId(handle) }.getOrNull()?.takeIf { it >= 0 }?.let { return it }
            return handle.id?.toIntOrNull()?.takeIf { id -> numbers.current().any { it.subscriptionId == id } }
        }

        /** The system's phone account of a SIM subscription. */
        @SuppressLint("MissingPermission")
        private fun account(subscriptionId: Int): android.telecom.PhoneAccountHandle? =
            runCatching { telecom.callCapablePhoneAccounts.firstOrNull { subscriptionOf(it) == subscriptionId } }.getOrNull()

        @SuppressLint("MissingPermission")
        override fun dial(number: String, numberId: String?): Boolean = runCatching {
            val extras = android.os.Bundle()
            numberId?.let { numbers.subscriptionFor(it) }?.let(::account)?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
            telecom.placeCall(android.net.Uri.fromParts("tel", number, null), extras)
        }.onFailure { Log.w(TAG, "dial", it) }.isSuccess

        override fun hasNumber(numberId: String) = numbers.subscriptionFor(numberId) != null

        override fun dtmf(digit: Char) = CallHub.dtmf(digit)

        /** As the default phone app, the ringing call's phone account; otherwise the SIM whose state is ringing. */
        @SuppressLint("MissingPermission")
        override fun ringingNumber(): String? = runCatching {
            val tm = getSystemService(TelephonyManager::class.java)
            val sub = CallHub.ringing()?.details?.accountHandle?.let(::subscriptionOf)
                ?: numbers.current().filter { it.active }.firstOrNull { tm.createForSubscriptionId(it.subscriptionId).callStateForSubscription == TelephonyManager.CALL_STATE_RINGING }?.subscriptionId
            sub?.let(numbers::numberIdFor)
        }.getOrNull()

        @SuppressLint("MissingPermission")
        override fun end(): Boolean = CallHub.end() || runCatching {
            @Suppress("DEPRECATION")
            telecom.endCall()
        }.onFailure { Log.w(TAG, "end", it) }.getOrDefault(false)
    }

    private fun goForeground() =
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)

    /**
     * MIUI holds a just-replaced app back for a few seconds: started then (after an update, by MY_PACKAGE_REPLACED),
     * startForeground is refused without an error and the system takes the service down two minutes later, leaving
     * the gateway offline until someone opens it (0.43.5 on the Redmi, 2026-11-13; likely the 2026-11-08 one too).
     * Asked again every 5 s for 3 minutes until it holds.
     */
    private fun keepForeground() {
        val main = android.os.Handler(mainLooper)
        Thread({
            for (i in 1..36) {
                Thread.sleep(5000)
                if (!running) return@Thread
                if (inForeground()) {
                    if (i > 1) Log.i(TAG, "in the foreground after ${i * 5}s")
                    return@Thread
                }
                Log.w(TAG, "not in the foreground, asking again")
                main.post { runCatching { goForeground() }.onFailure { Log.w(TAG, "foreground refused: ${it.message}") } }
            }
            Log.e(TAG, "still not in the foreground after 3 minutes")
        }, "foreground").apply { isDaemon = true }.start()
    }

    @Suppress("DEPRECATION")
    private fun inForeground(): Boolean = runCatching {
        (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getRunningServices(50)
            .any { it.service.className == GatewayService::class.java.name && it.foreground }
    }.getOrDefault(true)

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, L.t("网关运行状态"), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, GatewayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(L.t("SimNest 网关运行中"))
            .setContentText(L.t("来电会转给已配对的 iPhone"))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, L.t("关闭网关"), stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "CloudSimGateway"
        private const val CHANNEL = "gateway"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.cloudsim.gateway.STOP"

        /** This gateway's numbers while the service runs, for its own screen (the self-test, the home page); null when off. */
        @Volatile var liveNumbers: (() -> List<dev.cloudsim.gateway.numbers.GatewayNumber>)? = null

        val permissions = buildList {
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.ANSWER_PHONE_CALLS)
            // Only for the incoming caller's number, and for placing calls a phone asked for (CALLER_DIAL_3E.md).
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.CALL_PHONE)
            // Receiving SMS for the phones allowed to read them (SMS_4A.md).
            add(Manifest.permission.RECEIVE_SMS)
            // Sending for the phones allowed to (SMS_4B.md), and checking the Sent box when a result is unknown.
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_SMS)
            // The SIMs' own numbers, shown to the owner sealed (DUAL_SIM.md).
            add(Manifest.permission.READ_PHONE_NUMBERS)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }

        /**
         * Permissions the user must hold. Once paired, the gateway grants "answer phone calls" back itself over the
         * shell when MIUI takes it at a reboot or update (the user's 2026-11-04 decision), so it is not required then.
         */
        fun missingPermissions(context: Context): List<String> {
            val paired = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
            return permissions.filter {
                it !in optional && context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED && !(paired && it == Manifest.permission.ANSWER_PHONE_CALLS)
            }
        }

        /** Asked for with the rest, but the gateway still relays calls without them: no caller number, no dialing out. */
        val optional = setOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE, Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS, Manifest.permission.READ_PHONE_NUMBERS)

        fun missingOptional(context: Context) = optional.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        fun isEnabled(context: Context) = context.getSharedPreferences("gateway", MODE_PRIVATE).getBoolean("enabled", false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences("gateway", MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        }

        fun start(context: Context) {
            setEnabled(context, true)
            ContextCompat.startForegroundService(context, Intent(context, GatewayService::class.java))
        }

        fun stop(context: Context) {
            setEnabled(context, false)
            context.stopService(Intent(context, GatewayService::class.java))
        }
    }
}

/** The owner as the gateway keeps it, next to the paired phone's id (BACKUP_RESTORE_6.md). */
class PrefsOwnerState(private val prefs: android.content.SharedPreferences) : OwnerState {
    override var owner: String?
        get() = prefs.getString("phone_id", null)
        set(v) { prefs.edit().putString("phone_id", v).commit() }
    override var previous: PreviousOwner?
        get() = prefs.getString("previous_owner", null)?.let { PreviousOwner(it, prefs.getInt("previous_revision", 0)) }
        set(v) { prefs.edit().putString("previous_owner", v?.id).putInt("previous_revision", v?.revision ?: 0).commit() }
    override var recoveryKey: ByteArray?
        get() = ControlClient.unb64(prefs.getString("recovery_key", null))
        set(v) { prefs.edit().putString("recovery_key", v?.let(ControlClient::b64)).commit() }
}
