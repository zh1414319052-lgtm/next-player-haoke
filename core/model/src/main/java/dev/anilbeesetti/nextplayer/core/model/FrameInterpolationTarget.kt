package dev.anilbeesetti.nextplayer.core.model

/** Content-frame targets. Display refresh rates, including 120 Hz, are independent. */
object FrameInterpolationTarget {
    fun isSupported(value: Int): Boolean = value == 0 || value == 60

    fun restore(value: Int): Int = if (isSupported(value)) value else 60

    fun normalize(preferences: PlayerPreferences): PlayerPreferences =
        if (isSupported(preferences.frameInterpolationTargetFps)) {
            preferences
        } else {
            preferences.copy(frameInterpolationTargetFps = 60)
        }
}
