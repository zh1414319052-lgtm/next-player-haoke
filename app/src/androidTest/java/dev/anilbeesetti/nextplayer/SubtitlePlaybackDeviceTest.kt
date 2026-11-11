@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.anilbeesetti.nextplayer

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.feature.player.PlayerActivity
import dev.anilbeesetti.nextplayer.feature.player.service.CustomCommands
import dev.anilbeesetti.nextplayer.feature.player.service.PlayerService
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class SubtitlePlaybackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixtureDir get() = File(context.filesDir, "subtitle-qa")
    private var activePlayer: MediaController? = null

    @Test
    fun sameNameUppercaseAssLoadsAndBothLanguagesRenderOnSeparateRows() {
        withPlayer("sidecar.mp4") { player ->
            seek(player, 3_000)
            await("auto-selected ASS with three cues") {
                player.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected } &&
                    player.currentCues.cues.size == 3
            }
            val screenshot = awaitSeparatedSubtitleBands()
            screenshot.useBitmap {
                File(fixtureDir, "sidecar-rendered.png").outputStream().use { output ->
                    it.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
            }
            seek(player, 11_000)
            await("cues clear during the gap") { player.currentCues.cues.isEmpty() }
            seek(player, 14_000)
            main { player.play() }
            await("explicit ASS newline renders when playback resumes after seeking") {
                player.hasSecondAssLine()
            }
            seek(player, 3_000)
            await("the first bilingual lines return after seeking backward") { player.currentCues.cues.size == 3 }
            seek(player, 17_400)
            await("subtitles remain empty after the final line ends") { player.currentCues.cues.isEmpty() }
        }
    }

    @Test
    fun reopensVideoWithSavedHistoryAndFindsTheSameSidecar() {
        withPlayer("sidecar.mp4") { player ->
            seek(player, 14_000)
            await("the final ASS line is available before closing") { player.hasSecondAssLine() }
            val stopped = main {
                player.sendCustomCommand(CustomCommands.STOP_PLAYER_SESSION.sessionCommand, Bundle.EMPTY)
            }
            assertEquals(SessionResult.RESULT_SUCCESS, stopped.get(15, TimeUnit.SECONDS).resultCode)
        }
        withPlayer("sidecar.mp4") { player ->
            await("sidecar and saved playback position are restored on reopening") {
                player.currentPosition in 13_900..16_999 && player.hasSecondAssLine()
            }
        }
    }

    @Test
    fun explicitlyAddedAssSelectsEvenWhenRememberingIsOffAndSrtStillSwitches() {
        check(context.packageName == "dev.anilbeesetti.nextplayer.qafeedback")
        val preferences = GlobalContext.get().get<PreferencesRepository>()
        val original = preferences.playerPreferences.value
        val manualVideo = "manual-fresh-${SystemClock.elapsedRealtime()}.mp4"
        File(fixtureDir, "manual.mp4").copyTo(File(fixtureDir, manualVideo))
        try {
            runBlocking { preferences.updatePlayerPreferences { it.copy(rememberSelections = false) } }
            withPlayer(manualVideo) { player ->
                seek(player, 3_000)
                main {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                }
                addSubtitle(player, "manual-bilingual.ASS")
                await("manual ASS selected despite previously disabled text") {
                    player.currentCues.cues.size == 3 && !player.isPlaying &&
                        player.currentPosition in 2_800..3_200
                }
                awaitSeparatedSubtitleBands().recycle()
                seek(player, 14_000)
                await("the manually loaded ASS also shows its final line after seeking") { player.hasSecondAssLine() }
                seek(player, 3_000)
                addSubtitle(player, "manual-plain.srt")
                await("ordinary two-line SRT selected") {
                    player.currentCues.cues.singleOrNull()?.text?.contains("普通 SRT") == true
                }
                addSubtitle(player, "manual-bilingual.ASS")
                await("reselect existing ASS without duplicating it") {
                    player.currentCues.cues.size == 3 && player.currentTracks.groups.count { it.type == C.TRACK_TYPE_TEXT } == 2
                }
            }
            runBlocking { preferences.updatePlayerPreferences { it.copy(rememberSelections = true) } }
            withPlayer(manualVideo) { player ->
                seek(player, 3_000)
                await("remembered ASS is selected after reopening") { player.currentCues.cues.size == 3 }
                main {
                    val srt = player.currentTracks.groups.single {
                        it.type == C.TRACK_TYPE_TEXT && it.getTrackFormat(0).label == "manual-plain.srt"
                    }
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(srt.mediaTrackGroup, 0)).build()
                }
                await("SRT track remains switchable after reopening") {
                    player.currentCues.cues.singleOrNull()?.text?.contains("普通 SRT") == true
                }
            }
        } finally {
            runBlocking { preferences.updatePlayerPreferences { original } }
        }
    }

    private fun MediaController.hasSecondAssLine(): Boolean =
        currentCues.cues.singleOrNull()?.text?.contains("\n日本語：次のセリフ") == true

    private fun addSubtitle(player: MediaController, filename: String) {
        val file = File(fixtureDir, filename)
        check(file.isFile) { "Missing fixture: $filename" }
        val pending = main {
            player.sendCustomCommand(CustomCommands.ADD_SUBTITLE_TRACK.sessionCommand, Bundle().apply {
                putString(CustomCommands.SUBTITLE_TRACK_URI_KEY, Uri.fromFile(file).toString())
            })
        }
        assertEquals(SessionResult.RESULT_SUCCESS, pending.get(15, TimeUnit.SECONDS).resultCode)
    }

    private fun withPlayer(filename: String, block: (MediaController) -> Unit) {
        check(context.packageName == "dev.anilbeesetti.nextplayer.qafeedback")
        val video = File(fixtureDir, filename)
        check(video.isFile) { "Missing fixture: $filename" }
        val activity = instrumentation.startActivitySync(
            Intent(context, PlayerActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setDataAndType(Uri.fromFile(video), "video/mp4")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PlayerActivity
        val token = SessionToken(context, ComponentName(context, PlayerService::class.java))
        val pending = main { MediaController.Builder(context, token).buildAsync() }
        val player = pending.get(15, TimeUnit.SECONDS)
        activePlayer = player
        try {
            await("video playing through the media session") { player.isPlaying }
            await("player activity in foreground") { activity.hasWindowFocus() }
            block(player)
        } finally {
            activePlayer = null
            main { player.pause(); player.release(); activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun seek(player: MediaController, positionMs: Long) {
        main { player.pause(); player.seekTo(positionMs) }
        await("paused at $positionMs") {
            !player.isPlaying && player.playbackState == Player.STATE_READY &&
                kotlin.math.abs(player.currentPosition - positionMs) < 200
        }
    }

    private fun awaitSeparatedSubtitleBands(): Bitmap {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        var bands: SubtitleBands? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val frame = instrumentation.uiAutomation.takeScreenshot()
            if (frame != null) {
                bands = measureSubtitleBands(frame)
                if (bands.separated) return frame
                frame.recycle()
            }
            SystemClock.sleep(150)
        }
        throw AssertionError("Expected green Chinese above yellow Japanese and blue sign above both; detected $bands")
    }

    private data class SubtitleBands(val green: IntRange, val yellow: IntRange, val blue: IntRange) {
        val separated get() = !green.isEmpty() && !yellow.isEmpty() && !blue.isEmpty() &&
            green.last < yellow.first && blue.last < green.first
    }

    private fun measureSubtitleBands(frame: Bitmap): SubtitleBands {
        val pixels = IntArray(frame.width)
        val rows = List(3) { mutableListOf<Int>() }
        // The generated video is 16:9 and uses fit mode. Restrict measurement
        // to its center so system status icons cannot count as subtitle colors.
        val pictureHeight = minOf(frame.height, frame.width * 9 / 16)
        val top = maxOf(frame.height / 20, (frame.height - pictureHeight) / 2)
        val bottom = minOf(frame.height * 19 / 20, (frame.height + pictureHeight) / 2)
        for (y in top until bottom) {
            frame.getPixels(pixels, 0, frame.width, 0, y, frame.width, 1)
            val counts = IntArray(3)
            for (pixel in pixels) {
                val r = pixel shr 16 and 255
                val g = pixel shr 8 and 255
                val b = pixel and 255
                when {
                    g > 100 && r < g / 3 && b < g / 3 -> counts[0]++
                    r > 100 && g > 100 && b < minOf(r, g) / 3 -> counts[1]++
                    b > 110 && g in (b / 3)..(b * 9 / 10) && r < b / 4 -> counts[2]++
                }
            }
            counts.forEachIndexed { index, count -> if (count >= 5) rows[index].add(y) }
        }
        fun range(index: Int) = (rows[index].firstOrNull() ?: 1)..(rows[index].lastOrNull() ?: 0)
        return SubtitleBands(range(0), range(1), range(2))
    }

    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) {
        try { block(this) } finally { recycle() }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (main(condition)) return
            SystemClock.sleep(100)
        }
        val state = main {
            activePlayer?.run {
                val textTracks = currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.map {
                    val format = it.getTrackFormat(0)
                    listOf(it.mediaTrackGroup.id, format.id, format.label, format.sampleMimeType, format.codecs, it.isSelected)
                }
                "position=${currentPosition} state=${playbackState} playing=${isPlaying} " +
                    "disabled=${trackSelectionParameters.disabledTrackTypes} cues=${currentCues.cues.map { it.text }} tracks=$textTracks"
            }
        }
        throw AssertionError("Timed out: $label; $state")
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
