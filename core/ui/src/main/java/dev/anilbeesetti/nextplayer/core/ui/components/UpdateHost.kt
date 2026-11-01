package dev.anilbeesetti.nextplayer.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf

val LocalManualUpdateCheck = staticCompositionLocalOf<(() -> Unit)?> { null }
val LocalLibraryUpdateSafety = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

@Composable
fun BindLibraryUpdateSafety(safe: Boolean) {
    val setter = LocalLibraryUpdateSafety.current
    DisposableEffect(setter) { onDispose { setter(false) } }
    SideEffect { setter(safe) }
}
