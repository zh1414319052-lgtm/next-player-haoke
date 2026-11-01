package dev.anilbeesetti.nextplayer.core.cast.util

/**
 * UPnP AVTransport clock conversions. Renderers report and accept `H:MM:SS`
 * (fractions tolerated). Parsing is forgiving: some devices answer
 * NOT_IMPLEMENTED, others prepend garbage or omit the hour digits.
 */
object UpnpTime {

    fun formatClock(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) + 500L) / 1000L
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "%d:%02d:%02d".format(hours, minutes, seconds)
    }

    /** Returns milliseconds, or null when the value is absent or not a real clock. */
    fun parseClockOrNull(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() ||
            text.equals("NOT_IMPLEMENTED", ignoreCase = true) ||
            text.contains(":").not()
        ) {
            return null
        }
        val parts = text.substringBefore('.').split(":")
        if (parts.size !in 2..3) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        val (hours, minutes, seconds) = when (numbers.size) {
            3 -> Triple(numbers[0], numbers[1], numbers[2])
            else -> Triple(0, numbers[0], numbers[1])
        }
        if (hours < 0 || minutes !in 0..59 || seconds !in 0..59) return null
        return ((hours * 3600L + minutes * 60L + seconds) * 1000L)
    }
}
