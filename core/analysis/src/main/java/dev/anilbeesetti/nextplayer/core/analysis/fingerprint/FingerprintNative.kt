package dev.anilbeesetti.nextplayer.core.analysis.fingerprint

/**
 * JNI boundary to the vendored Chromaprint library.
 *
 * Input is interleaved signed 16-bit PCM (1 or 2 channels) at [sampleRate] Hz;
 * output is the raw fingerprint (one uint32 per item, delivered as IntArray)
 * together with the duration of one fingerprint item in milliseconds, which
 * callers use to map fingerprint positions to media timestamps.
 */
internal object FingerprintNative {

    init {
        System.loadLibrary("nextplayer_fingerprint")
    }

    /** Returns the raw fingerprint values, or null when no fingerprint could be computed. */
    @JvmStatic
    external fun computeFingerprint(
        pcm: ShortArray,
        sampleRate: Int,
        channels: Int,
        algorithm: Int,
        itemDurationMsOut: IntArray,
    ): IntArray?
}
