package dev.anilbeesetti.nextplayer.feature.player.ui

import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi

/**
 * Media3 paints simultaneous cues independently, even when they share an anchor.
 * Lay out text at that anchor as consecutive paragraphs so wrapping and line
 * heights are handled by the same subtitle painter. Keep separately positioned
 * signs, vertical text and bitmap subtitles independent.
 */
@UnstableApi
internal fun stackSubtitleCues(
    cues: List<Cue>,
    viewportHeightPx: Int,
    defaultTextSizePx: Float,
    applyEmbeddedStyles: Boolean,
): List<Cue> {
    if (cues.size < 2) return cues
    val keys = cues.map { cue ->
        if (cue.text.isNullOrEmpty() || cue.verticalType != Cue.TYPE_UNSET) {
            null
        } else {
            SubtitlePlacement(
                line = cue.line,
                lineType = cue.lineType,
                lineAnchor = cue.lineAnchor,
                position = cue.position,
                positionAnchor = cue.positionAnchor,
                width = cue.size,
                alignment = cue.textAlignment,
                multiRowAlignment = cue.multiRowAlignment,
                windowColor = cue.windowColor.takeIf { cue.windowColorSet },
                shearDegrees = cue.shearDegrees,
            )
        }
    }
    val groups = mutableMapOf<SubtitlePlacement, MutableList<Cue>>()
    keys.forEachIndexed { index, key ->
        if (key != null) groups.getOrPut(key) { mutableListOf() }.add(cues[index])
    }
    if (groups.values.none { it.size > 1 }) return cues

    return buildList {
        cues.forEachIndexed { index, cue ->
            val key = keys[index]
            if (key == null) {
                add(cue)
            } else {
                val group = groups.remove(key) ?: return@forEachIndexed
                add(if (group.size == 1) cue else mergeSubtitleParagraphs(group, viewportHeightPx, defaultTextSizePx, applyEmbeddedStyles))
            }
        }
    }
}

private data class SubtitlePlacement(
    val line: Float,
    val lineType: Int,
    val lineAnchor: Int,
    val position: Float,
    val positionAnchor: Int,
    val width: Float,
    val alignment: Layout.Alignment?,
    val multiRowAlignment: Layout.Alignment?,
    val windowColor: Int?,
    val shearDegrees: Float,
)

@UnstableApi
private fun mergeSubtitleParagraphs(
    group: List<Cue>,
    viewportHeightPx: Int,
    defaultTextSizePx: Float,
    applyEmbeddedStyles: Boolean,
): Cue {
    val first = group.first()
    val individualSizes = if (applyEmbeddedStyles && viewportHeightPx > 0 && group.any {
        it.textSizeType != first.textSizeType || it.textSize != first.textSize
    }) {
        group.map { cue ->
            when (cue.textSizeType) {
                Cue.TEXT_SIZE_TYPE_ABSOLUTE -> cue.textSize
                Cue.TEXT_SIZE_TYPE_FRACTIONAL, Cue.TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING -> cue.textSize * viewportHeightPx
                else -> defaultTextSizePx
            }.takeIf { it > 0f && it.isFinite() } ?: defaultTextSizePx
        }
    } else {
        emptyList()
    }
    val baseSize = individualSizes.maxOrNull()
    val paragraphs = SpannableStringBuilder()
    group.forEachIndexed { index, cue ->
        if (index > 0) paragraphs.append('\n')
        val start = paragraphs.length
        paragraphs.append(cue.text)
        if (baseSize != null && baseSize > 0f && individualSizes[index] != baseSize) {
            paragraphs.setSpan(
                RelativeSizeSpan(individualSizes[index] / baseSize),
                start,
                paragraphs.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }
    return first.buildUpon()
        .setText(paragraphs)
        .apply { if (baseSize != null && baseSize > 0f) setTextSize(baseSize, Cue.TEXT_SIZE_TYPE_ABSOLUTE) }
        .build()
}
