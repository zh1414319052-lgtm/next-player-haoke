@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.anilbeesetti.nextplayer

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import dev.anilbeesetti.nextplayer.core.common.PrimaryLanguage
import dev.anilbeesetti.nextplayer.feature.player.PlayerActivity
import dev.anilbeesetti.nextplayer.feature.player.service.PlayerService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerRotationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            onMain { ready = condition() }
            if (ready) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out: $label")
    }

    private fun clickRotationButton(activity: PlayerActivity) {
        var label = ""
        onMain { label = activity.getString(activity.resources.getIdentifier("screen_rotation", "string", activity.packageName)) }
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        var button = find(instrumentation.uiAutomation.rootInActiveWindow)
        if (button == null) {
            var x = 0f
            var y = 0f
            onMain { x = activity.window.decorView.width / 2f; y = activity.window.decorView.height * 0.30f }
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0)
            try { instrumentation.sendPointerSync(down); instrumentation.sendPointerSync(up) } finally { down.recycle(); up.recycle() }
            val deadline = SystemClock.elapsedRealtime() + 3000
            while (button == null && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100)
                button = find(instrumentation.uiAutomation.rootInActiveWindow)
            }
        }
        val bounds = Rect()
        checkNotNull(button) { "The real rotation button must be accessible" }.getBoundsInScreen(bounds)
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        try { instrumentation.sendPointerSync(down); instrumentation.sendPointerSync(up) } finally { down.recycle(); up.recycle() }
    }

    @Test fun rotationRoundTripsWhilePausedAndPlayingKeepLocalizedResourcesCurrent() {
        val uri = InstrumentationRegistry.getArguments().getString("interpolationVideoUri")
        org.junit.Assume.assumeTrue("An explicitly selected video is required", uri != null)
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, PlayerActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(uri), "video/x-matroska")
                .putExtra("position", InstrumentationRegistry.getArguments().getString("testPositionMs")?.toIntOrNull() ?: 120_000)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PlayerActivity
        lateinit var pending: ListenableFuture<MediaController>
        onMain {
            pending = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlayerService::class.java))).buildAsync()
        }
        val controller = pending.get(10, TimeUnit.SECONDS)
        try {
            onMain { controller.setPlaybackSpeed(1f); controller.play() }
            // The session controller need not expose a rendered video size. A live
            // playback clock is the prerequisite for exercising pause and rotation.
            await("playback ready") { controller.isPlaying }
            onMain {
                controller.pause()
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            await("initial portrait window") { activity.window.decorView.height > activity.window.decorView.width }
            await("paused playback before rotation") { !controller.isPlaying && !controller.playWhenReady }
            var pausedPosition = 0L
            onMain { pausedPosition = controller.currentPosition }
            for (playing in listOf(false, true)) {
                if (playing) onMain { controller.play() }
                repeat(4) { index ->
                    val landscape = index % 2 == 0
                    clickRotationButton(activity)
                    onMain {
                        instrumentation.sendStatus(0, android.os.Bundle().apply {
                            putString("stream", "\nrotation click requested=${activity.requestedOrientation} resource=${activity.resources.configuration.orientation} window=${activity.window.decorView.width}x${activity.window.decorView.height}\n")
                        })
                    }
                    await("actual window ${if (landscape) "landscape" else "portrait"}") {
                        val view = activity.window.decorView
                        if (landscape) view.width > view.height else view.height > view.width
                    }
                    onMain {
                        assertEquals(
                            "Localized resources must follow the real window after every rotation",
                            if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT,
                            activity.resources.configuration.orientation,
                        )
                        val system = Resources.getSystem().configuration.locales
                        val expected = PrimaryLanguage.languageTag(if (system.isEmpty) emptyList() else listOf(system[0].toLanguageTag()))
                        assertEquals(expected, activity.resources.configuration.locales[0].toLanguageTag())
                        if (!playing) {
                            assertFalse(controller.isPlaying)
                            assertTrue("Paused rotation must preserve position", kotlin.math.abs(controller.currentPosition - pausedPosition) < 500)
                        }
                    }
                    instrumentation.sendStatus(0, android.os.Bundle().apply {
                        putString("stream", "\nrotation playing=$playing landscape=$landscape passed\n")
                    })
                }
                if (playing) await("playback continues after rotation") { controller.isPlaying && controller.currentPosition > pausedPosition + 1000 }
            }
        } finally {
            onMain { controller.pause(); controller.release(); activity.finish() }
        }
    }
}
