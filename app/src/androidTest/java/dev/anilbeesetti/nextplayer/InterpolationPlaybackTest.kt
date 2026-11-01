package dev.anilbeesetti.nextplayer

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.runBlocking
import androidx.media3.session.MediaController
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.feature.player.PlayerActivity
import dev.anilbeesetti.nextplayer.feature.player.service.CustomCommands
import dev.anilbeesetti.nextplayer.feature.player.service.PlayerService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Opt-in device test; pass -e interpolationVideoUri to name the authorized test media. */
@RunWith(AndroidJUnit4::class)
class InterpolationPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Test fun originalVideoToggleSeekSpeedPauseAndPersistence() {
        val uri = InstrumentationRegistry.getArguments().getString("interpolationVideoUri")
        org.junit.Assume.assumeTrue("An explicitly selected video is required", uri != null)
        val requestedRate = InstrumentationRegistry.getArguments().getString("interpolationTargetFps")?.toIntOrNull() ?: 60
        require(requestedRate == 60)
        val requireDisplayRate = InstrumentationRegistry.getArguments().getString("interpolationRequireDisplayRate") == "true"
        val context = instrumentation.targetContext
        val preferences = GlobalContext.get().get<PreferencesRepository>()
        val originallyEnabled = preferences.playerPreferences.value.frameInterpolationEnabled
        val originalTarget = preferences.playerPreferences.value.frameInterpolationTargetFps
        val activity = instrumentation.startActivitySync(
            Intent(context, PlayerActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(uri), "video/x-matroska")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PlayerActivity
        var initialDisplayMode = 0
        onMain { initialDisplayMode = activity.window.attributes.preferredDisplayModeId }
        lateinit var controllerFuture: ListenableFuture<MediaController>
        onMain {
            controllerFuture = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlayerService::class.java))).buildAsync()
        }
        val controller = controllerFuture.get(10, TimeUnit.SECONDS)
        fun command(enabled: Boolean? = null, target: Int? = null): Bundle {
            lateinit var result: ListenableFuture<SessionResult>
            onMain {
                result = controller.sendCustomCommand(
                    (if (enabled == null && target == null) CustomCommands.GET_FRAME_INTERPOLATION else CustomCommands.SET_FRAME_INTERPOLATION).sessionCommand,
                    Bundle().apply { if (enabled != null) putBoolean("enabled", enabled); if (target != null) putInt("target", target) },
                )
            }
            return result.get(5, TimeUnit.SECONDS).also { assertEquals(SessionResult.RESULT_SUCCESS, it.resultCode) }.extras
        }
        fun await(label: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < deadline) {
                var ready = false
                onMain { ready = predicate() }
                if (ready) return
                SystemClock.sleep(100)
            }
            throw AssertionError("Timed out: $label")
        }
        try {
            onMain { controller.setPlaybackSpeed(1f); controller.play() }
            await("original playback") { controller.isPlaying }
            var seekPosition = 0L
            onMain {
                assertTrue("Test media must be at least one minute", controller.duration >= 60_000)
                controller.seekTo(minOf(120_000, controller.duration / 10))
                seekPosition = minOf(600_000, controller.duration / 2)
            }
            command(false)
            SystemClock.sleep(1500)
            assertEquals("off", command().getString("status"))
            command(true, target = requestedRate)
            assertEquals(requestedRate, preferences.playerPreferences.value.frameInterpolationTargetFps)
            assertTrue(preferences.playerPreferences.value.frameInterpolationEnabled)
            var startPosition = 0L
            onMain { startPosition = controller.currentPosition }
            val samples = InstrumentationRegistry.getArguments().getString("interpolationSamples")?.toIntOrNull()?.coerceIn(6, 60) ?: 6
            repeat(samples) {
                SystemClock.sleep(5000)
                val sample = command()
                android.util.Log.i("NextInterpolationPlayback", "sample=$it status=${sample.getString("status")} sourceFps=${sample.getFloat("fps")} emitted=${sample.getLong("emitted")} presented=${sample.getLong("presented")} generated=${sample.getLong("generated")} submitted=${sample.getLong("submitted")} gpuMs=${sample.getFloat("ms")}")
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "\nsample=$it status=${sample.getString("status")} sourceFps=${sample.getFloat("fps")} emitted=${sample.getLong("emitted")} presented=${sample.getLong("presented")} generated=${sample.getLong("generated")} submitted=${sample.getLong("submitted")} gpuMs=${sample.getFloat("ms")}\n")
                })
                val trace = sample.getLongArray("releaseTrace") ?: longArrayOf()
                if (trace.size >= 6) {
                    val rows = trace.toList().chunked(3)
                    val releaseDeltas = rows.zipWithNext { a, b -> b[1] - a[1] }
                    val mediaDeltas = rows.zipWithNext { a, b -> b[0] - a[0] }
                    val leadTimes = rows.map { (it[1] - it[2]) / 1_000_000.0 }.sorted()
                    val histogram = releaseDeltas.groupingBy { kotlin.math.round(it / 1_000_000.0).toInt() }.eachCount().toSortedMap()
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("stream", "releaseTrace n=${rows.size} mediaDeltaUs=${mediaDeltas.sorted()[mediaDeltas.size / 2]} releaseDeltaMs=$histogram releaseLeadMs(min/median/max)=${leadTimes.first()}/${leadTimes[leadTimes.size / 2]}/${leadTimes.last()}\n")
                    })
                }
                onMain { assertNull(controller.playerError) }
                onMain {
                    val screenHz = activity.window.decorView.display?.refreshRate ?: 0f
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("stream", "screenHz=$screenHz requestedMode=${activity.window.attributes.preferredDisplayModeId}\n")
                    })
                    if (requireDisplayRate && sample.getString("status") == "active") {
                        val multiple = kotlin.math.round(screenHz / requestedRate).toInt()
                        assertTrue("Display must run at the output rate or an integer multiple: $screenHz Hz / $requestedRate fps",
                            multiple >= 1 && kotlin.math.abs(screenHz - requestedRate * multiple) < 0.2f)
                    }
                }
            }
            val active = command()
            assertEquals("active", active.getString("status"))
            assertEquals(requestedRate, active.getInt("target"))
            assertTrue("Must output near real-time $requestedRate fps", active.getLong("emitted") >= samples * 5 * (requestedRate - 1L))
            assertTrue("Must create fractional frames", active.getLong("generated") >= samples * 5 * 30L)
            assertTrue("Generated frames must reach the timed display pipeline", active.getLong("presented") >= active.getLong("emitted") * 0.98)
            onMain {
                assertTrue("Playback clock must keep up", controller.currentPosition - startPosition in (samples * 5000L - 2000)..(samples * 5000L + 5000))
                controller.pause()
            }
            SystemClock.sleep(1200)
            onMain { assertEquals("Pause must restore the previous window mode", initialDisplayMode, activity.window.attributes.preferredDisplayModeId) }
            val paused = command().getLong("generated")
            SystemClock.sleep(800)
            assertEquals(paused, command().getLong("generated"))
            onMain { controller.seekTo(seekPosition); controller.play() }
            await("seek and resume") { controller.isPlaying && controller.currentPosition >= seekPosition }
            SystemClock.sleep(3000)
            val sought = command()
            assertEquals("active", sought.getString("status"))
            assertTrue(sought.getLong("generated") in 1..350)
            onMain { controller.setPlaybackSpeed(1.5f) }
            SystemClock.sleep(1000)
            assertEquals("speed", command().getString("status"))
            onMain { assertEquals("Speed fallback must restore the previous mode", initialDisplayMode, activity.window.attributes.preferredDisplayModeId) }
            onMain { controller.setPlaybackSpeed(1f) }
            SystemClock.sleep(2500)
            assertEquals("active", command().getString("status"))
            command(target = 0)
            SystemClock.sleep(3000)
            val doubleRate = command()
            assertEquals(0, doubleRate.getInt("target"))
            assertEquals("active", doubleRate.getString("status"))
            assertTrue("Legacy double rate remains available", doubleRate.getLong("emitted") in 125..170)
            command(target = 60)
            SystemClock.sleep(1500)
            assertEquals(60, command().getInt("target"))
            for (invalidTarget in listOf(90, 120)) {
                val before = command()
                val beforePreferences = preferences.playerPreferences.value
                var beforeMode = 0
                lateinit var rejected: ListenableFuture<SessionResult>
                onMain {
                    beforeMode = activity.window.attributes.preferredDisplayModeId
                    rejected = controller.sendCustomCommand(
                        CustomCommands.SET_FRAME_INTERPOLATION.sessionCommand,
                        Bundle().apply { putBoolean("enabled", false); putInt("target", invalidTarget) },
                    )
                }
                assertEquals(SessionError.ERROR_BAD_VALUE, rejected.get(5, TimeUnit.SECONDS).resultCode)
                val after = command()
                assertEquals("Rejected target must not switch modes", before.getInt("target"), after.getInt("target"))
                assertEquals("Rejected target must not toggle interpolation", before.getBoolean("enabled"), after.getBoolean("enabled"))
                assertEquals("Rejected target must not reset active playback", before.getString("status"), after.getString("status"))
                assertTrue("Rejected target must not reset counters", after.getLong("generated") >= before.getLong("generated"))
                assertEquals(beforePreferences, preferences.playerPreferences.value)
                onMain {
                    assertTrue(controller.isPlaying)
                    assertEquals(beforeMode, activity.window.attributes.preferredDisplayModeId)
                }
            }
            command(false)
            SystemClock.sleep(1000)
            assertEquals(0L, command().getLong("generated"))
            assertFalse(preferences.playerPreferences.value.frameInterpolationEnabled)
            onMain { assertEquals("Disabled interpolation must restore the previous mode", initialDisplayMode, activity.window.attributes.preferredDisplayModeId) }
            onMain { assertNull(controller.playerError) }
            command(true, target = requestedRate)
            onMain {
                assertTrue(controller.duration > 5000)
                controller.seekTo(controller.duration - 2000)
                controller.play()
            }
            await("fixed-rate end of source without a pending last-frame deadlock") {
                controller.playbackState == Player.STATE_ENDED || controller.mediaItemCount == 0
            }
        } finally {
            runBlocking {
                preferences.updatePlayerPreferences { it.copy(frameInterpolationEnabled = originallyEnabled, frameInterpolationTargetFps = originalTarget) }
            }
            onMain { controller.pause(); controller.release(); activity.finish() }
        }
    }
}
