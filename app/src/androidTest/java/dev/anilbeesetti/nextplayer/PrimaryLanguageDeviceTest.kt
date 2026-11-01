package dev.anilbeesetti.nextplayer

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anilbeesetti.nextplayer.core.common.PrimaryLanguage
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrimaryLanguageDeviceTest {
    @Test fun resolvesNamesAndInterpolationLabelsWithoutChangingSystemLanguage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // R8 inlines non-final R fields in release builds. Resolve the installed
        // resource table instead of depending on a stripped generated R class.
        val appName = context.resources.getIdentifier("app_name", "string", context.packageName)
        val interpolationTitle = context.resources.getIdentifier("interpolation_title", "string", context.packageName)
        org.junit.Assert.assertTrue(appName != 0 && interpolationTitle != 0)
        for (tags in listOf(listOf("zh-TW"), listOf("zh-Hant-HK"), listOf("en-US", "zh-CN"), listOf("ja-JP", "zh-CN"))) {
            val language = PrimaryLanguage.languageTag(tags)
            val configuration = Configuration(context.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(language)))
            }
            val localized = context.createConfigurationContext(configuration)
            assertEquals(if (language == "zh-CN") "下一代播放器" else "Next Player", localized.getString(appName))
            assertEquals(if (language == "zh-CN") "实时插帧" else "Real-time interpolation", localized.getString(interpolationTitle))
        }
    }
}
