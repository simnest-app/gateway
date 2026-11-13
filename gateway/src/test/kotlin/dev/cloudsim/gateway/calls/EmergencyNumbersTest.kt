package dev.cloudsim.gateway.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyNumbersTest {
    @Test
    fun refusesEmergencyNumbersOfOtherCountriesToo() {
        assertTrue(EmergencyNumbers.isEmergency("110", "cn"))
        assertTrue(EmergencyNumbers.isEmergency("911", "cn"))
        assertTrue(EmergencyNumbers.isEmergency("112", null))
        assertTrue(EmergencyNumbers.isEmergency("1669", "cn"))
    }

    @Test
    fun leavesServiceNumbersAlone() {
        assertFalse(EmergencyNumbers.isEmergency("114", "cn"))
        assertTrue(EmergencyNumbers.isEmergency("114", "vn"))
        assertFalse(EmergencyNumbers.isEmergency("10086", "cn"))
        assertFalse(EmergencyNumbers.isEmergency("13800001100", "cn"))
        assertFalse(EmergencyNumbers.isEmergency("+86110", "cn"))
    }
}
