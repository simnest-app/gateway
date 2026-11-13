package dev.cloudsim.gateway.protocol

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Byte-exact parts of pairing protocol v1, mirroring server/internal/protocol.
 * See docs/architecture/PAIRING_PROTOCOL.md; the test vectors in ProtocolTest must match the Go ones.
 */
object Protocol {
    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        parts.forEach { update(it) }
        digest()
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** First 16 bytes of SHA-256 over the SubjectPublicKeyInfo DER, lowercase hex. */
    fun deviceId(spki: ByteArray): String = hex(sha256(spki).copyOf(16))

    fun requestMessage(method: String, requestUri: String, timeMs: String, nonce: String, body: ByteArray): ByteArray =
        "cloudsim-req-v1\n$method\n$requestUri\n$timeMs\n$nonce\n${hex(sha256(body))}".toByteArray()

    private fun mac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    fun claimKey(secret: ByteArray) = mac(secret, "cloudsim-pair-v1 claim".toByteArray())
    fun authKey(secret: ByteArray) = mac(secret, "cloudsim-pair-v1 auth".toByteArray())
    fun claimMac(claimKey: ByteArray, phoneId: String) = mac(claimKey, phoneId.toByteArray())
    fun authMac(authKey: ByteArray, transcript: ByteArray) = mac(authKey, transcript)

    /** Constant-time comparison. */
    fun equalMac(a: ByteArray?, b: ByteArray?) = a != null && b != null && MessageDigest.isEqual(a, b)

    /** Every field is prefixed with its 4-byte big-endian length. */
    fun transcript(serviceId: String, pairingId: String, gatewaySpki: ByteArray, phoneSpki: ByteArray): ByteArray {
        val fields = listOf("cloudsim-pair-v1".toByteArray(), serviceId.toByteArray(), pairingId.toByteArray(), gatewaySpki, phoneSpki)
        val out = ByteBuffer.allocate(fields.sumOf { 4 + it.size })
        fields.forEach { out.putInt(it.size).put(it) }
        return out.array()
    }

    fun bindMessage(role: String, transcript: ByteArray) = "cloudsim-bind-v1 $role".toByteArray() + transcript

    /** What each endpoint signs for an SDP it sends: binds the DTLS fingerprint to this call, direction and generation. */
    fun mediaMessage(callId: String, kind: String, gen: Int, sdp: String): ByteArray =
        "cloudsim-media-v1\n$callId\n$kind\n$gen\n${hex(sha256(sdp.toByteArray()))}".toByteArray()

    /** What the owner signs for one revision of a gateway's sharing policy (docs/architecture/FAMILY_RING_3D.md). */
    fun policyMessage(gatewayId: String, revision: Int, policy: ByteArray): ByteArray =
        "cloudsim-policy-v1\n$gatewayId\n$revision\n${hex(sha256(policy))}".toByteArray()

    /** What the old owner, or the owner's recovery key, signs to hand the gateway to a new phone (BACKUP_RESTORE_6.md). */
    fun handoverMessage(gatewayId: String, newOwnerKey: ByteArray, atMs: Long): ByteArray =
        "cloudsim-handover-v1\n$gatewayId\n${hex(sha256(newOwnerKey))}\n$atMs".toByteArray()

    /** A connection self-test (SELF_TEST.md): the phone signs its request, this gateway its reply; sdp may be empty. */
    fun probeMessage(role: String, gatewayId: String, probeId: String, sdp: String): ByteArray =
        "cloudsim-probe-v1\n$role\n$gatewayId\n$probeId\n${hex(sha256(sdp.toByteArray()))}".toByteArray()

    /** What the owner signs to let a recovery key hand the gateway on. */
    fun recoveryMessage(gatewayId: String, recoveryKey: ByteArray): ByteArray =
        "cloudsim-recovery-v1\n$gatewayId\n${hex(sha256(recoveryKey))}".toByteArray()

    /** What the recovery key signs about the gateway's own key. */
    fun recoveryGatewayMessage(gatewayId: String, gatewayKey: ByteArray): ByteArray =
        "cloudsim-recovery-gateway-v1\n$gatewayId\n${hex(sha256(gatewayKey))}".toByteArray()

    /** What this gateway signs when it takes a new owner. */
    fun ownerMessage(gatewayId: String, ownerKey: ByteArray, previousId: String, policyRevision: Int, atMs: Long): ByteArray =
        "cloudsim-owner-v1\n$gatewayId\n${hex(sha256(ownerKey))}\n$previousId\n$policyRevision\n$atMs".toByteArray()

    /** What a device signs to publish its HPKE recipient key (docs/architecture/CALLER_DIAL_3E.md). */
    fun boxKeyMessage(deviceId: String, boxKey: ByteArray): ByteArray =
        "cloudsim-boxkey-v1\n$deviceId\n${hex(sha256(boxKey))}".toByteArray()

    /** Six digits both screens show for the user to compare. */
    fun confirmationCode(transcript: ByteArray): String {
        val sum = sha256("cloudsim-pair-v1 code".toByteArray(), transcript)
        val n = java.lang.Long.remainderUnsigned(ByteBuffer.wrap(sum, 0, 8).long, 1_000_000)
        return n.toString().padStart(6, '0')
    }

    /** Verifies a DER ECDSA signature; only P-256 keys are accepted. */
    fun verify(spki: ByteArray, message: ByteArray, signature: ByteArray?): Boolean = runCatching {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)) as ECPublicKey
        require(key.params.curve.field.fieldSize == 256)
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(message)
            verify(signature)
        }
    }.getOrDefault(false)
}
