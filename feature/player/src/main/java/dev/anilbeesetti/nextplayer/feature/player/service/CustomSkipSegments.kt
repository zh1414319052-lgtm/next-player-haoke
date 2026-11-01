package dev.anilbeesetti.nextplayer.feature.player.service

import dev.anilbeesetti.nextplayer.core.analysis.resolve.DetectedInterval
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences

/** User durations override detection independently for each end; short/unknown media stay intact. */
internal object CustomSkipSegments {
    fun merge(
        detected: List<AutomaticSkipState.Segment>,
        preferences: PlayerPreferences,
        durationMs: Long,
    ): List<AutomaticSkipState.Segment> {
        // A positive user duration owns that end even if this video is too short.
        // Never silently substitute a different detected boundary for an explicit setting.
        val retained = detected.filterNot {
            preferences.customSkipIntroOutro && (
                (it.kind == "INTRO" && preferences.customIntroSeconds > 0) ||
                    (it.kind == "OUTRO" && preferences.customOutroSeconds > 0)
                )
        }
        return retained + resolve(preferences, durationMs)
    }

    fun resolve(preferences: PlayerPreferences, durationMs: Long): List<AutomaticSkipState.Segment> {
        if (!preferences.customSkipIntroOutro || durationMs <= 0) return emptyList()
        val intro = preferences.customIntroSeconds.coerceIn(0, PlayerPreferences.MAX_CUSTOM_SKIP_SECONDS) * 1000L
        val outro = preferences.customOutroSeconds.coerceIn(0, PlayerPreferences.MAX_CUSTOM_SKIP_SECONDS) * 1000L
        // Keep at least one second of content. Never clamp an oversized setting into a full skip.
        if (intro + outro > durationMs - 1000) return emptyList()
        fun segment(kind: String, start: Long, end: Long, eof: Boolean) = AutomaticSkipState.Segment(
            kind,
            DetectedInterval(start, end, eof, sourcesMask = 0, referenceCount = 0, similarityPct = 100),
        )
        return buildList {
            if (intro > 0) add(segment("INTRO", 0, intro, false))
            if (outro > 0) add(segment("OUTRO", durationMs - outro, durationMs, true))
        }
    }
}
