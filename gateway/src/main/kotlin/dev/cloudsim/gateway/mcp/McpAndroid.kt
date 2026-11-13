package dev.cloudsim.gateway.mcp

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.cloudsim.gateway.adb.ShellLauncher
import dev.cloudsim.gateway.calls.GatewayStatus
import dev.cloudsim.gateway.calls.SharingPolicy
import dev.cloudsim.gateway.numbers.GatewayNumber
import dev.cloudsim.gateway.records.MissingPermission
import dev.cloudsim.gateway.records.RecordSource
import dev.cloudsim.gateway.screening.transcript.Transcripts
import dev.cloudsim.gateway.sms.SmsStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/**
 * The MCP server on this phone (GATEWAY_MCP.md): its switches, its grants and log kept in the app's private storage,
 * and where it listens. Everything is decided on this phone's screen; the owner's iPhone and the service have no
 * part in it.
 */
object Mcp {
    const val PREFS = "mcp"
    const val PORT = 18765
    private const val KEY_ON = "on"
    private const val KEY_PLAIN = "plain_lan"
    private const val KEY_LAN = "lan"
    private const val KEY_SEEN = "explained"

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    /** Off until switched on here (GATEWAY_MCP.md 4.1): nothing listens while off. */
    fun on(context: Context) = prefs(context).getBoolean(KEY_ON, false)
    fun setOn(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_ON, on).apply()
    /** Programs on this phone only, or the home network as well. */
    fun lan(context: Context) = prefs(context).getBoolean(KEY_LAN, true)
    fun setLan(context: Context, lan: Boolean) = prefs(context).edit().putBoolean(KEY_LAN, lan).apply()
    /** Without TLS on the home network, for clients that cannot take this phone's own certificate. Off unless the user turns it on. */
    fun plainLan(context: Context) = prefs(context).getBoolean(KEY_PLAIN, false)
    fun setPlainLan(context: Context, plain: Boolean) = prefs(context).edit().putBoolean(KEY_PLAIN, plain).apply()
    fun explained(context: Context) = prefs(context).getBoolean(KEY_SEEN, false)
    fun setExplained(context: Context) = prefs(context).edit().putBoolean(KEY_SEEN, true).apply()

    @Volatile private var grantsValue: Grants? = null
    @Volatile private var auditValue: FileAudit? = null
    /** One set for the screen and the server alike: a grant made on the screen is in force at once. */
    fun grants(context: Context): Grants = grantsValue ?: synchronized(this) {
        grantsValue ?: Grants(object : GrantStore {
            private val p = context.applicationContext.getSharedPreferences("mcp_grants", Context.MODE_PRIVATE)
            override fun load() = p.getString("grants", null)
            override fun save(json: String) { p.edit().putString("grants", json).apply() }
        }).also { grantsValue = it }
    }
    fun audit(context: Context): FileAudit = auditValue ?: synchronized(this) {
        auditValue ?: FileAudit(File(context.applicationContext.filesDir, "mcp/log.jsonl")).also { auditValue = it }
    }
    val events = Events()

    /** Where the running server listens, for the screen; null while it does not. */
    @Volatile var listening: Listening? = null
    class Listening(val lanAddress: String?, val port: Int, val tls: Boolean, val fingerprint: String)
}

/** The log on this phone: the last [KEEP] lines or [DAYS] days, whichever is less. Not part of the diagnostics log; never uploaded. */
class FileAudit(private val file: File, private val now: () -> Long = System::currentTimeMillis) : AuditLog {
    private val lines = ArrayDeque<JSONObject>()
    private var appended = 0

    init {
        runCatching {
            if (file.exists()) file.forEachLine { l -> runCatching { JSONObject(l) }.getOrNull()?.let { lines.addFirst(it) } }
        }
        trim()
    }

    @Synchronized
    override fun add(entry: JSONObject) {
        lines.addFirst(entry)
        runCatching {
            file.parentFile?.mkdirs()
            // Rewritten now and then, so the file does not grow without end.
            if (++appended > KEEP / 4) { trim(); file.writeText(lines.reversed().joinToString("") { "$it\n" }); appended = 0 } else file.appendText("$entry\n")
        }
    }

    @Synchronized
    override fun recent(tool: String?, limit: Int): List<JSONObject> = lines.filter { tool == null || it.optString("tool") == tool }.take(limit)

    private fun trim() {
        val oldest = now() - DAYS * 24 * 3600_000L
        while (lines.size > KEEP || (lines.isNotEmpty() && lines.last().optLong("at") < oldest)) lines.removeLast()
    }

    companion object {
        const val KEEP = 5000
        const val DAYS = 30
    }
}

/** This phone's data for [McpServer], through what the gateway already has; a permission taken back comes out as [NotReady]. */
class GatewayMcpData(
    private val context: Context,
    private val gatewayNumbers: () -> List<GatewayNumber>,
    private val records: RecordSource,
    private val sms: SmsStore,
    private val transcripts: Transcripts,
    private val policy: () -> SharingPolicy?,
    private val owner: () -> String?,
    private val call: () -> JSONObject?,
) : McpData {
    private fun <T> guarded(block: () -> T): T = try { block() } catch (e: MissingPermission) { throw NotReady("the permission ${e.message} is not given to the gateway on this phone") }

    override fun status(): JSONObject = GatewayStatus.collect(context, gatewayNumbers(), owner(), policy(), owner() ?: "")
        .put("bridge", ShellLauncher.status.value is ShellLauncher.Status.Running)

    override fun numbers(): List<JSONObject> = gatewayNumbers().map { n ->
        JSONObject().put("number_id", n.numberId).put("slot", n.slot + 1).put("carrier", n.carrier).put("active", n.active).apply { n.number?.let { put("number", it) } }
    }

    override fun calls(from: Long, to: Long): List<JSONObject> = guarded { records.calls(from, to) }
    override fun activeCall(): JSONObject? = call()
    override fun transcript(callId: String): List<JSONObject>? = transcripts.entries(callId)?.map { it.json() }
    override fun sms(q: SmsQuery): List<McpSms> = sms.query(q)
    override fun contacts(): List<JSONObject> = guarded { records.contacts() }

    override fun screeningRules(): JSONObject = JSONObject().apply {
        policy()?.screening?.forEach { (number, s) ->
            put(number, JSONObject().put("mode", s.mode).put("ring_s", s.ringS).put("who", s.who).put("hidden_callers", s.hidden).put("keeps_message", s.record)
                .apply { s.hours?.let { put("hours", JSONArray().put(it.first).put(it.second)) } })
        }
    }
}

/** This phone's own certificate for MCP over the home network; the key is made in and never leaves the Android Keystore (GATEWAY_MCP.md 13). */
object McpTls {
    private const val ALIAS = "mcp-tls"

    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.also { ks ->
        if (!ks.containsAlias(ALIAS)) KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512, KeyProperties.DIGEST_NONE)
                .setCertificateSubject(X500Principal("CN=simnest-gateway")).build())
        }.generateKeyPair()
    }

    /** SHA-256 of the certificate, what a client pins instead of trusting any authority. */
    fun fingerprint(): String = MessageDigest.getInstance("SHA-256").digest(store().getCertificate(ALIAS).encoded).joinToString("") { "%02x".format(it) }

    fun context(): SSLContext = SSLContext.getInstance("TLSv1.3").apply {
        init(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store(), null) }.keyManagers, null, null)
    }
}

/**
 * Starts and stops the listeners as the switches say (GATEWAY_MCP.md 2.3): always this phone itself (127.0.0.1, plain:
 * nothing leaves the phone), and the Wi-Fi address when the home network is allowed, over TLS unless the user chose
 * plain. Never the mobile network's address, never a public one. The Wi-Fi address changes; it is looked at again
 * every half minute.
 */
class McpRuntime(private val context: Context, private val data: McpData, private val log: (String) -> Unit) {
    private val server = McpServer(Mcp.grants(context), data, Mcp.events, Mcp.audit(context), version = dev.cloudsim.gateway.BuildConfig.VERSION_NAME)
    private val watcher = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mcp-watch").apply { isDaemon = true } }
    private var watch: ScheduledFuture<*>? = null
    private var local: McpHttp? = null
    private var lan: McpHttp? = null
    private var lanAddress: String? = null
    private var lanTls = true
    private var registered: NsdManager.RegistrationListener? = null

    /** Brings what is listening in line with the switches; called at start and whenever one changes. */
    @Synchronized
    fun apply() {
        if (!Mcp.on(context)) { stop(); return }
        if (local == null) runCatching {
            local = McpHttp(ServerSocket(Mcp.PORT, 16, InetAddress.getByName("127.0.0.1")), server, log).also { it.start() }
            log("mcp: listening on this phone, port ${Mcp.PORT}")
        }.onFailure { log("mcp: could not listen on this phone: ${it.message}") }
        val want = if (Mcp.lan(context)) wifiAddress() else null
        val tls = !Mcp.plainLan(context)
        if (want?.hostAddress != lanAddress || (lan != null && tls != lanTls)) {
            lan?.stop(); lan = null; lanAddress = null
            unregister()
            if (want != null) runCatching {
                val socket = if (tls) McpTls.context().serverSocketFactory.createServerSocket(Mcp.PORT, 16, want) else ServerSocket(Mcp.PORT, 16, want)
                lan = McpHttp(socket, server, log).also { it.start() }
                lanAddress = want.hostAddress
                lanTls = tls
                log("mcp: listening on the home network${if (tls) "" else ", without TLS"}")
                register()
            }.onFailure { log("mcp: could not listen on the home network: ${it.message}") }
        }
        Mcp.listening = Mcp.Listening(lanAddress, Mcp.PORT, lanTls, runCatching { McpTls.fingerprint() }.getOrDefault(""))
        if (watch == null) watch = watcher.scheduleWithFixedDelay({ runCatching { apply() } }, 30, 30, TimeUnit.SECONDS)
    }

    @Synchronized
    fun stop() {
        watch?.cancel(false); watch = null
        if (local != null || lan != null) log("mcp: stopped")
        local?.stop(); local = null
        lan?.stop(); lan = null; lanAddress = null
        unregister()
        Mcp.listening = null
    }

    fun shutdown() { stop(); watcher.shutdownNow() }

    /** The Wi-Fi interface's private IPv4 address, or null off Wi-Fi. */
    private fun wifiAddress(): InetAddress? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence().filter { it.name.startsWith("wlan") && it.isUp }
            .flatMap { it.inetAddresses.asSequence() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    }.getOrNull()

    /** So the connection program finds the gateway after the router gave it another address: the port and the start of the certificate's fingerprint, nothing else. */
    private fun register() {
        runCatching {
            val info = NsdServiceInfo().apply {
                serviceName = "SimNest gateway"
                serviceType = "_simnest-mcp._tcp"
                port = Mcp.PORT
                setAttribute("fp", McpTls.fingerprint().take(16))
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
            }
            context.getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registered = listener
        }
    }

    private fun unregister() {
        registered?.let { l -> runCatching { context.getSystemService(NsdManager::class.java).unregisterService(l) } }
        registered = null
    }
}
