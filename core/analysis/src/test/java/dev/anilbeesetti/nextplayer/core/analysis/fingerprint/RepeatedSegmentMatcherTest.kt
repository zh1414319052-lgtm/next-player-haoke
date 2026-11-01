package dev.anilbeesetti.nextplayer.core.analysis.fingerprint

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatedSegmentMatcherTest {

    private val itemMs = 124L

    /** Fingerprint item noise makes boundaries drift by a few items; real-world
     * acceptance is tolerance based, so the tests assert with a small slack. */
    private val boundarySlackMs = 8 * itemMs

    private fun fingerprint(values: IntArray, windowStartMs: Long = 0) =
        AudioFingerprint(values, itemMs, windowStartMs)

    private fun randomValues(size: Int, seed: Long): IntArray {
        val rng = Random(seed)
        return IntArray(size) { rng.nextInt() }
    }

    private fun assertNear(expected: Long, actual: Long, message: String) {
        assertTrue(
            "$message expected ~$expected (±$boundarySlackMs) but was $actual",
            abs(expected - actual) <= boundarySlackMs,
        )
    }

    @Test
    fun `identical windows produce a full-length run`() {
        val values = randomValues(500, seed = 1)
        val target = fingerprint(values)
        val reference = fingerprint(values, windowStartMs = 5_000)

        val result = RepeatedSegmentMatcher.findLongestRepeat(
            target = target,
            reference = reference,
            minRunItems = 60,
        )

        assertNotNull(result)
        assertEquals(0L, result!!.targetStartMs)
        assertEquals(500 * itemMs, result.targetEndMs)
        assertEquals(5_000L, result.referenceStartMs)
        assertTrue("similarity ${result.similarityPct} should be near-perfect", result.similarityPct >= 95)
    }

    @Test
    fun `shared opening block is found with per-episode cold open preserved`() {
        val rng = Random(seed = 7)
        val coldOpenTarget = IntArray(200) { rng.nextInt() } // ~25s
        val coldOpenReference = IntArray(120) { rng.nextInt() } // ~15s
        val opening = IntArray(720) { rng.nextInt() } // ~90s shared OP
        val restTarget = IntArray(1_000) { rng.nextInt() }
        val restReference = IntArray(1_000) { rng.nextInt() }

        val target = fingerprint(coldOpenTarget + opening + restTarget)
        val reference = fingerprint(coldOpenReference + opening + restReference)

        val result = RepeatedSegmentMatcher.findLongestRepeat(
            target = target,
            reference = reference,
            minRunItems = 60,
        )

        assertNotNull(result)
        // Shared block spans items 200..920 in the target and 120..840 in the
        // reference (shorter cold open -> negative offset). Boundaries may drift
        // a few items due to accidental fingerprint matches around the block.
        assertNear(200 * itemMs, result!!.targetStartMs, "targetStart")
        assertNear(920 * itemMs, result.targetEndMs, "targetEnd")
        assertNear(120 * itemMs, result.referenceStartMs, "referenceStart")
        assertTrue("similarity ${result.similarityPct} should be near-perfect", result.similarityPct >= 95)
    }

    @Test
    fun `shared opening block longer in the reference is found at positive offset`() {
        val rng = Random(seed = 71)
        val coldOpenTarget = IntArray(80) { rng.nextInt() }
        val coldOpenReference = IntArray(240) { rng.nextInt() }
        val opening = IntArray(600) { rng.nextInt() }
        val restTarget = IntArray(900) { rng.nextInt() }
        val restReference = IntArray(900) { rng.nextInt() }

        val target = fingerprint(coldOpenTarget + opening + restTarget)
        val reference = fingerprint(coldOpenReference + opening + restReference)

        val result = RepeatedSegmentMatcher.findLongestRepeat(
            target = target,
            reference = reference,
            minRunItems = 60,
        )

        assertNotNull(result)
        assertNear(80 * itemMs, result!!.targetStartMs, "targetStart")
        assertNear(680 * itemMs, result.targetEndMs, "targetEnd")
        assertNear(240 * itemMs, result.referenceStartMs, "referenceStart")
    }

    @Test
    fun `no shared content yields no segment`() {
        val target = fingerprint(randomValues(400, seed = 11))
        val reference = fingerprint(randomValues(400, seed = 12))

        val result = RepeatedSegmentMatcher.findLongestRepeat(
            target = target,
            reference = reference,
            minRunItems = 60,
        )

        assertNull(result)
    }

    @Test
    fun `short repeated logo below the minimum run is ignored`() {
        val rng = Random(seed = 21)
        val logo = IntArray(30) { rng.nextInt() } // < minRunItems
        val target = fingerprint(logo + IntArray(400) { rng.nextInt() })
        val reference = fingerprint(logo + IntArray(400) { rng.nextInt() })

        val result = RepeatedSegmentMatcher.findLongestRepeat(
            target = target,
            reference = reference,
            minRunItems = 60,
        )

        assertNull(result)
    }

    @Test
    fun `moderate bit noise still matches the whole block`() {
        val rng = Random(seed = 31)
        val opening = IntArray(600) { rng.nextInt() }
        // Flip 15 bits on every 17th item: distances above the per-item threshold
        // are bridged by the gap tolerance instead of splitting the run.
        val noisy = opening.mapIndexed { index, value ->
            if (index % 17 == 0) value xor 0x7FFF else value
        }.toIntArray()

        val target = fingerprint(IntArray(100) { rng.nextInt() } + noisy)
        val reference = fingerprint(opening)

        val result = RepeatedSegmentMatcher.findLongestRepeat(
            target = target,
            reference = reference,
            minRunItems = 60,
        )

        assertNotNull(result)
        assertNear(100 * itemMs, result!!.targetStartMs, "targetStart")
        assertNear(700 * itemMs, result.targetEndMs, "targetEnd")
        assertTrue("similarity ${result.similarityPct} should stay high", result.similarityPct in 90..96)
    }

    @Test
    fun `heavy bit noise drops below the similarity floor and is rejected`() {
        val rng = Random(seed = 32)
        val opening = IntArray(600) { rng.nextInt() }
        // Flip on every 5th item -> ~80% similarity, below the 85% floor.
        val noisy = opening.mapIndexed { index, value ->
            if (index % 5 == 0) value xor 0x7FFF else value
        }.toIntArray()

        val target = fingerprint(IntArray(100) { rng.nextInt() } + noisy)
        val reference = fingerprint(opening)

        val result = RepeatedSegmentMatcher.findLongestRepeat(
            target = target,
            reference = reference,
            minRunItems = 60,
        )

        assertNull(result)
    }

    @Test
    fun `repeated block near the end of the reference is reported for tails`() {
        val rng = Random(seed = 41)
        val credits = IntArray(480) { rng.nextInt() } // ~60s ED block
        val target = fingerprint(IntArray(800) { rng.nextInt() } + credits)
        val reference = fingerprint(credits)

        val result = RepeatedSegmentMatcher.findLongestRepeat(
            target = target,
            reference = reference,
            minRunItems = 60,
        )

        assertNotNull(result)
        assertNear(800 * itemMs, result!!.targetStartMs, "targetStart")
        assertNear(1_280 * itemMs, result.targetEndMs, "targetEnd")
        assertEquals(0L, result.referenceStartMs)
    }

    @Test
    fun `windows shorter than the minimum run are rejected`() {
        val values = randomValues(50, seed = 51)
        val result = RepeatedSegmentMatcher.findLongestRepeat(
            target = fingerprint(values),
            reference = fingerprint(values),
            minRunItems = 60,
        )

        assertNull(result)
    }
}
