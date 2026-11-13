package dev.cloudsim.gateway.system

import dev.cloudsim.gateway.pairing.SoftwareSigner
import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ServiceConfig
import dev.cloudsim.gateway.net.ServiceException
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/** The service's move to a domain name (SERVER_LAYOUT.md): the new place first, the old one when it cannot be reached. */
class ServiceMoveSystemTest {
    companion object {
        private const val SERVICE_ID = "cloudsim-move-test"
        private lateinit var server: LocalServer

        @BeforeClass @JvmStatic
        fun up() {
            assumeTrue("set CLOUDSIM_V4=1 to run the service-move system test", System.getenv("CLOUDSIM_V4") == "1")
            server = LocalServer(SERVICE_ID).also { it.start() }
        }

        @AfterClass @JvmStatic
        fun down() {
            if (::server.isInitialized) server.stop()
        }
    }

    private val old get() = "127.0.0.1:${server.port}"

    @Test
    fun nothingAtTheNewPlaceFallsBackToTheOld() {
        var clock = System.currentTimeMillis() // also the signed time, so close to the real one
        val client = ControlClient(ServiceConfig(old, server.certSha256, SERVICE_ID, movedTo = "127.0.0.1:1"), SoftwareSigner(), { clock })
        client.register("gateway")
        // Within the minute after, straight to the old place; and after it, the new one is tried again (and fails again).
        clock += 30_000
        client.register("gateway")
        clock += ControlClient.RETRY_MOVED_MS
        client.register("gateway")
    }

    @Test
    fun aSelfSignedCertificateIsNotTakenWithoutThePin() {
        // The same service under a "domain" with no pin: the system's checks refuse its self-signed certificate, so the
        // request goes to the pinned old place instead of being sent to a server nobody vouched for.
        val client = ControlClient(ServiceConfig("127.0.0.1:1", server.certSha256, SERVICE_ID, movedTo = old), SoftwareSigner())
        val e = runCatching { client.register("gateway") }.exceptionOrNull()
        assertTrue("refused at the new place, then nothing at the old: $e", e is ServiceException && e.code == "NETWORK")
    }

    @Test
    fun withoutAMoveOnlyTheAddressIsUsed() {
        val client = ControlClient(ServiceConfig(old, server.certSha256, SERVICE_ID), SoftwareSigner())
        client.register("gateway")
    }
}
