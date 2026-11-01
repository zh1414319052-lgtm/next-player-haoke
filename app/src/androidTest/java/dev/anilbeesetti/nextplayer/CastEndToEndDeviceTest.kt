package dev.anilbeesetti.nextplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anilbeesetti.nextplayer.core.cast.CastSessionManager
import dev.anilbeesetti.nextplayer.core.cast.model.CastMedia
import dev.anilbeesetti.nextplayer.core.cast.model.CastTransportState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * End-to-end cast verification against a live DLNA renderer on the local
 * network (tools/dlna-renderer-harness.py on the PC). Pass -e rendererHost to
 * point at the harness; skipped when no harness is reachable.
 */
@RunWith(AndroidJUnit4::class)
class CastEndToEndDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun castsDirectPlayToRendererAndMirrorsRemoteState() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("rendererHost")
        val videoUri = args.getString("videoUri")
        assumeTrue("Pass rendererHost and videoUri", !host.isNullOrBlank() && !videoUri.isNullOrBlank())

        val manager = GlobalContext.get().get<CastSessionManager>()
        // Emulator NAT drops guest-originated URLs; advertise the adb-forwarded loopback.
        manager.advertisedHostOverride = "127.0.0.1"
        val device = withTimeout(10_000) {
            manager.probeDevice(host!!) ?: manager.describeDevice("http://$host:49381/dd.xml")
        }
        assertNotNull("Harness renderer must be discoverable via SSDP or description URL", device)
        assertTrue("Harness must expose AVTransport", device!!.avTransportUrl.isNotBlank())

        val media = CastMedia(
            title = "VisionQuest.S01E06",
            uri = videoUri!!,
            mime = "video/x-matroska",
            sizeBytes = 5_180_188_402L,
            durationMs = 3_248_224L,
        )
        val state = manager.startCast(device, media)
        assertEquals("Harness accepts the media", CastTransportState.PLAYING, state.transportState)

        withTimeout(15_000) {
            while (manager.state.value.transportState != CastTransportState.PLAYING ||
                (manager.state.value.positionMs ?: 0L) < 2_000
            ) {
                delay(200)
            }
        }
        assertTrue("Renderer must report an advancing position", (manager.state.value.positionMs ?: 0L) >= 2_000)

        manager.pause()
        withTimeout(10_000) { while (manager.state.value.transportState != CastTransportState.PAUSED) delay(200) }
        manager.seekTo(600_000)
        withTimeout(10_000) { while ((manager.state.value.positionMs ?: 0L) < 599_000) delay(200) }
        assertTrue("Seek must land inside the credits-chapter mapping", (manager.state.value.positionMs ?: 0L) in 599_000..601_500)

        manager.stopCast()
        assertTrue("Session must end cleanly", manager.state.value.device == null)
    }

    private suspend fun delay(ms: Long) = kotlinx.coroutines.delay(ms)
}
