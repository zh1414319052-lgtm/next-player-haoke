@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.anilbeesetti.nextplayer.feature.player.interpolation

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.GlShaderProgram
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/**
 * Motion is estimated once per source pair and reused for every fractional output.
 * A bounded pool of six owned output textures decouples fixed-rate display from decoding.
 * Source inputs are returned once copied; output textures remain owned until presentation.
 * Double-rate mode keeps the original frame passthrough path.
 */
internal class MotionInterpolationProgram(
    private val context: Context,
    private val hdr: Boolean,
    private val state: InterpolationState,
) : GlShaderProgram {
    private var inputListener: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
    private var outputListener: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}
    private val timing = InterpolationTiming()
    private val fixedTiming = FixedRateTiming()
    private var generation = -1L
    private var previousTime: Long? = null
    private var previous: GlTextureInfo? = null
    private val outputTextures = mutableListOf<GlTextureInfo>()
    private var lumaPrevious: GlTextureInfo? = null
    private var lumaCurrent: GlTextureInfo? = null
    private var pyramidPrevious: GlTextureInfo? = null
    private var pyramidCurrent: GlTextureInfo? = null
    private var previousAnalysisValid = false
    private var currentAnalysisValid = false
    private var coarse: GlTextureInfo? = null
    private var forward: GlTextureInfo? = null
    private var backward: GlTextureInfo? = null
    private var motionField: GlTextureInfo? = null
    private var inputInFlight: GlTextureInfo? = null
    private val outputsInFlight = mutableSetOf<GlTextureInfo>()
    private var waitingForConfiguration = false
    private var waitingForStream = false
    private val pending = ArrayDeque<FrameSample>()
    private var inputTimeUs = 0L
    private var lastOutputTimeUs = Long.MIN_VALUE
    private var saveHistory = false
    private var processing = false
    private var credit = false
    private var released = false
    private var endRequested = false
    private var processingNanos = 0L
    private var inputIntervalUs = 0L
    private var madeSyntheticFrame = false
    private var copy: GlProgram? = null
    private var luma: GlProgram? = null
    private var flow: GlProgram? = null
    private var synthesize: GlProgram? = null
    private var compensation: GlProgram? = null
    private var downsample: GlProgram? = null
    private var slowFrames = 0

    override fun setInputListener(inputListener: GlShaderProgram.InputListener) {
        this.inputListener = inputListener
        credit = false
        requestInput()
    }

    override fun setOutputListener(outputListener: GlShaderProgram.OutputListener) {
        this.outputListener = outputListener
    }

    // The optional effect reports GPU failures in its UI and continues with original textures.
    override fun setErrorListener(executor: Executor, errorListener: GlShaderProgram.ErrorListener) = Unit

    private fun requestInput() {
        val free = outputTextures.count { it !in outputsInFlight }
        // Start the next pair as soon as one output slot is free. Waiting for all
        // 2–3 slots drains the display's lookahead before the next pair is ready.
        val ready = if (!state.interpolatesNow()) outputsInFlight.isEmpty() else
            outputTextures.isEmpty() || free >= 1
        if (!released && !processing && !credit && !endRequested && inputInFlight == null && ready) {
            credit = true
            inputListener.onReadyToAcceptInputFrame()
        }
    }

    override fun queueInputFrame(provider: GlObjectsProvider, input: GlTextureInfo, timeUs: Long) {
        check(credit && inputInFlight == null && pending.isEmpty())
        credit = false
        inputInFlight = input
        inputTimeUs = timeUs
        objectsProvider = provider
        if (endRequested) {
            waitingForStream = true
            return
        }
        if (outputsInFlight.isNotEmpty() && (!state.interpolatesNow() || (previous != null && (previous?.width != input.width || previous?.height != input.height)))) {
            waitingForConfiguration = true
            return
        }
        prepareInput(provider)
    }

    private var objectsProvider: GlObjectsProvider? = null

    private fun prepareInput(provider: GlObjectsProvider) {
        objectsProvider = provider
        val input = checkNotNull(inputInFlight)
        val timeUs = inputTimeUs
        processingNanos = 0
        currentAnalysisValid = false
        madeSyntheticFrame = false
        saveHistory = false
        inputIntervalUs = 0
        try {
            if (generation != state.generation.get()) {
                generation = state.generation.get()
                resetHistory()
                slowFrames = 0
            }
            if (state.interpolatesNow() && input.width.toLong() * input.height > 3840L * 2160L) state.fail("resolution")
            if (state.interpolatesNow()) {
                ensureTextures(provider, input.width, input.height)
                inputIntervalUs = previousTime?.let { timeUs - it } ?: 0L
                if (inputIntervalUs > 0) state.recordInterval(inputIntervalUs)
                if (state.targetFps > 0) {
                    pending.addAll(fixedTiming.next(timeUs, state.targetFps))
                } else {
                    timing.next(timeUs)?.let { pending.add(FrameSample(it, 0.5f)) }
                    pending.add(FrameSample(timeUs, 1f))
                }
                if (pending.any { it.fraction > 0f && it.fraction < 1f }) {
                    val start = System.nanoTime()
                    estimateMotion(input)
                    processingNanos += System.nanoTime() - start
                }
                saveHistory = true
            } else {
                resetHistory()
                state.active = false
                releaseTextures()
                pending.add(FrameSample(timeUs, 1f))
            }
        } catch (e: Exception) {
            fail(e)
            pending.clear()
            pending.add(FrameSample(timeUs, 1f))
        }
        drainOutputs()
    }

    private fun drainOutputs() {
        if (processing || inputInFlight == null || waitingForConfiguration) return
        processing = true
        try {
            val input = checkNotNull(inputInFlight)
            while (pending.isNotEmpty()) {
                if (generation != state.generation.get()) {
                    pending.clear()
                    pending.add(FrameSample(inputTimeUs, 1f))
                    saveHistory = false
                }
                val sample = pending.first()
                if (sample.timeUs <= lastOutputTimeUs) { pending.removeFirst(); continue }
                val synthetic = sample.fraction > 0f && sample.fraction < 1f
                val passthrough = !saveHistory || (state.targetFps == 0 && sample.fraction == 1f)
                val target = if (passthrough) input else outputTextures.firstOrNull { it !in outputsInFlight } ?: break
                pending.removeFirst()
                if (!passthrough) {
                    try {
                        val start = System.nanoTime()
                        if (synthetic) {
                            renderFraction(input, target, sample.fraction)
                            madeSyntheticFrame = true
                        } else {
                            draw(checkNotNull(copy), target, "uImage" to (if (sample.fraction == 0f) checkNotNull(previous).texId else input.texId))
                        }
                        // All stages share Media3's GL command stream. Synchronize once when
                        // returning the source, rather than stall after every generated frame.
                        processingNanos += System.nanoTime() - start
                    } catch (e: Exception) {
                        fail(e)
                        pending.clear()
                        pending.add(FrameSample(inputTimeUs, 1f))
                        continue
                    }
                }
                outputsInFlight.add(target)
                lastOutputTimeUs = sample.timeUs
                state.recordOutput(sample.timeUs, synthetic, generation)
                outputListener.onOutputFrameAvailable(target, sample.timeUs)
            }
        } finally {
            processing = false
        }
        if (pending.isEmpty() && inputInFlight !in outputsInFlight) finishInput()
    }

    private fun finishInput() {
        val input = inputInFlight ?: return
        if (saveHistory && generation == state.generation.get() && state.interpolatesNow()) {
            try {
                val start = System.nanoTime()
                draw(checkNotNull(copy), checkNotNull(previous), "uImage" to input.texId)
                GLES20.glFinish()
                processingNanos += System.nanoTime() - start
                // Retire the source pair only after every pending output has read its
                // old history. The current analysis becomes the next pair's history.
                if (currentAnalysisValid) {
                    val oldLuma = lumaPrevious
                    lumaPrevious = lumaCurrent
                    lumaCurrent = oldLuma
                    val oldPyramid = pyramidPrevious
                    pyramidPrevious = pyramidCurrent
                    pyramidCurrent = oldPyramid
                }
                previousAnalysisValid = currentAnalysisValid
                previousTime = inputTimeUs
                state.active = madeSyntheticFrame
                if (madeSyntheticFrame) {
                    state.processingMs = processingNanos / 1_000_000f
                    if (state.processingMs > inputIntervalUs / 1000f * 0.9f) slowFrames++ else slowFrames = 0
                    if (slowFrames >= 12) state.fail("overload")
                }
            } catch (e: Exception) { fail(e) }
        }
        inputInFlight = null
        saveHistory = false
        inputListener.onInputFrameProcessed(input)
        if (endRequested && outputsInFlight.isEmpty()) endStream()
        requestInput()
    }

    private fun resetHistory() {
        timing.reset()
        fixedTiming.reset()
        previousTime = null
        previousAnalysisValid = false
        currentAnalysisValid = false
    }

    private fun fail(error: Exception) {
        state.fail("gpu")
        saveHistory = false
        resetHistory()
        Log.e("NextInterpolation", "Interpolation disabled; passing original frames", error)
    }

    private fun ensureTextures(provider: GlObjectsProvider, width: Int, height: Int) {
        if (copy == null) {
            fun program(fragment: String) = GlProgram(context, "interpolation/quad.vert", "interpolation/$fragment.frag").apply {
                setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            }
            val created = mutableListOf<GlProgram>()
            try {
                listOf("copy", "luma", "flow", "midpoint", "compensation", "downsample").forEach { created.add(program(it)) }
            } catch (e: Exception) {
                created.forEach { it.delete() }
                throw e
            }
            copy = created[0]
            luma = created[1]
            flow = created[2]
            synthesize = created[3]
            compensation = created[4]
            downsample = created[5]
        }
        fun texture(w: Int, h: Int, highPrecision: Boolean = false): GlTextureInfo {
            val id = GlUtil.createTexture(w, h, highPrecision)
            return try {
                provider.createBuffersForTexture(id, w, h)
            } catch (e: Exception) {
                GlUtil.deleteTexture(id)
                throw e
            }
        }
        // Pending samples drain as output slots return, including 12 -> 60 fps.
        // Keep memory bounded at 4K/HDR instead of allocating one texture per sample.
        val required = if (state.targetFps > 0) 6 else 1
        if (previous?.width == width && previous?.height == height &&
            listOf(lumaPrevious, lumaCurrent, pyramidPrevious, pyramidCurrent, coarse, forward, backward, motionField).all { it != null }
        ) {
            while (outputTextures.size < required) outputTextures.add(texture(width, height, hdr))
            return
        }
        check(outputsInFlight.isEmpty())
        releaseTextures()
        previous = texture(width, height, hdr)
        repeat(required) { outputTextures.add(texture(width, height, hdr)) }
        val analysisWidth = minOf(width, 640)
        val analysisHeight = maxOf(16, (height.toFloat() / width * analysisWidth).roundToInt())
        lumaPrevious = texture(analysisWidth, analysisHeight, true)
        lumaCurrent = texture(analysisWidth, analysisHeight, true)
        pyramidPrevious = texture(maxOf(1, analysisWidth / 4), maxOf(1, analysisHeight / 4), true)
        pyramidCurrent = texture(maxOf(1, analysisWidth / 4), maxOf(1, analysisHeight / 4), true)
        coarse = texture(80, maxOf(8, (analysisHeight.toFloat() / analysisWidth * 80).roundToInt()), true)
        // A coarse seed is a candidate displacement, not a continuous image. Blending
        // different candidates can move the fine search away from both valid solutions.
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, checkNotNull(coarse).texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GlUtil.checkGlError()
        val flowWidth = minOf(160, analysisWidth)
        val flowHeight = maxOf(8, (analysisHeight.toFloat() / analysisWidth * flowWidth).roundToInt())
        forward = texture(flowWidth, flowHeight, true)
        backward = texture(flowWidth, flowHeight, true)
        val motionWidth = minOf(320, analysisWidth)
        motionField = texture(motionWidth, maxOf(8, (analysisHeight.toFloat() / analysisWidth * motionWidth).roundToInt()), true)
        resetHistory()
    }

    private fun estimateMotion(input: GlTextureInfo) {
        val a = checkNotNull(lumaPrevious)
        val b = checkNotNull(lumaCurrent)
        val seed = checkNotNull(coarse)
        val f = checkNotNull(forward)
        val r = checkNotNull(backward)
        val estimator = checkNotNull(flow)
        checkNotNull(luma).setFloatsUniform(
            "uFilterStep",
            floatArrayOf(
                maxOf(0f, input.width.toFloat() / a.width - 1f) / (4f * input.width),
                maxOf(0f, input.height.toFloat() / a.height - 1f) / (4f * input.height),
            ),
        )
        if (!previousAnalysisValid) draw(checkNotNull(luma), a, "uImage" to checkNotNull(previous).texId)
        draw(checkNotNull(luma), b, "uImage" to input.texId)
        val smallA = checkNotNull(pyramidPrevious)
        val smallB = checkNotNull(pyramidCurrent)
        val pyramid = checkNotNull(downsample)
        pyramid.setFloatsUniform("uPixel", floatArrayOf(1f / a.width, 1f / a.height))
        if (!previousAnalysisValid) draw(pyramid, smallA, "uImage" to a.texId)
        draw(pyramid, smallB, "uImage" to b.texId)
        currentAnalysisValid = true
        fun estimate(from: GlTextureInfo, to: GlTextureInfo, smallFrom: GlTextureInfo, smallTo: GlTextureInfo, out: GlTextureInfo) {
            estimator.setFloatUniform("uRefine", 0f)
            estimator.setFloatsUniform("uStep", floatArrayOf(1f / smallFrom.width, 1f / smallFrom.height))
            estimator.setFloatsUniform("uPatch", floatArrayOf(1f / smallFrom.width, 1f / smallFrom.height))
            draw(estimator, seed, "uFrom" to smallFrom.texId, "uTo" to smallTo.texId, "uSeed" to smallFrom.texId)
            estimator.setFloatUniform("uRefine", 1f)
            estimator.setFloatsUniform("uStep", floatArrayOf(1f / a.width, 1f / a.height))
            estimator.setFloatsUniform("uPatch", floatArrayOf(1f / a.width, 1f / a.height))
            draw(estimator, out, "uFrom" to from.texId, "uTo" to to.texId, "uSeed" to seed.texId)
        }
        estimate(a, b, smallA, smallB, f)
        estimate(b, a, smallB, smallA, r)
    }

    private fun renderFraction(input: GlTextureInfo, target: GlTextureInfo, fraction: Float) {
        val a = checkNotNull(lumaPrevious)
        val field = checkNotNull(motionField)
        val motion = checkNotNull(compensation)
        motion.setFloatsUniform("uPixel", floatArrayOf(1f / a.width, 1f / a.height))
        motion.setFloatUniform("uFraction", fraction)
        draw(motion, field, "uForward" to checkNotNull(forward).texId, "uBackward" to checkNotNull(backward).texId)
        val program = checkNotNull(synthesize)
        program.setFloatUniform("uFraction", fraction)
        draw(program, target, "uPrevious" to checkNotNull(previous).texId, "uCurrent" to input.texId, "uMotion" to field.texId)
    }

    private fun draw(program: GlProgram, target: GlTextureInfo, vararg textures: Pair<String, Int>) {
        GlUtil.focusFramebufferUsingCurrentContext(target.fboId, target.width, target.height)
        program.use()
        textures.forEachIndexed { index, (name, id) -> program.setSamplerTexIdUniform(name, id, index) }
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
    }

    override fun releaseOutputFrame(outputTexture: GlTextureInfo) {
        if (!outputsInFlight.remove(outputTexture)) return
        if (waitingForStream) {
            if (outputsInFlight.isEmpty()) endStream()
        } else if (waitingForConfiguration && outputsInFlight.isEmpty()) {
            waitingForConfiguration = false
            prepareInput(checkNotNull(objectsProvider))
        } else if (inputInFlight != null) {
            drainOutputs()
        } else {
            if (endRequested && outputsInFlight.isEmpty()) endStream()
            requestInput()
        }
    }

    override fun signalEndOfCurrentInputStream() {
        endRequested = true
        if (inputInFlight == null && outputsInFlight.isEmpty()) endStream()
    }

    private fun endStream() {
        // Media3's playback sink waits for the final source timestamp. Preserve an endpoint
        // when it falls between fixed-rate samples; do not extrapolate beyond the source duration.
        val finalTime = previousTime
        val finalTexture = previous
        if (state.targetFps > 0 && finalTime != null && finalTexture != null && lastOutputTimeUs < finalTime) {
            lastOutputTimeUs = finalTime
            outputsInFlight.add(finalTexture)
            state.recordOutput(finalTime, false, generation)
            outputListener.onOutputFrameAvailable(finalTexture, finalTime)
            return
        }
        endRequested = false
        resetHistory()
        lastOutputTimeUs = Long.MIN_VALUE
        state.active = false
        outputListener.onCurrentOutputStreamEnded()
        if (waitingForStream && !released) {
            waitingForStream = false
            prepareInput(checkNotNull(objectsProvider))
        }
    }

    override fun flush() {
        inputInFlight = null
        outputsInFlight.clear()
        waitingForConfiguration = false
        waitingForStream = false
        pending.clear()
        endRequested = false
        saveHistory = false
        resetHistory()
        lastOutputTimeUs = Long.MIN_VALUE
        credit = false
        state.reset()
        inputListener.onFlush()
        requestInput()
    }

    private fun releaseTextures() {
        (listOfNotNull(previous, lumaPrevious, lumaCurrent, pyramidPrevious, pyramidCurrent, coarse, forward, backward, motionField) + outputTextures).forEach { it.release() }
        previous = null
        outputTextures.clear()
        lumaPrevious = null
        lumaCurrent = null
        pyramidPrevious = null
        pyramidCurrent = null
        previousAnalysisValid = false
        currentAnalysisValid = false
        coarse = null
        forward = null
        backward = null
        motionField = null
    }

    override fun release() {
        released = true
        state.active = false
        try {
            releaseTextures()
            listOfNotNull(copy, luma, flow, synthesize, compensation, downsample).forEach { it.delete() }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}
