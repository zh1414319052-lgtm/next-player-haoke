package dev.anilbeesetti.nextplayer

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.os.Bundle
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.feature.player.PlayerActivity
import dev.anilbeesetti.nextplayer.feature.player.service.CustomCommands
import dev.anilbeesetti.nextplayer.feature.player.service.PlayerService
import dev.anilbeesetti.nextplayer.feature.player.utils.PlayerApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class CustomSkipPlaybackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun suppliedHdrVideoSupportsCustomSkipUndoResumeRepeatAndEof() {
        val uri = checkNotNull(InstrumentationRegistry.getArguments().getString("videoUri"))
        val context = instrumentation.targetContext
        val preferences = GlobalContext.get().get<PreferencesRepository>()
        val original = preferences.playerPreferences.value
        var activity: PlayerActivity? = null
        var future: ListenableFuture<MediaController>? = null
        var controller: MediaController? = null
        try {
            runBlocking {
                preferences.updatePlayerPreferences {
                    it.copy(
                        autoSkipIntroOutro = false, customSkipIntroOutro = true,
                        customIntroSeconds = 4, customOutroSeconds = 3,
                        frameInterpolationEnabled = true, frameInterpolationTargetFps = 60,
                        // Explicit API_POSITION below selects the start; avoid test references
                        // to model enum constants optimized out of a distributable R8 APK.
                        autoPip = false, autoplay = false, defaultPlaybackSpeed = 1f,
                    )
                }
            }
            onMain { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlayerService::class.java))).buildAsync() }
            val player = checkNotNull(future).get(15, TimeUnit.SECONDS)
            controller = player
            fun start(position: Int) {
                activity = instrumentation.startActivitySync(
                    Intent(context, PlayerActivity::class.java).setData(Uri.parse(uri))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(PlayerApi.API_POSITION, position),
                ) as PlayerActivity
            }
            start(0)
            await("opening skip") {
                assertNull(player.playerError)
                player.isPlaying && player.currentPosition >= 4_000 && player.sessionExtras.getString(CustomCommands.SKIP_KIND_KEY) == "INTRO"
            }
            var token = 0L
            onMain {
                player.pause()
                token = player.sessionExtras.getLong(CustomCommands.UNDO_TOKEN_KEY)
                assertTrue(token > 0)
                assertTrue(player.currentPosition < 10_000)
            }
            lateinit var undo: ListenableFuture<SessionResult>
            onMain {
                undo = player.sendCustomCommand(CustomCommands.UNDO_INTRO_OUTRO_SKIP.sessionCommand, Bundle().apply {
                    putLong(CustomCommands.UNDO_TOKEN_KEY, token)
                })
            }
            assertEquals(SessionResult.RESULT_SUCCESS, undo.get(5, TimeUnit.SECONDS).resultCode)
            onMain { assertFalse(player.playWhenReady); assertTrue(player.currentPosition < 4_000); player.play() }
            SystemClock.sleep(1_000)
            onMain {
                assertTrue("Undo must allow replay of the intro", player.currentPosition < 4_000)
                player.pause()
                player.clearMediaItems()
                activity?.finish()
            }
            start(15_000)
            await("resume prepared") { player.playbackState == Player.STATE_READY && player.currentPosition >= 15_000 }
            // Reopening a paused controller correctly retains pause. Resume explicitly.
            onMain { player.play() }
            await("resume beyond intro") { player.isPlaying && player.currentPosition >= 15_000 }
            await("4K video track") {
                player.currentTracks.groups.any { group -> (0 until group.length).any { group.getTrackFormat(it).width == 3840 } }
            }
            onMain {
                assertTrue(player.currentPosition < 22_000)
                val video = player.currentTracks.groups.flatMap { group -> (0 until group.length).map { group.getTrackFormat(it) } }.first { it.width == 3840 }
                assertEquals(1632, video.height)
                assertTrue(player.duration in 950_000..953_000)
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.seekTo(player.duration - 5_000)
            }
            await("repeat retains outro") { player.isPlaying && player.currentPosition >= player.duration - 2_500 }
            onMain {
                assertNull(player.sessionExtras.getString(CustomCommands.SKIP_KIND_KEY))
                player.pause()
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.play()
            }
            await("ending skip completes playback") {
                assertNull(player.playerError)
                player.playbackState == Player.STATE_ENDED || player.mediaItemCount == 0
            }
            Log.i("CustomSkipPlaybackTest", "PASS supplied 4K HDR: intro=4s, undo/review, resume=15s, repeat retention, outro=3s and EOF with 60fps interpolation enabled")
        } finally {
            onMain {
                activity?.finish()
                controller?.let { it.pause(); it.clearMediaItems(); it.stop() }
                future?.let { MediaController.releaseFuture(it) }
            }
            context.stopService(Intent(context, PlayerService::class.java))
            runBlocking { preferences.updatePlayerPreferences { original } }
        }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var done = false
            onMain { done = predicate() }
            if (done) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Timed out: $description")
    }
}
