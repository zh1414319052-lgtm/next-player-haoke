package dev.anilbeesetti.nextplayer.core.analysis

import android.content.Context
import android.net.Uri
import dev.anilbeesetti.nextplayer.core.analysis.audio.AudioWindowReader
import dev.anilbeesetti.nextplayer.core.analysis.fingerprint.AudioFingerprint
import dev.anilbeesetti.nextplayer.core.analysis.fingerprint.FingerprintCodec
import dev.anilbeesetti.nextplayer.core.analysis.fingerprint.FingerprintEngine
import dev.anilbeesetti.nextplayer.core.analysis.fingerprint.RepeatedSegment
import dev.anilbeesetti.nextplayer.core.analysis.fingerprint.RepeatedSegmentMatcher
import dev.anilbeesetti.nextplayer.core.analysis.resolve.DetectedInterval
import dev.anilbeesetti.nextplayer.core.analysis.resolve.SegmentConsensus
import dev.anilbeesetti.nextplayer.core.analysis.resolve.SegmentResolver
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.database.dao.AnalysisFingerprintDao
import dev.anilbeesetti.nextplayer.core.database.dao.DetectedSegmentDao
import dev.anilbeesetti.nextplayer.core.database.entities.AnalysisFingerprintEntity
import dev.anilbeesetti.nextplayer.core.database.entities.DetectedSegmentEntity
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** A sibling episode that can serve as a fingerprint reference. */
data class ReferenceCandidate(
    val uri: Uri,
    val contentSize: Long,
    val contentModified: Long,
    val durationMs: Long,
)

/** Final analysis outcome for one media file. */
data class AnalysisOutcome(
    val intro: DetectedInterval?,
    val outro: DetectedInterval?,
    val fromCache: Boolean,
)

/**
 * Orchestrates local intro/outro detection for the currently playing file:
 * caches fingerprints, reuses sibling episodes as references, runs the
 * repeated-segment matcher, applies the resolver guards and persists results.
 *
 * One analysis runs at a time; a new request for a different file cancels the
 * previous one. [onResult] is invoked on the coordinator scope's dispatcher.
 */
@Single
class AnalysisCoordinator(
    private val context: Context,
    private val fingerprintDao: AnalysisFingerprintDao,
    private val segmentDao: DetectedSegmentDao,
    @Named(DiQualifiers.APPLICATION_SCOPE) private val scope: CoroutineScope,
) {
    private val reader = AudioWindowReader(context)
    private var job: Job? = null
    private var activeIdentity: String? = null
    private val failedIdentities = LinkedHashSet<String>()
    private val analysisMutex = kotlinx.coroutines.sync.Mutex()
    private var generation = 0L

    fun request(
        uri: Uri,
        contentSize: Long,
        contentModified: Long,
        durationMs: Long,
        references: List<ReferenceCandidate>,
        chapterIntroEndMs: Long?,
        chapterOutroStartMs: Long?,
        onResult: (AnalysisOutcome) -> Unit,
    ) {
        val identity = identityKey(uri, contentSize, contentModified, durationMs)
        if (identity == activeIdentity && job?.isActive == true) return
        job?.cancel()
        activeIdentity = identity
        val requestGeneration = ++generation
        job = scope.launch {
            val outcome = analysisMutex.withLock {
                coroutineContext.ensureActive()
                try {
                    analyse(
                        uri, identity, contentSize, contentModified, durationMs,
                        references, chapterIntroEndMs, chapterOutroStartMs,
                    )
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    recordFailure(identity)
                    AnalysisOutcome(null, null, false)
                }
            }
            coroutineContext.ensureActive()
            if (identity == activeIdentity && requestGeneration == generation) onResult(outcome)
        }
    }

    fun cancel() {
        generation++
        job?.cancel()
        job = null
        activeIdentity = null
    }

    private suspend fun analyse(
        uri: Uri,
        identity: String,
        contentSize: Long,
        contentModified: Long,
        durationMs: Long,
        references: List<ReferenceCandidate>,
        chapterIntroEndMs: Long?,
        chapterOutroStartMs: Long?,
    ): AnalysisOutcome {
        if (durationMs < MIN_ANALYSIS_DURATION_MS) return AnalysisOutcome(null, null, false)
        if (identity in failedIdentities) return AnalysisOutcome(null, null, false)

        val cachedSegments = segmentDao.get(uri.toString())
            .filter { it.algoVersion == ANALYSIS_VERSION }
        val cachedFingerprint = fingerprintDao.get(uri.toString())
        if (cachedSegments.isNotEmpty() &&
            cachedFingerprint != null &&
            cachedFingerprint.algoVersion == ANALYSIS_VERSION &&
            cachedFingerprint.contentSize == contentSize &&
            cachedFingerprint.contentModified == contentModified &&
            cachedFingerprint.durationMs == durationMs
        ) {
            return AnalysisOutcome(
                intro = cachedSegments.find { it.kind == DetectedSegmentEntity.KIND_INTRO }?.toInterval(),
                outro = cachedSegments.find { it.kind == DetectedSegmentEntity.KIND_OUTRO }?.toInterval(),
                fromCache = true,
            )
        }

        val target = try {
            ownFingerprint(uri, identity, contentSize, contentModified, durationMs)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailure(identity)
            return AnalysisOutcome(null, null, false)
        } ?: return AnalysisOutcome(null, null, false)

        val introMatches = mutableListOf<RepeatedSegment>()
        val outroMatches = mutableListOf<RepeatedSegment>()
        val orderedReferences = references
            .filter { it.durationMs >= MIN_ANALYSIS_DURATION_MS && it.uri != uri }
            .sortedBy { kotlin.math.abs(it.durationMs - durationMs) }
            .take(MAX_REFERENCES)

        for (reference in orderedReferences) {
            coroutineContext.ensureActive()
            val refFingerprint = try {
                referenceFingerprint(reference)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: continue

            RepeatedSegmentMatcher.findLongestRepeat(target.head, refFingerprint.head, MIN_RUN_ITEMS)?.let {
                introMatches += it
            }
            RepeatedSegmentMatcher.findLongestRepeat(target.tail, refFingerprint.tail, MIN_RUN_ITEMS)?.let {
                outroMatches += it
            }
        }

        val intro = SegmentResolver.resolveIntro(
            durationMs = durationMs,
            consensus = SegmentConsensus.introConsensus(introMatches),
            chapterEndMs = chapterIntroEndMs,
        )
        val outro = SegmentResolver.resolveOutro(
            durationMs = durationMs,
            consensus = SegmentConsensus.outroConsensus(outroMatches),
            chapterStartMs = chapterOutroStartMs,
        )

        segmentDao.delete(uri.toString())
        val now = System.currentTimeMillis()
        val rows = mutableListOf<DetectedSegmentEntity>()
        intro?.let {
            rows += DetectedSegmentEntity(
                uri = uri.toString(),
                kind = DetectedSegmentEntity.KIND_INTRO,
                startMs = it.startMs,
                endMs = it.endMs,
                endIsEof = it.endIsEof,
                sourcesMask = it.sourcesMask,
                referenceCount = it.referenceCount,
                similarityPct = it.similarityPct,
                algoVersion = ANALYSIS_VERSION,
                createdAt = now,
            )
        }
        outro?.let {
            rows += DetectedSegmentEntity(
                uri = uri.toString(),
                kind = DetectedSegmentEntity.KIND_OUTRO,
                startMs = it.startMs,
                endMs = it.endMs,
                endIsEof = it.endIsEof,
                sourcesMask = it.sourcesMask,
                referenceCount = it.referenceCount,
                similarityPct = it.similarityPct,
                algoVersion = ANALYSIS_VERSION,
                createdAt = now,
            )
        }
        if (rows.isNotEmpty()) segmentDao.upsertAll(rows)
        pruneCache()

        return AnalysisOutcome(intro, outro, fromCache = false)
    }

    private data class OwnFingerprints(val head: AudioFingerprint, val tail: AudioFingerprint)

    private suspend fun ownFingerprint(
        uri: Uri,
        identity: String,
        contentSize: Long,
        contentModified: Long,
        durationMs: Long,
    ): OwnFingerprints? {
        val cached = fingerprintDao.get(uri.toString())
        if (cached != null &&
            cached.algoVersion == ANALYSIS_VERSION &&
            cached.contentSize == contentSize &&
            cached.contentModified == contentModified &&
            cached.durationMs == durationMs
        ) {
            return OwnFingerprints(
                head = cached.toFingerprint(isHead = true) ?: return null,
                tail = cached.toFingerprint(isHead = false) ?: return null,
            )
        }

        val head = extract(uri, 0, headWindowEndMs(durationMs))
        val tail = extract(uri, tailWindowStartMs(durationMs), durationMs)
        if (head == null || tail == null) {
            recordFailure(identity)
            return null
        }
        fingerprintDao.upsert(
            AnalysisFingerprintEntity(
                uri = uri.toString(),
                contentSize = contentSize,
                contentModified = contentModified,
                durationMs = durationMs,
                audioTrackHash = 0,
                algoVersion = ANALYSIS_VERSION,
                itemDurationMs = head.itemDurationMs,
                headFingerprint = FingerprintCodec.encode(head.values),
                tailFingerprint = FingerprintCodec.encode(tail.values),
                createdAt = System.currentTimeMillis(),
            ),
        )
        return OwnFingerprints(head, tail)
    }

    private suspend fun referenceFingerprint(reference: ReferenceCandidate): OwnFingerprints? {
        val cached = fingerprintDao.get(reference.uri.toString())
        if (cached != null &&
            cached.algoVersion == ANALYSIS_VERSION &&
            cached.contentSize == reference.contentSize &&
            cached.contentModified == reference.contentModified &&
            cached.durationMs == reference.durationMs
        ) {
            val head = cached.toFingerprint(isHead = true)
            val tail = cached.toFingerprint(isHead = false)
            if (head != null && tail != null) return OwnFingerprints(head, tail)
        }

        val head = extract(reference.uri, 0, headWindowEndMs(reference.durationMs))
        val tail = extract(reference.uri, tailWindowStartMs(reference.durationMs), reference.durationMs)
        if (head == null || tail == null) return null
        fingerprintDao.upsert(
            AnalysisFingerprintEntity(
                uri = reference.uri.toString(),
                contentSize = reference.contentSize,
                contentModified = reference.contentModified,
                durationMs = reference.durationMs,
                audioTrackHash = 0,
                algoVersion = ANALYSIS_VERSION,
                itemDurationMs = head.itemDurationMs,
                headFingerprint = FingerprintCodec.encode(head.values),
                tailFingerprint = FingerprintCodec.encode(tail.values),
                createdAt = System.currentTimeMillis(),
            ),
        )
        return OwnFingerprints(head, tail)
    }

    private suspend fun extract(uri: Uri, startMs: Long, endMs: Long): AudioFingerprint? {
        val window = reader.read(uri, startMs, endMs) ?: return null
        if (window.isEmpty) return null
        return FingerprintEngine.compute(window.pcm, window.sampleRate, window.channels, window.startMs)
    }

    private suspend fun pruneCache() {
        val uris = fingerprintDao.allUrisOldestFirst()
        if (uris.size <= MAX_CACHED_FINGERPRINTS) return
        val toRemove = uris.take(uris.size - MAX_CACHED_FINGERPRINTS)
        for (uri in toRemove) {
            fingerprintDao.delete(uri)
            segmentDao.delete(uri.toString())
        }
    }

    private fun recordFailure(identity: String) {
        failedIdentities.add(identity)
        while (failedIdentities.size > MAX_FAILURE_MEMORY) {
            failedIdentities.remove(failedIdentities.first())
        }
    }

    private fun identityKey(uri: Uri, size: Long, modified: Long, durationMs: Long) =
        "$uri|$size|$modified|$durationMs|$ANALYSIS_VERSION"

    private fun AnalysisFingerprintEntity.toFingerprint(isHead: Boolean): AudioFingerprint? {
        val values = FingerprintCodec.decode(if (isHead) headFingerprint else tailFingerprint)
        if (values.isEmpty()) return null
        val windowStart = if (isHead) 0L else maxOf(0L, durationMs - TAIL_WINDOW_MS)
        return AudioFingerprint(values, itemDurationMs, windowStart)
    }

    private fun DetectedSegmentEntity.toInterval() = DetectedInterval(
        startMs = startMs,
        endMs = endMs,
        endIsEof = endIsEof,
        sourcesMask = sourcesMask,
        referenceCount = referenceCount,
        similarityPct = similarityPct,
    )

    companion object {
        /** Bump when fingerprint computation or matching rules change. */
        const val ANALYSIS_VERSION = 1

        /** Files shorter than this are not analysed. */
        const val MIN_ANALYSIS_DURATION_MS = 10 * 60_000L

        const val HEAD_WINDOW_MS = 5 * 60_000L
        const val TAIL_WINDOW_MS = 6 * 60_000L
        const val MAX_REFERENCES = 4
        const val MIN_RUN_ITEMS = 65
        const val MAX_CACHED_FINGERPRINTS = 400
        const val MAX_FAILURE_MEMORY = 100

        fun headWindowEndMs(durationMs: Long): Long = minOf(durationMs, HEAD_WINDOW_MS)

        fun tailWindowStartMs(durationMs: Long): Long = maxOf(0L, durationMs - TAIL_WINDOW_MS)
    }
}
