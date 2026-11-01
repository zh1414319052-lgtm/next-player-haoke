package dev.anilbeesetti.nextplayer

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Explicitly seeded, newly installed formal package only; never reads personal media. */
@RunWith(AndroidJUnit4::class)
class ReleaseUpgradeDeviceTest {
    @Test fun settingsAndHistorySurviveTheRealSignedUpgrade() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("dev.anilbeesetti.nextplayer.haoke", context.packageName)
        // Let Android register the package-owned external directory before ADB fixtures are read.
        assertTrue(context.getExternalFilesDir(null)!!.isDirectory)
        val phase = InstrumentationRegistry.getArguments().getString("upgradePhase") ?: "verify"
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val marker = context.getSharedPreferences("release_upgrade_fixture", 0)
        val app = File(context.filesDir, "datastore/app_preferences.json")
        val player = File(context.filesDir, "datastore/player_preferences.json")
        val uri = "file:///__nextplayer_release_upgrade_fixture__/sample.mkv"
        val database = context.getDatabasePath("media_db")
        if (phase == "seed") {
            assertEquals("Seed must run on the established old formal APK", "0.19.0-haoke.5", version)
            assertFalse("Never overwrite existing preferences", app.exists())
            assertFalse("Never overwrite existing preferences", player.exists())
            app.parentFile!!.mkdirs()
            app.writeText(JSONObject().put("themeConfig", "ON").put("useDynamicColors", false)
                .put("excludeFolders", org.json.JSONArray().put("/storage/emulated/0/Movies/NextPlayer-release-fixture")).toString())
            player.writeText(JSONObject().put("defaultPlaybackSpeed", 1.25).put("rememberSelections", false).toString())
            assertTrue(marker.edit().putBoolean("owned", true).commit())
            val deadline = System.currentTimeMillis() + 15_000
            fun schemaReady() = runCatching {
                SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT name FROM sqlite_master WHERE name = 'media_state'", null).use { it.moveToFirst() }
                }
            }.getOrDefault(false)
            while (!schemaReady() && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue("The old application's Room schema must initialize itself", schemaReady())
            SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.insertOrThrow("media_state", null, ContentValues().apply {
                    put("uri", uri); put("playback_position", 12_345L)
                    put("last_played_time", 1_700_000_000_000L); put("duration", 60_000L)
                    put("external_subs", ""); put("external_audio", "")
                    put("video_scale", 1f); put("subtitle_delay", 0L); put("subtitle_speed", 1f)
                })
            }
        } else {
            assertEquals("0.19.0-haoke.6", version)
            assertTrue("Only operate on this task's fixture", marker.getBoolean("owned", false))
            val preferences = GlobalContext.get().get<PreferencesRepository>()
            if (phase == "cleanup") {
                SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                    assertEquals(1, db.delete("media_state", "uri = ?", arrayOf(uri)))
                    val videoUris = arrayOf(
                        "file:///sdcard/Android/data/${context.packageName}/files/TerminalList-S02E08.mkv",
                        "file:///storage/emulated/0/Android/data/${context.packageName}/files/TerminalList-S02E08.mkv",
                    )
                    for (table in listOf("media_state", "detected_segment", "analysis_fingerprint")) {
                        for (videoUri in videoUris) db.delete(table, "uri = ?", arrayOf(videoUri))
                    }
                }
                preferences.resetPreferences()
                assertTrue(marker.edit().clear().commit())
                for (name in listOf("TerminalList-S02E08.mkv", "cinema-more.png", "cinema-settings.png", "cinema-player-settings.png", "cinema-skip-dialog.png", "cinema-default-speed.png", "cinema-network.png", "cinema-playlists.png", "cinema-about.png")) {
                    val owned = File(context.getExternalFilesDir(null), name)
                    if (owned.exists()) assertTrue("Remove only the named task file: $name", owned.delete())
                }
            } else {
                assertEquals("verify", phase)
                assertEquals("ON", JSONObject(app.readText()).getString("themeConfig"))
                assertEquals("/storage/emulated/0/Movies/NextPlayer-release-fixture",
                    JSONObject(app.readText()).getJSONArray("excludeFolders").getString(0))
                val restored = withTimeout(10_000) { preferences.playerPreferences.first { it.defaultPlaybackSpeed == 1.25f } }
                assertFalse(restored.rememberSelections)
                assertFalse(restored.customSkipIntroOutro)
                SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT playback_position,duration FROM media_state WHERE uri = ?", arrayOf(uri)).use { row ->
                        assertTrue(row.moveToFirst())
                        assertEquals(12_345L, row.getLong(0)); assertEquals(60_000L, row.getLong(1))
                    }
                }
            }
        }
    }
}
