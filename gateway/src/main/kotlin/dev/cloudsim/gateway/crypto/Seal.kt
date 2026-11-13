package dev.cloudsim.gateway.crypto

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePrivateKey
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.util.Bytes
import com.google.crypto.tink.util.SecretBytes
import dev.cloudsim.gateway.identity.Signer
import dev.cloudsim.gateway.net.ControlClient.Companion.b64
import dev.cloudsim.gateway.net.ControlClient.Companion.unb64
import dev.cloudsim.gateway.protocol.Protocol
import org.json.JSONObject

/**
 * End-to-end sealing (docs/architecture/CALLER_DIAL_3E.md): RFC 9180 HPKE base mode with DHKEM(X25519, HKDF-SHA256),
 * HKDF-SHA256 and AES-128-GCM, one ciphertext per recipient device, signed by the sender's device key because HPKE alone
 * does not say who sent it. Tink's RAW output is enc (32 bytes) followed by the ciphertext, as CryptoKit expects.
 */
object Seal {
    init {
        HybridConfig.register()
    }

    val parameters: HpkeParameters = HpkeParameters.builder()
        .setVariant(HpkeParameters.Variant.NO_PREFIX)
        .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
        .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
        .setAeadId(HpkeParameters.AeadId.AES_128_GCM)
        .build()

    const val ENC_LENGTH = 32

    fun info(kind: String, from: String, to: String, id: String) = "cloudsim-seal-v1\n$kind\n$from\n$to\n$id".toByteArray()

    fun signedPart(kind: String, from: String, to: String, id: String, sealed: ByteArray) =
        "cloudsim-seal-v1\n$kind\n$from\n$to\n$id\n${Protocol.hex(Protocol.sha256(sealed))}".toByteArray()

    fun encrypter(recipientKey: ByteArray): HybridEncrypt {
        val key = HpkePublicKey.create(parameters, Bytes.copyFrom(recipientKey), null)
        val handle = KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary()).build()
        return handle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
    }

    /** A decrypter from raw key bytes, for tests and cross-platform vectors. */
    fun decrypter(privateKey: ByteArray, publicKey: ByteArray): HybridDecrypt {
        val pub = HpkePublicKey.create(parameters, Bytes.copyFrom(publicKey), null)
        val key = HpkePrivateKey.create(pub, SecretBytes.copyFrom(privateKey, InsecureSecretKeyAccess.get()))
        val handle = KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary()).build()
        return handle.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)
    }

    /** Seals [plaintext] for one recipient and signs it with [signer]. */
    fun seal(kind: String, from: String, to: String, id: String, recipientKey: ByteArray, plaintext: ByteArray, signer: Signer): JSONObject {
        val sealed = encrypter(recipientKey).encrypt(plaintext, info(kind, from, to, id))
        return JSONObject().put("v", 1).put("kind", kind).put("from", from).put("to", to).put("id", id)
            .put("enc", b64(sealed.copyOfRange(0, ENC_LENGTH))).put("ct", b64(sealed.copyOfRange(ENC_LENGTH, sealed.size)))
            .put("sig", b64(signer.sign(signedPart(kind, from, to, id, sealed))))
    }

    /**
     * Opens an envelope meant for [me] of [kind], if [senderKey] (whose hash must be the claimed sender) signed it.
     * Returns null for anything else.
     */
    fun open(envelope: JSONObject, kind: String, me: String, senderKey: ByteArray, decrypt: HybridDecrypt): ByteArray? = runCatching {
        val from = envelope.getString("from")
        val id = envelope.getString("id")
        require(envelope.getInt("v") == 1 && envelope.getString("kind") == kind && envelope.getString("to") == me && Protocol.deviceId(senderKey) == from)
        val sealed = unb64(envelope.getString("enc"))!! + unb64(envelope.getString("ct"))!!
        require(Protocol.verify(senderKey, signedPart(kind, from, me, id, sealed), unb64(envelope.getString("sig"))))
        decrypt.decrypt(sealed, info(kind, from, me, id))
    }.getOrNull()
}
