package dev.anilbeesetti.nextplayer

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import android.util.Log
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.DefaultGlObjectsProvider
import androidx.media3.effect.GlShaderProgram
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anilbeesetti.nextplayer.feature.player.interpolation.InterpolationState
import dev.anilbeesetti.nextplayer.feature.player.interpolation.MotionInterpolationEffect
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Image-quality assertions against known motion, independent of frame counters. */
@RunWith(AndroidJUnit4::class)
class InterpolationQualityGpuTest {
    @Test fun smallObjectsWithDifferentMotionImproveAgainstPreviousShader() = withGl { gl ->
        val width = 512
        val height = 288
        val random = java.util.Random(817)
        val texture = IntArray(width * height) { 75 + random.nextInt(160) }
        fun scene(step: Int): IntArray = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            var value = 30 + ((x / 16 + y / 16) % 2) * 12
            // Two independently moving, small textured foreground objects.
            val ax = x - (110 + step * 4)
            val ay = y - 78
            val bx = x - (320 - step * 4)
            val by = y - (176 + step * 2)
            if (ax in 0 until 32 && ay in 0 until 32) value = texture[ay * width + ax]
            if (bx in 0 until 28 && by in 0 until 36) value = texture[(by + 48) * width + bx + 64]
            Color.rgb(value, value, value)
        }
        val firstPixels = scene(0)
        val expected = scene(1)
        val first = gl.upload(firstPixels, width, height)
        val second = gl.upload(scene(2), width, height)
        val current = gl.interpolate(first, second, 0).single { it.first == 20_833L }.second
        val legacy = gl.legacyMidpoint(first, second)
        var currentError = 0.0
        var legacyError = 0.0
        var repeatedError = 0.0
        var count = 0
        for (y in 60 until 228) for (x in 88 until 372) {
            val inFirstRegion = x in 104..156 && y in 72..116
            val inSecondRegion = x in 306..354 && y in 170..222
            if (!inFirstRegion && !inSecondRegion) continue
            val index = y * width + x
            val truth = Color.red(expected[index])
            currentError += abs(current[index] - truth)
            legacyError += abs(legacy[index] - truth)
            repeatedError += abs(Color.red(firstPixels[index]) - truth)
            count++
        }
        Log.i("NextInterpolationQuality", "smallObjects pixels=$count currentMAE=${currentError / count} legacyMAE=${legacyError / count} repeatedMAE=${repeatedError / count}")
        assertTrue("Moving objects must improve over repeated originals", currentError < repeatedError * 0.75)
        assertTrue("Small-object image error must improve over the previous algorithm", currentError < legacyError * 0.9)
    }

    @Test fun sixtyFpsHasDistinctFractionalMotionPositions() = withGl { gl ->
        val width = 256
        val height = 128
        val random = java.util.Random(903)
        val pattern = IntArray(width * height) { 35 + random.nextInt(180) }
        fun frame(shift: Int) = gl.upload(IntArray(width * height) { index ->
            val c = pattern[index / width * width + (index % width - shift + width) % width]
            Color.rgb(c, c, c)
        }, width, height)
        val outputs = gl.interpolate(frame(0), frame(15), 60)
        assertEquals(listOf(0L, 16_666L, 33_333L), outputs.map { it.first })
        val signatures = mutableSetOf<Int>()
        for ((time, actual) in outputs.drop(1)) {
            val shift = time.toDouble() / 41_666 * 15
            var error = 0.0
            var count = 0
            for (y in 20 until height - 20) for (x in 40 until width - 32) {
                val sampleX = x - shift
                val left = kotlin.math.floor(sampleX).toInt()
                val fraction = sampleX - left
                val expected = pattern[y * width + left] * (1 - fraction) + pattern[y * width + left + 1] * fraction
                error += abs(actual[y * width + x] - expected)
                count++
            }
            signatures.add(actual.contentHashCode())
            Log.i("NextInterpolationQuality", "fraction timeUs=$time MAE=${error / count}")
            assertTrue("60 fps output must contain the correct intermediate position at $time", error / count < 6.0)
        }
        assertEquals("60 fps frames must not repeat the same image", 2, signatures.size)
    }

    @Test fun sceneCutRetainsAnOriginalInsteadOfDissolving() = withGl { gl ->
        val width = 128
        val height = 64
        val black = gl.upload(IntArray(width * height) { Color.BLACK }, width, height)
        val white = gl.upload(IntArray(width * height) { Color.WHITE }, width, height)
        val outputs = gl.interpolate(black, white, 60)
        for ((time, pixels) in outputs) {
            val expected = if (time <= 20_833) 0 else 255
            for (y in 8 until height - 8) for (x in 8 until width - 8) {
                assertEquals("Cut must not invent a blended frame at $time", expected, pixels[y * width + x])
            }
        }
    }

    @Test fun movingObjectDoesNotBleedThroughAnOccluder() = withGl { gl ->
        val width = 256
        val height = 128
        val random = java.util.Random(805)
        val pattern = IntArray(32 * 48) { 100 + random.nextInt(155) }
        fun frame(shift: Int) = gl.upload(IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val localX = x - 100 - shift
            val localY = y - 40
            var value = 24
            if (localX in 0 until 32 && localY in 0 until 48) value = pattern[localY * 32 + localX]
            // This foreground bar occludes a moving object in both source frames.
            if (x in 124 until 144) value = 64
            Color.rgb(value, value, value)
        }, width, height)
        val outputs = gl.interpolate(frame(0), frame(16), 60)
        for ((time, pixels) in outputs.drop(1)) {
            var largestError = 0
            for (y in 44 until 84) for (x in 128 until 140) {
                largestError = max(largestError, abs(pixels[y * width + x] - 64))
            }
            assertTrue("Occluded motion must not appear through the foreground at $time (error=$largestError)", largestError <= 2)
        }
    }

    @Test fun cachedAnalysisMatchesFreshPairsAcrossFlushAndSizeChange() = withGl { gl ->
        fun frames(width: Int, height: Int, seed: Long): List<GlTextureInfo> {
            val random = java.util.Random(seed)
            val pattern = IntArray(width * height) { 35 + random.nextInt(180) }
            return (0..3).map { step ->
                gl.upload(IntArray(width * height) { index ->
                    val value = pattern[index / width * width + (index % width - step * 8 + width) % width]
                    Color.rgb(value, value, value)
                }, width, height)
            }
        }
        val first = frames(256, 128, 619)
        val resized = frames(192, 96, 620)
        val continuous = gl.sequence(first, first.indices.map { it * 41_666L }, 0)
        for (index in 2..3) {
            val expected = gl.interpolate(first[index - 1], first[index], 0).single { it.first == 20_833L }.second
            val actual = continuous.single { it.first == index * 41_666L - 20_833 }.second
            assertArrayEquals("Cached analysis changed pixels for source $index", expected, actual)
        }
        // A seek may reuse every texture allocation, but never its pre-seek analysis.
        val afterSeek = gl.sequence(first + first, (0..7).map { it * 41_666L }, 0, setOf(4))
        val seekExpected = gl.interpolate(first[0], first[1], 0).single { it.first == 20_833L }.second
        assertArrayEquals("Flush must invalidate the pre-seek analysis", seekExpected, afterSeek.single { it.first == 187_497L }.second)
        val afterGap = gl.sequence(first, listOf(0L, 41_666L, 500_000L, 541_666L), 0)
        val gapExpected = gl.interpolate(first[2], first[3], 0).single { it.first == 20_833L }.second
        assertArrayEquals("A source without interpolation must invalidate older analysis", gapExpected, afterGap.single { it.first == 520_833L }.second)
        // A new size invalidates both full-color history and the smaller analysis textures.
        val afterResize = gl.sequence(first + resized, (0..7).map { it * 41_666L }, 0)
        val resizedExpected = gl.interpolate(resized[0], resized[1], 0).single { it.first == 20_833L }.second
        assertArrayEquals("Size changes must rebuild the analysis", resizedExpected, afterResize.single { it.first == 187_497L }.second)
    }

    @Test fun lowResolutionConfidenceBoundaryDoesNotDrawHardStairSteps() = withGl { gl ->
        val width = 1024
        val height = 512
        fun frame(shift: Int) = gl.upload(IntArray(width * height) { index ->
            val value = (48 + (index % width - shift) / 6).coerceIn(0, 255)
            Color.rgb(value, value, value)
        }, width, height)
        val first = frame(0)
        val second = frame(48)
        val legacy = gl.confidenceBoundary(first, second, true)
        val current = gl.confidenceBoundary(first, second, false)
        fun seam(pixels: IntArray): Int {
            var jump = 0
            for (y in 100 until height - 100) for (x in 100 until width - 100) {
                // Remove the source gradient; measure only the artificial mask seam.
                val delta = pixels[y * width + x + 1] - pixels[y * width + x]
                jump = max(jump, abs(delta))
            }
            return jump
        }
        val before = seam(legacy)
        val after = seam(current)
        Log.i("NextInterpolationQuality", "confidenceBoundary maxAdjacentJump before=$before after=$after")
        assertTrue("Baseline must expose the coarse hard-mask seam", before >= 4)
        assertTrue("Confidence upsampling must remove the hard stair-step seam ($before -> $after)", after <= 2 && after < before)
    }

    @Test fun highResolutionAnalysisFiltersFineLinesBeforeMotionEstimation() = withGl { gl ->
        val input = gl.upload(IntArray(1920 * 384) { index ->
            if ((index % 1920 / 2) % 2 == 0) Color.BLACK else Color.WHITE
        }, 1920, 384)
        val unfiltered = gl.lumaReduction(input, false)
        val filtered = gl.lumaReduction(input, true)
        fun contrast(pixels: IntArray) = pixels.maxOrNull()!! - pixels.minOrNull()!!
        val before = contrast(unfiltered)
        val after = contrast(filtered)
        Log.i("NextInterpolationQuality", "analysisAliasing contrast before=$before after=$after")
        assertTrue("Baseline must expose aliased fine lines", before > 200)
        assertTrue("Analysis prefilter must suppress the false low-resolution pattern", after < before / 3)
    }

    private fun withGl(test: (GlFixture) -> Unit) {
        val fixture = GlFixture()
        try { test(fixture) } finally { fixture.close() }
    }

    private class GlFixture {
        private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        private val display = GlUtil.getDefaultEglDisplay()
        private val provider = DefaultGlObjectsProvider()
        private val context = provider.createEglContext(display, 3, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888)
        private val surface = provider.createFocusedPlaceholderEglSurface(context, display)
        private val textures = mutableListOf<GlTextureInfo>()
        private val programs = mutableListOf<GlProgram>()

        fun upload(pixels: IntArray, width: Int, height: Int): GlTextureInfo {
            val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            return try {
                provider.createBuffersForTexture(GlUtil.createTexture(bitmap), width, height).also(textures::add)
            } finally { bitmap.recycle() }
        }

        fun interpolate(first: GlTextureInfo, second: GlTextureInfo, rate: Int) =
            sequence(listOf(first, second), listOf(0L, 41_666L), rate)

        fun sequence(inputs: List<GlTextureInfo>, times: List<Long>, rate: Int, flushBefore: Set<Int> = emptySet()): List<Pair<Long, IntArray>> {
            val state = InterpolationState().apply { setTargetFps(rate); setEnabled(true) }
            val shader = MotionInterpolationEffect(state).toGlShaderProgram(appContext, false)
            val pending = mutableListOf<Pair<GlTextureInfo, Long>>()
            val result = mutableListOf<Pair<Long, IntArray>>()
            shader.setInputListener(object : GlShaderProgram.InputListener {})
            shader.setOutputListener(object : GlShaderProgram.OutputListener {
                override fun onOutputFrameAvailable(outputTexture: GlTextureInfo, presentationTimeUs: Long) {
                    pending.add(outputTexture to presentationTimeUs)
                }
            })
            fun drain() {
                while (pending.isNotEmpty()) {
                    val (texture, time) = pending.removeAt(0)
                    result.add(time to read(texture))
                    shader.releaseOutputFrame(texture)
                }
            }
            try {
                inputs.forEachIndexed { index, input ->
                    if (index in flushBefore) shader.flush()
                    shader.queueInputFrame(provider, input, times[index])
                    drain()
                }
                assertEquals("Interpolation GPU failed", "", state.failure)
            } finally { shader.release() }
            return result
        }

        fun lumaReduction(input: GlTextureInfo, filtered: Boolean): IntArray {
            val output = texture(320, 64, false)
            val program = assetProgram("luma")
            program.setFloatsUniform("uFilterStep", if (filtered) floatArrayOf(5f / (4f * 1920), 5f / (4f * 384)) else floatArrayOf(0f, 0f))
            draw(program, output, "uImage" to input.texId)
            return read(output)
        }

        fun confidenceBoundary(first: GlTextureInfo, second: GlTextureInfo, legacy: Boolean): IntArray {
            val field = texture(32, 16)
            val output = texture(first.width, first.height, false)
            val createField = sourceProgram("""
                precision highp float;
                varying vec2 vUv;
                void main() {
                    float confidence = step(vUv.x + vUv.y * 0.6, 0.8);
                    vec2 displacement = vec2(48.0 / 1024.0, 0.0);
                    gl_FragColor = vec4(displacement * ${if (legacy) "1.0" else "confidence"}, confidence, 1.0);
                }
            """.trimIndent())
            draw(createField, field)
            val midpoint = if (legacy) sourceProgram(LEGACY_MIDPOINT) else assetProgram("midpoint")
            midpoint.setFloatUniform("uFraction", 0.5f)
            draw(midpoint, output, "uPrevious" to first.texId, "uCurrent" to second.texId, "uMotion" to field.texId)
            return read(output)
        }

        /** Frozen 0.19.0-haoke.1 algorithm, used only as an A/B image-quality baseline. */
        fun legacyMidpoint(first: GlTextureInfo, second: GlTextureInfo): IntArray {
            val analysisWidth = min(first.width, 512)
            val analysisHeight = max(16, (first.height.toFloat() / first.width * analysisWidth).roundToInt())
            val a = texture(analysisWidth, analysisHeight)
            val b = texture(analysisWidth, analysisHeight)
            val seed = texture(64, max(8, analysisHeight / 8))
            val flowHeight = max(8, (analysisHeight.toFloat() / analysisWidth * 96).roundToInt())
            val forward = texture(96, flowHeight)
            val backward = texture(96, flowHeight)
            val field = texture(min(256, analysisWidth), max(8, analysisHeight / 2))
            val output = texture(first.width, first.height, false)
            val luma = assetProgram("luma")
            val flow = sourceProgram(LEGACY_FLOW)
            val compensation = sourceProgram(LEGACY_COMPENSATION)
            val midpoint = sourceProgram(LEGACY_MIDPOINT)
            draw(luma, a, "uImage" to first.texId)
            draw(luma, b, "uImage" to second.texId)
            fun estimate(from: GlTextureInfo, to: GlTextureInfo, result: GlTextureInfo) {
                flow.setFloatUniform("uRefine", 0f)
                flow.setFloatsUniform("uStep", floatArrayOf(4f / a.width, 4f / a.height))
                flow.setFloatsUniform("uPatch", floatArrayOf(3f / a.width, 3f / a.height))
                draw(flow, seed, "uFrom" to from.texId, "uTo" to to.texId, "uSeed" to from.texId)
                flow.setFloatUniform("uRefine", 1f)
                flow.setFloatsUniform("uStep", floatArrayOf(1f / a.width, 1f / a.height))
                flow.setFloatsUniform("uPatch", floatArrayOf(2f / a.width, 2f / a.height))
                draw(flow, result, "uFrom" to from.texId, "uTo" to to.texId, "uSeed" to seed.texId)
            }
            estimate(a, b, forward)
            estimate(b, a, backward)
            compensation.setFloatsUniform("uPixel", floatArrayOf(1f / a.width, 1f / a.height))
            compensation.setFloatUniform("uFraction", 0.5f)
            draw(compensation, field, "uForward" to forward.texId, "uBackward" to backward.texId)
            midpoint.setFloatUniform("uFraction", 0.5f)
            draw(midpoint, output, "uPrevious" to first.texId, "uCurrent" to second.texId, "uMotion" to field.texId)
            return read(output)
        }

        private fun texture(width: Int, height: Int, highPrecision: Boolean = true) =
            provider.createBuffersForTexture(GlUtil.createTexture(width, height, highPrecision), width, height).also(textures::add)

        private fun assetProgram(name: String) = GlProgram(appContext, "interpolation/quad.vert", "interpolation/$name.frag").also(::configure)

        private fun sourceProgram(fragment: String): GlProgram {
            val vertex = appContext.assets.open("interpolation/quad.vert").bufferedReader().use { it.readText() }
            return GlProgram(vertex, fragment).also(::configure)
        }

        private fun configure(program: GlProgram) {
            programs.add(program)
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
        }

        private fun draw(program: GlProgram, target: GlTextureInfo, vararg inputs: Pair<String, Int>) {
            GlUtil.focusFramebufferUsingCurrentContext(target.fboId, target.width, target.height)
            program.use()
            inputs.forEachIndexed { index, (name, id) -> program.setSamplerTexIdUniform(name, id, index) }
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        }

        private fun read(texture: GlTextureInfo): IntArray {
            GlUtil.focusFramebufferUsingCurrentContext(texture.fboId, texture.width, texture.height)
            val data = ByteBuffer.allocateDirect(texture.width * texture.height * 4)
            GLES20.glReadPixels(0, 0, texture.width, texture.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, data)
            GlUtil.checkGlError()
            return IntArray(texture.width * texture.height) { data.get(it * 4).toInt() and 255 }
        }

        fun close() {
            programs.forEach { it.delete() }
            textures.forEach { it.release() }
            GlUtil.destroyEglSurface(display, surface)
            provider.release(display)
        }
    }

    private companion object {
        val LEGACY_MIDPOINT = """
            precision highp float;
            uniform sampler2D uPrevious;
            uniform sampler2D uCurrent;
            uniform sampler2D uMotion;
            uniform float uFraction;
            varying vec2 vUv;
            void main() {
                vec4 motion = texture2D(uMotion, vUv);
                vec2 p = vUv - uFraction * motion.rg;
                vec2 q = vUv + (1.0 - uFraction) * motion.rg;
                bool valid = motion.b > 0.99;
                valid = valid && all(greaterThanEqual(p, vec2(0.0))) && all(lessThanEqual(p, vec2(1.0)));
                valid = valid && all(greaterThanEqual(q, vec2(0.0))) && all(lessThanEqual(q, vec2(1.0)));
                // Occlusions and scene cuts retain an original instead of inventing blended edges.
                gl_FragColor = valid
                    ? mix(texture2D(uPrevious, p), texture2D(uCurrent, q), uFraction)
                    : (uFraction <= 0.5 ? texture2D(uPrevious, vUv) : texture2D(uCurrent, vUv));
            }
        """.trimIndent()

        val LEGACY_FLOW = """
            precision highp float;
            uniform sampler2D uFrom;
            uniform sampler2D uTo;
            uniform sampler2D uSeed;
            uniform vec2 uStep;
            uniform vec2 uPatch;
            uniform float uRefine;
            varying vec2 vUv;
            float cost(vec2 d) {
                float result = 0.0;
                for (int y = -1; y <= 1; ++y) {
                    for (int x = -1; x <= 1; ++x) {
                        vec2 p = vUv + vec2(float(x), float(y)) * uPatch;
                        result += abs(texture2D(uFrom, p).r - texture2D(uTo, p + d).r);
                    }
                }
                return result / 9.0;
            }
            void main() {
                vec2 seed = uRefine > 0.5 ? texture2D(uSeed, vUv).rg : vec2(0.0);
                vec2 best = vec2(0.0);
                float zero = cost(best);
                float bestCost = zero;
                for (int y = -4; y <= 4; ++y) {
                    for (int x = -4; x <= 4; ++x) {
                        if (uRefine > 0.5 && (x < -2 || x > 2 || y < -2 || y > 2)) continue;
                        vec2 d = seed + vec2(float(x), float(y)) * uStep;
                        float c = cost(d);
                        if (c + 0.0002 * length(d / uStep) < bestCost) {
                            bestCost = c;
                            best = d;
                        }
                    }
                }
                gl_FragColor = vec4(best, bestCost, zero);
            }
        """.trimIndent()
        val LEGACY_COMPENSATION = """
            precision highp float;
            uniform sampler2D uForward;
            uniform sampler2D uBackward;
            uniform vec2 uPixel;
            uniform float uFraction;
            varying vec2 vUv;
            void main() {
                vec2 f = texture2D(uForward, vUv).rg;
                vec2 b = texture2D(uBackward, vUv).rg;
                vec4 forward = texture2D(uForward, vUv - uFraction * f);
                vec4 backward = texture2D(uBackward, vUv - (1.0 - uFraction) * b);
                float consistency = length((forward.rg + backward.rg) / uPixel);
                float valid = max(forward.b, backward.b) < 0.075 && consistency < 4.0 ? 1.0 : 0.0;
                gl_FragColor = vec4(0.5 * (forward.rg - backward.rg), valid, 1.0);
            }
        """.trimIndent()
    }
}
