package dev.cloudsim.gateway.calls

/**
 * Emergency numbers of many countries and regions, the same table as the iPhone's (ios/Sources/Emergency.swift): a
 * dial request from a phone for any of them is refused, on top of the system's own list for this gateway's SIM, since
 * a call through the gateway would give the emergency centre the gateway's place and number. Calls made on this phone
 * itself are not touched.
 */
object EmergencyNumbers {
    private val all = setOf(
        "110", "119", "120", "122", // mainland China
        "999", "112", "911", "000", "111", "106", // Hong Kong, Macao, Europe, Americas, Australia, New Zealand
        "15", "17", "18", "113", "115", "118", // France, Italy, Japan, Indonesia
        "100", "101", "102", "103", "108", // India, Russia
        "995", "997", "998", "191", "199", "1669", "190", "192", "193", // Singapore, Gulf, Thailand, Brazil
    )

    /** Emergency elsewhere, a service at home: blocked only where it is one (114 is fire in Vietnam). */
    private val local = mapOf("114" to setOf("vn"))

    /** [country] is the SIM's ISO country code, lower case ("cn"), when known. */
    fun isEmergency(number: String, country: String?): Boolean {
        val n = number.filter { it.isDigit() }
        if (n.isEmpty() || n.length > 4 || n.length != number.count { !it.isWhitespace() && it != '-' }) return false
        local[n]?.let { return country != null && country.lowercase() in it }
        return n in all
    }
}
