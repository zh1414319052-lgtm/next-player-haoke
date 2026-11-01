package dev.anilbeesetti.nextplayer.core.analysis.resolve

import java.text.Normalizer
import java.util.Locale

object ChapterSkipResolver {
    data class ChapterMarker(val title: String, val startMs: Long, val endMs: Long? = null)

    enum class Kind { INTRO, OUTRO }

    data class Segment(val kind: Kind, val interval: DetectedInterval)

    fun resolve(chapters: List<ChapterMarker>, durationMs: Long): List<Segment> {
        if (durationMs <= 0) return emptyList()
        val groups = chapters.filter { it.startMs in 0 until durationMs }
            .groupBy { it.startMs }.toSortedMap().entries.toList()
        return groups.mapIndexedNotNull { index, group ->
            val kinds = group.value.map { kind(it.title) }.distinct()
            // Conflicting track labels must not turn a Scene into an Opening.
            val kind = kinds.singleOrNull() ?: return@mapIndexedNotNull null
            val nextStart = groups.getOrNull(index + 1)?.key ?: durationMs
            val explicitEnd = group.value.mapNotNull { it.endMs?.takeIf { end -> end > group.key } }.minOrNull()
            val end = minOf(nextStart, explicitEnd ?: durationMs, durationMs)
            val length = end - group.key
            val valid = when (kind) {
                Kind.INTRO -> length in 10_000L..300_000L && end <= durationMs * 0.45 && group.key <= 900_000
                Kind.OUTRO -> length >= 5_000 && group.key >= 60_000 && group.key >= durationMs * 0.5
            }
            if (!valid) return@mapIndexedNotNull null
            Segment(
                kind,
                DetectedInterval(
                    startMs = group.key,
                    endMs = end,
                    endIsEof = kind == Kind.OUTRO && end == durationMs,
                    sourcesMask = DetectedSources.CHAPTER,
                    referenceCount = 0,
                    similarityPct = 0,
                ),
            )
        }
    }

    private fun kind(title: String): Kind? {
        val normalized = Normalizer.normalize(title, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).trim().replace(Regex("[\\s_\\-]+"), " ")
        return when {
            intro.matches(normalized) -> Kind.INTRO
            outro.matches(normalized) -> Kind.OUTRO
            else -> null
        }
    }

    private val intro = Regex("(?:intro|opening(?: credits| theme)?|ncop|op|片头(?:曲)?|片頭(?:曲)?|オープニング)(?: ?[0-9]+)?")
    private val outro = Regex("(?:outro|ending(?: credits| theme)?|closing credits|end credits|credits|nced|ed|片尾(?:曲)?|スタッフロール|エンディング)(?: ?[0-9]+)?")
}
