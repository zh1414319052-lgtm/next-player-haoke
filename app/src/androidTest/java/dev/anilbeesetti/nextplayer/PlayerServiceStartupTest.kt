package dev.anilbeesetti.nextplayer

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.model.LoopMode
import dev.anilbeesetti.nextplayer.feature.player.service.PlayerService
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlayerServiceStartupTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun playbackStartsWithAutomaticSkippingDisabled() = checkPlayback(autoSkip = false)

    @Test
    fun playbackStartsWithAutomaticSkippingEnabled() = checkPlayback(autoSkip = true)

    private fun checkPlayback(autoSkip: Boolean) {
        val context = instrumentation.targetContext
        val preferences = GlobalContext.get().get<PreferencesRepository>()
        val original = preferences.playerPreferences.value
        val file = File(context.cacheDir, "player-startup-${System.nanoTime()}.wav")
        val frames = 16_000 * 30
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(frames * 2)
        file.outputStream().use {
            it.write(header.array())
            it.write(ByteArray(frames * 2))
        }

        var future: ListenableFuture<MediaController>? = null
        var controller: MediaController? = null
        try {
            runBlocking {
                preferences.updatePlayerPreferences {
                    it.copy(autoSkipIntroOutro = autoSkip, autoplay = false, loopMode = LoopMode.OFF)
                }
            }
            await("automatic skipping preference") {
                preferences.playerPreferences.value.autoSkipIntroOutro == autoSkip
            }
            onMain {
                future = MediaController.Builder(
                    context,
                    SessionToken(context, ComponentName(context, PlayerService::class.java)),
                ).buildAsync()
            }
            val player = checkNotNull(future).get(15, TimeUnit.SECONDS)
            controller = player
            onMain {
                assertTrue(player.isConnected)
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                player.prepare()
                player.play()
            }
            await("playback to advance with automatic skipping=$autoSkip") {
                assertNull(player.playerError)
                player.isPlaying && player.currentPosition >= 1_000
            }
            onMain {
                player.pause()
                assertTrue(player.playbackState == Player.STATE_READY)
                player.play()
            }
            await("playback to resume with automatic skipping=$autoSkip") {
                assertNull(player.playerError)
                player.isPlaying && player.currentPosition >= 2_000
            }
        } finally {
            onMain {
                controller?.let {
                    it.pause()
                    it.clearMediaItems()
                    it.stop()
                }
                future?.let { MediaController.releaseFuture(it) }
            }
            context.stopService(Intent(context, PlayerService::class.java))
            file.delete()
            runBlocking { preferences.updatePlayerPreferences { original } }
        }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var satisfied = false
            onMain { satisfied = condition() }
            if (satisfied) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Timed out waiting for $description")
    }
}
