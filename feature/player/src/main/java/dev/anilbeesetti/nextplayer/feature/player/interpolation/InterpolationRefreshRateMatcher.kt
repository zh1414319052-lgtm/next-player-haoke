package dev.anilbeesetti.nextplayer.feature.player.interpolation

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Surface
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import dev.anilbeesetti.nextplayer.feature.player.service.CustomCommands
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal data class InterpolationDisplayMode(
    val id: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
)

/** Keep resolution unchanged and prefer the lowest exact cadence to avoid needless power use. */
internal fun interpolationDisplayMode(
    outputFps: Float,
    width: Int,
    height: Int,
    modes: List<InterpolationDisplayMode>,
): Int? {
    if (!outputFps.isFinite() || outputFps <= 0f) return null
    val sameSize = modes.filter { it.width == width && it.height == height && it.refreshRate.isFinite() && it.refreshRate > 0f }
    val exactCadence = sameSize.filter {
        val multiple = (it.refreshRate / outputFps).roundToInt()
        multiple >= 1 && abs(it.refreshRate - outputFps * multiple) <= 0.5f
    }
    return exactCadence.minByOrNull { it.refreshRate }?.id
        // For e.g. 48 fps on a 60/90/120 Hz panel, the fastest mode minimizes timing quantization.
        ?: sameSize.maxByOrNull { it.refreshRate }?.id
}

internal fun interpolationDisplayFps(enabled: Boolean, status: String?, target: Int, sourceFps: Float, isPlaying: Boolean): Float =
    if (!enabled || status != "active" || !isPlaying) 0f else when (target) {
        60 -> 60f
        0 -> if (sourceFps.isFinite() && sourceFps in 11.7f..30.1f) sourceFps * 2f else 0f
        else -> 0f
    }

/**
 * Requests both the real video Surface cadence and a same-resolution foreground-window mode.
 * Media3's source-frame hint is disabled by the service while interpolation is available.
 * The platform may still decline the request (e.g. power saving); the UI reports actual display Hz.
 */
internal class InterpolationRefreshRateMatcher(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val controller: () -> MediaController?,
) {
    private var job: Job? = null
    private var savedModeId: Int? = null
    private var requestedModeId: Int? = null
    private var surface: Surface? = null
    private var surfaceFps = 0f
    private var desiredFps = 0f

    fun setSurface(value: Surface) {
        // SurfaceHolder callbacks define the lifetime; a Surface wrapper may be reused by Android.
        if (surface !== value) clearSurfaceRate()
        surface = value
        surfaceFps = Float.NaN
        setSurfaceRate(desiredFps)
    }

    fun clearSurface(value: Surface) {
        if (surface !== value) return
        clearSurfaceRate()
        surface = null
    }

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                val player = controller()
                if (player?.isConnected == true && player.isPlaying) {
                    try {
                        val result = withTimeout(1500) {
                            player.sendCustomCommand(CustomCommands.GET_FRAME_INTERPOLATION.sessionCommand, Bundle.EMPTY).await()
                        }
                        // A controller/foreground transition may complete while the query is pending.
                        if (player === controller() && player.isConnected && player.isPlaying && result.resultCode == SessionResult.RESULT_SUCCESS) {
                            val status = result.extras
                            apply(interpolationDisplayFps(status.getBoolean("enabled"), status.getString("status"), status.getInt("target"), status.getFloat("fps"), true))
                        } else {
                            restore()
                        }
                    } catch (_: TimeoutCancellationException) {
                        restore()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        restore()
                    }
                } else {
                    restore()
                }
                delay(500)
            }
        }
    }

    fun onPlaybackChanged() {
        // Clear a high-refresh request immediately on pause or controller detachment.
        if (controller()?.isPlaying != true) restore()
    }

    fun stop() {
        job?.cancel()
        job = null
        restore()
    }

    private fun apply(outputFps: Float) {
        val display = activity.window.decorView.display
        if (outputFps <= 0f || display == null) {
            restore()
            return
        }
        desiredFps = outputFps
        setSurfaceRate(outputFps)
        val current = display.mode
        val modeId = interpolationDisplayMode(
            outputFps,
            current.physicalWidth,
            current.physicalHeight,
            display.supportedModes.map { InterpolationDisplayMode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) },
        ) ?: return restore()
        val attributes = activity.window.attributes
        if (savedModeId == null) savedModeId = attributes.preferredDisplayModeId
        requestedModeId = modeId
        if (attributes.preferredDisplayModeId != modeId) {
            attributes.preferredDisplayModeId = modeId
            activity.window.attributes = attributes
        }
    }

    private fun restore() {
        desiredFps = 0f
        clearSurfaceRate()
        val saved = savedModeId ?: return
        val attributes = activity.window.attributes
        // Do not overwrite a window request made by another owner after ours.
        if (attributes.preferredDisplayModeId == requestedModeId && attributes.preferredDisplayModeId != saved) {
            attributes.preferredDisplayModeId = saved
            activity.window.attributes = attributes
        }
        savedModeId = null
        requestedModeId = null
    }

    private fun clearSurfaceRate() = setSurfaceRate(0f)

    private fun setSurfaceRate(fps: Float) {
        val target = surface ?: return
        if (Build.VERSION.SDK_INT < 30 || !target.isValid || surfaceFps == fps) return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                target.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
            } else {
                target.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
            surfaceFps = fps
        } catch (_: IllegalStateException) {
            // Surface destruction can race a status response. A new Surface will apply the request.
        } catch (_: IllegalArgumentException) {
            // An OEM may reject a rate; retain ordinary playback and report actual display Hz.
        }
    }
}
