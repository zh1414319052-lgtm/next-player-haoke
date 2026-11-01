package dev.anilbeesetti.nextplayer

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native accessibility acceptance on the exact R8 artifact; no Compose test-only runtime. */
@RunWith(AndroidJUnit4::class)
class FormalUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun label(id: String): String {
        val resource = context.resources.getIdentifier(id, "string", context.packageName)
        assertTrue("Existing public resource: $id", resource != 0)
        return context.getString(resource)
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.takeIf { it.packageName?.toString() == context.packageName }?.let(::visit)
        return result
    }
    private fun matches(node: AccessibilityNodeInfo, text: String) =
        node.isVisibleToUser && (node.text?.toString()?.contains(text) == true || node.contentDescription?.toString()?.contains(text) == true)
    private fun await(description: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 35_000
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) return
            SystemClock.sleep(150)
        }
        throw AssertionError("Timed out: $description")
    }
    private fun click(id: String) {
        val text = label(id)
        await(text) { nodes().any { matches(it, text) } }
        for (found in nodes().filter { matches(it, text) }) {
            var node: AccessibilityNodeInfo? = found
            while (node != null) {
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    SystemClock.sleep(300)
                    return
                }
                node = node.parent
            }
        }
        throw AssertionError("No clickable node for $text")
    }
    private fun scrollTo(id: String) {
        val until = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < until) {
            if (nodes().any { matches(it, label(id)) }) return
            val scroll = nodes().firstOrNull { it.isVisibleToUser && it.isScrollable }
            scroll?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(200)
        }
        throw AssertionError("Cannot scroll to ${label(id)}")
    }
    private fun speed(value: Float) {
        await("speed slider") { nodes().any { it.isVisibleToUser && it.rangeInfo?.max == 4f } }
        val node = nodes().first { it.isVisibleToUser && it.rangeInfo?.max == 4f }
        assertTrue(node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value)
        }))
        await("saved speed") { nodes().any { matches(it, "$value×") } }
    }
    private fun durations(intro: String, outro: String) {
        await("two duration inputs") { nodes().count { it.isVisibleToUser && it.className?.toString()?.endsWith("EditText") == true } == 2 }
        val inputs = nodes().filter { it.isVisibleToUser && it.className?.toString()?.endsWith("EditText") == true }
        for ((node, value) in inputs.zip(listOf(intro, outro))) {
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }))
        }
    }
    private fun save(stem: String) {
        SystemClock.sleep(450)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "$stem.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun coldStartSettingsSaveCancelAndRecreationOnFormalApk() {
        assertEquals("0.19.0-haoke.6", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        click("more")
        await("more tools") { nodes().any { matches(it, label("vault")) } }
        save("cinema-more")
        click("settings")
        save("cinema-settings")
        click("player_name")
        click("default_playback_speed")
        speed(1.5f)
        save("cinema-default-speed")
        click("done")
        click("default_playback_speed")
        speed(1f)
        click("done")
        scrollTo("custom_skip_durations")
        click("custom_skip_durations")
        durations("90", "60")
        save("cinema-skip-dialog")
        click("save")
        await("stored duration") { nodes().any { matches(it, "90") } }
        save("cinema-player-settings")
        click("custom_skip_durations")
        durations("7", "60")
        click("cancel")
        await("cancel preserves duration") { nodes().any { matches(it, "90") } }
        instrumentation.runOnMainSync { activity.recreate() }
        await("old activity destroyed") { activity.isDestroyed }
        await("settings after recreation") { nodes().any { matches(it, label("cinema_playback_settings")) } }
        scrollTo("custom_skip_durations")
        click("custom_skip_durations")
        durations("0", "0")
        click("save")
        click("navigate_up")
        await("settings overview") { nodes().any { matches(it, label("cinema_settings_playback")) } }
        scrollTo("about_name")
        click("about_name")
        await("About licenses") { nodes().any { matches(it, label("open_source_licenses")) } }
        save("cinema-about")
    }
}
