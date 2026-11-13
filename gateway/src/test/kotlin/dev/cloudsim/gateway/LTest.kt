package dev.cloudsim.gateway

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LTest {
    private val saved = Locale.getDefault()

    @After fun restore() = Locale.setDefault(saved)

    @Test
    fun followsTheLanguage() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        assertEquals("开启网关", L.t("开启网关"))
        Locale.setDefault(Locale.US)
        assertEquals("Turn On Gateway", L.t("开启网关"))
        assertEquals("Version 0.18.0 (51)", L.t("版本 %1\$s（%2\$s）", "0.18.0", 51))
        Locale.setDefault(Locale.TRADITIONAL_CHINESE)
        assertEquals("開啟閘道", L.t("开启网关"))
        Locale.setDefault(Locale.JAPAN)
        assertEquals("Turn On Gateway", L.t("开启网关"))
    }

    @Test
    fun everyKeyHasBothTranslations() {
        assertEquals(I18nTables.en.keys, I18nTables.zhHant.keys)
    }
}
