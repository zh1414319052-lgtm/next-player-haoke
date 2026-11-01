package dev.anilbeesetti.nextplayer.core.analysis.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/**
 * Decodes a finite window of the first audio track into 16-bit mono PCM for
 * fingerprinting. Samples are downmixed while the codec owns its output buffer;
 * no interleaved copy of the whole window is retained.
 *
 * Unsupported formats, discontinuous output, stalled decoders and windows that
 * exceed the PCM budget fail without returning a truncated analysis window.
 */
class AudioWindowReader(private val context: Context) {

    class UnsupportedAudioException(message: String) : Exception(message)

    suspend fun read(uri: Uri, startMs: Long, endMs: Long): PcmWindow? {
        require(startMs >= 0) { "startMs must not be negative" }
        require(endMs > startMs && endMs <= Long.MAX_VALUE / 1000) { "invalid window end" }
        val callerContext = coroutineContext
        callerContext.ensureActive()
        if (endMs - startMs > MAX_WINDOW_MS) {
            throw UnsupportedAudioException("requested audio window is too long")
        }
        val window = readInternal(uri, startMs, endMs, callerContext)
        callerContext.ensureActive()
        return window
    }

    private fun readInternal(uri: Uri, startMs: Long, endMs: Long, callerContext: CoroutineContext): PcmWindow? {
        callerContext.ensureActive()
        var descriptor: ParcelFileDescriptor? = null
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            descriptor = pfd
            val mediaExtractor = MediaExtractor()
            extractor = mediaExtractor
            val decoder = configureAndStart(pfd, mediaExtractor, uri, startMs)
            codec = decoder
            return decodeWindow(mediaExtractor, decoder, startMs, endMs, callerContext)
        } catch (e: UnsupportedAudioException) {
            callerContext.ensureActive()
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            callerContext.ensureActive()
            throw UnsupportedAudioException(e.message ?: "decoder state error")
        } catch (e: Exception) {
            callerContext.ensureActive()
            return null
        } finally {
            codec?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            runCatching { extractor?.release() }
            runCatching { descriptor?.close() }
        }
    }

    private fun configureAndStart(
        pfd: ParcelFileDescriptor,
        extractor: MediaExtractor,
        uri: Uri,
        startMs: Long,
    ): MediaCodec {
        extractor.setDataSource(pfd.fileDescriptor)
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                extractor.selectTrack(i)
                val codec = MediaCodec.createDecoderByType(mime)
                try {
                    codec.configure(format, null, null, 0)
                    codec.start()
                    extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    return codec
                } catch (failure: Throwable) {
                    // The caller cannot own the codec until this function returns.
                    runCatching { codec.stop() }
                    runCatching { codec.release() }
                    throw failure
                }
            }
        }
        throw UnsupportedAudioException("no audio track in $uri")
    }

    private data class OutputPcmFormat(val sampleRate: Int, val channels: Int, val encoding: Int) {
        val bytesPerFrame: Int
            get() = channels * if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
    }

    private fun checkedOutputFormat(format: MediaFormat, previous: OutputPcmFormat?): OutputPcmFormat {
        val pcmFormat = OutputPcmFormat(
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
            encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            },
        )
        if (pcmFormat.sampleRate <= 0 || pcmFormat.channels !in 1..MAX_INPUT_CHANNELS) {
            throw UnsupportedAudioException("invalid PCM sample rate or channel count")
        }
        if (pcmFormat.encoding != AudioFormat.ENCODING_PCM_16BIT &&
            pcmFormat.encoding != AudioFormat.ENCODING_PCM_FLOAT
        ) {
            throw UnsupportedAudioException("unsupported PCM encoding ${pcmFormat.encoding}")
        }
        if (previous != null && previous != pcmFormat) {
            throw UnsupportedAudioException("PCM output format changed within the window")
        }
        return pcmFormat
    }

    private fun decodeWindow(
        extractor: MediaExtractor,
        codec: MediaCodec,
        startMs: Long,
        endMs: Long,
        callerContext: CoroutineContext,
    ): PcmWindow? {
        val startUs = startMs * 1000
        val endUs = endMs * 1000
        var outputFormat: OutputPcmFormat? = null
        var firstPtsUs: Long? = null
        var firstFrameToKeep = 0L
        var endFrameToKeep = 0L
        var decodedFrames = 0L
        var pcm: ShortArray? = null
        var framesWritten = 0
        var inputDone = false
        var outputDone = false
        var lastProgressMs = SystemClock.elapsedRealtime()
        val bufferInfo = MediaCodec.BufferInfo()

        fun checkProgress() {
            callerContext.ensureActive()
            if (SystemClock.elapsedRealtime() - lastProgressMs >= NO_PROGRESS_TIMEOUT_MS) {
                throw UnsupportedAudioException("audio decoder made no PCM progress")
            }
        }

        while (!outputDone) {
            checkProgress()
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                        ?: throw UnsupportedAudioException("decoder returned no input buffer")
                    inputBuffer.clear()
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    val pts = extractor.sampleTime
                    if (sampleSize < 0 || pts >= endUs) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, pts, 0)
                        extractor.advance()
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outputFormat = checkedOutputFormat(codec.outputFormat, outputFormat)
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                outputIndex >= 0 -> {
                    try {
                        checkProgress()
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                        if (bufferInfo.size < 0 || bufferInfo.offset < 0) {
                            throw UnsupportedAudioException("invalid PCM output range")
                        }
                        if (bufferInfo.size == 0 ||
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        ) {
                            continue
                        }
                        val outBuffer = codec.getOutputBuffer(outputIndex)
                            ?: throw UnsupportedAudioException("decoder returned no PCM output buffer")
                        if (bufferInfo.offset.toLong() + bufferInfo.size > outBuffer.capacity()) {
                            throw UnsupportedAudioException("PCM output range exceeds its buffer")
                        }
                        val format = outputFormat ?: checkedOutputFormat(codec.outputFormat, null).also {
                            outputFormat = it
                        }
                        if (bufferInfo.size % format.bytesPerFrame != 0) {
                            throw UnsupportedAudioException("incomplete PCM output frame")
                        }
                        val bufferFrames = bufferInfo.size / format.bytesPerFrame
                        if (firstPtsUs == null) {
                            val originUs = bufferInfo.presentationTimeUs
                            firstPtsUs = originUs
                            firstFrameToKeep = frameIndexAt(startUs, originUs, format.sampleRate, roundUp = true)
                            endFrameToKeep = frameIndexAt(endUs, originUs, format.sampleRate, roundUp = false)
                            val capacity = endFrameToKeep - firstFrameToKeep
                            if (capacity <= 0) return null
                            if (capacity > MAX_PCM_FRAMES) {
                                throw UnsupportedAudioException("audio window exceeds the 48 MiB PCM budget")
                            }
                            pcm = ShortArray(capacity.toInt())
                        }

                        val originUs = checkNotNull(firstPtsUs)
                        val expectedPtsUs = timestampUs(originUs, decodedFrames, format.sampleRate)
                        // Permit timestamp rounding, not gaps that would be silently joined.
                        val toleranceUs = maxOf(1_000L, (MICROS_PER_SECOND + format.sampleRate - 1) / format.sampleRate)
                        val ptsDeltaUs = Math.subtractExact(bufferInfo.presentationTimeUs, expectedPtsUs)
                        if (ptsDeltaUs !in -toleranceUs..toleranceUs) {
                            throw UnsupportedAudioException("non-contiguous PCM output timestamps")
                        }
                        val nextDecodedFrames = Math.addExact(decodedFrames, bufferFrames.toLong())
                        val keepStart = maxOf(decodedFrames, firstFrameToKeep)
                        val keepEnd = minOf(nextDecodedFrames, endFrameToKeep)
                        if (keepEnd > keepStart) {
                            val byteStart = bufferInfo.offset + (keepStart - decodedFrames).toInt() * format.bytesPerFrame
                            val byteEnd = byteStart + (keepEnd - keepStart).toInt() * format.bytesPerFrame
                            val samples = outBuffer.duplicate().apply {
                                clear()
                                limit(byteEnd)
                                position(byteStart)
                            }
                            framesWritten += Pcm.decodeToMono(
                                pcm = samples,
                                channels = format.channels,
                                floatPcm = format.encoding == AudioFormat.ENCODING_PCM_FLOAT,
                                destination = checkNotNull(pcm),
                                destinationOffset = framesWritten,
                            )
                        }
                        decodedFrames = nextDecodedFrames
                        lastProgressMs = SystemClock.elapsedRealtime()
                        outputDone = outputDone || decodedFrames >= endFrameToKeep
                        callerContext.ensureActive()
                    } finally {
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }

        callerContext.ensureActive()
        val samples = pcm ?: return null
        val format = outputFormat ?: return null
        val originUs = firstPtsUs ?: return null
        if (framesWritten == 0) return null
        val result = if (framesWritten == samples.size) {
            samples
        } else {
            // Early EOF may need a smaller result. Budget both live arrays too.
            if (samples.size.toLong() + framesWritten > MAX_PCM_FRAMES * 2L) {
                throw UnsupportedAudioException("PCM result copy exceeds the 96 MiB peak budget")
            }
            samples.copyOf(framesWritten)
        }
        callerContext.ensureActive()
        return PcmWindow(
            pcm = result,
            sampleRate = format.sampleRate,
            channels = 1,
            startMs = timestampUs(originUs, firstFrameToKeep, format.sampleRate) / 1000,
            endMs = timestampUs(originUs, firstFrameToKeep + framesWritten, format.sampleRate) / 1000,
        )
    }

    private fun frameIndexAt(timeUs: Long, originUs: Long, sampleRate: Int, roundUp: Boolean): Long {
        val elapsedUs = Math.subtractExact(timeUs, originUs)
        if (elapsedUs <= 0) return 0
        val scaled = Math.multiplyExact(elapsedUs, sampleRate.toLong())
        return scaled / MICROS_PER_SECOND + if (roundUp && scaled % MICROS_PER_SECOND != 0L) 1 else 0
    }

    private fun timestampUs(originUs: Long, frames: Long, sampleRate: Int): Long =
        Math.addExact(originUs, Math.multiplyExact(frames, MICROS_PER_SECOND) / sampleRate)

    private companion object {
        const val INPUT_TIMEOUT_US = 10_000L
        const val OUTPUT_TIMEOUT_US = 10_000L
        const val NO_PROGRESS_TIMEOUT_MS = 10_000L
        const val MAX_WINDOW_MS = 12 * 60 * 1000L
        const val MAX_INPUT_CHANNELS = 32
        const val MAX_PCM_BYTES = 48 * 1024 * 1024
        const val MAX_PCM_FRAMES = MAX_PCM_BYTES / 2
        const val MICROS_PER_SECOND = 1_000_000L
    }
}
