package dev.anilbeesetti.nextplayer.feature.player.interpolation

import android.os.Bundle
import dev.anilbeesetti.nextplayer.core.model.FrameInterpolationTarget
import java.util.concurrent.atomic.AtomicLong

/** Main-thread settings and bounded diagnostics shared with Media3's GL thread. */
class InterpolationState {
    companion object {
        fun isSupportedTargetFps(value: Int): Boolean = FrameInterpolationTarget.isSupported(value)

        fun restoreTargetFps(value: Int): Int = FrameInterpolationTarget.restore(value)
    }

    @Volatile var enabled = false
        private set
    @Volatile var targetFps = 0
        private set
    @Volatile var blockedReason = ""
    @Volatile var failure = ""
        private set
    @Volatile var sourceFps = 0f
    @Volatile var processingMs = 0f
    @Volatile var active = false
    val generation = AtomicLong()
    val generated = AtomicLong()
    val submitted = AtomicLong()
    val emitted = AtomicLong()
    val presented = AtomicLong()
    private val outputTimes = LinkedHashMap<Long, Boolean>()
    private val intervals = ArrayDeque<Long>()
    private val releaseTrace = ArrayDeque<LongArray>()

    fun setEnabled(value: Boolean) {
        enabled = value
        failure = ""
        reset()
    }

    fun setTargetFps(value: Int) {
        require(isSupportedTargetFps(value))
        targetFps = value
        failure = ""
        reset()
    }

    @Synchronized fun reset() {
        generation.incrementAndGet()
        outputTimes.clear()
        intervals.clear()
        releaseTrace.clear()
        active = false
        sourceFps = 0f
        generated.set(0)
        submitted.set(0)
        emitted.set(0)
        presented.set(0)
    }

    fun fail(reason: String) {
        failure = reason
        active = false
    }

    @Synchronized fun recordOutput(timeUs: Long, synthetic: Boolean, expectedGeneration: Long = generation.get()) {
        // A switch or seek can reset diagnostics while the GL thread finishes an old draw.
        if (expectedGeneration != generation.get()) return
        if (synthetic) generated.incrementAndGet()
        emitted.incrementAndGet()
        if (outputTimes.size >= 128) outputTimes.remove(outputTimes.keys.first())
        outputTimes[timeUs] = synthetic
    }

    @Synchronized fun onFrameSubmitted(timeUs: Long, releaseTimeNs: Long = 0L, callbackTimeNs: Long = 0L) {
        val synthetic = outputTimes.remove(timeUs) ?: return
        if (synthetic) submitted.incrementAndGet()
        presented.incrementAndGet()
        if (releaseTimeNs > 0L) {
            if (releaseTrace.size == 128) releaseTrace.removeFirst()
            releaseTrace.addLast(longArrayOf(timeUs, releaseTimeNs, callbackTimeNs))
        }
    }

    fun canInterpolate(): Boolean = enabled && blockedReason.isEmpty() && failure.isEmpty()

    /**
     * Set by the service shortly before the final media item ends. Media3 playback only ends once
     * a frame at the last source timestamp leaves the effect, which the fixed-rate grid can skip.
     */
    @Volatile var endingSoon = false

    /** Whether the GL program synthesizes frames now; the user setting stays [canInterpolate]. */
    fun interpolatesNow(): Boolean = canInterpolate() && !endingSoon

    @Synchronized fun recordInterval(intervalUs: Long) {
        if (intervals.size == 24) intervals.removeFirst()
        intervals.addLast(intervalUs)
        sourceFps = (1_000_000.0 * intervals.size / intervals.sum()).toFloat()
    }

    @Synchronized fun snapshot() = Bundle().apply {
        putBoolean("enabled", enabled)
        putInt("target", targetFps)
        putString("status", when {
            !enabled -> "off"
            failure == "overload" -> "overload"
            failure == "resolution" -> "resolution"
            failure.isNotEmpty() -> "failed"
            blockedReason.isNotEmpty() -> blockedReason
            active -> "active"
            else -> "waiting"
        })
        putFloat("fps", sourceFps)
        putFloat("ms", processingMs)
        putLong("generated", generated.get())
        putLong("submitted", submitted.get())
        putLong("emitted", emitted.get())
        putLong("presented", presented.get())
        // Local bounded diagnostics distinguish media timestamps from scheduled presentation times.
        putLongArray("releaseTrace", releaseTrace.flatMap { it.toList() }.toLongArray())
    }
}
