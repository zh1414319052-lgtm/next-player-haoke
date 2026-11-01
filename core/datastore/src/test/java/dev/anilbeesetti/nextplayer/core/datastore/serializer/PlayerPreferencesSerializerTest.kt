package dev.anilbeesetti.nextplayer.core.datastore.serializer

import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayerPreferencesSerializerTest {
    @Test fun oldPreferencesKeepPlaybackSettingsAndLeaveCustomSkipDisabled() = runBlocking {
        val restored = PlayerPreferencesSerializer.readFrom(
            ByteArrayInputStream("""{"autoSkipIntroOutro":true,"frameInterpolationEnabled":true,"frameInterpolationTargetFps":60}""".toByteArray()),
        )
        assertFalse(restored.customSkipIntroOutro)
        assertEquals(0, restored.customIntroSeconds)
        assertEquals(0, restored.customOutroSeconds)
        assertEquals(true, restored.autoSkipIntroOutro)
        assertEquals(true, restored.frameInterpolationEnabled)
    }

    @Test fun customValuesSurviveDiskRoundTripIncludingDisabledState() = runBlocking {
        val saved = PlayerPreferences(customSkipIntroOutro = false, customIntroSeconds = 90, customOutroSeconds = 60)
        val output = ByteArrayOutputStream()
        PlayerPreferencesSerializer.writeTo(saved, output)
        assertEquals(saved, PlayerPreferencesSerializer.readFrom(ByteArrayInputStream(output.toByteArray())))
    }
}
