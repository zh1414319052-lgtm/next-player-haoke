package dev.anilbeesetti.nextplayer.feature.player.ui

import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.SubtitleParser
import dev.anilbeesetti.nextplayer.feature.player.service.PlayerSubtitleParserFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SubtitleCueLayoutTest {
    @Test
    fun separatesSimultaneousAssDialogueWithoutMovingIndependentlyPositionedSign() {
        val bytes = """
            [Script Info]
            PlayResX: 640
            PlayResY: 360
            [V4+ Styles]
            Format: Name, Fontsize, PrimaryColour, Alignment
            Style: Chinese,26,&H0000FF00,2
            Style: Japanese,19,&H0000FFFF,2
            [Events]
            Format: Layer, Start, End, Style, Text
            Dialogue: 0,0:00:01.00,0:00:08.00,Chinese,中文
            Dialogue: 0,0:00:01.00,0:00:08.00,Japanese,日本語
            Dialogue: 1,0:00:01.00,0:00:08.00,Chinese,{\an8\pos(320,20)}看板
        """.trimIndent().encodeToByteArray()
        val parsed = mutableListOf<Cue>()
        val format = Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build()
        PlayerSubtitleParserFactory.create(format).parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.allCues()) {
            parsed += it.cues
        }
        assertEquals(3, parsed.size)

        val laidOut = stackSubtitleCues(parsed, 360, 18f, applyEmbeddedStyles = true)
        assertEquals(
            parsed.joinToString { "line=${it.line}/${it.lineAnchor} position=${it.position}/${it.positionAnchor} size=${it.textSize} layer=${it.zIndex}" },
            2,
            laidOut.size,
        )
        val dialogue = laidOut.first()
        assertEquals("中文\n日本語", dialogue.text.toString())
        assertEquals(parsed.first().line, dialogue.line)
        assertEquals(parsed.first().position, dialogue.position)
        assertSame(parsed.last(), laidOut.last())
        val text = dialogue.text as Spanned
        val sizes = text.getSpans(0, text.length, RelativeSizeSpan::class.java)
        assertEquals(19f / 26f, sizes.single().sizeChange, 0.001f)
        assertEquals(2, text.getSpans(0, text.length, ForegroundColorSpan::class.java).size)
    }

    @Test
    fun keepsAnExistingTwoLineCaptionAndDifferentAnchorsUnchanged() {
        val cues = listOf(
            Cue.Builder().setText("中文\n日本語").build(),
            Cue.Builder().setText("Top sign").setLine(0.1f, Cue.LINE_TYPE_FRACTION).build(),
        )
        assertSame(cues, stackSubtitleCues(cues, 360, 18f, applyEmbeddedStyles = false))
        val stacked = stackSubtitleCues(listOf(cues[0], Cue.Builder().setText("Other cue").build()), 360, 18f, false)
        assertTrue(stacked.single().text.toString().endsWith("\nOther cue"))
    }
}
