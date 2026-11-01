package dev.anilbeesetti.nextplayer

import android.graphics.Bitmap
import android.opengl.GLES20
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.DefaultGlObjectsProvider
import androidx.media3.effect.GlShaderProgram
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anilbeesetti.nextplayer.feature.player.interpolation.InterpolationState
import dev.anilbeesetti.nextplayer.feature.player.interpolation.MotionInterpolationEffect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InterpolationGpuTest {
    @Test fun translatesTextureHalfwayAndHonorsOwnershipToggleAndFlush() {
        val display = GlUtil.getDefaultEglDisplay()
        val provider = DefaultGlObjectsProvider()
        val context = provider.createEglContext(display, 3, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888)
        val surface = provider.createFocusedPlaceholderEglSurface(context, display)
        val state = InterpolationState().apply { setEnabled(true) }
        val shader = MotionInterpolationEffect(state).toGlShaderProgram(InstrumentationRegistry.getInstrumentation().targetContext, false)
        val outputs = mutableListOf<Pair<GlTextureInfo, Long>>()
        var credits = 0
        var processed = 0
        shader.setInputListener(object : GlShaderProgram.InputListener {
            override fun onReadyToAcceptInputFrame() { credits++ }
            override fun onInputFrameProcessed(inputTexture: GlTextureInfo) { processed++ }
            override fun onFlush() { credits = 0 }
        })
        shader.setOutputListener(object : GlShaderProgram.OutputListener {
            override fun onOutputFrameAvailable(outputTexture: GlTextureInfo, presentationTimeUs: Long) {
                outputs.add(outputTexture to presentationTimeUs)
            }
        })
        val width = 256
        val height = 128
        val random = java.util.Random(19)
        val pattern = IntArray(width * height) { 35 + random.nextInt(180) }
        fun makeFrame(shift: Int): GlTextureInfo {
            val pixels = IntArray(width * height) { index ->
                val y = index / width
                val x = (index % width - shift + width) % width
                val c = pattern[y * width + x]
                android.graphics.Color.rgb(c, c, c)
            }
            val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            val tex = provider.createBuffersForTexture(GlUtil.createTexture(bitmap), width, height)
            bitmap.recycle()
            return tex
        }
        val first = makeFrame(0)
        val second = makeFrame(8)
        try {
            assertEquals(1, credits)
            credits--
            shader.queueInputFrame(provider, first, 0)
            assertEquals(listOf(0L), outputs.map { it.second })
            assertEquals(0, credits)
            assertEquals(0, processed)
            shader.releaseOutputFrame(outputs.removeAt(0).first)
            assertEquals(1, credits)
            credits--
            shader.queueInputFrame(provider, second, 41_666)
            assertEquals("GPU failure: ${state.failure}", listOf(20_833L, 41_666L), outputs.map { it.second })
            val middle = outputs[0].first
            GlUtil.focusFramebufferUsingCurrentContext(middle.fboId, width, height)
            val pixels = ByteBuffer.allocateDirect(width * height * 4)
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            GlUtil.checkGlError()
            var motionError = 0.0
            var repeatedError = 0.0
            var blendedError = 0.0
            for (y in 16 until height - 16) for (x in 24 until width - 24) {
                val actual = pixels.get((y * width + x) * 4).toInt() and 255
                val expected = pattern[y * width + x - 4]
                motionError += abs(actual - expected)
                repeatedError += abs(actual - pattern[y * width + x])
                blendedError += abs(actual - (pattern[y * width + x] + pattern[y * width + x - 8]) / 2)
            }
            android.util.Log.i("NextInterpolationTest", "motionError=$motionError repeated=$repeatedError blended=$blendedError gpuMs=${state.processingMs}")
            assertTrue("Midpoint must move the texture, not repeat or dissolve it", motionError < repeatedError * 0.5 && motionError < blendedError * 0.5)
            shader.releaseOutputFrame(outputs.removeAt(0).first)
            assertEquals("Original ownership must also block the next input", 0, credits)
            assertEquals(listOf(41_666L), outputs.map { it.second })
            shader.releaseOutputFrame(outputs.removeAt(0).first)
            assertEquals(1, credits)
            state.setEnabled(false)
            credits--
            shader.queueInputFrame(provider, first, 83_333)
            assertEquals(1, outputs.size)
            assertSame(first, outputs[0].first)
            shader.releaseOutputFrame(outputs.removeAt(0).first)
            state.setEnabled(true)
            shader.flush()
            credits--
            shader.queueInputFrame(provider, second, 5_000_000)
            assertEquals(listOf(5_000_000L), outputs.map { it.second })
            assertFalse(state.active)
            shader.releaseOutputFrame(outputs.removeAt(0).first)
        } finally {
            shader.release()
            first.release()
            second.release()
            GlUtil.destroyEglSurface(display, surface)
            provider.release(display)
        }
    }

    @Test fun keepsHdrValuesAboveSdrWhite() {
        val display = GlUtil.getDefaultEglDisplay()
        val provider = DefaultGlObjectsProvider()
        val context = provider.createEglContext(display, 3, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888)
        val surface = provider.createFocusedPlaceholderEglSurface(context, display)
        val state = InterpolationState().apply { setEnabled(true) }
        val shader = MotionInterpolationEffect(state).toGlShaderProgram(InstrumentationRegistry.getInstrumentation().targetContext, true)
        val width = 64
        val height = 32
        val input = provider.createBuffersForTexture(GlUtil.createTexture(width, height, true), width, height)
        val data = ByteBuffer.allocateDirect(width * height * 16).order(ByteOrder.nativeOrder()).asFloatBuffer()
        repeat(width * height) { data.put(2f).put(1.5f).put(0.75f).put(1f) }
        data.flip()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, input.texId)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_FLOAT, data)
        val outputs = mutableListOf<Pair<GlTextureInfo, Long>>()
        shader.setInputListener(object : GlShaderProgram.InputListener {})
        shader.setOutputListener(object : GlShaderProgram.OutputListener {
            override fun onOutputFrameAvailable(outputTexture: GlTextureInfo, presentationTimeUs: Long) {
                outputs.add(outputTexture to presentationTimeUs)
            }
        })
        try {
            shader.queueInputFrame(provider, input, 0)
            shader.releaseOutputFrame(outputs.removeAt(0).first)
            shader.queueInputFrame(provider, input, 41_666)
            assertEquals("HDR processing failed: ${state.failure}", 2, outputs.size)
            val middle = outputs[0].first
            GlUtil.focusFramebufferUsingCurrentContext(middle.fboId, width, height)
            val result = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asFloatBuffer()
            GLES20.glReadPixels(20, 16, 1, 1, GLES20.GL_RGBA, GLES20.GL_FLOAT, result)
            GlUtil.checkGlError()
            assertEquals(2f, result[0], 0.02f)
            assertEquals(1.5f, result[1], 0.02f)
            assertEquals(0.75f, result[2], 0.02f)
            while (outputs.isNotEmpty()) shader.releaseOutputFrame(outputs.removeAt(0).first)
        } finally {
            shader.release()
            input.release()
            GlUtil.destroyEglSurface(display, surface)
            provider.release(display)
        }
    }
    @Test fun sixtyFpsUsesDifferentMotionPositionsAndDefersEndUntilOutputsDrain() {
        val display = GlUtil.getDefaultEglDisplay()
        val provider = DefaultGlObjectsProvider()
        val context = provider.createEglContext(display, 3, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888)
        val surface = provider.createFocusedPlaceholderEglSurface(context, display)
        val state = InterpolationState().apply { setTargetFps(60); setEnabled(true) }
        val shader = MotionInterpolationEffect(state).toGlShaderProgram(InstrumentationRegistry.getInstrumentation().targetContext, false)
        val width = 256
        val height = 128
        val random = java.util.Random(31)
        val pattern = IntArray(width * height) { 35 + random.nextInt(180) }
        fun frame(shift: Int): GlTextureInfo {
            val bitmap = Bitmap.createBitmap(IntArray(width * height) { i ->
                val c = pattern[i / width * width + (i % width - shift + width) % width]
                android.graphics.Color.rgb(c, c, c)
            }, width, height, Bitmap.Config.ARGB_8888)
            val result = provider.createBuffersForTexture(GlUtil.createTexture(bitmap), width, height)
            bitmap.recycle()
            return result
        }
        val inputs = listOf(frame(0), frame(16), frame(32))
        val pending = mutableListOf<Pair<GlTextureInfo, Long>>()
        val times = mutableListOf<Long>()
        var credits = 0
        var ended = 0
        shader.setInputListener(object : GlShaderProgram.InputListener {
            override fun onReadyToAcceptInputFrame() { credits++ }
            override fun onFlush() { credits = 0 }
        })
        shader.setOutputListener(object : GlShaderProgram.OutputListener {
            override fun onOutputFrameAvailable(outputTexture: GlTextureInfo, presentationTimeUs: Long) {
                pending.add(outputTexture to presentationTimeUs)
            }
            override fun onCurrentOutputStreamEnded() { ended++ }
        })
        fun drain() {
            while (pending.isNotEmpty()) {
                val (texture, time) = pending.removeAt(0)
                times.add(time)
                GlUtil.focusFramebufferUsingCurrentContext(texture.fboId, width, height)
                val bytes = ByteBuffer.allocateDirect(width * height * 4)
                GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
                GlUtil.checkGlError()
                val shift = time / 1_000_000.0 * 24 * 16
                var error = 0.0
                var count = 0
                for (y in 24 until height - 24) for (x in 56 until width - 40) {
                    val sampleX = x - shift
                    val left = kotlin.math.floor(sampleX).toInt()
                    val amount = sampleX - left
                    val expected = pattern[y * width + left] * (1 - amount) + pattern[y * width + left + 1] * amount
                    val actual = bytes.get((y * width + x) * 4).toInt() and 255
                    error += abs(actual - expected)
                    count++
                }
                assertTrue("Wrong motion position at $time: MAE=${error / count}, GPU=${state.failure}", error / count < 2.5)
                assertTrue("Input credits stay bounded", credits in 0..1)
                shader.releaseOutputFrame(texture)
            }
        }
        try {
            for (i in inputs.indices) {
                assertEquals(1, credits)
                credits--
                shader.queueInputFrame(provider, inputs[i], i * 1_000_000L / 24)
                if (i == 2) {
                    shader.signalEndOfCurrentInputStream()
                    assertEquals(0, ended)
                }
                if (i != 0) drain() // Keep the first output owned while the next source is processed.
            }
            val expectedTimes = listOf(0L, 16_666L, 33_333L, 50_000L, 66_666L, 83_333L)
            assertEquals(expectedTimes, times)
            assertEquals(1, ended)
            assertEquals(4L, state.generated.get())
            assertEquals(expectedTimes.size.toLong(), state.emitted.get())
            shader.flush()
            credits--
            shader.queueInputFrame(provider, inputs[0], 5_000_000)
            assertEquals(listOf(5_000_000L), pending.map { it.second })
            shader.signalEndOfCurrentInputStream()
            assertEquals(1, credits)
            credits--
            shader.queueInputFrame(provider, inputs[1], 0)
            assertEquals("Next stream waits for the old output", listOf(5_000_000L), pending.map { it.second })
            shader.releaseOutputFrame(pending.removeAt(0).first)
            assertEquals(2, ended)
            assertEquals(listOf(0L), pending.map { it.second })
            shader.releaseOutputFrame(pending.removeAt(0).first)
            credits--
            shader.queueInputFrame(provider, inputs[2], 41_666)
            shader.signalEndOfCurrentInputStream()
            val intervalSamples = listOf(16_666L, 33_333L)
            assertEquals(intervalSamples, pending.map { it.second })
            repeat(intervalSamples.size) { shader.releaseOutputFrame(pending.removeAt(0).first) }
            assertEquals("Preserve the final source endpoint required by the playback sink", listOf(41_666L), pending.map { it.second })
            shader.releaseOutputFrame(pending.removeAt(0).first)
            assertEquals(3, ended)
        } finally {
            shader.release()
            inputs.forEach { it.release() }
            GlUtil.destroyEglSurface(display, surface)
            provider.release(display)
        }
    }

}
