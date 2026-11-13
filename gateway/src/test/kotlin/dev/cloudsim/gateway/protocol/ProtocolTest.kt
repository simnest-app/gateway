package dev.cloudsim.gateway.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** Same vectors as server/internal/protocol/protocol_test.go. */
class ProtocolTest {
    private val secret = ByteArray(32) { it.toByte() }
    private val transcript = Protocol.transcript("svc", "pair", byteArrayOf(1, 2), byteArrayOf(3))

    @Test
    fun vectors() {
        assertEquals("446dfe1cf7b97c2ea9003274fde7d4512e795cf0951976612122bdfa3471496f", Protocol.hex(Protocol.claimKey(secret)))
        assertEquals("5d12b93fdfb99aa6247dee15d3ab1745c16e980566144199716290532a2a774e", Protocol.hex(Protocol.authKey(secret)))
        assertEquals("00000010636c6f756473696d2d706169722d76310000000373766300000004706169720000000201020000000103", Protocol.hex(transcript))
        assertEquals("678811", Protocol.confirmationCode(transcript))
        assertEquals("6feecc8c16c5551d9feb3eb5f77e2da7", Protocol.deviceId("spki".toByteArray()))
        assertEquals("55926c1ff858f9d4c3720377bcd7a19cbad231dda949325f6fd47cbbe0d77a8e", Protocol.hex(Protocol.sha256(Protocol.mediaMessage("call-1", "offer", 2, "v=0\r\n"))))
        assertEquals("f2f2167b2055020487005dd17afb937d1e7342c41aa308764340338df681c0f8", Protocol.hex(Protocol.sha256(Protocol.policyMessage("gw", 3, "{\"v\":1}".toByteArray()))))
        // Changing the owner (BACKUP_RESTORE_6.md); the same vectors as the Go and Swift tests.
        assertEquals("df52cb451c1c46de19b5ab57d7d43c71aaf988829342d0baa7e687eeaf074cac", Protocol.hex(Protocol.sha256(Protocol.handoverMessage("gw", byteArrayOf(1, 2, 3), 1700000000000L))))
        assertEquals("701469ff15a89f662e70fe926a8489ed5f90836ebac2e2940944a75896562dad", Protocol.hex(Protocol.sha256(Protocol.recoveryMessage("gw", byteArrayOf(1, 2, 3)))))
        assertEquals("29149c84e0f75de0a634866eac6ceb23c13fb202fae0c00c95e94bdbfbf61b69", Protocol.hex(Protocol.sha256(Protocol.recoveryGatewayMessage("gw", byteArrayOf(1, 2, 3)))))
        assertEquals("ac6ad3e76a33bc4613d2871cf89f130ffbbed6c00bdbdb2706c9b926a410ce48", Protocol.hex(Protocol.sha256(Protocol.ownerMessage("gw", byteArrayOf(1, 2, 3), "old", 4, 1700000000000L))))
        assertEquals("f4e7b91be411aad7c4241c90d5f6536d61a2e6603d8a0fe74259f93b56687ed3", Protocol.hex(Protocol.sha256(Protocol.probeMessage("request", "gw", "p1", "v=0"))))
    }

    @Test
    fun signatures() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val message = Protocol.requestMessage("GET", "/v1/pairings/x", "1", "n", ByteArray(0))
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(message); sign() }
        assertTrue(Protocol.verify(pair.public.encoded, message, signature))
        assertFalse(Protocol.verify(pair.public.encoded, message + 1, signature))
        val p384 = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        assertFalse(Protocol.verify(p384.public.encoded, message, signature))
    }
}
