package dev.anilbeesetti.nextplayer.core.analysis.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A decoded window of interleaved PCM from a media file. */
class PcmWindow(
    val pcm: ShortArray,
    val sampleRate: Int,
    val channels: Int,
    /** Media timestamp of the first frame in [pcm], in milliseconds. */
    val startMs: Long,
    /** Media timestamp just past the last frame in [pcm], in milliseconds. */
    val endMs: Long,
) {
    val durationMs: Long get() = endMs - startMs
    val isEmpty: Boolean get() = pcm.isEmpty()
}

/**
 * Pure PCM helpers used while assembling a [PcmWindow]. Kept free of Android
 * classes so they can be unit tested on the JVM.
 */
object Pcm {

    /** Converts normalized little-endian float PCM; NaN is silence. */
    fun floatToShort(floatPcm: ByteArray): ShortArray {
        require(floatPcm.size % 4 == 0) { "incomplete float PCM sample" }
        val samples = ByteBuffer.wrap(floatPcm).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(floatPcm.size / 4) { normalizedFloatToShort(samples.float) }
    }

    /**
     * Decodes only [pcm]'s remaining byte range directly into caller-owned mono
     * storage. Input position/order are unchanged, and no interleaved array is
     * allocated. Channels have equal weight; frames and timeline are preserved.
     */
    fun decodeToMono(
        pcm: ByteBuffer,
        channels: Int,
        floatPcm: Boolean,
        destination: ShortArray,
        destinationOffset: Int = 0,
    ): Int {
        val bytesPerSample = if (floatPcm) 4 else 2
        require(channels in 1..(Int.MAX_VALUE / bytesPerSample)) { "invalid channel count" }
        val bytesPerFrame = channels * bytesPerSample
        require(pcm.remaining() % bytesPerFrame == 0) { "incomplete PCM frame" }
        val frames = pcm.remaining() / bytesPerFrame
        require(destinationOffset in 0..destination.size && frames <= destination.size - destinationOffset) {
            "PCM destination is too small"
        }
        val samples = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until frames) {
            var sum = 0L
            repeat(channels) {
                sum += if (floatPcm) normalizedFloatToShort(samples.float).toLong() else samples.short.toLong()
            }
            destination[destinationOffset + frame] = (sum / channels).toShort()
        }
        return frames
    }

    private fun normalizedFloatToShort(sample: Float): Short = when {
        sample.isNaN() -> 0
        sample >= 1f -> Short.MAX_VALUE
        sample <= -1f -> Short.MIN_VALUE
        else -> clampToShort(sample * 32768f)
    }

    /**
     * Deterministic downmix of interleaved multi-channel PCM to stereo (or mono
     * passthrough). For more than two channels, even-indexed channels are
     * averaged into the left bus and odd-indexed channels into the right bus.
     * Channel layouts are not reliably exposed by decoders, so this simple,
     * stable rule is used instead of layout-aware coefficients.
     */
    fun downmixToStereo(pcm: ShortArray, channels: Int): Pair<ShortArray, Int> {
        require(channels >= 1)
        if (channels <= 2) return pcm to channels

        val frames = pcm.size / channels
        val out = ShortArray(frames * 2)
        var leftCount = 0
        var rightCount = 0
        for (ch in 0 until channels) {
            if (ch % 2 == 0) leftCount++ else rightCount++
        }
        for (frame in 0 until frames) {
            val base = frame * channels
            var left = 0f
            var right = 0f
            for (ch in 0 until channels) {
                val s = pcm[base + ch].toInt()
                if (ch % 2 == 0) left += s else right += s
            }
            out[frame * 2] = clampToShort(left / leftCount)
            out[frame * 2 + 1] = clampToShort(right / rightCount)
        }
        return out to 2
    }

    fun clampToShort(sample: Float): Short = when {
        sample.isNaN() -> 0
        sample >= Short.MAX_VALUE -> Short.MAX_VALUE
        sample <= Short.MIN_VALUE -> Short.MIN_VALUE
        else -> sample.toInt().toShort()
    }
}
