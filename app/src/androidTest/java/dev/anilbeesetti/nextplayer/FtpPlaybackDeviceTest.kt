package dev.anilbeesetti.nextplayer

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import dev.anilbeesetti.nextplayer.feature.player.PlayerActivity
import dev.anilbeesetti.nextplayer.feature.player.service.PlayerService
import dev.anilbeesetti.nextplayer.feature.player.utils.PlayerApi
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run against scripts/ftp_playback_fixture.py in QA or a task-owned formal test install. */
@RunWith(AndroidJUnit4::class)
class FtpPlaybackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun lanVideoSurvivesDisconnectLongPauseSeekAndEndsNormally() {
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        check(
            context.packageName.endsWith(".qaftp") ||
                (context.packageName == "dev.anilbeesetti.nextplayer.haoke" && arguments.getString("ownedFormalFixture") == "true"),
        ) { "This test requires an isolated QA install or a task-owned formal install" }
        val uri = Uri.parse(checkNotNull(arguments.getString("ftpUri")))
        require(uri.scheme == "ftp" && uri.path == "/ftp-synthetic.mp4") { "Use the synthetic FTP fixture" }
        var activity: PlayerActivity? = null
        var future: ListenableFuture<MediaController>? = null
        var controller: MediaController? = null
        try {
            onMain {
                future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlayerService::class.java))).buildAsync()
            }
            val player = checkNotNull(future).get(20, TimeUnit.SECONDS)
            controller = player
            activity = instrumentation.startActivitySync(
                Intent(context, PlayerActivity::class.java).setData(uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(PlayerApi.API_POSITION, 0),
            ) as PlayerActivity
            await("initial playback", 45_000) { assertNull(player.playerError); player.isPlaying && player.currentPosition > 1_000 }
            onMain { assertTrue(player.duration in 479_000..482_000) }
            // Five real minutes at 1x, including the server's deliberate drop after two minutes.
            await("five minutes including server disconnect", 370_000) {
                assertNull(player.playerError)
                assertTrue("Premature video EOF", player.playbackState != Player.STATE_ENDED)
                player.isPlaying && player.currentPosition >= 300_000
            }
            Log.i("FtpPlaybackDeviceTest", "PASS five-minute FTP video with forced server disconnect")
            var pausedAt = 0L
            onMain { player.pause(); pausedAt = player.currentPosition }
            SystemClock.sleep(80_000)
            onMain {
                assertNull(player.playerError)
                assertTrue(kotlin.math.abs(player.currentPosition - pausedAt) < 500)
                player.play()
            }
            await("resume after idle timeout", 90_000) {
                assertNull(player.playerError)
                player.isPlaying && player.currentPosition > pausedAt + 10_000
            }
            onMain { player.seekTo(400_000) }
            await("seek forward", 45_000) { assertNull(player.playerError); player.isPlaying && player.currentPosition >= 402_000 }
            onMain { player.seekTo(30_000) }
            await("seek backward", 45_000) { assertNull(player.playerError); player.isPlaying && player.currentPosition in 32_000..45_000 }
            onMain { assertEquals(1f, player.playbackParameters.speed, 0.001f); player.seekTo(player.duration - 8_000) }
            await("normal final EOF", 45_000) {
                assertNull(player.playerError)
                player.playbackState == Player.STATE_ENDED || player.mediaItemCount == 0
            }
            Log.i("FtpPlaybackDeviceTest", "PASS idle pause/resume, forward/backward seeking, normal end")
        } finally {
            onMain {
                activity?.finish()
                controller?.let { it.pause(); it.clearMediaItems(); it.stop() }
                future?.let { MediaController.releaseFuture(it) }
            }
            context.stopService(Intent(context, PlayerService::class.java))
        }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(description: String, timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var nextLog = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            var complete = false
            onMain { complete = predicate() }
            if (complete) return
            if (SystemClock.elapsedRealtime() >= nextLog) {
                Log.i("FtpPlaybackDeviceTest", "Waiting: $description")
                nextLog = SystemClock.elapsedRealtime() + 30_000
            }
            SystemClock.sleep(250)
        }
        throw AssertionError("Timed out: $description")
    }
}
