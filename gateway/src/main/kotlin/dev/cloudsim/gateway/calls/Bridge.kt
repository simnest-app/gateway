package dev.cloudsim.gateway.calls

import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Commands to the shell audio bridge; a fake in tests. */
interface MediaBridge {
    fun send(command: JSONObject): Boolean
}

/**
 * The app's end of the loopback connection to the shell audio bridge (shell/BridgeMain.java). SELinux does not let an
 * app connect to a shell Unix socket, so this is TCP: the bridge checks this socket's uid, and this side accepts the
 * bridge only if it answers a fresh challenge with the token written to [tokenFile], which only this app and shell can
 * read. Reconnects on demand; messages from the bridge go to onMessage on the reader thread.
 */
class BridgeConnection(
    private val tokenFile: File,
    private val onMessage: (JSONObject) -> Unit,
    private val log: (String) -> Unit,
    /** Told to the bridge first thing on every connection (settings it keeps, like port mapping). */
    private val hello: () -> JSONObject? = { null },
) : MediaBridge {
    private val random = SecureRandom()
    private val token: ByteArray = ByteArray(32).also(random::nextBytes)
    private var socket: Socket? = null
    private var writer: Writer? = null
    /** The APK the connected bridge runs from. */
    @Volatile var bridgeClasspath: String? = null
        private set

    init {
        // A new token at every start, so a bridge that read an old one cannot pass as current.
        tokenFile.parentFile?.mkdirs()
        tokenFile.writeBytes(token)
    }

    @Synchronized
    private fun connect(): Writer? {
        writer?.let { return it }
        val s = Socket()
        return try {
            s.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 2_000)
            s.soTimeout = 5_000
            val w = OutputStreamWriter(s.outputStream, Charsets.UTF_8)
            val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
            val nonce = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
            w.write(JSONObject().put("type", "hello").put("nonce", nonce).toString() + "\n")
            w.flush()
            val ready = JSONObject(reader.readLine() ?: "{}")
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(token, "HmacSHA256")) }.doFinal("cloudsim-bridge-v1$nonce".toByteArray())
            val proof = runCatching { Base64.getDecoder().decode(ready.optString("proof")) }.getOrNull()
            if (ready.optString("type") != "ready" || proof == null || !MessageDigest.isEqual(mac, proof)) {
                log("loopback port $PORT is not our audio bridge; refused")
                s.close()
                return null
            }
            s.soTimeout = 0
            bridgeClasspath = ready.optString("classpath")
            socket = s
            writer = w
            Thread({ read(s, reader) }, "bridge-reader").apply { isDaemon = true }.start()
            hello()?.let { w.write(it.toString() + "\n"); w.flush() }
            w
        } catch (e: java.io.IOException) {
            log("bridge not running: ${e.message}")
            runCatching { s.close() }
            null
        }
    }

    private fun read(s: Socket, reader: BufferedReader) {
        try {
            reader.forEachLine { onMessage(JSONObject(it)) }
        } catch (e: Exception) {
            log("bridge read: ${e.message}")
        } finally {
            synchronized(this) {
                if (socket === s) {
                    socket = null
                    writer = null
                }
            }
            onMessage(JSONObject().put("type", "disconnected"))
        }
    }

    @Synchronized
    override fun send(command: JSONObject): Boolean {
        val w = connect() ?: return false
        return try {
            w.write(command.toString() + "\n")
            w.flush()
            true
        } catch (e: java.io.IOException) {
            log("bridge send: ${e.message}")
            runCatching { socket?.close() }
            false
        }
    }

    /** Whether a verified bridge is reachable now (connecting if needed). */
    @Synchronized
    fun reachable(): Boolean = connect() != null

    companion object {
        const val PORT = 47710
    }
}
