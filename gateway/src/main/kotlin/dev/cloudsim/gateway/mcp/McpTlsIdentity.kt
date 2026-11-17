package dev.cloudsim.gateway.mcp

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * The gateway's own certificate for MCP over the home network (GATEWAY_MCP.md 14, "TLS on the Mate 40").
 *
 * The EC P-256 key is made and used for signing in this process, not in the Android Keystore: on the Mate 40 the
 * Keystore refused the raw ECDSA signature TLS asks of it (KeyMint -65530), so every handshake failed. The key and
 * the certificate are kept in the app's private directory, sealed by [Sealer] (a key that does live in the Keystore),
 * and stay the same across restarts: tools recognise the gateway by the certificate's fingerprint.
 */
class McpTlsIdentity(val key: PrivateKey, val certificate: X509Certificate) {
    /** SHA-256 of the certificate, what a client pins instead of trusting any authority. */
    val fingerprint: String = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

    fun sslContext(): SSLContext {
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry(ALIAS, key, PASSWORD, arrayOf(certificate)) }
        return SSLContext.getInstance("TLSv1.3").apply {
            init(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, PASSWORD) }.keyManagers, null, null)
        }
    }

    fun encode(): ByteArray = ByteArrayOutputStream().also { b ->
        DataOutputStream(b).use { o ->
            o.writeInt(VERSION)
            key.encoded.let { o.writeInt(it.size); o.write(it) }
            certificate.encoded.let { o.writeInt(it.size); o.write(it) }
        }
    }.toByteArray()

    /** Seals and unseals the stored identity; on the phone this is a Tink AEAD whose key is in the Android Keystore. */
    interface Sealer {
        fun seal(plain: ByteArray, ad: ByteArray): ByteArray
        fun open(sealed: ByteArray, ad: ByteArray): ByteArray
    }

    companion object {
        private const val ALIAS = "mcp"
        private const val VERSION = 1
        private val PASSWORD = CharArray(0)
        private val AD = "simnest-mcp-tls-v1".toByteArray()

        /** A new key and a self-signed certificate that does not expire (RFC 5280 4.1.2.5: 99991231235959Z). */
        fun generate(random: SecureRandom = SecureRandom()): McpTlsIdentity {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
            val name = X500Name("CN=simnest-gateway")
            val notBefore = Date(System.currentTimeMillis() - 24 * 3600_000L)
            val notAfter = Date(253402300799000L)
            val serial = BigInteger(64, random).add(BigInteger.ONE)
            val holder = JcaX509v3CertificateBuilder(name, serial, notBefore, notAfter, name, pair.public)
                .build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private))
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(holder.encoded)) as X509Certificate
            return McpTlsIdentity(pair.private, cert)
        }

        fun decode(bytes: ByteArray): McpTlsIdentity = DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            require(i.readInt() == VERSION) { "unknown version" }
            fun chunk() = ByteArray(i.readInt().also { require(it in 1..65536) }).also { i.readFully(it) }
            val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(chunk()))
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(chunk())) as X509Certificate
            require(i.read() == -1) { "trailing bytes" }
            McpTlsIdentity(key, cert)
        }

        /**
         * The identity kept in [file]; made and written the first time, and made again (a new fingerprint) when the
         * file is missing, damaged or cannot be unsealed. [log] hears when that happens, never key material.
         */
        fun loadOrCreate(file: File, sealer: Sealer, log: (String) -> Unit = {}): McpTlsIdentity {
            if (file.exists()) {
                runCatching { return decode(sealer.open(file.readBytes(), AD)) }
                    .onFailure { log("mcp: stored certificate unreadable (${it.javaClass.simpleName}), making a new one") }
            }
            val made = generate()
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(sealer.seal(made.encode(), AD))
            if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "could not store the certificate" } }
            log("mcp: made a new certificate")
            return made
        }
    }
}
