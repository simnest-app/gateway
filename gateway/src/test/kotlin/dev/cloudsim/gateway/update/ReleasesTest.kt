package dev.cloudsim.gateway.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {
    private fun release(vararg assets: Pair<String, String>, draft: Boolean = false, pre: Boolean = false) = JSONObject()
        .put("tag_name", "v0.43.0").put("body", "notes").put("draft", draft).put("prerelease", pre)
        .put("assets", JSONArray(assets.map { JSONObject().put("name", it.first).put("browser_download_url", it.second).put("size", 50_000_000) }))

    private val good = "https://github.com/simnest-app/gateway/releases/download/v0.43.0/simnest-gateway-0.43.0-105.apk"

    @Test
    fun takesTheGatewayPackageOfARelease() {
        val r = Releases.parse("github", release("notes.txt" to "https://github.com/x/y/notes.txt", "simnest-gateway-0.43.0-105.apk" to good))!!
        assertEquals(105, r.versionCode)
        assertEquals("0.43.0", r.versionName)
        assertEquals(good, r.url)
        assertEquals("notes", r.notes)
    }

    @Test
    fun leavesOutWhatIsNotARelease() {
        assertNull(Releases.parse("github", release("simnest-gateway-0.43.0-105.apk" to good, draft = true)))
        assertNull(Releases.parse("github", release("simnest-gateway-0.43.0-105.apk" to good, pre = true)))
        assertNull(Releases.parse("github", release("gateway.apk" to good)))
        assertNull(Releases.parse("github", release("simnest-gateway-0.43.0-105.apk.sha256" to good)))
        assertNull(Releases.parse("github", JSONObject().put("message", "Not Found")))
        // A package kept somewhere else is not taken, whatever the release says.
        assertNull(Releases.parse("github", release("simnest-gateway-0.43.0-105.apk" to "https://example.com/simnest-gateway-0.43.0-105.apk")))
        assertNull(Releases.parse("github", release("simnest-gateway-0.43.0-105.apk" to "http://github.com/a/b/simnest-gateway-0.43.0-105.apk")))
    }

    @Test
    fun onlyTheTwoSitesOverHttps() {
        assertTrue(Releases.allowed("https://github.com/a/b"))
        assertTrue(Releases.allowed("https://release-assets.githubusercontent.com/x"))
        assertTrue(Releases.allowed("https://gitee.com/a/b/releases/download/v1/x.apk"))
        assertTrue(Releases.allowed("https://foruda.gitee.com/x"))
        assertFalse(Releases.allowed("http://github.com/a/b"))
        assertFalse(Releases.allowed("https://github.com.evil.example/a"))
        assertFalse(Releases.allowed("https://notgithub.com/a"))
        assertFalse(Releases.allowed("https://user@github.com/a"))
        assertFalse(Releases.allowed("file:///sdcard/x.apk"))
        assertFalse(Releases.allowed("not a url"))
    }

    @Test
    fun offersTheHighestVersionAboveTheInstalledOne() {
        val hub = Release("github", "0.43.0", 105, good, 1, "")
        val gitee = Release("gitee", "0.43.0", 105, "https://gitee.com/a/b/x.apk", 1, "")
        val older = Release("gitee", "0.42.0", 103, "https://gitee.com/a/b/y.apk", 1, "")
        assertNull(Releases.newest(listOf(hub, older), 105))
        assertNull(Releases.newest(emptyList(), 1))
        assertEquals(hub, Releases.newest(listOf(older, gitee, hub), 104))
        assertEquals(gitee, Releases.newest(listOf(older, gitee), 104))
        assertEquals(Release("gitee", "0.44.0", 106, "u", 1, ""), Releases.newest(listOf(hub, Release("gitee", "0.44.0", 106, "u", 1, "")), 104))
    }

    @Test
    fun theAddressToAskIsBuiltOnlyFromAPlainRepositoryName() {
        assertEquals("https://api.github.com/repos/simnest-app/gateway/releases/latest", Releases.latestUrl("github", "simnest-app/gateway"))
        assertEquals("https://gitee.com/api/v5/repos/simnest/gateway/releases/latest", Releases.latestUrl("gitee", "simnest/gateway"))
        assertNull(Releases.latestUrl("gitee", ""))
        assertNull(Releases.latestUrl("github", "a/b/../../c"))
        assertNull(Releases.latestUrl("github", "a/b?x=1"))
        assertNull(Releases.latestUrl("elsewhere", "a/b"))
    }
}
