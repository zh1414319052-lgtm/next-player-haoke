package dev.anilbeesetti.nextplayer.feature.player.interpolation

import androidx.media3.common.Player

/**
 * Decides when interpolation passes source frames through before playback ends. The final item
 * has to emit its last source timestamp for Media3 to report the end; transitions do not.
 */
internal object InterpolationEndGuard {
    const val WINDOW_MS = 1_500L

    fun isEndingSoon(durationMs: Long, positionMs: Long, hasNext: Boolean, repeatMode: Int): Boolean =
        !hasNext && repeatMode == Player.REPEAT_MODE_OFF && durationMs > 0 && durationMs - positionMs <= WINDOW_MS
}
