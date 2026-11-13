package dev.cloudsim.gateway

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.zip.Inflater

class DiagnosticsTest {
    @Test
    fun masksLongDigitRunsOnly() {
        assertEquals("call *** at 12:30 code 1234 id 1234567e", Diagnostics.mask("call 13800000000 at 12:30 code 1234 id 1234567e"))
    }

    @Test
    fun masksAddressesAndWholeIds() {
        val line = "11-08 12:34:56.789 v 0.14.0 lan 192.168.1.20:5000 v6 2408:8456:1234:abcd::1 ll fe80::1%wlan0 " +
            "full 2001:0db8:0000:0000:0000:ff00:0042:8329 dev 0a1b2c3d4e5f60718293a4b5c6d7e8f9 call 6f9619ff-8b86-d011-b42d-00c04fc964ff."
        assertEquals(
            "11-08 12:34:56.789 v 0.14.0 lan <ipv4>:5000 v6 <ipv6> ll <ipv6> full <ipv6> dev 0a1b2c3d… call 6f9619ff….",
            Diagnostics.mask(line),
        )
    }

    @Test
    fun packsRawDeflate() {
        val text = "line\n".repeat(1000)
        val inflater = Inflater(true)
        inflater.setInput(Diagnostics.pack(text))
        val out = ByteArray(10_000)
        val n = inflater.inflate(out)
        assertEquals(text, String(out, 0, n))
    }
}
