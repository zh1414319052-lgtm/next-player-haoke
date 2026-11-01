package dev.anilbeesetti.nextplayer

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.Chapter
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.model.LoopMode
import dev.anilbeesetti.nextplayer.feature.player.PlayerActivity
import dev.anilbeesetti.nextplayer.feature.player.service.CustomCommands
import dev.anilbeesetti.nextplayer.feature.player.service.PlayerService
import dev.anilbeesetti.nextplayer.feature.player.service.undoIntroOutroSkip
import dev.anilbeesetti.nextplayer.feature.player.utils.PlayerApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ChapterPlaybackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun suppliedVideoStartsAndCreditsRespectTheSwitchAndFollowingScene() {
        val videoUri = InstrumentationRegistry.getArguments().getString("videoUri")
        assumeTrue("Pass an authorized local videoUri to exercise the actual video", !videoUri.isNullOrBlank())
        val context = instrumentation.targetContext
        val preferences = org.koin.core.context.GlobalContext.get().get<PreferencesRepository>()
        val original = preferences.playerPreferences.value
        var activity: PlayerActivity? = null
        var future: ListenableFuture<MediaController>? = null
        var controller: MediaController? = null
        try {
            runBlocking {
                preferences.updatePlayerPreferences {
                    it.copy(autoSkipIntroOutro = false, autoPip = false, autoplay = false, loopMode = LoopMode.OFF)
                }
            }
            await("disabled preference", 10_000) { !preferences.playerPreferences.value.autoSkipIntroOutro }
            onMain {
                future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlayerService::class.java))).buildAsync()
            }
            val player = checkNotNull(future).get(15, TimeUnit.SECONDS)
            controller = player
            val started = SystemClock.elapsedRealtime()
            val intent = Intent(context, PlayerActivity::class.java)
                .setData(Uri.parse(videoUri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(PlayerApi.API_POSITION, 0)
            activity = instrumentation.startActivitySync(intent) as PlayerActivity
            await("actual video playback", 90_000) {
                assertNull(player.playerError)
                player.isPlaying && player.currentPosition >= 1_000
            }
            Log.i("ChapterPlaybackTest", "Actual video start-to-playingMs=${SystemClock.elapsedRealtime() - started}")

            var chapters = emptyList<Chapter>()
            onMain {
                chapters = player.currentTracks.groups.flatMap { group ->
                    (0 until group.length).flatMap { index ->
                        group.getTrackFormat(index).metadata?.getEntriesOfType(Chapter::class.java).orEmpty()
                    }
                }.distinctBy { it.startTimeMs }.sortedBy { it.startTimeMs }
            }
            val credits = chapters.first { it.title?.value.equals("Credits", ignoreCase = true) }
            val next = chapters.first { it.startTimeMs > credits.startTimeMs }
            assertTrue("Credits must be followed by a protected Scene", next.title?.value.equals("Scene", true))
            Log.i("ChapterPlaybackTest", "Credits=${credits.startTimeMs}..${next.startTimeMs}, titleAfter=${next.title?.value}")
            onMain { player.seekTo(credits.startTimeMs - 2_000) }
            await("credits playing with switch disabled", 90_000) {
                assertNull(player.playerError)
                player.isPlaying && player.currentPosition >= credits.startTimeMs + 1_500
            }
            onMain {
                player.pause()
                assertTrue("Switch disabled must not skip credits", player.currentPosition < next.startTimeMs)
                assertNull(player.sessionExtras.getString(CustomCommands.SKIP_KIND_KEY))
            }
            runBlocking { preferences.updatePlayerPreferences { it.copy(autoSkipIntroOutro = true) } }
            await("enabled preference", 10_000) { preferences.playerPreferences.value.autoSkipIntroOutro }

            var observedTarget = C.TIME_UNSET
            var undoToken = 0L
            onMain {
                player.addListener(object : Player.Listener {
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK && newPosition.positionMs >= next.startTimeMs - 10) {
                            observedTarget = newPosition.positionMs
                            player.pause()
                        }
                    }
                })
                player.play()
            }
            await("automatic credits skip", 15_000) {
                undoToken = player.sessionExtras.getLong(CustomCommands.UNDO_TOKEN_KEY)
                observedTarget != C.TIME_UNSET && undoToken > 0
            }
            onMain {
                assertEquals(next.startTimeMs, observedTarget)
                assertTrue("Must preserve the scene even when it is only one second long", observedTarget < player.duration)
                assertFalse(player.playWhenReady)
            }
            val undone = runBlocking { withContext(Dispatchers.Main) { player.undoIntroOutroSkip(undoToken) } }
            assertTrue("Undo must be accepted for the same playback session", undone)
            onMain {
                assertTrue(player.currentPosition < next.startTimeMs)
                assertFalse("Undo must not unpause the user", player.playWhenReady)
            }
            Log.i("ChapterPlaybackTest", "PASS: switch off retained credits, switch on target=$observedTarget, following Scene and undo preserved")
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

    private fun await(description: String, timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            var satisfied = false
            onMain { satisfied = predicate() }
            if (satisfied) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Timed out waiting for $description")
    }
}
