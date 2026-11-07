package dev.anilbeesetti.nextplayer

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly named synthetic records only; never enumerates or clears personal history. */
@RunWith(AndroidJUnit4::class)
class FeedbackFixtureCleanupDeviceTest {
    @Test fun cleanOnlyTheAcceptedFeedbackFixtures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        check(context.packageName == "dev.anilbeesetti.nextplayer.haoke")
        check(args.getString("formalFeedbackAcceptance") == "true")
        val phase = checkNotNull(args.getString("cleanupPhase"))
        check(phase in setOf("preflight", "delete", "verify"))
        val input = JSONArray(checkNotNull(args.getString("fixtureUris")))
        check(input.length() in 1..3)
        val uris = mutableSetOf<String>()
        repeat(input.length()) { index ->
            val value = input.getString(index)
            val uri = Uri.parse(value)
            val local = uri.scheme == "file" && uri.path in listOf(
                "/sdcard/Android/data/${context.packageName}/files/feedback84-frame.mkv",
                "/sdcard/Android/data/${context.packageName}/files/feedback84-hlg.mp4",
            )
            val network = uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in 1024..65535 && uri.path == "/frame-feedback.mkv" && uri.query == null && uri.fragment == null
            check(local || network) { "Not a feedback fixture" }
            uris += value
            if (local) uris += value.replace("file:///sdcard/", "file:///storage/emulated/0/")
        }
        val flag = if (phase == "delete") SQLiteDatabase.OPEN_READWRITE else SQLiteDatabase.OPEN_READONLY
        SQLiteDatabase.openDatabase(context.getDatabasePath("media_db").path, null, flag).use { database ->
            for (table in listOf("media_state", "detected_segment", "analysis_fingerprint")) for (uri in uris) {
                if (phase == "delete") {
                    database.delete(table, "uri = ?", arrayOf(uri))
                } else {
                    database.rawQuery("SELECT COUNT(*) FROM $table WHERE uri = ?", arrayOf(uri)).use { row ->
                        check(row.moveToFirst())
                        val count = row.getInt(0)
                        if (phase == "verify") assertEquals("Only synthetic history is cleaned", 0, count)
                        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$phase $table synthetic rows=$count\n") })
                    }
                }
            }
        }
    }
}
