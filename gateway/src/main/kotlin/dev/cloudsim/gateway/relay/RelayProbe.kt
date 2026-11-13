package dev.cloudsim.gateway.relay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A small STUN/TURN client (RFC 8489, RFC 8656), only what checking a relay needs: a binding request, to see that it
 * answers and how fast, and one allocation, to see that it takes a credential and would carry a call. The same checks
 * the relay's own helper runs on its machine, here from where this gateway is.
 */
object RelayProbe {
    private const val COOKIE = 0x2112A442
    private const val BINDING = 0x0001
    private const val ALLOCATE = 0x0003
    private const val REFRESH = 0x0004
    private const val CREATE_PERMISSION = 0x0008
    private const val XOR_PEER_ADDRESS = 0x0012
    /** An ordinary public address the relay must be willing to forward to; nothing is ever sent to it. */
    private val PUBLIC_PEER = byteArrayOf(1, 1, 1, 1)
    private const val SUCCESS = 0x0100
    private const val ERROR = 0x0110
    private const val USERNAME = 0x0006
    private const val MESSAGE_INTEGRITY = 0x0008
    private const val ERROR_CODE = 0x0009
    private const val LIFETIME = 0x000D
    private const val REALM = 0x0014
    private const val NONCE = 0x0015
    private const val XOR_RELAYED_ADDRESS = 0x0016
    private const val REQUESTED_TRANSPORT = 0x0019

    class Message(val type: Int, val txid: ByteArray, val attrs: List<Pair<Int, ByteArray>>) {
        fun get(type: Int): ByteArray? = attrs.firstOrNull { it.first == type }?.second
        /** The code of an error response; 0 when there is none. */
        val errorCode: Int get() = get(ERROR_CODE)?.takeIf { it.size >= 4 }?.let { (it[2].toInt() and 7) * 100 + (it[3].toInt() and 0xff) } ?: 0
    }

    fun request(type: Int, vararg attrs: Pair<Int, ByteArray>) = Message(type, ByteArray(12).also(SecureRandom()::nextBytes), attrs.toList())

    /** The message's bytes; with a [key], MESSAGE-INTEGRITY is added last, over everything before it. */
    fun encode(m: Message, key: ByteArray? = null): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        for ((type, value) in m.attrs) {
            body.write(byteArrayOf((type shr 8).toByte(), type.toByte(), (value.size shr 8).toByte(), value.size.toByte()))
            body.write(value)
            repeat((4 - value.size % 4) % 4) { body.write(0) }
        }
        val length = body.size() + if (key != null) 24 else 0
        val out = ByteBuffer.allocate(20 + length)
        out.putShort(m.type.toShort()).putShort(length.toShort()).putInt(COOKIE).put(m.txid).put(body.toByteArray())
        if (key != null) {
            val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
            mac.update(out.array(), 0, 20 + body.size())
            out.putShort(MESSAGE_INTEGRITY.toShort()).putShort(20).put(mac.doFinal())
        }
        return out.array()
    }

    fun decode(bytes: ByteArray, n: Int): Message? {
        if (n < 20) return null
        val b = ByteBuffer.wrap(bytes, 0, n)
        val type = b.short.toInt() and 0xffff
        val length = b.short.toInt() and 0xffff
        if (b.int != COOKIE || length != n - 20) return null
        val txid = ByteArray(12).also(b::get)
        val attrs = mutableListOf<Pair<Int, ByteArray>>()
        while (b.remaining() >= 4) {
            val t = b.short.toInt() and 0xffff
            val len = b.short.toInt() and 0xffff
            if (len > b.remaining()) return null
            attrs += t to ByteArray(len).also(b::get)
            b.position(minOf(b.limit(), b.position() + (4 - len % 4) % 4))
        }
        return Message(type, txid, attrs)
    }

    /** Sends over UDP and waits for the answer to this request, sending again twice if none comes. */
    private fun exchange(socket: DatagramSocket, m: Message, key: ByteArray?, timeoutMs: Int): Pair<Message, Long>? {
        val packet = encode(m, key)
        val buf = ByteArray(2048)
        var wait = maxOf(timeoutMs / 4, 50)
        repeat(3) {
            val start = System.nanoTime()
            socket.send(DatagramPacket(packet, packet.size))
            val deadline = start + wait * 1_000_000L
            while (true) {
                val left = ((deadline - System.nanoTime()) / 1_000_000).toInt()
                if (left <= 0) break
                socket.soTimeout = left
                val got = DatagramPacket(buf, buf.size)
                if (runCatching { socket.receive(got) }.isFailure) break
                val reply = decode(buf, got.length)
                if (reply != null && reply.txid.contentEquals(m.txid)) return reply to (System.nanoTime() - start) / 1_000_000
            }
            wait *= 2
        }
        return null
    }

    private fun connect(host: String, port: Int, timeoutMs: Int): DatagramSocket =
        DatagramSocket().apply { soTimeout = timeoutMs; connect(InetSocketAddress(InetAddress.getByName(host), port)) }

    /** The round trip to the relay in ms, by a binding request; null when it does not answer. */
    fun binding(host: String, port: Int, timeoutMs: Int = 2000): Long? = runCatching {
        connect(host, port, timeoutMs).use { s ->
            exchange(s, request(BINDING), null, timeoutMs)?.takeIf { it.first.type == (BINDING or SUCCESS) }?.second
        }
    }.getOrNull()

    /** What an allocation attempt came to: null when the relay handed out a relayed address, else why not. */
    fun allocate(host: String, port: Int, username: String, password: String, timeoutMs: Int = 2000): String? = runCatching {
        connect(host, port, timeoutMs).use { s ->
            val transport = REQUESTED_TRANSPORT to byteArrayOf(17, 0, 0, 0)
            // The first request carries no credentials; the relay answers 401 with its realm and a nonce.
            val first = exchange(s, request(ALLOCATE, transport), null, timeoutMs)?.first ?: return@use "no_answer"
            val realm = first.get(REALM)
            val nonce = first.get(NONCE)
            if (first.type != (ALLOCATE or ERROR) || first.errorCode != 401 || realm == null || nonce == null) return@use "not_a_relay"
            val key = MessageDigest.getInstance("MD5").digest("$username:${String(realm)}:$password".toByteArray())
            val auth = arrayOf(USERNAME to username.toByteArray(), REALM to realm, NONCE to nonce)
            val second = exchange(s, request(ALLOCATE, transport, *auth), key, timeoutMs)?.first ?: return@use "no_answer"
            if (second.type != (ALLOCATE or SUCCESS)) return@use if (second.errorCode == 401 || second.errorCode == 431) "wrong_secret" else "refused_${second.errorCode}"
            if (second.get(XOR_RELAYED_ADDRESS) == null) return@use "refused_0"
            // Would it forward to an ordinary public address? A forwarding rule too many shuts those out while the
            // allocation still works, and calls then fail with no other sign (seen on the official relay, 2026-11-11).
            val ask = request(CREATE_PERMISSION)
            val cookie = ByteBuffer.allocate(4).putInt(COOKIE).array()
            val peer = byteArrayOf(0, 1, (9 xor 0x21).toByte(), (0 xor 0x12).toByte()) + ByteArray(4) { (PUBLIC_PEER[it].toInt() xor cookie[it].toInt()).toByte() }
            val allowed = exchange(s, Message(ask.type, ask.txid, listOf(XOR_PEER_ADDRESS to peer) + auth), key, timeoutMs)?.first
            val problem = if (allowed != null && allowed.type != (CREATE_PERMISSION or SUCCESS)) "blocks_public" else null
            // Given back, so a check does not hold one of the relay's ports until it times out.
            exchange(s, request(REFRESH, LIFETIME to byteArrayOf(0, 0, 0, 0), *auth), key, timeoutMs / 2)
            problem
        }
    }.getOrElse { "unreachable" }
}
