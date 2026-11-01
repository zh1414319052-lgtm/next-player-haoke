package dev.anilbeesetti.nextplayer

import android.graphics.Bitmap
import android.graphics.Color
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InterpolationBackpressureGpuTest {
    private data class HeldFrame(
        val texture: GlTextureInfo,
        val timeUs: Long,
        val pixel: List<Int>,
    )

    @Test
    fun twelveToSixtyWaitsForSixOwnedTexturesAndEmitsEndPointOnce() {
        val display = GlUtil.getDefaultEglDisplay()
        val provider = DefaultGlObjectsProvider()
        val context = provider.createEglContext(display, 3, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888)
        val surface = provider.createFocusedPlaceholderEglSurface(context, display)
        val state = InterpolationState().apply {
            setTargetFps(60)
            setEnabled(true)
        }
        val shader = MotionInterpolationEffect(state).toGlShaderProgram(
            InstrumentationRegistry.getInstrumentation().targetContext,
            false,
        )
        val width = 64
        val height = 32
        fun frame(gray: Int): GlTextureInfo {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(gray, gray, gray))
            val texture = provider.createBuffersForTexture(GlUtil.createTexture(bitmap), width, height)
            bitmap.recycle()
            return texture
        }
        fun pixel(texture: GlTextureInfo): List<Int> {
            GlUtil.focusFramebufferUsingCurrentContext(texture.fboId, width, height)
            val bytes = ByteBuffer.allocateDirect(4)
            GLES20.glReadPixels(width / 2, height / 2, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
            GlUtil.checkGlError()
            return (0..3).map { bytes[it].toInt() and 255 }
        }
        val inputs = listOf(frame(24), frame(104), frame(200), frame(64))
        val ownedInputs = mutableSetOf<GlTextureInfo>()
        val held = mutableListOf<HeldFrame>()
        val emittedTimes = mutableListOf<Long>()
        val poolIds = mutableSetOf<Int>()
        var credits = 0
        var processed = 0
        var ended = 0
        shader.setInputListener(object : GlShaderProgram.InputListener {
            override fun onReadyToAcceptInputFrame() {
                credits++
                assertEquals("Only one input credit may be outstanding", 1, credits)
            }

            override fun onInputFrameProcessed(inputTexture: GlTextureInfo) {
                assertTrue("Only a queued input can be returned, exactly once", ownedInputs.remove(inputTexture))
                processed++
            }
        })
        shader.setOutputListener(object : GlShaderProgram.OutputListener {
            override fun onOutputFrameAvailable(outputTexture: GlTextureInfo, presentationTimeUs: Long) {
                assertFalse("An output texture cannot be reused before its owner releases it", held.any { it.texture.texId == outputTexture.texId })
                held.add(HeldFrame(outputTexture, presentationTimeUs, pixel(outputTexture)))
                emittedTimes.add(presentationTimeUs)
                if (presentationTimeUs < 245_000) poolIds.add(outputTexture.texId)
                assertTrue("The output pool stays bounded while downstream is stalled", held.size <= 6)
            }

            override fun onCurrentOutputStreamEnded() {
                ended++
            }
        })
        fun assertHeldPixelsUnchanged() {
            for (output in held) {
                assertEquals("Owned output at ${output.timeUs} was overwritten", output.pixel, pixel(output.texture))
            }
        }
        fun queue(index: Int, timeUs: Long) {
            assertEquals(1, credits)
            credits--
            assertTrue(ownedInputs.add(inputs[index]))
            shader.queueInputFrame(provider, inputs[index], timeUs)
            assertEquals("Unexpected GPU fallback", "", state.failure)
            assertHeldPixelsUnchanged()
        }
        fun releaseFirst() {
            assertHeldPixelsUnchanged()
            val released = held.removeAt(0)
            shader.releaseOutputFrame(released.texture)
            assertHeldPixelsUnchanged()
            assertEquals("Unexpected GPU fallback", "", state.failure)
        }
        try {
            queue(0, 0)
            assertEquals(1, processed)
            assertEquals(1, held.size)
            // Returned source memory is reusable, but neither its output nor the saved history
            // may still alias it. Keep the first displayed output owned throughout this overwrite.
            GlUtil.focusFramebufferUsingCurrentContext(inputs[0].fboId, width, height)
            GLES20.glClearColor(240f / 255, 240f / 255, 240f / 255, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            assertHeldPixelsUnchanged()
            assertEquals(24, held.single().pixel[0])

            queue(1, 83_333)
            assertEquals(6, held.size)
            assertEquals(0, credits)
            assertEquals("Five interval samples fill the remaining output slots", 2, processed)
            assertTrue(ownedInputs.isEmpty())
            releaseFirst()
            queue(2, 166_666)
            assertEquals(6, held.size)
            assertEquals(0, credits)
            assertEquals("The source must stay owned while four samples await an output slot", 2, processed)
            assertTrue(ownedInputs.contains(inputs[2]))
            val firstPoolTexture = held.first().texture
            releaseFirst()
            assertSame("Only the released slot can supply the next sample", firstPoolTexture, held.last().texture)
            assertEquals(116_666L, held.last().timeUs)
            assertEquals(2, processed)
            repeat(3) { releaseFirst() }
            assertEquals("All five 12 fps interval samples must drain before returning the input", 3, processed)
            assertTrue(ownedInputs.isEmpty())
            assertEquals(166_666L, emittedTimes.last())
            assertEquals(6, held.size)
            assertEquals("A full output pool must still block the next input", 0, credits)

            releaseFirst()
            queue(3, 245_000)
            shader.signalEndOfCurrentInputStream()
            assertEquals(0, ended)
            assertEquals(3, processed)
            repeat(3) { releaseFirst() }
            assertEquals(4, processed)
            assertEquals(0, credits)
            assertEquals(0, ended)
            assertEquals("Every regular sample reuses the same six output slots", 6, poolIds.size)

            // Releasing the final regular output must expose exactly one final source endpoint,
            // and completion must wait until downstream also releases that endpoint.
            repeat(6) { releaseFirst() }
            assertEquals(listOf(245_000L), held.map { it.timeUs })
            assertEquals(0, ended)
            assertEquals(0, credits)
            releaseFirst()
            assertEquals(1, ended)
            assertTrue(held.isEmpty())
            assertTrue(ownedInputs.isEmpty())
            assertEquals((0L..14L).map { it * 1_000_000 / 60 } + 245_000L, emittedTimes)
            assertEquals(16L, state.emitted.get())
            assertEquals(1, emittedTimes.count { it == 245_000L })

            // Disabling interpolation restores original-texture ownership: the source cannot be
            // returned to the decoder while downstream still owns its passthrough output.
            state.setEnabled(false)
            queue(0, 1_000_000)
            assertSame(inputs[0], held.single().texture)
            assertEquals(4, processed)
            assertEquals(0, credits)
            assertTrue(ownedInputs.contains(inputs[0]))
            releaseFirst()
            assertEquals(5, processed)
            assertTrue(ownedInputs.isEmpty())
            assertEquals(1, credits)
        } finally {
            shader.release()
            inputs.forEach { it.release() }
            GlUtil.destroyEglSurface(display, surface)
            provider.release(display)
        }
    }
}
