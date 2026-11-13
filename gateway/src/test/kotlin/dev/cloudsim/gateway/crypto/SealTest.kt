package dev.cloudsim.gateway.crypto

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.hybrid.HpkePublicKey
import dev.cloudsim.gateway.pairing.SoftwareSigner
import dev.cloudsim.gateway.protocol.Protocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SealTest {
    private val handle = KeysetHandle.generateNew(Seal.parameters)
    private val recipientKey = (handle.publicKeysetHandle.primary.key as HpkePublicKey).publicKeyBytes.toByteArray()
    private val decrypt = handle.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)
    private val sender = SoftwareSigner()
    private val from = Protocol.deviceId(sender.publicKey)

    @Test
    fun roundTripAndEveryBindingIsChecked() {
        val env = Seal.seal("caller", from, "phone-1", "call-1", recipientKey, "13800138000".toByteArray(), sender)
        assertEquals(32, java.util.Base64.getDecoder().decode(env.getString("enc")).size)
        assertArrayEquals("13800138000".toByteArray(), Seal.open(env, "caller", "phone-1", sender.publicKey, decrypt))
        // Another kind, another recipient, another sender key, or a changed object ID all fail.
        assertNull(Seal.open(env, "dial", "phone-1", sender.publicKey, decrypt))
        assertNull(Seal.open(env, "caller", "phone-2", sender.publicKey, decrypt))
        assertNull(Seal.open(env, "caller", "phone-1", SoftwareSigner().publicKey, decrypt))
        assertNull(Seal.open(org.json.JSONObject(env.toString()).put("id", "call-2"), "caller", "phone-1", sender.publicKey, decrypt))
    }

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private val fixedPrivate = ByteArray(32) { (it + 1).toByte() }
    private val fixedPublic = unhex("07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c")
    private val vectorInfo = "cloudsim-seal-v1\ncaller\nfrom-a\nto-b\ncall-c".toByteArray()

    /** Produced by CryptoKit on the Mac (ios/Tests/HPKEVectors.swift); Tink must open it with the same key and info. */
    @Test
    fun opensWhatCryptoKitSealed() {
        val enc = unhex("014c99fbf2d58dfc50c5cabd968d440214ada2cdb934befdaf2a0fb755ea892f")
        val ct = unhex("85bb212ce33ba226fa7c93667ccd7b8738dd6d3c3013aeb46585a6")
        assertArrayEquals("13800138000".toByteArray(), Seal.decrypter(fixedPrivate, fixedPublic).decrypt(enc + ct, vectorInfo))
    }

    /** Writes a Tink ciphertext for the Swift side to open (checked once on the Mac; see the Swift vector file). */
    @Test
    fun sealsSomethingCryptoKitCanOpen() {
        val sealed = Seal.encrypter(fixedPublic).encrypt("10086".toByteArray(), vectorInfo)
        val hex = { b: ByteArray -> b.joinToString("") { "%02x".format(it) } }
        java.io.File(System.getProperty("java.io.tmpdir"), "cloudsim-tink-vector.txt")
            .writeText(hex(sealed.copyOfRange(0, 32)) + "\n" + hex(sealed.copyOfRange(32, sealed.size)) + "\n")
        assertArrayEquals("10086".toByteArray(), Seal.decrypter(fixedPrivate, fixedPublic).decrypt(sealed, vectorInfo))
    }
}
