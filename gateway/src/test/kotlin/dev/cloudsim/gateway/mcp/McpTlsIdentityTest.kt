package dev.cloudsim.gateway.mcp

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

class McpTlsIdentityTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sealer(): McpTlsIdentity.Sealer {
        AeadConfig.register()
        val aead = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")).getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        return object : McpTlsIdentity.Sealer {
            override fun seal(plain: ByteArray, ad: ByteArray) = aead.encrypt(plain, ad)
            override fun open(sealed: ByteArray, ad: ByteArray) = aead.decrypt(sealed, ad)
        }
    }

    @Test
    fun madeOnceThenTheSameAfterARestart() {
        val file = File(tmp.root, "mcp/tls.bin")
        val sealer = sealer()
        val logs = mutableListOf<String>()
        val first = McpTlsIdentity.loadOrCreate(file, sealer) { logs += it }
        assertTrue(file.exists())
        assertEquals(64, first.fingerprint.length)
        assertEquals("CN=simnest-gateway", first.certificate.subjectX500Principal.name)
        assertEquals("EC", first.key.algorithm)
        assertTrue(first.certificate.notAfter.time >= 253402300799000L)
        // Stored sealed: neither the key nor the certificate is readable in the file.
        val raw = file.readBytes()
        val latin = { b: ByteArray -> String(b, Charsets.ISO_8859_1) }
        assertFalse(latin(raw).contains(latin(first.certificate.encoded.copyOfRange(0, 32))))
        assertFalse(latin(raw).contains(latin(first.key.encoded.copyOfRange(first.key.encoded.size - 32, first.key.encoded.size))))
        // A new process: same key, same certificate, same fingerprint, nothing made.
        val again = McpTlsIdentity.loadOrCreate(file, sealer) { logs += it }
        assertEquals(first.fingerprint, again.fingerprint)
        assertEquals(first.key, again.key)
        assertEquals(listOf("mcp: made a new certificate"), logs)
    }

    @Test
    fun damagedMissingOrUnsealableIsMadeAgain() {
        val file = File(tmp.root, "tls.bin")
        val sealer = sealer()
        val first = McpTlsIdentity.loadOrCreate(file, sealer)

        file.writeBytes(file.readBytes().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() })
        val logs = mutableListOf<String>()
        val second = McpTlsIdentity.loadOrCreate(file, sealer) { logs += it }
        assertNotEquals(first.fingerprint, second.fingerprint)
        assertTrue(logs.first().startsWith("mcp: stored certificate unreadable"))
        assertEquals(second.fingerprint, McpTlsIdentity.loadOrCreate(file, sealer).fingerprint)

        file.delete()
        val third = McpTlsIdentity.loadOrCreate(file, sealer)
        assertNotEquals(second.fingerprint, third.fingerprint)

        // The Keystore key behind the sealer is gone (app data restored elsewhere): a new identity, not a crash.
        val fourth = McpTlsIdentity.loadOrCreate(file, sealer())
        assertNotEquals(third.fingerprint, fourth.fingerprint)

        file.writeBytes(ByteArray(0))
        McpTlsIdentity.loadOrCreate(file, sealer)
    }

    @Test
    fun encodeDecodeRoundTripAndRejectsJunk() {
        val id = McpTlsIdentity.generate()
        val back = McpTlsIdentity.decode(id.encode())
        assertEquals(id.fingerprint, back.fingerprint)
        assertEquals(id.key, back.key)
        assertTrue(runCatching { McpTlsIdentity.decode(id.encode() + 0) }.isFailure)
        assertTrue(runCatching { McpTlsIdentity.decode(byteArrayOf(0, 0, 0, 9)) }.isFailure)
    }

    /** A TLS 1.3 handshake with the stored identity; the client sees the certificate whose fingerprint the screen shows. */
    @Test
    fun servesTls13WithThePinnedCertificate() {
        val file = File(tmp.root, "tls.bin")
        val sealer = sealer()
        McpTlsIdentity.loadOrCreate(file, sealer)
        val id = McpTlsIdentity.loadOrCreate(file, sealer)
        val server = id.sslContext().serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        val t = thread { server.accept().use { s -> s.getOutputStream().write(s.getInputStream().read()) } }
        var seen: X509Certificate? = null
        val pin = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                seen = chain[0]
                val fp = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
                if (fp != id.fingerprint) throw java.security.cert.CertificateException("not the pinned certificate")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val client = SSLContext.getInstance("TLSv1.3").apply { init(null, arrayOf(pin), null) }
        (client.socketFactory.createSocket(InetAddress.getLoopbackAddress(), server.localPort) as SSLSocket).use { s ->
            s.outputStream.write(7)
            assertEquals(7, s.inputStream.read())
            assertEquals("TLSv1.3", s.session.protocol)
        }
        t.join(5000)
        server.close()
        assertEquals(id.certificate, seen)
    }
}
