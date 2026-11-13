package dev.cloudsim.gateway.net

import dev.cloudsim.gateway.L

import dev.cloudsim.gateway.identity.Signer
import dev.cloudsim.gateway.protocol.Protocol
import org.json.JSONObject
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Where the control service is. Reached by IP, its self-signed certificate is pinned by SHA-256; reached by a domain
 * name, [certSha256] is empty and the certificate is checked as any website's is (the system's roots and the name).
 *
 * [movedTo]: a domain name (host:port) the service has moved to, or is about to (SERVER_LAYOUT.md). It is tried first;
 * only when no connection can be made there is [address] tried. Pairing codes still carry [address] and its pin, which
 * older iPhones understand, and which phones that know the move map to the same [movedTo] by the service ID.
 */
data class ServiceConfig(val address: String, val certSha256: ByteArray, val serviceId: String, val movedTo: String = "")

/** A contract error from the service, or a transport failure (code NETWORK). */
class ServiceException(val code: String, val retryMode: String, message: String) : Exception(message)

/** Signed calls to the control service; blocking, so call it off the main thread. */
open class ControlClient(private val config: ServiceConfig, private val signer: Signer, private val now: () -> Long = System::currentTimeMillis) {
    val deviceId: String = Protocol.deviceId(signer.publicKey)
    private val random = SecureRandom()

    /** The ways to the service in the order they are tried: where it moved to, then where it was. */
    private val endpoints: List<Endpoint> = buildList {
        if (config.movedTo.isNotEmpty() && config.movedTo != config.address) add(Endpoint(config.movedTo, ByteArray(0)))
        add(Endpoint(config.address, config.certSha256))
    }

    private class Endpoint(val address: String, val pin: ByteArray) {
        /** Null for a domain name: the system's own checks of the certificate and the name. */
        val tls: SSLContext? = if (pin.isEmpty()) null else SSLContext.getInstance("TLS").apply {
            val pinned = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw java.security.cert.CertificateException()
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (!Protocol.equalMac(Protocol.sha256(chain[0].encoded), pin)) throw java.security.cert.CertificateException("certificate does not match the pin")
                }
                override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            }
            init(null, arrayOf(pinned), SecureRandom())
        }
    }

    open fun call(method: String, path: String, body: JSONObject? = null, readTimeoutMs: Int = 15_000): JSONObject {
        val bytes = body?.toString()?.toByteArray() ?: ByteArray(0)
        var failed: java.io.IOException? = null
        for ((i, endpoint) in endpoints.withIndex()) {
            // Straight to the old place while the new one failed to connect a moment ago, rather than waiting on it each time.
            if (i == 0 && endpoints.size > 1 && movedFailedAt[config.movedTo]?.let { now() - it < RETRY_MOVED_MS } == true) continue
            val connection = try {
                open(endpoint, method, path, bytes, readTimeoutMs)
            } catch (e: java.io.IOException) {
                // Not even connected (no answer, refused, or the certificate not accepted): the request never reached the service, so it is safe to try the next way.
                failed = e
                if (i == 0 && endpoints.size > 1) movedFailedAt[config.movedTo] = now()
                continue
            }
            if (i == 0 && endpoints.size > 1) movedFailedAt.remove(config.movedTo)
            return answer(connection, bytes)
        }
        throw ServiceException("NETWORK", "query_original", L.t("连不上服务：%1\$s", failed?.message))
    }

    /** Connected (TLS handshake done) and the headers set; the body is written by [answer]. */
    private fun open(endpoint: Endpoint, method: String, path: String, bytes: ByteArray, readTimeoutMs: Int): HttpsURLConnection {
        // Signed anew for each way tried: the service takes a nonce once.
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(random::nextBytes))
        val time = now().toString()
        val signature = signer.sign(Protocol.requestMessage(method, path, time, nonce, bytes))
        return (URL("https://${endpoint.address}$path").openConnection() as HttpsURLConnection).apply {
            if (endpoint.tls != null) {
                sslSocketFactory = endpoint.tls.socketFactory
                // The certificate is pinned, and an IP address has no name to check.
                hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = readTimeoutMs
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-CS-Device", deviceId)
            setRequestProperty("X-CS-Time", time)
            setRequestProperty("X-CS-Nonce", nonce)
            setRequestProperty("X-CS-Signature", Base64.getEncoder().encodeToString(signature))
            if (bytes.isNotEmpty()) doOutput = true
            connect()
        }
    }

    private fun answer(connection: HttpsURLConnection, bytes: ByteArray): JSONObject {
        try {
            if (bytes.isNotEmpty()) connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            // The service's clock against this phone's, to the second, from the answer's own date.
            connection.date.takeIf { it > 0 }?.let { lastSkewMs = now() - it }
            val text = (if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes().decodeToString() } ?: "{}"
            val json = JSONObject(text)
            if (status >= 400) throw ServiceException(json.optString("error_code", "HTTP_$status"), json.optString("retry_mode", "none"), json.optString("message", L.t("请求失败")))
            return json
        } catch (e: java.io.IOException) {
            throw ServiceException("NETWORK", "query_original", L.t("连不上服务：%1\$s", e.message))
        } finally {
            connection.disconnect()
        }
    }

    fun register(kind: String) = call("POST", "/v1/devices", JSONObject().put("kind", kind).put("public_key", b64(signer.publicKey)))

    companion object {
        /** How far this phone's clock was ahead of the service's at the last answer (the header gives whole seconds); null before any. */
        @Volatile var lastSkewMs: Long? = null
        /** How long, after the new place could not be reached, requests go straight to the old one. */
        const val RETRY_MOVED_MS = 60_000L
        /** When a connection to each new place last failed, for every client in this process. */
        private val movedFailedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

        fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        fun unb64(text: String?): ByteArray? = text?.takeIf { it.isNotEmpty() }?.let { Base64.getDecoder().decode(it) }
    }
}
