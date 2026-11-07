@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.anilbeesetti.nextplayer

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import android.os.Bundle
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


    // The fixture must contain colored video pixels. Checking only the playback clock misses
    // a black Surface or a shutter covering the last frame while playback remains paused.
    private fun assertVideoVisible(label: String) {
        val deadline = SystemClock.elapsedRealtime() + 7000
        var coloredFraction = 0.0
        val minimumColoredFraction = if (InstrumentationRegistry.getArguments().getString("hdr") == "true") 0.15 else 0.85
        do {
            val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            val image = screenshot.copy(Bitmap.Config.ARGB_8888, false)
            screenshot.recycle()
            var colored = 0
            var samples = 0
            // Central 16:9 video region, excluding controls, letterboxing and system bars.
            val width = image.width * 0.65
            val height = minOf(image.height * 0.40, width * 9 / 16)
            val left = ((image.width - width) / 2).toInt()
            val top = ((image.height - height) / 2).toInt()
            for (y in top until top + height.toInt() step 8) {
                for (x in left until left + width.toInt() step 8) {
                    val pixel = image.getPixel(x, y)
                    val high = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                    val low = minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                    if (high > 70 && high - low > 35) colored++
                    samples++
                }
            }
            coloredFraction = colored.toDouble() / samples
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "paused-rotation-evidence").apply { mkdirs() }
            File(directory, label + ".png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            if (coloredFraction > minimumColoredFraction) break
            SystemClock.sleep(200)
        } while (SystemClock.elapsedRealtime() < deadline)
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "\nframe=$label coloredFraction=$coloredFraction\n") })
        assertTrue("$label must visibly render the fixture, coloredFraction=$coloredFraction", coloredFraction > minimumColoredFraction)
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
        var originalInterpolation: Bundle? = null
        var originalSpeed = 1f
        try {
            lateinit var initialInterpolation: ListenableFuture<SessionResult>
            onMain {
                originalSpeed = controller.playbackParameters.speed
                initialInterpolation = controller.sendCustomCommand(SessionCommand("GET_FRAME_INTERPOLATION", Bundle.EMPTY), Bundle.EMPTY)
            }
            val initialResult = initialInterpolation.get(10, TimeUnit.SECONDS)
            assertEquals(SessionResult.RESULT_SUCCESS, initialResult.resultCode)
            originalInterpolation = initialResult.extras
            lateinit var interpolationResult: ListenableFuture<SessionResult>
            onMain {
                interpolationResult = controller.sendCustomCommand(SessionCommand("SET_FRAME_INTERPOLATION", Bundle.EMPTY), Bundle().apply {
                    putBoolean("enabled", InstrumentationRegistry.getArguments().getString("interpolation") == "true")
                    putInt("target", 60)
                })
                controller.setPlaybackSpeed(1f)
                controller.play()
            }
            assertEquals(SessionResult.RESULT_SUCCESS, interpolationResult.get(10, TimeUnit.SECONDS).resultCode)
            // The session controller need not expose a rendered video size. A live
            // playback clock is the prerequisite for exercising pause and rotation.
            await("playback ready") { controller.isPlaying }
            onMain {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            await("initial portrait window") { activity.window.decorView.height > activity.window.decorView.width }
            assertVideoVisible("initial-playing")
            onMain { controller.pause() }
            await("paused playback before rotation") { !controller.isPlaying && !controller.playWhenReady }
            fun surface(view: View): SurfaceView? {
                if (view is SurfaceView) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) surface(view.getChildAt(i))?.let { return it }
                return null
            }
            onMain {
                surface(activity.window.decorView)?.holder?.addCallback(object : SurfaceHolder.Callback {
                    fun report(event: String) = instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "\nsurface event=$event\n") })
                    override fun surfaceCreated(holder: SurfaceHolder) { report("created") }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { report("changed " + width + "x" + height) }
                    override fun surfaceDestroyed(holder: SurfaceHolder) { report("destroyed") }
                })
            }
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
                    SystemClock.sleep(800) // Let the actual orientation animation and buffer resize finish.
                    onMain {
                        val output = checkNotNull(surface(activity.window.decorView))
                        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nvideoView=" + output.width + "x" + output.height + " buffer=" + output.holder.surfaceFrame + "\n") })
                    }
                    assertVideoVisible("rotation-$playing-$index")
                    if (!playing) onMain {
                        assertFalse(controller.playWhenReady)
                        assertTrue("Paused position remains stable after pixels settle", kotlin.math.abs(controller.currentPosition - pausedPosition) < 100)
                    }
                    instrumentation.sendStatus(0, android.os.Bundle().apply {
                        putString("stream", "\nrotation playing=$playing landscape=$landscape passed\n")
                    })
                }
                if (playing) await("playback continues after rotation") { controller.isPlaying && controller.currentPosition > pausedPosition + 1000 }
            }
            onMain { controller.pause(); controller.seekTo(10_000) }
            await("paused seek ready") { !controller.playWhenReady && controller.playbackState == androidx.media3.common.Player.STATE_READY && kotlin.math.abs(controller.currentPosition - 10_000) < 100 }
            assertVideoVisible("paused-seek")
            onMain { activity.moveTaskToBack(true) }
            SystemClock.sleep(1000)
            context.startActivity(Intent(context, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            await("foreground window ready") { activity.hasWindowFocus() }
            SystemClock.sleep(800)
            assertVideoVisible("paused-foreground")
            onMain {
                assertFalse("Foreground return must keep pause intent", controller.playWhenReady)
                assertTrue("Foreground return keeps paused position", kotlin.math.abs(controller.currentPosition - 10_000) < 100)
            }
            clickRotationButton(activity)
            SystemClock.sleep(800)
            assertVideoVisible("paused-foreground-rotation")
            onMain { controller.play() }
            await("resume after paused seek and foreground") { controller.isPlaying && controller.currentPosition > 11_000 }
            assertVideoVisible("final-resumed")
        } finally {
            try {
                originalInterpolation?.let { original ->
                    lateinit var restored: ListenableFuture<SessionResult>
                    onMain {
                        controller.pause()
                        controller.setPlaybackSpeed(originalSpeed)
                        restored = controller.sendCustomCommand(SessionCommand("SET_FRAME_INTERPOLATION", Bundle.EMPTY), Bundle().apply {
                            putBoolean("enabled", original.getBoolean("enabled"))
                            putInt("target", original.getInt("target"))
                        })
                    }
                    val result = restored.get(10, TimeUnit.SECONDS)
                    assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
                    assertEquals(original.getBoolean("enabled"), result.extras.getBoolean("enabled"))
                    assertEquals(original.getInt("target"), result.extras.getInt("target"))
                }
            } finally {
                onMain { controller.pause(); controller.release(); activity.finish() }
            }
        }
    }
}
