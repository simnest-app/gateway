package dev.cloudsim.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildInfoTest {
    @Test
    fun summaryShowsNameAndCode() {
        assertEquals("版本 0.1.0（1）", BuildInfo.summary("0.1.0", 1))
    }
}
