package dev.cloudsim.gateway.screening.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceStyleTest {
    @get:Rule val tmp = TemporaryFolder()

    private val pcm = ShortArray(24000 * 4) { (it % 200 - 100).toShort() }

    @Test fun `a voice at the default style keeps the id it had before styles`() {
        val p = VoiceProfile(tmp.newFolder())
        val id = p.set(pcm, "您好，我是机主的接听助手。")
        assertEquals(VoiceProfile.id(dev.cloudsim.gateway.screening.ScreenAssetStore.bytes(pcm), "您好，我是机主的接听助手。"), id)
        assertEquals(VoiceProfile.Style(), p.get()!!.style)
        assertEquals(id, p.setStyle(VoiceProfile.Style()))
    }

    @Test fun `another style is another id, kept with the voice, and a new recording starts at the defaults`() {
        val p = VoiceProfile(tmp.newFolder())
        val plain = p.set(pcm, "好的，我记下了。")
        val styled = p.setStyle(VoiceProfile.Style(1.1f, 1.5f))!!
        assertNotEquals(plain, styled)
        assertEquals(styled, p.get()!!.id)
        assertEquals(VoiceProfile.Style(1.1f, 1.5f), p.get()!!.style)
        assertEquals(plain, p.set(pcm, "好的，我记下了。"))
        assertEquals(VoiceProfile.Style(), p.get()!!.style)
    }

    @Test fun `only the ranges the iPhone offers`() {
        assertEquals(VoiceProfile.Style(0.8f, 2f), VoiceProfile.Style.of(0.8, 2.0))
        assertEquals(VoiceProfile.Style(1.05f, 1.33f), VoiceProfile.Style.of(1.0499999, 1.333))
        assertNull(VoiceProfile.Style.of(0.79, 1.0))
        assertNull(VoiceProfile.Style.of(1.0, 2.01))
        assertNull(VoiceProfile.Style.of(Double.NaN, 1.0))
    }

    @Test fun `no style without a voice`() {
        assertNull(VoiceProfile(tmp.newFolder()).setStyle(VoiceProfile.Style(1.1f, 1f)))
    }
}
