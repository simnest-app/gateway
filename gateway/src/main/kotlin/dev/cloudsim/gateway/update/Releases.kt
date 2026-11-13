package dev.cloudsim.gateway.update

import org.json.JSONObject
import java.net.URI

/**
 * Where a new gateway comes from (docs/architecture/GATEWAY_UPDATE.md): the latest release of the gateway's public
 * repository on GitHub, and of its mirror on Gitee for where GitHub is slow or shut out. This file is the part that
 * knows nothing of Android: reading what the two sites answer and choosing among it. What is trusted is not the site
 * but the package itself, checked before it is installed ([Updater.verify]): the same name, a higher version, and
 * signed with the key this gateway was signed with.
 */
data class Release(
    /** "github" or "gitee". */
    val source: String,
    val versionName: String,
    val versionCode: Int,
    val url: String,
    /** In bytes, when the site says; 0 when it does not. */
    val size: Long,
    val notes: String,
    /** How long the site took to answer when asked; the quicker one is downloaded from. */
    val askedMs: Long = 0,
)

object Releases {
    /** The one file of a release this gateway takes: simnest-gateway-<version name>-<version code>.apk. */
    val ASSET = Regex("^simnest-gateway-(\\d+(?:\\.\\d+){1,3})-(\\d{1,9})\\.apk$")
    const val MAX_NOTES = 2000
    const val MAX_BYTES = 300L * 1024 * 1024

    fun latestUrl(source: String, repository: String): String? = when {
        !Regex("^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$").matches(repository) -> null
        source == "github" -> "https://api.github.com/repos/$repository/releases/latest"
        source == "gitee" -> "https://gitee.com/api/v5/repos/$repository/releases/latest"
        else -> null
    }

    /** The release in a site's answer to "latest"; both sites name the fields alike. Null when it carries no package for this gateway. */
    fun parse(source: String, json: JSONObject): Release? {
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val m = ASSET.matchEntire(a.optString("name")) ?: continue
            val url = a.optString("browser_download_url")
            val code = m.groupValues[2].toIntOrNull() ?: continue
            val size = a.optLong("size", 0)
            if (!allowed(url) || size < 0 || size > MAX_BYTES) continue
            return Release(source, m.groupValues[1], code, url, size, json.optString("body").take(MAX_NOTES))
        }
        return null
    }

    /**
     * The one to offer: the highest version among what the sites have, when it is above the installed one. Both having
     * it, the site that answered sooner (GitHub is slow from some countries, Gitee from others), GitHub when equal.
     */
    fun newest(found: List<Release>, installedCode: Int): Release? =
        found.filter { it.versionCode > installedCode }.maxWithOrNull(compareBy<Release> { it.versionCode }.thenBy { -it.askedMs }.thenBy { if (it.source == "github") 1 else 0 })

    /** Only over HTTPS and only from the two sites and where they keep their files; also asked of every redirect. */
    fun allowed(url: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        val host = u.host?.lowercase() ?: return false
        return u.scheme == "https" && u.userInfo == null && HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private val HOSTS = listOf("github.com", "githubusercontent.com", "gitee.com")
}
