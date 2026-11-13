package dev.cloudsim.gateway.mcp

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger

/**
 * HTTP for [McpServer] on a plain or TLS server socket the caller made (GATEWAY_MCP.md 12: the platform's own sockets,
 * no HTTP library). Connections stay open between requests: on a phone asleep on Wi-Fi a new one costs half a second
 * (GATEWAY_MCP.md 13). Kept apart from calls: its own threads, a cap on connections, on sizes and on idle time, and
 * nothing thrown in here goes further than the one connection.
 */
class McpHttp(private val socket: ServerSocket, private val server: McpServer, private val log: (String) -> Unit = {}) {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "mcp-http").apply { isDaemon = true } } as ThreadPoolExecutor
    private val open = AtomicInteger()

    fun start() {
        pool.execute {
            while (!socket.isClosed) {
                val s = runCatching { socket.accept() }.getOrNull() ?: break
                if (open.incrementAndGet() > MAX_CONNECTIONS) { open.decrementAndGet(); runCatching { s.close() }; continue }
                pool.execute {
                    try { serve(s) } catch (e: Exception) { /* a connection dropped or cut short: nothing to do */ } finally {
                        open.decrementAndGet()
                        runCatching { s.close() }
                    }
                }
            }
        }
    }

    fun stop() {
        runCatching { socket.close() }
        pool.shutdownNow()
    }

    private fun serve(s: Socket) {
        s.tcpNoDelay = true
        s.soTimeout = IDLE_MS
        val input = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()
        while (true) {
            val first = line(input) ?: return
            val method = first.substringBefore(' ')
            val headers = HashMap<String, String>()
            while (true) {
                val l = line(input) ?: return
                if (l.isEmpty()) break
                if (headers.size >= MAX_HEADERS) return
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            // Only bodies with a stated length: no chunked uploads, nothing without an end.
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length < 0 || length > MAX_BODY || headers["transfer-encoding"] != null) { write(out, McpServer.Reply(413, null), close = true); return }
            val body = ByteArray(length)
            var n = 0
            while (n < length) { val k = input.read(body, n, length - n); if (k < 0) return; n += k }
            val reply = runCatching { server.handle(method, headers["origin"], headers["authorization"], String(body, Charsets.UTF_8)) }
                .getOrElse { log("request failed: ${it.javaClass.simpleName}"); McpServer.Reply(500, null) }
            val close = headers["connection"].equals("close", ignoreCase = true) || reply.status == 401 || reply.status == 403
            write(out, reply, close)
            if (close) return
        }
    }

    private fun line(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            b.write(c)
            if (b.size() > MAX_LINE) return null
        }
    }

    private fun write(out: OutputStream, reply: McpServer.Reply, close: Boolean) {
        val body = reply.body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val head = StringBuilder("HTTP/1.1 ${reply.status} ${REASONS[reply.status] ?: "Error"}\r\n")
        if (reply.body != null) head.append("Content-Type: application/json\r\n")
        if (reply.status == 401) head.append("WWW-Authenticate: Bearer\r\n")
        head.append("Content-Length: ${body.size}\r\n").append(if (close) "Connection: close\r\n" else "Connection: keep-alive\r\n").append("\r\n")
        out.write(head.toString().toByteArray(Charsets.US_ASCII) + body)
        out.flush()
    }

    companion object {
        const val MAX_CONNECTIONS = 16
        const val MAX_BODY = 256 * 1024
        const val MAX_LINE = 8192
        const val MAX_HEADERS = 64
        /** A connection nobody speaks on is let go after this; a tool that keeps one open asks at least this often. */
        const val IDLE_MS = 120_000
        private val REASONS = mapOf(200 to "OK", 202 to "Accepted", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 405 to "Method Not Allowed",
            413 to "Payload Too Large", 500 to "Internal Server Error")
    }
}
