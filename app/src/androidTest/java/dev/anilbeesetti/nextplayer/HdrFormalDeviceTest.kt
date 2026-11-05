package dev.anilbeesetti.nextplayer

import android.content.ComponentName
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.feature.player.PlayerActivity
import dev.anilbeesetti.nextplayer.feature.player.service.CustomCommands
import dev.anilbeesetti.nextplayer.feature.player.service.PlayerService
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Exact signed/R8 package acceptance; only task fixtures are played or removed. */
@RunWith(AndroidJUnit4::class)
class HdrFormalDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun guard() {
        check(context.packageName == "dev.anilbeesetti.nextplayer.haoke")
        check(args.getString("formalHdrAcceptance") == "true")
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun upgradeKeepsExistingData() {
        guard()
        val marker = context.getSharedPreferences("hdr_release_upgrade_83", 0)
        val values = sortedMapOf<String, String>()
        for (name in listOf("app_preferences.json", "player_preferences.json")) {
            val file = File(context.filesDir, "datastore/$name")
            values[name] = if (file.exists()) digest(file.readBytes()) else "absent"
        }
        val dbFile = context.getDatabasePath("media_db")
        values["history"] = if (!dbFile.exists()) "absent" else SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT * FROM media_state ORDER BY uri", null).use { rows ->
                val hash = MessageDigest.getInstance("SHA-256")
                while (rows.moveToNext()) for (i in 0 until rows.columnCount) {
                    hash.update((rows.getString(i) ?: "<null>").toByteArray())
                    hash.update(0.toByte())
                }
                hash.digest().joinToString("") { "%02x".format(it) }
            }
        }
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        if (args.getString("upgradePhase") == "before") {
            assertEquals(82L, version)
            val edit = marker.edit()
            for ((key, value) in values) edit.putString(key, value)
            assertTrue(edit.putBoolean("owned", true).commit())
        } else {
            assertEquals(83L, version)
            assertTrue(marker.getBoolean("owned", false))
            for ((key, value) in values) assertEquals("Preserve $key", marker.getString(key, null), value)
            assertTrue(marker.edit().clear().commit())
        }
        report("upgrade phase=${args.getString("upgradePhase")} version=$version settings/history fingerprints verified")
    }

    @Test fun signedHdrAndSdrPlayback() {
        guard()
        val samples = listOf("colorbars.mp4", "hlg-loop.mp4", "hdr10plus-loop.mp4", "av1-loop.mp4", "dolbyvision-loop.mp4", "sdr-motion.mp4")
        val preferences = GlobalContext.get().get<PreferencesRepository>()
        SystemClock.sleep(500)
        val original = preferences.playerPreferences.value
        var activity: PlayerActivity? = null
        var player: MediaController? = null
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        try {
            runBlocking { preferences.updatePlayerPreferences { it.copy(autoPip = false, enableTunneledPlayback = false, customSkipIntroOutro = false, autoSkipIntroOutro = false) } }
            main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlayerService::class.java))).buildAsync() }
            player = future!!.get(20, TimeUnit.SECONDS)
            fun command(command: CustomCommands, data: Bundle = Bundle.EMPTY): Bundle {
                lateinit var response: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult>
                main { response = player!!.sendCustomCommand(command.sessionCommand, data) }
                return response.get(15, TimeUnit.SECONDS).extras
            }
            for (sample in samples) for (enabled in listOf(false, true)) {
                val file = File(context.getExternalFilesDir(null), "hdr83-$sample")
                assertTrue("Task fixture is readable: $file", file.isFile)
                if (activity == null) activity = instrumentation.startActivitySync(Intent(context, PlayerActivity::class.java)
                    .setData(Uri.fromFile(file)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlayerActivity
                else main {
                    val uri = Uri.fromFile(file)
                    player!!.setMediaItem(androidx.media3.common.MediaItem.Builder().setUri(uri).setMediaId(uri.toString()).build())
                    player!!.prepare(); player!!.play()
                }
                command(CustomCommands.SET_FRAME_INTERPOLATION, Bundle().apply { putBoolean("enabled", enabled); putInt("target", 60) })
                val until = SystemClock.elapsedRealtime() + 30_000
                var ready = false
                while (!ready && SystemClock.elapsedRealtime() < until) {
                    main { assertNull(player!!.playerError); ready = player!!.isPlaying && player!!.currentPosition >= 1500 }
                    SystemClock.sleep(100)
                }
                assertTrue("First frames: $sample", ready)
                val metrics = command(CustomCommands.GET_PLAYBACK_METRICS)
                assertEquals("HARDWARE", metrics.getString("decoderMode"))
                assertTrue(metrics.getInt("rendered") > 0)
                if (sample != "sdr-motion.mp4" && enabled) assertEquals("hdr", command(CustomCommands.GET_FRAME_INTERPOLATION).getString("status"))
                main { player!!.pause(); player!!.seekTo(7000); player!!.play() }
                SystemClock.sleep(1500)
                main { assertNull(player!!.playerError); assertTrue(player!!.currentPosition >= 7500) }
                report("signed R8: $sample interpolation=$enabled frames=${metrics.getInt("rendered")} decoder=${metrics.getString("decoderMode")} seek/resume passed")
            }
        } finally {
            main { activity?.finish(); player?.let { it.pause(); it.clearMediaItems(); it.stop() }; future?.let(MediaController::releaseFuture) }
            context.stopService(Intent(context, PlayerService::class.java))
            SystemClock.sleep(700)
            runBlocking { preferences.updatePlayerPreferences { original } }
            SQLiteDatabase.openDatabase(context.getDatabasePath("media_db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                for (sample in samples) for (root in listOf("/sdcard", "/storage/emulated/0")) {
                    val uri = "file://$root/Android/data/${context.packageName}/files/hdr83-$sample"
                    for (table in listOf("media_state", "detected_segment", "analysis_fingerprint")) db.delete(table, "uri = ?", arrayOf(uri))
                }
            }
        }
    }

    private fun report(text: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$text\n") })
}
