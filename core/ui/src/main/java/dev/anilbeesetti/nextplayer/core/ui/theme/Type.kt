package dev.anilbeesetti.nextplayer.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.anilbeesetti.nextplayer.core.ui.R

// Noto Sans SC, SIL OFL 1.1. Commercial redistribution allowed; license packaged in assets.
// The bundled file is the variable font whose default instance is Thin (wght=100); every weight
// must be registered with an explicit wght axis value, otherwise all text renders thin and bold
// is only synthesized. Variation settings apply on Android 8.0+ and are ignored below it.
private fun notoSansScFont(weight: FontWeight) = Font(
    resId = R.font.noto_sans_sc,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val PlayerFontFamily = FontFamily(
    notoSansScFont(FontWeight.Thin),
    notoSansScFont(FontWeight.ExtraLight),
    notoSansScFont(FontWeight.Light),
    notoSansScFont(FontWeight.Normal),
    notoSansScFont(FontWeight.Medium),
    notoSansScFont(FontWeight.SemiBold),
    notoSansScFont(FontWeight.Bold),
    notoSansScFont(FontWeight.ExtraBold),
    notoSansScFont(FontWeight.Black),
)

private val defaults = Typography()
val Typography = Typography(
    displayLarge = defaults.displayLarge.copy(fontFamily = PlayerFontFamily),
    displayMedium = defaults.displayMedium.copy(fontFamily = PlayerFontFamily),
    displaySmall = defaults.displaySmall.copy(fontFamily = PlayerFontFamily),
    headlineLarge = defaults.headlineLarge.copy(fontFamily = PlayerFontFamily),
    headlineMedium = defaults.headlineMedium.copy(fontFamily = PlayerFontFamily),
    headlineSmall = defaults.headlineSmall.copy(fontFamily = PlayerFontFamily),
    titleLarge = defaults.titleLarge.copy(fontFamily = PlayerFontFamily),
    titleMedium = defaults.titleMedium.copy(fontFamily = PlayerFontFamily),
    titleSmall = defaults.titleSmall.copy(fontFamily = PlayerFontFamily),
    bodyLarge = defaults.bodyLarge.copy(fontFamily = PlayerFontFamily),
    bodyMedium = defaults.bodyMedium.copy(fontFamily = PlayerFontFamily),
    bodySmall = defaults.bodySmall.copy(fontFamily = PlayerFontFamily),
    labelLarge = defaults.labelLarge.copy(fontFamily = PlayerFontFamily),
    labelMedium = defaults.labelMedium.copy(fontFamily = PlayerFontFamily),
    labelSmall = defaults.labelSmall.copy(fontFamily = PlayerFontFamily),
)
