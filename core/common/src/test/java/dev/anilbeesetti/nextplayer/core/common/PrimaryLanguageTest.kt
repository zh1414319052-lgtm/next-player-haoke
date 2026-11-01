package dev.anilbeesetti.nextplayer.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class PrimaryLanguageTest {
    @Test
    fun chineseVariantsAlwaysSelectSimplifiedChinese() {
        listOf("zh", "zh-CN", "zh-SG", "zh-Hant", "zh-HK", "zh-TW", "ZH_hant_TW").forEach {
            assertEquals("zh-CN", PrimaryLanguage.languageTag(listOf(it, "en-US")))
        }
    }

    @Test
    fun laterChineseNeverOverridesFirstSystemLanguage() {
        listOf("en", "en-US", "ja-JP", "de-DE", "ar", "").forEach {
            assertEquals("en", PrimaryLanguage.languageTag(listOf(it, "zh-CN")))
        }
        assertEquals("en", PrimaryLanguage.languageTag(emptyList()))
    }
}
