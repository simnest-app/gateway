package dev.cloudsim.gateway.adb

import dev.cloudsim.gateway.L

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okio.Path.Companion.toPath
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.NetworkInterface
import java.util.concurrent.Executors

/**
 * This phone's own wireless debugging, used as the shell user (option B, docs/architecture/SHELL_3C.md): pairs once with
 * the code from system settings, then connects to 127.0.0.1 to run shell commands. Kadb (Apache-2.0) speaks the adb
 * protocol; its key stays in this app's private files. Ports come from mDNS, filtered to this phone's own addresses.
 */
class LocalAdb(private val context: Context) {
    init {
        KadbCert.configure(OkioFilePrivateKeyStore(File(context.filesDir, "adb/private_key.pem").absolutePath.toPath()))
    }

    /**
     * The first port this phone advertises for an adb service type, or null within the timeout. Services are resolved one
     * at a time (before API 34 a second concurrent resolve fails), and only those resolving to one of this phone's own
     * addresses count, compared by address bytes so an IPv6 scope suffix does not matter.
     */
    suspend fun discover(type: String, timeoutMs: Long = 10_000): Int? {
        val nsd = context.getSystemService(NsdManager::class.java)
        val found = CompletableDeferred<Int>()
        val own = ownAddresses()
        val pending = java.util.concurrent.LinkedBlockingQueue<NsdServiceInfo>()
        val resolving = java.util.concurrent.atomic.AtomicBoolean(false)
        fun next() {
            if (found.isCompleted || !resolving.compareAndSet(false, true)) return
            val info = pending.poll() ?: run { resolving.set(false); return }
            resolve(nsd, info) { host, port ->
                val mine = host == null || host.address.toList() in own
                Log.i(TAG, "mdns $type ${info.serviceName} -> ${host?.hostAddress}:$port own=$mine")
                if (mine && port > 0) found.complete(port)
                resolving.set(false)
                next()
            }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                pending.add(info)
                next()
            }
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mdns $type discovery failed $errorCode")
                found.cancel()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        return try {
            withTimeoutOrNull(timeoutMs) { runCatching { found.await() }.getOrNull() }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(nsd: NsdManager, info: NsdServiceInfo, done: (InetAddress?, Int) -> Unit) {
        if (Build.VERSION.SDK_INT >= 34) {
            nsd.registerServiceInfoCallback(info, Executors.newSingleThreadExecutor(), object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(resolved: NsdServiceInfo) {
                    runCatching { nsd.unregisterServiceInfoCallback(this) }
                    done(resolved.hostAddresses.firstOrNull(), resolved.port)
                }
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = done(null, -1)
                override fun onServiceLost() {}
                override fun onServiceInfoCallbackUnregistered() {}
            })
        } else {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) = done(resolved.host, resolved.port)
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mdns resolve ${info.serviceName} failed $errorCode")
                    done(null, -1)
                }
            })
        }
    }

    private fun ownAddresses(): Set<List<Byte>> =
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.map { it.address.toList() }.toSet()

    /**
     * TCP ports listening on 127.0.0.1 in the system's ephemeral range, where adbd puts its wireless-debugging ports.
     * MIUI does not let a phone see its own adbd over mDNS (seen on the Redmi, 2026-11-04), so this is the reliable way.
     * A closed loopback port refuses at once, but this MediaTek phone checks every socket against its CTA policy,
     * so the range is split wide to keep the scan to a few seconds.
     */
    suspend fun listeningPorts(): Set<Int> = coroutineScope {
        val loopback = InetAddress.getByName("127.0.0.1")
        (32768..60999).chunked(128).map { chunk ->
            async(Dispatchers.IO) {
                chunk.filter { port ->
                    runCatching { Socket().use { it.connect(InetSocketAddress(loopback, port), 100); true } }.getOrDefault(false)
                }
            }
        }.awaitAll().flatten().toSet()
    }

    /** Pairs with the code from the pairing dialog, on the port that appeared when the dialog opened. */
    suspend fun pair(port: Int, code: String) = Kadb.pair("127.0.0.1", port, code)

    private val prefs = context.getSharedPreferences("adb", Context.MODE_PRIVATE)

    /** Whether adbd listens on the classic TCP port on this phone (after `adb tcpip 5555` from a computer). */
    fun tcpOpen(): Boolean =
        runCatching { Socket().use { it.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), TCP_PORT), 300); true } }.getOrDefault(false)

    /**
     * For phones without wireless debugging: connects to the classic TCP port with this app's key. adbd does not know the
     * key yet, so the system asks the user to allow debugging and holds the connection until they answer; [waitMs] is
     * how long they get.
     */
    fun authorizeTcp(waitMs: Int = 60_000) {
        val kadb = open(TCP_PORT, waitMs) ?: error(L.t("没有连上：请确认已在电脑上运行 adb tcpip 5555，并在手机弹出的窗口里点了允许"))
        kadb.close()
        prefs.edit().putInt("connect_port", TCP_PORT).apply()
    }

    /** An open, working adb connection on this port, or null. */
    private fun open(port: Int, socketTimeout: Int = 15_000): Kadb? {
        val kadb = Kadb.create("127.0.0.1", port, connectTimeout = 2_000, socketTimeout = socketTimeout)
        return runCatching { kadb.also { check(it.shell("echo ok").output.trim() == "ok") } }
            .onFailure { Log.i(TAG, "adb port $port: ${it.javaClass.simpleName} ${it.message}"); runCatching { kadb.close() } }
            .getOrNull()
    }

    /**
     * Runs commands over one adb connection. adbd resets a new connection from the same key while it is still tearing
     * down the previous one (seen on the Redmi), so everything goes through a single connection.
     * The connect port only changes when wireless debugging restarts (reboot, Wi-Fi change), so the last good one is tried
     * first; then mDNS, in case this phone sees itself; then a scan of its own open ports. A phone set up over the
     * classic TCP port ([usesTcp]) only ever has that one.
     */
    suspend fun <T> session(block: (Kadb) -> T): T {
        val last = prefs.getInt("connect_port", 0)
        if (usesTcp(context)) {
            val tcp = (if (tcpOpen()) open(TCP_PORT) else null)
                ?: error(L.t("连不上本机调试：手机重启后要再连一次电脑，运行 adb tcpip 5555"))
            return tcp.use(block)
        }
        val kadb = (if (last > 0) open(last) else null)
            ?: discover(CONNECT, timeoutMs = 2_000)?.let(::open)
            ?: (listeningPorts() - EXCLUDED - last).also { Log.i(TAG, "open loopback ports: $it") }.firstNotNullOfOrNull { port ->
                delay(500)
                open(port)?.also { prefs.edit().putInt("connect_port", port).apply() }
            }
            ?: error(L.t("连不上本机无线调试：请确认无线调试已打开，且已配对"))
        return kadb.use(block)
    }

    /** Runs one shell command over this phone's wireless debugging. */
    suspend fun shell(command: String): String = session { it.shell(command).allOutput }

    companion object {
        const val PAIRING = "_adb-tls-pairing._tcp"
        const val CONNECT = "_adb-tls-connect._tcp"
        private const val TAG = "CloudSimGateway"
        /** adbd's classic TCP port, the one `adb tcpip 5555` opens. */
        const val TCP_PORT = 5555

        /**
         * Whether this phone reaches its shell over the classic TCP port instead of wireless debugging. Huawei's
         * HarmonyOS has no wireless debugging in developer options (Mate 40 Pro+, HarmonyOS 4.2, 2026-11-10), so it
         * starts that way; the setup guide lets the user switch either way.
         */
        fun usesTcp(context: Context): Boolean {
            val prefs = context.getSharedPreferences("adb", Context.MODE_PRIVATE)
            if (prefs.contains("tcp")) return prefs.getBoolean("tcp", false)
            return Build.MANUFACTURER.uppercase() in setOf("HUAWEI", "HONOR")
        }

        fun setUsesTcp(context: Context, tcp: Boolean) {
            context.getSharedPreferences("adb", Context.MODE_PRIVATE).edit().putBoolean("tcp", tcp).remove("connect_port").apply()
        }

        /** The audio bridge's own port, never an adb one. */
        private val EXCLUDED = setOf(47710)
    }
}
