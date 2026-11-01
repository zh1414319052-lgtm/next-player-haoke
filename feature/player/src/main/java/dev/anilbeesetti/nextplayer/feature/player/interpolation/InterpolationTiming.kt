package dev.anilbeesetti.nextplayer.feature.player.interpolation

/** Keeps originals unchanged and inserts only within adjacent, low frame rate frames. */
internal class InterpolationTiming {
    private var previous: Long? = null

    fun reset() { previous = null }

    fun next(timeUs: Long): Long? {
        val last = previous
        previous = timeUs
        if (last == null) return null
        val interval = timeUs - last
        // 12–30 fps. Excludes seeks, repeated timestamps and already smooth video.
        return if (interval in 32_000L..85_000L) last + interval / 2 else null
    }
}
