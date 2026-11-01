package dev.anilbeesetti.nextplayer.feature.player.interpolation

/** One sample on a fixed media-time grid; fraction is between adjacent source frames. */
internal data class FrameSample(val timeUs: Long, val fraction: Float)

internal class FixedRateTiming {
    private var previous: Long? = null
    private var origin = 0L
    private var index = 1L

    fun reset() { previous = null }

    fun next(timeUs: Long, targetFps: Int = 60): List<FrameSample> {
        require(targetFps == 60)
        val last = previous
        previous = timeUs
        if (last == null || timeUs - last !in 32_000L..85_000L) {
            origin = timeUs
            index = 1
            return listOf(FrameSample(timeUs, 1f))
        }
        val frames = mutableListOf<FrameSample>()
        // Integer division from a fixed origin avoids accumulating microsecond rounding error.
        var next = origin + index * 1_000_000L / 60
        while (next <= timeUs) {
            if (next > last) {
                // MKV often quantizes 24 fps timestamps to milliseconds. Reuse the source if
                // it is within that quantization error; the output keeps its fixed-rate timestamp.
                val fraction = when {
                    timeUs - next <= 800 -> 1f
                    next - last <= 800 -> 0f
                    else -> (next - last).toFloat() / (timeUs - last)
                }
                frames.add(FrameSample(next, fraction))
            }
            index++
            next = origin + index * 1_000_000L / 60
        }
        return frames
    }
}
