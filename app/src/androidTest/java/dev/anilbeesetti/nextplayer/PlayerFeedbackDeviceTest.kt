@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.anilbeesetti.nextplayer

import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import dev.anilbeesetti.nextplayer.feature.player.PlayerActivity
import dev.anilbeesetti.nextplayer.feature.player.service.PlayerService
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerFeedbackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            SystemClock.sleep(100)
        }
        error("Timed out: " + label)
    }
    private fun awaitUi(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        reportRemoteUi("timeout: " + label)
        error("Timed out: " + label)
    }
    private fun awaitControlsHidden(label: String) {
        var hiddenSince = 0L
        awaitUi(label) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val hidden = root != null && find(root, this.label("screen_rotation"))?.isVisibleToUser != true
            val now = SystemClock.elapsedRealtime()
            if (!hidden) hiddenSince = 0L else if (hiddenSince == 0L) hiddenSince = now
            // A transient accessibility-tree update is not a settled hidden-controls state.
            hidden && now - hiddenSince >= 600
        }
    }
    private fun reportRemoteUi(stage: String) {
        val lines = mutableListOf("remote stage=$stage rotationLabel=" + label("screen_rotation") + " pauseLabel=" + label("play_pause"))
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 16) return
            lines += "  ".repeat(depth) + "text=" + node.text + " desc=" + node.contentDescription + " visible=" + node.isVisibleToUser + " focused=" + node.isFocused + " focusable=" + node.isFocusable + " clickable=" + node.isClickable
            repeat(node.childCount) { visit(node.getChild(it), depth + 1) }
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow, 0)
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "\n" + lines.joinToString("\n") + "\n") })
    }
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        repeat(node.childCount) { find(node.getChild(it), label)?.let { found -> return found } }
        return null
    }
    private fun focusTarget(name: String): AccessibilityNodeInfo? {
        var node = find(instrumentation.uiAutomation.rootInActiveWindow, label(name)) ?: return null
        while (!node.isClickable) node = node.parent ?: return null
        return node
    }
    private fun label(name: String) = context.getString(context.resources.getIdentifier(name, "string", context.packageName))
    private fun visible(name: String): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow
        root?.refresh()
        return find(root, label(name))?.isVisibleToUser == true
    }
    private fun tap(name: String) {
        val end = SystemClock.elapsedRealtime() + 4000
        var node: AccessibilityNodeInfo? = null
        while (node == null && SystemClock.elapsedRealtime() < end) {
            node = find(instrumentation.uiAutomation.rootInActiveWindow, label(name))
            if (node?.isEnabled != true || node?.isVisibleToUser != true) node = null
            if (node == null) SystemClock.sleep(100)
        }
        val rect = Rect()
        checkNotNull(node) { "Missing real control: " + name }.getBoundsInScreen(rect)
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY(), 0)
        val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, rect.exactCenterX(), rect.exactCenterY(), 0)
        try { instrumentation.sendPointerSync(down); instrumentation.sendPointerSync(up) } finally { down.recycle(); up.recycle() }
    }
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); instrumentation.waitForIdleSync() }
    private fun withPlayer(block: (PlayerActivity, MediaController) -> Unit) {
        val formalOptIn = InstrumentationRegistry.getArguments().getString("formalFeedbackAcceptance") == "true"
        check(context.packageName == "dev.anilbeesetti.nextplayer.qafeedback" ||
            (formalOptIn && context.packageName == "dev.anilbeesetti.nextplayer.haoke")) { "Only the isolated QA package or explicitly accepted signed formal package may be tested" }
        val args = InstrumentationRegistry.getArguments()
        val uri = args.getString("sampleUri") ?: Uri.fromFile(File(context.getExternalFilesDir(null), "frame-feedback.mkv")).toString()
        val activity = instrumentation.startActivitySync(Intent(context, PlayerActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "video/x-matroska")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlayerActivity
        lateinit var pending: ListenableFuture<MediaController>
        main { pending = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlayerService::class.java))).buildAsync() }
        val controller = pending.get(15, TimeUnit.SECONDS)
        try {
            await("video playing") { controller.isPlaying }
            main { controller.pause(); controller.seekTo(5000) }
            await("seek settled") { kotlin.math.abs(controller.currentPosition - 5000) < 300 && !controller.isPlaying && controller.playbackState == androidx.media3.common.Player.STATE_READY }
            await("player window focused") { activity.hasWindowFocus() }
            awaitUi("player controls initially visible") { visible("screen_rotation") }
            block(activity, controller)
        } finally {
            main { controller.pause(); controller.release(); activity.finish() }
        }
    }

    @Test fun phoneRemoteKeepsWorkingWithHiddenControls() = withPlayer { activity, player ->
        main { player.play() }
        awaitControlsHidden("controls automatically hidden")
        main { player.pause(); player.seekTo(5000) }
        await("hidden paused seek settled") { kotlin.math.abs(player.currentPosition - 5000) < 300 && !player.isPlaying && player.playbackState == androidx.media3.common.Player.STATE_READY }
        assertFalse(activity.isFinishing)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("hidden remote fast forward") { player.currentPosition >= 14_500 }
        assertFalse(visible("screen_rotation"))
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        await("hidden remote rewind") { player.currentPosition in 4500..5500 }
        key(KeyEvent.KEYCODE_MEDIA_PLAY)
        await("media play") { player.isPlaying }
        awaitUi("media play reveals controls before the next auto hide") { visible("screen_rotation") }
        awaitControlsHidden("hidden while playing")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUi("remote reveals controls") { visible("screen_rotation") }
        var nativeFocus = ""
        main { nativeFocus = "touch=" + activity.window.decorView.isInTouchMode + " nativeFocus=" + activity.currentFocus?.javaClass?.name + " windowFocus=" + activity.hasWindowFocus() }
        val pauseNode = find(instrumentation.uiAutomation.rootInActiveWindow, label("play_pause"))
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "\nremote focus " + nativeFocus + " playPauseFocused=" + pauseNode?.isFocused + " focusable=" + pauseNode?.isFocusable + " enabled=" + pauseNode?.isEnabled + " clickable=" + pauseNode?.isClickable + " actionFocused=" + focusTarget("play_pause")?.isFocused + " actionFocusable=" + focusTarget("play_pause")?.isFocusable + "\n") })
        awaitUi("play pause receives remote focus") { focusTarget("play_pause")?.isFocused == true }
        main { assertTrue("Revealing controls must not activate the newly focused button", player.isPlaying) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("focused remote pause") { !player.isPlaying && !player.playWhenReady }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("focused remote resumes") { player.isPlaying && player.playWhenReady }
        assertFalse(activity.isFinishing)
    }

    @Test fun captureSavesCleanFullResolutionFrameWithoutChangingPlayback() = withPlayer { activity, player ->
        val hdr = InstrumentationRegistry.getArguments().getString("hdr") == "true"
        if (!hdr) await("selected subtitle delivered") { player.currentCues.cues.any { it.text?.contains("QA EXTERNAL SUBTITLE") == true } }
        val before = ownedImages()
        val evidenceName = InstrumentationRegistry.getArguments().getString("evidenceDir") ?: "verification"
        check(evidenceName.matches(Regex("[A-Za-z0-9_-]+")))
        val shots = File(context.getExternalFilesDir(null), evidenceName).apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            File(shots, "frame-controls-and-subtitles.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        val playing = InstrumentationRegistry.getArguments().getString("playing") == "true"
        if (playing) main { player.play() }
        tap("frame_capture")
        await("gallery export") { ownedImages().size > before.size }
        val newImages = ownedImages() - before
        assertEquals("One click creates exactly one image", 1, newImages.size)
        val uri = newImages.single()
        val image = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
        assertNotNull(image)
        if (!hdr) {
            assertEquals(1920, image.width)
            assertEquals(1080, image.height)
            val reference = image.getPixel(40, 1000)
            for (y in 800 until 1080 step 12) for (x in 24 until 1896 step 12) {
                assertEquals("Export must contain neither subtitle pixels nor player controls", reference, image.getPixel(x, y))
            }
        }
        File(shots, if (hdr) "export-hdr.png" else "export-sdr.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        main {
            if (playing) {
                assertTrue(player.isPlaying)
                assertTrue(player.currentPosition > 5000)
            } else {
                assertFalse(player.isPlaying)
                assertTrue(kotlin.math.abs(player.currentPosition - 5000) < 300)
            }
        }
        assertFalse(activity.isFinishing)
        // Retain the exact synthetic URI for scoped post-test gallery cleanup.
        File(shots, if (hdr) "export-hdr-uri.txt" else "export-sdr-uri.txt").writeText(uri.toString())
        File(shots, "exported-uris.txt").appendText(uri.toString() + "\n")
    }

    private fun ownedImages(): Set<Uri> {
        val result = mutableSetOf<Uri>()
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            MediaStore.Images.Media.DISPLAY_NAME + " LIKE ? AND " + MediaStore.Images.Media.IS_PENDING + " = 0",
            arrayOf("NextPlayer_%"), null)?.use { cursor ->
            while (cursor.moveToNext()) result += Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0).toString())
        }
        return result
    }
}
