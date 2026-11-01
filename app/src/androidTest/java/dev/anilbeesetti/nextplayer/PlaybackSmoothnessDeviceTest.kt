package dev.anilbeesetti.nextplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.feature.player.PlayerActivity
import dev.anilbeesetti.nextplayer.feature.player.service.CustomCommands
import dev.anilbeesetti.nextplayer.feature.player.service.PlayerService
import dev.anilbeesetti.nextplayer.feature.player.utils.PlayerApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackSmoothnessDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var serviceConnection: ServiceConnection? = null
    private var serviceInstance: PlayerService? = null

    @Test
    fun measureContinuousPlaybackOfAuthorizedVideo() {
        val args = InstrumentationRegistry.getArguments()
        val uri = args.getString("videoUri")
        assumeTrue("Pass an authorized videoUri", !uri.isNullOrBlank())
        val startPosition = args.getString("startPositionMs")?.toIntOrNull() ?: 180_000
        val seconds = args.getString("sampleSeconds")?.toIntOrNull()?.coerceIn(10, 120) ?: 30
        val maxDropPercent = args.getString("maxDropPercent")?.toDoubleOrNull()
        val context = instrumentation.targetContext
        val preferences = GlobalContext.get().get<PreferencesRepository>()
        val original = preferences.playerPreferences.value
        var future: ListenableFuture<MediaController>? = null
        var player: MediaController? = null
        var activity: PlayerActivity? = null
        try {
            runBlocking { preferences.updatePlayerPreferences { it.copy(autoPip = false) } }
            onMain {
                future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlayerService::class.java))).buildAsync()
            }
            val controller = checkNotNull(future).get(15, TimeUnit.SECONDS)
            player = controller
            if (args.getString("unchangedApk") == "true") bindServiceForCounters(context)
            activity = instrumentation.startActivitySync(
                Intent(context, PlayerActivity::class.java)
                    .setData(Uri.parse(uri))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(PlayerApi.API_POSITION, startPosition),
            ) as PlayerActivity
            await("continuous playback to start") {
                assertNull(controller.playerError)
                controller.isPlaying && controller.currentPosition >= startPosition
            }
            // Exclude initial seek/preroll drops from steady-state counters.
            SystemClock.sleep(3_000)
            val before = metrics(controller)
            val startWallMs = SystemClock.elapsedRealtime()
            val startCpuMs = Process.getElapsedCpuTime()
            var lastWasBuffering = false
            var bufferingTransitions = 0
            val bufferingListener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_BUFFERING && !lastWasBuffering) bufferingTransitions++
                    lastWasBuffering = playbackState == Player.STATE_BUFFERING
                }
            }
            onMain { controller.addListener(bufferingListener) }
            repeat(seconds / 5) {
                SystemClock.sleep(5_000)
                val snapshot = metrics(controller)
                Log.i("PlaybackSmoothness", "sample=$it $snapshot")
                onMain { assertNull(controller.playerError) }
            }
            onMain { controller.removeListener(bufferingListener) }
            val after = metrics(controller)
            val wallMs = SystemClock.elapsedRealtime() - startWallMs
            val cpuMs = Process.getElapsedCpuTime() - startCpuMs
            val rendered = after.getInt("rendered") - before.getInt("rendered")
            val dropped = after.getInt("dropped") - before.getInt("dropped")
            val skipped = after.getInt("skipped") - before.getInt("skipped")
            val mediaMs = after.getLong("positionMs") - before.getLong("positionMs")
            val fraction = dropped * 100.0 / (rendered + dropped).coerceAtLeast(1)
            val summary = "wallMs=$wallMs mediaMs=$mediaMs cpuMs=$cpuMs rendered=$rendered dropped=$dropped skipped=$skipped dropPercent=$fraction rebuffer=$bufferingTransitions decoder=${after.getString("decoderMode")} width=${after.getInt("width")} height=${after.getInt("height")} bufferMs=${after.getLong("bufferedDurationMs")}" 
            Log.i("PlaybackSmoothness", summary)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$summary\n") })
            assertTrue("Playback clock must advance", mediaMs > 0)
            if (maxDropPercent != null) {
                assertTrue("Drop percent $fraction exceeds $maxDropPercent", fraction <= maxDropPercent)
                assertEquals("Unexpected steady-state rebuffer", 0, bufferingTransitions)
                assertTrue("Playback clock fell behind wall time", mediaMs >= wallMs * 0.9)
            }
        } finally {
            onMain {
                activity?.finish()
                player?.let { it.pause(); it.clearMediaItems(); it.stop() }
                future?.let { MediaController.releaseFuture(it) }
            }
            serviceConnection?.let { context.unbindService(it) }
            serviceConnection = null
            serviceInstance = null
            context.stopService(Intent(context, PlayerService::class.java))
            runBlocking { preferences.updatePlayerPreferences { original } }
        }
    }

    private fun bindServiceForCounters(context: Context) {
        val connected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val reference = binder.javaClass.getDeclaredField("serviceReference").apply { isAccessible = true }.get(binder) as WeakReference<*>
                serviceInstance = reference.get() as PlayerService
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { serviceInstance = null }
        }
        onMain {
            assertTrue(
                context.bindService(
                    Intent(context, PlayerService::class.java).setAction("androidx.media3.session.MediaSessionService"),
                    connection,
                    Context.BIND_AUTO_CREATE,
                ),
            )
        }
        serviceConnection = connection
        assertTrue("Bind to the unchanged app's local service", connected.await(15, TimeUnit.SECONDS))
    }

    private fun metrics(player: MediaController): Bundle {
        if (InstrumentationRegistry.getArguments().getString("unchangedApk") == "true") {
            var result = Bundle.EMPTY
            onMain {
                val service = checkNotNull(serviceInstance)
                val session = PlayerService::class.java.getDeclaredField("mediaSession").apply { isAccessible = true }.get(service) as MediaSession
                val exoPlayer = session.player as ExoPlayer
                val counters = checkNotNull(exoPlayer.videoDecoderCounters)
                counters.ensureUpdated()
                result = Bundle().apply {
                    putInt("rendered", counters.renderedOutputBufferCount)
                    putInt("dropped", counters.droppedBufferCount)
                    putInt("skipped", counters.skippedOutputBufferCount)
                    putInt("maxConsecutiveDropped", counters.maxConsecutiveDroppedBufferCount)
                    putLong("positionMs", exoPlayer.currentPosition)
                    putLong("bufferedDurationMs", exoPlayer.totalBufferedDuration)
                    putInt("state", exoPlayer.playbackState)
                    putInt("width", exoPlayer.videoFormat?.width ?: 0)
                    putInt("height", exoPlayer.videoFormat?.height ?: 0)
                    putFloat("sourceFps", exoPlayer.videoFormat?.frameRate ?: 0f)
                    putString("decoderMode", player.sessionExtras.getString(CustomCommands.VIDEO_DECODER_MODE_KEY))
                    putBoolean("playing", exoPlayer.isPlaying)
                }
            }
            return result
        }
        lateinit var result: ListenableFuture<SessionResult>
        onMain { result = player.sendCustomCommand(CustomCommands.GET_PLAYBACK_METRICS.sessionCommand, Bundle.EMPTY) }
        val completed = result.get(10, TimeUnit.SECONDS)
        assertEquals(SessionResult.RESULT_SUCCESS, completed.resultCode)
        return completed.extras
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var satisfied = false
            onMain { satisfied = predicate() }
            if (satisfied) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for $description")
    }
}
