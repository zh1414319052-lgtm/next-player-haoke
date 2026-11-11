package dev.anilbeesetti.nextplayer.feature.player.extensions

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleMimeTypeTest {
    @Test
    fun identifiesAssByProviderNameEvenWithOpaqueUriAndWrongMimeType() {
        assertEquals(MimeTypes.TEXT_SSA, subtitleMimeType("Episode 01.ASS", "/document/42", "audio/aac"))
        assertEquals(MimeTypes.TEXT_SSA, subtitleMimeType(null, "/folder/subtitles.SsA", null))
        assertEquals(MimeTypes.TEXT_SSA, subtitleMimeType(null, "/document/42", "application/x-ass"))
    }

    @Test
    fun retainsOtherSubtitleFormatsAndSubRipFallback() {
        assertEquals(MimeTypes.TEXT_VTT, subtitleMimeType("Captions.VTT", "/document/42", "text/plain"))
        assertEquals(MimeTypes.APPLICATION_TTML, subtitleMimeType(null, "/subtitle.DFXP", null))
        assertEquals(MimeTypes.APPLICATION_SUBRIP, subtitleMimeType("captions", "/document/42", "text/plain"))
    }
}
