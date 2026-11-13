package dev.cloudsim.gateway.pairing

import dev.cloudsim.gateway.net.ServiceConfig
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Gateway side against a running control service, for cross-platform runs with a real phone app.
 * Skipped unless CLOUDSIM_IT_ADDRESS, CLOUDSIM_IT_PIN (hex), CLOUDSIM_IT_SERVICE and CLOUDSIM_IT_DIR are set.
 * Writes the QR link to $CLOUDSIM_IT_DIR/link, then the code it confirmed to $CLOUDSIM_IT_DIR/gateway-code.
 */
class GatewayIntegrationTest {
    @Test
    fun pairWithAPhone() {
        val address = System.getenv("CLOUDSIM_IT_ADDRESS")
        assumeTrue(address != null)
        val config = ServiceConfig(address, System.getenv("CLOUDSIM_IT_PIN").chunked(2).map { it.toInt(16).toByte() }.toByteArray(), System.getenv("CLOUDSIM_IT_SERVICE"))
        val dir = File(System.getenv("CLOUDSIM_IT_DIR"))
        val pairing = GatewayPairing(config, SoftwareSigner())
        val code = pairing.open()
        File(dir, "link").writeText(code.link)
        var state: PairingState = PairingState.Waiting
        while (state == PairingState.Waiting && System.currentTimeMillis() < code.expiresAtMs) {
            Thread.sleep(1_000)
            state = pairing.poll(code)
        }
        val claimed = state as PairingState.Claimed
        File(dir, "gateway-code").writeText(claimed.claimant.code)
        pairing.confirm(code, claimed.claimant)
        assertEquals(PairingState.Confirmed, pairing.poll(code))
    }
}
