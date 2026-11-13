package dev.cloudsim.gateway.relay

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The owner's own media relay (docs/architecture/RELAY_SPLIT.md 17), as its setup page gives it: where it is, for
 * phones away from home ([host]) and for this gateway when it sits in the relay's own network ([lan]), and the secret
 * its credentials are made from. The secret stays on this gateway: phones get credentials, never the secret, and the
 * control service gets neither.
 */
data class OwnRelay(val host: String, val port: Int, val lan: List<String>, val secret: String, val version: String) {
    /** The relay's addresses at [h], over UDP and TCP. */
    fun urls(h: String): List<String> {
        val at = if (':' in h) "[$h]" else h
        return listOf("turn:$at:$port", "turn:$at:$port?transport=tcp")
    }

    /**
     * coturn's short-term credential (use-auth-secret): the user name carries its expiry, the password is HMAC-SHA1 of
     * the user name with the secret. [tag] tells sessions apart on the relay; it is random, never a device's ID.
     */
    fun credential(tag: String, expiresAtS: Long): Pair<String, String> {
        val user = "$expiresAtS:$tag"
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA1")) }
        return user to Base64.getEncoder().encodeToString(mac.doFinal(user.toByteArray()))
    }

    /** One relay candidate, as the service's are (RELAY_SPLIT.md 18.2), of kind "own". */
    fun candidate(hosts: List<String>, tag: String, expiresAtMs: Long): JSONObject {
        val (user, pass) = credential(tag, expiresAtMs / 1000)
        return JSONObject().put("id", ID).put("kind", KIND).put("urls", JSONArray(hosts.flatMap(::urls)))
            .put("username", user).put("credential", pass).put("expires_at_ms", expiresAtMs)
    }

    fun toJson(): JSONObject = JSONObject().put("t", TYPE).put("v", 1).put("host", host).put("port", port).put("lan", JSONArray(lan)).put("secret", secret).put("ver", version)

    companion object {
        const val TYPE = "cloudsim-relay"
        const val ID = "own"
        const val KIND = "own"

        private val hostPattern = Regex("^[A-Za-z0-9.:-]{1,253}$")
        private val secretPattern = Regex("^[A-Za-z0-9_+/=-]{32,128}$")

        /** Reads the code from the relay's setup page; null for anything else, so no other text is taken for a relay. */
        fun parse(code: String): OwnRelay? = runCatching {
            val o = JSONObject(code.trim())
            if (o.optString("t") != TYPE || o.optInt("v") != 1) return null
            val host = o.getString("host")
            val port = o.getInt("port")
            val secret = o.getString("secret")
            val lan = o.optJSONArray("lan")?.let { a -> (0 until minOf(a.length(), 4)).map(a::getString) } ?: emptyList()
            if (!hostPattern.matches(host) || port !in 1..65535 || !secretPattern.matches(secret) || lan.any { !hostPattern.matches(it) }) return null
            OwnRelay(host, port, lan, secret, o.optString("ver").take(20))
        }.getOrNull()
    }
}
