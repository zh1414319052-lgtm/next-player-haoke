package dev.anilbeesetti.nextplayer.feature.player.service

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.ssa.SsaParser

@UnstableApi
internal object PlayerSubtitleParserFactory : SubtitleParser.Factory by DefaultSubtitleParserFactory() {
    private val defaults = DefaultSubtitleParserFactory()

    override fun create(format: Format): SubtitleParser {
        if (format.sampleMimeType != MimeTypes.TEXT_SSA) return defaults.create(format)

        if (format.initializationData.size >= 2) {
            val header = format.initializationData[1]
            val normalized = normalizeAssHeader(header, 0, header.size)
            if (normalized != null) {
                val initializationData = format.initializationData.toMutableList()
                initializationData[1] = normalized
                return SsaParser(initializationData)
            }
            return defaults.create(format)
        }

        val parser = defaults.create(format)
        return object : SubtitleParser by parser {
            override fun parse(
                data: ByteArray,
                offset: Int,
                length: Int,
                outputOptions: SubtitleParser.OutputOptions,
                output: Consumer<CuesWithTiming>,
            ) {
                val normalized = normalizeAssHeader(data, offset, length)
                var endUs = C.TIME_UNSET
                val recordEnd = Consumer<CuesWithTiming> { cues ->
                    if (cues.endTimeUs != C.TIME_UNSET) {
                        endUs = maxOf(endUs, cues.endTimeUs)
                    }
                    output.accept(cues)
                }
                if (normalized == null) {
                    parser.parse(data, offset, length, outputOptions, recordEnd)
                } else {
                    parser.parse(normalized, 0, normalized.size, outputOptions, recordEnd)
                }
                if (endUs != C.TIME_UNSET) {
                    // The lazy sidecar source can infer its duration from the last sample's
                    // timestamp alone. Give it a boundary at the true end of the final line.
                    output.accept(CuesWithTiming(emptyList(), endUs, 0))
                }
            }
        }
    }
}

/**
 * Media3 1.11.1 reads past the final field/style immediately before the next section.
 * Insert a blank line only at affected header boundaries. Leave dialogue bytes,
 * the original charset/BOM, and already separated sections intact.
 */
@UnstableApi
private fun normalizeAssHeader(data: ByteArray, offset: Int, length: Int): ByteArray? {
    val end = offset + length
    val input = ParsableByteArray(data, end).apply { position = offset }
    val charset = input.readUtfCharsetFromBom() ?: Charsets.UTF_8
    val insertions = mutableListOf<Int>()
    var inAffectedSection = false
    var previousHasContent = false
    while (input.bytesLeft() > 0) {
        val lineStart = input.position
        val line = input.readLine(charset) ?: break
        if (line.startsWith('[')) {
            if (inAffectedSection && previousHasContent) insertions.add(lineStart)
            if (line.equals("[Events]", ignoreCase = true)) break
            inAffectedSection = line.equals("[Script Info]", ignoreCase = true) || line.equals("[V4+ Styles]", ignoreCase = true)
        }
        previousHasContent = line.isNotBlank()
    }
    if (insertions.isEmpty()) return null

    val blankLine = "\n".toByteArray(charset)
    val result = ByteArray(length + blankLine.size * insertions.size)
    var copiedThrough = offset
    var outputOffset = 0
    for (insertion in insertions) {
        data.copyInto(result, outputOffset, copiedThrough, insertion)
        outputOffset += insertion - copiedThrough
        blankLine.copyInto(result, outputOffset)
        outputOffset += blankLine.size
        copiedThrough = insertion
    }
    data.copyInto(result, outputOffset, copiedThrough, end)
    return result
}
