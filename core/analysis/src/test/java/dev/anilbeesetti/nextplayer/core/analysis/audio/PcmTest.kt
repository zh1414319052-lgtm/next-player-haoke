package dev.anilbeesetti.nextplayer.core.analysis.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmTest {

    @Test
    fun `float PCM scales signed amplitudes and saturates`() {
        val input = floatBytes(-2f, -1f, -0.5f, 0f, 0.5f, 1f, 2f)

        assertArrayEquals(
            shortArrayOf(-32768, -32768, -16384, 0, 16384, 32767, 32767),
            Pcm.floatToShort(input),
        )
    }

    @Test
    fun `NaN is silence and infinities saturate`() {
        val input = floatBytes(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -0f)

        assertArrayEquals(shortArrayOf(0, -32768, 32767, 0), Pcm.floatToShort(input))
    }

    @Test
    fun `float PCM uses little endian bytes`() {
        // Explicit IEEE-754 byte sequences rather than reusing the conversion helper.
        val input = byteArrayOf(0x00, 0x00, 0x00, 0x3F, 0x00, 0x00, 0x00, 0xBF.toByte())

        assertArrayEquals(shortArrayOf(16384, -16384), Pcm.floatToShort(input))
    }

    @Test
    fun `mono decoder honors byte range and preserves caller buffer state`() {
        val input = ByteBuffer.wrap(byteArrayOf(0x55, 0x66, 0x34, 0x12, 0xCC.toByte(), 0xED.toByte(), 0x77))
            .order(ByteOrder.BIG_ENDIAN)
        input.position(2)
        input.limit(6)
        val output = shortArrayOf(99, 0, 0, 88)

        val frames = Pcm.decodeToMono(input, channels = 1, floatPcm = false, destination = output, destinationOffset = 1)

        assertEquals(2, frames)
        assertArrayEquals(shortArrayOf(99, 4660, -4660, 88), output)
        assertEquals(2, input.position())
        assertEquals(6, input.limit())
        assertEquals(ByteOrder.BIG_ENDIAN, input.order())
    }

    @Test
    fun `multichannel PCM is averaged into one sample per frame`() {
        val input = shortBytes(
            1000, 2000, 3000, 4000, 5000, 6000,
            -1000, -2000, -3000, -4000, -5000, -6000,
        )
        val output = ShortArray(2)

        val frames = Pcm.decodeToMono(input, channels = 6, floatPcm = false, destination = output)

        assertEquals(2, frames)
        assertArrayEquals(shortArrayOf(3500, -3500), output)
    }

    @Test
    fun `multichannel average does not overflow at signed extremes`() {
        val input = shortBytes(32767, 32767, -32768, -32768, 32767, -32768)
        val output = ShortArray(3)

        Pcm.decodeToMono(input, channels = 2, floatPcm = false, destination = output)

        assertArrayEquals(shortArrayOf(32767, -32768, 0), output)
    }

    @Test
    fun `float multichannel decode scales before averaging`() {
        val input = ByteBuffer.wrap(floatBytes(0.5f, 0.25f, -0.5f, -0.25f, Float.NaN, 0.5f))
        val output = ShortArray(3)

        val frames = Pcm.decodeToMono(input, channels = 2, floatPcm = true, destination = output)

        assertEquals(3, frames)
        assertArrayEquals(shortArrayOf(12288, -12288, 8192), output)
    }

    @Test
    fun `mono decoder consumes only complete frames across sequential buffers`() {
        val output = ShortArray(4)
        val first = Pcm.decodeToMono(shortBytes(100, 300, 200, 400), 2, false, output)
        val second = Pcm.decodeToMono(shortBytes(-100, -300, -200, -400), 2, false, output, first)

        assertEquals(2, first)
        assertEquals(2, second)
        assertArrayEquals(shortArrayOf(200, 300, -200, -300), output)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `partial float sample is rejected`() {
        Pcm.floatToShort(byteArrayOf(0, 0, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `partial multichannel frame is rejected`() {
        Pcm.decodeToMono(shortBytes(1, 2, 3), channels = 2, floatPcm = false, destination = ShortArray(2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `output capacity is checked before decoding`() {
        Pcm.decodeToMono(shortBytes(1, 2), channels = 1, floatPcm = false, destination = ShortArray(1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero channels are rejected`() {
        Pcm.decodeToMono(shortBytes(1), channels = 0, floatPcm = false, destination = ShortArray(1))
    }

    @Test
    fun `legacy stereo helper keeps its channel contract`() {
        val (output, channels) = Pcm.downmixToStereo(shortArrayOf(100, 200, 300, 400, 500, 600), 6)

        assertEquals(2, channels)
        assertArrayEquals(shortArrayOf(300, 400), output)
    }

    private fun floatBytes(vararg values: Float): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (value in values) buffer.putFloat(value)
        return buffer.array()
    }

    private fun shortBytes(vararg values: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (value in values) buffer.putShort(value.toShort())
        buffer.flip()
        return buffer
    }
}
