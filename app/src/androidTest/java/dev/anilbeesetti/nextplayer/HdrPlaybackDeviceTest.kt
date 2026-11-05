@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.anilbeesetti.nextplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.feature.player.PlayerActivity
import dev.anilbeesetti.nextplayer.feature.player.service.PlayerService
import dev.anilbeesetti.nextplayer.feature.player.interpolation.InterpolationState
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.RendererCapabilities
import dev.anilbeesetti.nextplayer.feature.player.service.CustomCommands
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Captures the real service output and a native decoder reference on the same device/surface. */
@RunWith(AndroidJUnit4::class)
class HdrPlaybackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Test fun nativeCapabilityBoundaryPreservesSdr() {
        check(context.packageName.endsWith(".qahdr"))
        val renderer = FfmpegVideoRenderer(0, null, null, 0)
        for (mime in listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_AV1)) {
            for (transfer in listOf(C.COLOR_TRANSFER_SDR, C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG)) {
                val format = Format.Builder().setSampleMimeType(mime).setColorInfo(
                    ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setLumaBitdepth(10)
                        .setChromaBitdepth(10).setColorTransfer(transfer).build(),
                ).build()
                assertEquals("$mime transfer=$transfer", if (transfer == C.COLOR_TRANSFER_SDR) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE,
                    RendererCapabilities.getFormatSupport(renderer.supportsFormat(format)))
            }
        }
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 35_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            main { ready = predicate() }
            if (ready) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out: $label")
    }

    @Test fun compareServiceWithNativeHdrOutput() {
        check(context.packageName.endsWith(".qahdr")) { "Only run against this task's isolated QA package" }
        val args = InstrumentationRegistry.getArguments()
        val sample = args.getString("sample") ?: "colorbars.mp4"
        val enabled = args.getString("interpolation") == "true"
        val evidence = args.getString("evidence") ?: "baseline"
        val uri = Uri.fromFile(File(context.getExternalFilesDir(null), sample))
        val preferences = GlobalContext.get().get<PreferencesRepository>()
        val original = preferences.playerPreferences.value
        var controllerFuture: ListenableFuture<MediaController>? = null
        var controller: MediaController? = null
        var activity: PlayerActivity? = null
        var reference: ExoPlayer? = null
        var referenceSurface: SurfaceView? = null
        var connection: ServiceConnection? = null
        var service: PlayerService? = null
        try {
            runBlocking { preferences.updatePlayerPreferences { it.copy(frameInterpolationEnabled = enabled, frameInterpolationTargetFps = 60, autoPip = false, enableTunneledPlayback = false) } }
            val connected = CountDownLatch(1)
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    val field = binder.javaClass.getDeclaredField("serviceReference").apply { isAccessible = true }
                    service = (field.get(binder) as WeakReference<*>).get() as PlayerService
                    connected.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
            main {
                assertTrue(context.bindService(Intent(context, PlayerService::class.java).setAction("androidx.media3.session.MediaSessionService"), connection!!, Context.BIND_AUTO_CREATE))
                controllerFuture = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlayerService::class.java))).buildAsync()
            }
            assertTrue(connected.await(15, TimeUnit.SECONDS))
            controller = controllerFuture!!.get(15, TimeUnit.SECONDS)
            activity = instrumentation.startActivitySync(Intent(context, PlayerActivity::class.java).setData(uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlayerActivity
            val current = controller!!
            await("service first frames") { check(current.playerError == null) { current.playerError!!.stackTraceToString() }; current.isPlaying && current.currentPosition >= 2000 }
            main { current.pause(); current.seekTo(3000) }
            SystemClock.sleep(1500)
            lateinit var surface: SurfaceView
            lateinit var player: ExoPlayer
            lateinit var state: InterpolationState
            main {
                surface = findSurface(activity!!.window.decorView) ?: error("Player surface missing")
                val sessionField = PlayerService::class.java.getDeclaredField("mediaSession").apply { isAccessible = true }
                player = (sessionField.get(service) as MediaSession).player as ExoPlayer
                state = PlayerService::class.java.getDeclaredField("interpolation").apply { isAccessible = true }.get(service) as InterpolationState
                val decoder = PlayerService::class.java.getDeclaredField("decoderManager").apply { isAccessible = true }.get(service) as DecoderManager
                report("service sample=$sample enabled=$enabled decoder=${decoder.activeVideoMode} format=${player.videoFormat} state=${state.snapshot()}")
                if (args.getString("verifyHdrDecoder") == "true") assertTrue("HDR must not select FFmpeg's untonemapped RGBA output", decoder.activeVideoMode != DecoderMode.FFMPEG)
            }
            val production = capture(surface, "$evidence-$sample-$enabled-service")
            main {
                referenceSurface = SurfaceView(activity!!).apply { setZOrderMediaOverlay(true) }
                (activity!!.findViewById<View>(android.R.id.content) as ViewGroup).addView(referenceSurface, FrameLayout.LayoutParams(surface.width, surface.height, android.view.Gravity.CENTER))
            }
            await("reference surface") { referenceSurface!!.holder.surface.isValid }
            main {
                reference = ExoPlayer.Builder(context).setRenderersFactory(DefaultRenderersFactory(context)).build().also {
                    it.setVideoSurfaceView(referenceSurface!!)
                    it.setMediaItem(MediaItem.fromUri(uri))
                    it.seekTo(3000)
                    it.prepare()
                }
            }
            await("native reference frame") { check(reference!!.playerError == null) { reference!!.playerError!!.stackTraceToString() }; reference!!.playbackState == Player.STATE_READY }
            SystemClock.sleep(1500)
            val native = capture(referenceSurface!!, "$evidence-$sample-$enabled-native")
            val error = pixelError(production, native)
            report("comparison sample=$sample enabled=$enabled meanChannelError=$error serviceSize=${production.width}x${production.height}")
            if (args.getString("verifyColors") == "true") assertTrue("Service deviates from native HDR output: $error", error < 8.0)
            main {
                reference!!.release(); reference = null
                (referenceSurface!!.parent as ViewGroup).removeView(referenceSurface)
                referenceSurface = null
                current.seekTo(7000)
                current.play()
            }
            await("seek/resume") { assertNull(current.playerError); current.currentPosition >= 8000 }
            report("seek/resume passed sample=$sample enabled=$enabled")
            if (args.getString("exerciseModes") == "true") {
                fun choose(mode: DecoderMode) = main {
                    current.sendCustomCommand(CustomCommands.SET_VIDEO_DECODER_MODE.sessionCommand,
                        Bundle().apply { putString(CustomCommands.VIDEO_DECODER_MODE_KEY, mode.name) })
                }
                choose(DecoderMode.FFMPEG)
                await("HDR FFmpeg fallback confirmation") {
                    current.sessionExtras.getString(CustomCommands.DECODER_RECOVERY_STATUS_KEY) == "AWAITING_CONFIRMATION"
                }
                main { current.sendCustomCommand(CustomCommands.TRY_DECODER_FALLBACK.sessionCommand, Bundle.EMPTY) }
                await("HDR native recovery") {
                    player.videoFormat != null && current.sessionExtras.getString(CustomCommands.VIDEO_DECODER_MODE_KEY) != "FFMPEG" &&
                        current.sessionExtras.getString(CustomCommands.DECODER_RECOVERY_STATUS_KEY) == "NONE"
                }
                main {
                    val sdrUri = Uri.fromFile(File(context.getExternalFilesDir(null), "sdr-motion.mp4"))
                    current.setMediaItem(MediaItem.Builder().setUri(sdrUri).setMediaId(sdrUri.toString()).build())
                    current.prepare(); current.play()
                }
                await("HDR to SDR") { check(current.playerError == null) { current.playerError!!.stackTraceToString() }; current.isPlaying && current.currentPosition >= 1000 }
                choose(DecoderMode.FFMPEG)
                await("SDR FFmpeg still works") { current.isPlaying && current.sessionExtras.getString(CustomCommands.VIDEO_DECODER_MODE_KEY) == "FFMPEG" }
                main {
                    current.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(uri.toString()).build()); current.prepare(); current.play()
                }
                await("SDR to HDR resets decoder choice") {
                    current.isPlaying && current.currentPosition >= 1000 &&
                        current.sessionExtras.getString(CustomCommands.VIDEO_DECODER_MODE_KEY) != "FFMPEG"
                }
                report("manual HDR fallback, SDR FFmpeg, HDR/SDR transitions passed")
            }
        } finally {
            main {
                reference?.release()
                referenceSurface?.let { (it.parent as? ViewGroup)?.removeView(it) }
                activity?.finish()
                controller?.let { it.pause(); it.clearMediaItems(); it.stop() }
                controllerFuture?.let(MediaController::releaseFuture)
            }
            connection?.let(context::unbindService)
            context.stopService(Intent(context, PlayerService::class.java))
            runBlocking { preferences.updatePlayerPreferences { original } }
        }
    }

    private fun capture(surface: SurfaceView, name: String): Bitmap {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = -1
        main { PixelCopy.request(surface, bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper())) }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun pixelError(first: Bitmap, second: Bitmap): Double {
        // PixelCopy sees the video graph's letterboxed output but the reference codec's
        // full buffer. Normalize active content for these controlled test patterns only.
        // Moving footage is recorded for inspection; verifyColors is for static patterns.
        fun content(bitmap: Bitmap): Bitmap {
            var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (maxOf(pixel and 255, (pixel shr 8) and 255, (pixel shr 16) and 255) > 10) {
                    left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
                }
            }
            check(right > left && bottom > top) { "Empty video capture" }
            return Bitmap.createScaledBitmap(Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1), 320, 180, true)
        }
        val aContent = content(first)
        val bContent = content(second)
        var total = 0L
        var count = 0L
        for (y in 10 until aContent.height - 10 step 3) for (x in 10 until aContent.width - 10 step 3) {
            val a = aContent.getPixel(x, y)
            val b = bContent.getPixel(x, y)
            for (shift in listOf(0, 8, 16)) {
                total += kotlin.math.abs(((a shr shift) and 255) - ((b shr shift) and 255))
                count++
            }
        }
        return total.toDouble() / count
    }

    private fun findSurface(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findSurface(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun report(text: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$text\n") })
}
