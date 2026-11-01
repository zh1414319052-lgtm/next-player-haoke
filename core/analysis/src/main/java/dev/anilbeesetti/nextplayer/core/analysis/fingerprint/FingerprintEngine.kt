package dev.anilbeesetti.nextplayer.core.analysis.fingerprint

/**
 * Kotlin-facing fingerprint computation. Chromaprint handles downmixing and
 * resampling internally (bundled resampler), so raw decode output can be fed
 * as-is; the resulting raw fingerprint is mapped back to media time through
 * the window start and the per-item duration reported by the library.
 */
object FingerprintEngine {

    /** Chromaprint algorithm 1 (TEST2), the default of the fpcalc family. */
    const val ALGORITHM = 1

    fun compute(pcm: ShortArray, sampleRate: Int, channels: Int, windowStartMs: Long): AudioFingerprint? {
        if (pcm.isEmpty()) return null
        val itemDurationMsOut = IntArray(1)
        val values = FingerprintNative.computeFingerprint(
            pcm = pcm,
            sampleRate = sampleRate,
            channels = channels,
            algorithm = ALGORITHM,
            itemDurationMsOut = itemDurationMsOut,
        ) ?: return null
        val itemDurationMs = itemDurationMsOut[0].toLong()
        if (itemDurationMs <= 0 || values.isEmpty()) return null
        return AudioFingerprint(values, itemDurationMs, windowStartMs)
    }
}

/** Serialisation helpers for storing raw fingerprints as BLOBs. */
object FingerprintCodec {

    fun encode(values: IntArray): ByteArray {
        val out = ByteArray(values.size * 4)
        for (i in values.indices) {
            val v = values[i]
            out[i * 4] = (v and 0xFF).toByte()
            out[i * 4 + 1] = (v shr 8 and 0xFF).toByte()
            out[i * 4 + 2] = (v shr 16 and 0xFF).toByte()
            out[i * 4 + 3] = (v shr 24 and 0xFF).toByte()
        }
        return out
    }

    fun decode(bytes: ByteArray): IntArray {
        val out = IntArray(bytes.size / 4)
        for (i in out.indices) {
            val o = i * 4
            out[i] = (bytes[o].toInt() and 0xFF) or
                ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                ((bytes[o + 2].toInt() and 0xFF) shl 16) or
                (bytes[o + 3].toInt() shl 24)
        }
        return out
    }
}
