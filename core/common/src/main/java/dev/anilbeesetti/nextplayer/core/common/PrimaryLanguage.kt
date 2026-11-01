package dev.anilbeesetti.nextplayer.core.common

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

/** The first system language alone selects the UI language; user content is never translated. */
object PrimaryLanguage {
    fun languageTag(systemTags: List<String>): String =
        if (systemTags.firstOrNull()?.replace('_', '-')?.substringBefore('-')?.lowercase(Locale.ROOT) == "zh") "zh-CN" else "en"

    fun wrap(context: Context): Context {
        val system = Resources.getSystem().configuration.locales
        val tag = languageTag(if (system.isEmpty) emptyList() else listOf(system[0].toLanguageTag()))
        // Override language only. Copying the complete current configuration pins
        // orientation/size/night mode in this context across Activity config changes.
        val config = Configuration()
        config.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        return context.createConfigurationContext(config)
    }
}
