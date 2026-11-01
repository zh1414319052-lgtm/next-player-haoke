package dev.anilbeesetti.nextplayer.feature.player.service

import dev.anilbeesetti.nextplayer.core.analysis.resolve.DetectedInterval

internal class AutomaticSkipState {
    data class Segment(val kind: String, val interval: DetectedInterval) {
        val key: String get() = "$kind|${interval.startMs}|${interval.endMs}"
    }

    private val suppressed = mutableSetOf<String>()
    var segments: List<Segment> = emptyList()
        private set

    fun update(value: List<Segment>) {
        segments = value.sortedBy { it.interval.startMs }
    }

    fun candidate(positionMs: Long, playing: Boolean, ready: Boolean, scrubbing: Boolean): Segment? {
        if (!playing || !ready || scrubbing) return null
        return segments.firstOrNull {
            it.key !in suppressed && positionMs >= it.interval.startMs && positionMs < it.interval.endMs - 500
        }
    }

    fun suppress(segment: Segment) {
        suppressed += segment.key
    }

    fun manualSeek(positionMs: Long) {
        segments.filter { positionMs >= it.interval.startMs && positionMs < it.interval.endMs }
            .forEach(::suppress)
    }

    fun reset() {
        segments = emptyList()
        suppressed.clear()
    }
}
