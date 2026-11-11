package dev.anilbeesetti.nextplayer.feature.player.service

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION
import androidx.media3.common.Player.DISCONTINUITY_REASON_REMOVE
import androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.CommandButton
import androidx.media3.session.CommandButton.ICON_UNDEFINED
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import coil3.ImageLoader
import coil3.request.ImageRequest
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.anilbeesetti.nextplayer.core.common.Logger
import dev.anilbeesetti.nextplayer.core.common.extensions.deleteFiles
import dev.anilbeesetti.nextplayer.core.common.extensions.getFilenameFromUri
import dev.anilbeesetti.nextplayer.core.common.extensions.getLocalSubtitles
import dev.anilbeesetti.nextplayer.core.common.extensions.getPath
import dev.anilbeesetti.nextplayer.core.common.extensions.subtitleCacheDir
import dev.anilbeesetti.nextplayer.core.data.repository.MediaRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.media.network.NetworkUri
import dev.anilbeesetti.nextplayer.core.media.network.datasource.NextDataSourceFactory
import dev.anilbeesetti.nextplayer.core.model.LoopMode
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.core.model.Resume
import dev.anilbeesetti.nextplayer.core.ui.R as coreUiR
import dev.anilbeesetti.nextplayer.feature.player.PlayerActivity
import dev.anilbeesetti.nextplayer.feature.player.R
import dev.anilbeesetti.nextplayer.feature.player.extensions.addAdditionalSubtitleConfiguration
import dev.anilbeesetti.nextplayer.feature.player.extensions.audioDecoderMode
import dev.anilbeesetti.nextplayer.feature.player.extensions.audioTrackIndex
import dev.anilbeesetti.nextplayer.feature.player.extensions.copy
import dev.anilbeesetti.nextplayer.feature.player.extensions.externalAudio
import dev.anilbeesetti.nextplayer.feature.player.extensions.externalAudioIndex
import dev.anilbeesetti.nextplayer.feature.player.extensions.getManuallySelectedTrackIndex
import dev.anilbeesetti.nextplayer.feature.player.extensions.playbackSpeed
import dev.anilbeesetti.nextplayer.feature.player.extensions.positionMs
import dev.anilbeesetti.nextplayer.feature.player.extensions.setExtras
import dev.anilbeesetti.nextplayer.feature.player.extensions.setIsScrubbingModeEnabled
import dev.anilbeesetti.nextplayer.feature.player.extensions.selectTrack
import dev.anilbeesetti.nextplayer.feature.player.extensions.subtitleDelayMilliseconds
import dev.anilbeesetti.nextplayer.feature.player.extensions.subtitleSpeed
import dev.anilbeesetti.nextplayer.feature.player.extensions.subtitleTrackIndex
import dev.anilbeesetti.nextplayer.feature.player.extensions.switchTrack
import dev.anilbeesetti.nextplayer.feature.player.extensions.uriToSubtitleConfiguration
import dev.anilbeesetti.nextplayer.feature.player.extensions.videoDecoderMode
import dev.anilbeesetti.nextplayer.feature.player.extensions.videoZoom
import dev.anilbeesetti.nextplayer.feature.player.extensions.withExternalAudio
import dev.anilbeesetti.nextplayer.feature.player.model.DecoderTrackType
import dev.anilbeesetti.nextplayer.feature.player.interpolation.InterpolationEndGuard
import dev.anilbeesetti.nextplayer.feature.player.interpolation.InterpolationState
import dev.anilbeesetti.nextplayer.feature.player.interpolation.MotionInterpolationEffect
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleDelayMilliseconds
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleSpeed
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfoBuilder
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import android.os.SystemClock
import dev.anilbeesetti.nextplayer.core.analysis.AnalysisCoordinator
import dev.anilbeesetti.nextplayer.core.analysis.resolve.ChapterSkipResolver
import dev.anilbeesetti.nextplayer.core.analysis.resolve.DetectedSources
import dev.anilbeesetti.nextplayer.feature.player.extensions.chapters
import androidx.media3.common.Timeline
import dev.anilbeesetti.nextplayer.core.database.entities.DetectedSegmentEntity
import dev.anilbeesetti.nextplayer.core.analysis.AnalysisOutcome
import dev.anilbeesetti.nextplayer.core.analysis.ReferenceCandidate
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named

@OptIn(UnstableApi::class)
class PlayerService : MediaSessionService() {

    private val serviceScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var mediaSession: MediaSession? = null
    private var artworkLoadJob: Job? = null

    private val preferencesRepository: PreferencesRepository by inject()

    private val dataSourceFactory: NextDataSourceFactory by inject()

    private val mediaRepository: MediaRepository by inject()

    private val analysisCoordinator: AnalysisCoordinator by inject()

    private val ioDispatcher: CoroutineDispatcher by inject<CoroutineDispatcher>(qualifier = named(DiQualifiers.IO_DISPATCHER))

    private val imageLoader: ImageLoader by inject()

    private val playerPreferences: PlayerPreferences
        get() = preferencesRepository.playerPreferences.value

    private val customCommands = CustomCommands.asSessionCommands()

    private var isMediaItemReady = false
    private var pendingAudioSelection: Pair<String, Uri>? = null
    private var pendingSubtitleSelection: Pair<String, String>? = null

    private var pendingUndo: SkipRecord? = null
    private var lastOutcome: AnalysisOutcome? = null
    private var lastOutcomeMediaId: String? = null
    private val automaticSkipState = AutomaticSkipState()
    private var chapterSegments: List<AutomaticSkipState.Segment> = emptyList()
    private var outroMonitorJob: Job? = null
    private var endGuardJob: Job? = null
    private var analysisRequestJob: Job? = null
    private var skipTokenCounter = 0L
    private var playbackGeneration = 0L
    private var analysisRequestedFor: String? = null
    private var scrubbing = false
    private var automaticSeek = false
    private var hasRenderedFirstFrame = false
    private var startupStartedAt = 0L

    private class SkipRecord(
        val mediaId: String,
        val fromMs: Long,
        val token: Long,
        val expireElapsed: Long,
    )

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentVolumeGain: Int = 0

    private lateinit var decoderManager: DecoderManager
    private lateinit var trackSelector: DefaultTrackSelector
    private val decoderRecoveryManager = DecoderRecoveryManager()
    private val interpolation = InterpolationState()
    private var interpolationPipelineAvailable = false

    private fun refreshInterpolationAvailability() {
        val player = mediaSession?.player
        val frameRate = (player as? ExoPlayer)?.videoFormat?.frameRate ?: -1f
        val reason = when {
            !interpolationPipelineAvailable -> "tunneling"
            (player as? ExoPlayer)?.videoFormat?.isHdrVideo() == true -> "hdr"
            decoderManager.activeVideoMode == DecoderMode.SOFTWARE ||
                decoderManager.activeVideoMode == DecoderMode.FFMPEG -> "decoder"
            player != null && player.playbackParameters.speed != 1f -> "speed"
            frameRate > 30.1f || frameRate in 0f..11.7f -> "framerate"
            else -> ""
        }
        if (reason != interpolation.blockedReason) {
            interpolation.blockedReason = reason
            interpolation.reset()
        }
        (player as? ExoPlayer)?.let { exoPlayer ->
            // The video graph still knows the source format's frame rate. While generating frames,
            // let the visible output Surface request the interpolated rate instead of source fps.
            val strategy = if (interpolation.canInterpolate()) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
            if (exoPlayer.videoChangeFrameRateStrategy != strategy) exoPlayer.setVideoChangeFrameRateStrategy(strategy)
        }
    }

    private val decoderAnalyticsListener = object : AnalyticsListener {
        override fun onEvents(player: Player, events: AnalyticsListener.Events) {
            publishDecoderState()
            refreshInterpolationAvailability()
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            Logger.logInfo(
                DECODER_LOG_TAG,
                "Video decoder initialized with requested=${decoderManager.videoMode} " +
                    "as ${decoderManager.activeVideoMode}: $decoderName, initMs=$initializationDurationMs",
            )
            decoderRecoveryManager.onDecoderInitialized(DecoderTrackType.VIDEO)
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            Logger.logInfo(
                DECODER_LOG_TAG,
                "Audio decoder initialized with requested=${decoderManager.audioMode} " +
                    "as ${decoderManager.activeAudioMode}: $decoderName",
            )
            decoderRecoveryManager.onDecoderInitialized(DecoderTrackType.AUDIO)
        }

        override fun onLoadStarted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
        ) {
            Logger.logInfo(DECODER_LOG_TAG, "Media load started afterMs=${SystemClock.elapsedRealtime() - startupStartedAt}")
        }

        override fun onTracksChanged(
            eventTime: AnalyticsListener.EventTime,
            tracks: Tracks,
        ) {
            val videoTracks = tracks.groups
                .filter { it.type == C.TRACK_TYPE_VIDEO }
                .joinToString { group ->
                    val mimeType = group.mediaTrackGroup.getFormat(0).sampleMimeType
                    "$mimeType(supported=${group.isSupported(true)}, selected=${group.isSelected})"
                }
            Logger.logInfo(
                DECODER_LOG_TAG,
                "Video tracks: ${videoTracks.ifEmpty { "none" }}, " +
                    "unmapped=${trackSelector.unmappedTrackCount(C.TRACK_TYPE_VIDEO)}",
            )
        }

        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long,
        ) {
            Logger.logInfo(
                DECODER_LOG_TAG,
                "Rendered first frame with video=${decoderManager.activeVideoMode}, " +
                    "startupMs=${SystemClock.elapsedRealtime() - startupStartedAt}",
            )
            if (!hasRenderedFirstFrame) {
                hasRenderedFirstFrame = true
                loadArtworkForCurrentMediaItem()
            }
        }

        override fun onPlayerError(
            eventTime: AnalyticsListener.EventTime,
            error: PlaybackException,
        ) {
            Logger.logError(
                DECODER_LOG_TAG,
                "Player error with requestedVideo=${decoderManager.videoMode}, " +
                    "activeVideo=${decoderManager.activeVideoMode}, " +
                    "requestedAudio=${decoderManager.audioMode}, " +
                    "activeAudio=${decoderManager.activeAudioMode}: ${error.message}",
            )
        }
    }

    private val playbackStateListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            super.onMediaItemTransition(mediaItem, reason)
            interpolation.setEnabled(interpolation.enabled)
            resetAnalysisState()
            hasRenderedFirstFrame = false
            startupStartedAt = SystemClock.elapsedRealtime()
            Logger.logInfo(DECODER_LOG_TAG, "Playback transition generation=$playbackGeneration reason=$reason")
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
            restoreDecoderChoices(mediaItem)
            isMediaItemReady = false
            if (mediaItem != null && pendingSubtitleSelection?.first != mediaItem.mediaId) {
                pendingSubtitleSelection = null
            }
            mediaItem?.mediaMetadata?.let { metadata ->
                mediaSession?.player?.run {
                    setPlaybackSpeed(metadata.playbackSpeed ?: playerPreferences.defaultPlaybackSpeed)
                    playerSpecificSubtitleDelayMilliseconds = metadata.subtitleDelayMilliseconds ?: 0L
                    playerSpecificSubtitleSpeed = metadata.subtitleSpeed ?: 1f
                }

                metadata.positionMs?.takeIf { playerPreferences.resume == Resume.YES }?.let {
                    mediaSession?.player?.seekTo(it)
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            super.onPositionDiscontinuity(oldPosition, newPosition, reason)
            interpolation.reset()
            // A seek into the final seconds must switch modes before the decoder reaches the end.
            mediaSession?.player?.let(::updateInterpolationEndGuard)
            if (reason == DISCONTINUITY_REASON_SEEK && !automaticSeek) {
                pendingUndo = null
                automaticSkipState.manualSeek(newPosition.positionMs)
                clearSkipExtras()
            }
            val oldMediaItem = oldPosition.mediaItem ?: return

            when (reason) {
                DISCONTINUITY_REASON_SEEK,
                DISCONTINUITY_REASON_AUTO_TRANSITION,
                -> {
                    if (newPosition.mediaItem == null || oldMediaItem == newPosition.mediaItem) return

                    val updatedPosition = oldPosition.positionMs.takeIf { reason == DISCONTINUITY_REASON_SEEK } ?: C.TIME_UNSET
                    mediaSession?.player?.replaceMediaItem(
                        oldPosition.mediaItemIndex,
                        oldMediaItem.copy(positionMs = updatedPosition),
                    )
                    serviceScope.launch {
                        mediaRepository.updateMediumPosition(
                            uri = oldMediaItem.mediaId,
                            position = updatedPosition,
                        )
                    }
                }

                DISCONTINUITY_REASON_REMOVE -> {
                    serviceScope.launch {
                        val durationMs = oldMediaItem.mediaMetadata.durationMs
                        val isAtEnd = durationMs != null && oldPosition.positionMs >= durationMs - 1000
                        mediaRepository.updateMediumPosition(
                            uri = oldMediaItem.mediaId,
                            position = if (isAtEnd) C.TIME_UNSET else oldPosition.positionMs,
                        )
                    }
                }

                else -> return
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            super.onTracksChanged(tracks)
            serviceScope.launch {
                mediaSession?.player?.currentTracks?.let(::handleUnsupportedTracks)
            }
            val player = mediaSession?.player ?: return
            val pendingSubtitleId = pendingSubtitleSelection
                ?.takeIf { it.first == player.currentMediaItem?.mediaId }
                ?.second
            // Source replacement can report a track set before the new sidecar arrives.
            // Only consume the request when that exact text track is available.
            pendingSubtitleId?.let { tracks.externalSubtitleTrack(it) }?.let {
                pendingSubtitleSelection = null
                player.selectTrack(it.mediaTrackGroup)
            }

            if (!isMediaItemReady && tracks.groups.isNotEmpty()) {
                isMediaItemReady = true

                val pending = pendingAudioSelection
                pendingAudioSelection = null
                if (pending != null && pending.first == player.currentMediaItem?.mediaId) {
                    val audioIndex = player.currentMediaItem?.mediaMetadata?.externalAudio.orEmpty().indexOf(pending.second)
                    val trackIndex = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                        .indexOfFirst { it.mediaTrackGroup.externalAudioIndex == audioIndex }
                    if (audioIndex >= 0 && trackIndex >= 0) player.switchTrack(C.TRACK_TYPE_AUDIO, trackIndex)
                }
                if (!playerPreferences.rememberSelections) return
                mediaSession?.player?.mediaMetadata?.audioTrackIndex?.takeIf { pending == null }?.let {
                    mediaSession?.player?.switchTrack(C.TRACK_TYPE_AUDIO, it)
                }
                if (pendingSubtitleId == null) {
                    player.mediaMetadata.subtitleTrackIndex?.let { player.switchTrack(C.TRACK_TYPE_TEXT, it) }
                }
            }
        }

        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
            super.onTrackSelectionParametersChanged(parameters)
            val player = mediaSession?.player ?: return
            val currentMediaItem = player.currentMediaItem ?: return

            val audioTrackIndex = player.getManuallySelectedTrackIndex(C.TRACK_TYPE_AUDIO)
            val subtitleTrackIndex = player.getManuallySelectedTrackIndex(C.TRACK_TYPE_TEXT)

            if (audioTrackIndex != null) {
                serviceScope.launch {
                    mediaRepository.updateMediumAudioTrack(
                        uri = currentMediaItem.mediaId,
                        audioTrackIndex = audioTrackIndex,
                    )
                }
            }

            if (subtitleTrackIndex != null) {
                serviceScope.launch {
                    mediaRepository.updateMediumSubtitleTrack(
                        uri = currentMediaItem.mediaId,
                        subtitleTrackIndex = subtitleTrackIndex,
                    )
                }
            }

            player.replaceMediaItem(
                player.currentMediaItemIndex,
                currentMediaItem.copy(
                    audioTrackIndex = audioTrackIndex,
                    subtitleTrackIndex = subtitleTrackIndex,
                ),
            )
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            super.onPlaybackParametersChanged(playbackParameters)
            refreshInterpolationAvailability()
            val player = mediaSession?.player ?: return
            val currentMediaItem = player.currentMediaItem ?: return
            val playbackSpeed = playbackParameters.speed

            serviceScope.launch {
                mediaRepository.updateMediumPlaybackSpeed(
                    uri = currentMediaItem.mediaId,
                    playbackSpeed = playbackSpeed,
                )
            }
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                currentMediaItem.copy(playbackSpeed = playbackSpeed),
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            super.onPlaybackStateChanged(playbackState)

            val player = mediaSession?.player
            val shouldResetPlaybackParameters = playbackState == Player.STATE_ENDED ||
                (
                    playbackState == Player.STATE_IDLE &&
                        player?.mediaItemCount == 0
                    )
            if (shouldResetPlaybackParameters) {
                mediaSession?.player?.trackSelectionParameters = TrackSelectionParameters.DEFAULT
                mediaSession?.player?.setPlaybackSpeed(playerPreferences.defaultPlaybackSpeed)
            }

            if (playbackState == Player.STATE_READY && playerPreferences.autoSkipIntroOutro) {
                requestAnalysisForCurrentItem()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!error.isDecoderFailure) {
                decoderRecoveryManager.onNonDecoderError()
                return
            }

            val trackType = error.decoderTrackType() ?: run {
                decoderRecoveryManager.onNonDecoderError()
                return
            }
            handleDecoderFailure(trackType)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            super.onPlayWhenReadyChanged(playWhenReady, reason)

            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                if (mediaSession?.player?.repeatMode != Player.REPEAT_MODE_OFF) {
                    mediaSession?.player?.seekTo(0)
                    mediaSession?.player?.play()
                    return
                }
                mediaSession?.run {
                    player.clearMediaItems()
                    player.stop()
                }
                stopSelf()
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                )
            ) {
                updateInterpolationEndGuard(player)
            }
            if ((playerPreferences.autoSkipIntroOutro || playerPreferences.customSkipIntroOutro) && events.containsAny(
                    Player.EVENT_TRACKS_CHANGED,
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                )
            ) {
                updateChapterSegments(player)
                refreshSkipSegments()
                if (player.isPlaying) startOutroMonitor()
            }
            // Consecutive items can reuse the renderer without another first-frame callback.
            if (player.playbackState != Player.STATE_READY ||
                !events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAYBACK_STATE_CHANGED)
            ) {
                return
            }
            val currentMediaItem = player.currentMediaItem ?: return
            val duration = player.duration.validDurationOrNull()
            // Update the media metadata duration so that it will be used later in position discontinuity handling
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                currentMediaItem.copy(durationMs = duration ?: 0),
            )

            serviceScope.launch {
                mediaRepository.updateMediumLastPlayedTime(
                    uri = currentMediaItem.mediaId,
                    lastPlayedTime = System.currentTimeMillis(),
                    duration = duration,
                )
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)
            if (isPlaying) startOutroMonitor() else stopOutroMonitor()
            if (isPlaying) startEndGuardMonitor() else stopEndGuardMonitor()
            mediaSession?.run {
                serviceScope.launch {
                    mediaRepository.updateMediumPosition(
                        uri = player.currentMediaItem?.mediaId ?: return@launch,
                        position = player.currentPosition,
                    )
                }
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            super.onRepeatModeChanged(repeatMode)
            serviceScope.launch {
                preferencesRepository.updatePlayerPreferences {
                    it.copy(
                        loopMode = when (repeatMode) {
                            Player.REPEAT_MODE_OFF -> LoopMode.OFF
                            Player.REPEAT_MODE_ONE -> LoopMode.ONE
                            Player.REPEAT_MODE_ALL -> LoopMode.ALL
                            else -> LoopMode.OFF
                        },
                    )
                }
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            super.onAudioSessionIdChanged(audioSessionId)
            if (!playerPreferences.enableVolumeBoost) return
            if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
            try {
                loudnessEnhancer?.release()
                loudnessEnhancer = LoudnessEnhancer(audioSessionId)
                if (currentVolumeGain > 0) {
                    setEnhancerTargetGain(currentVolumeGain)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                loudnessEnhancer = null
            }
        }
    }

    private fun setEnhancerTargetGain(gain: Int) {
        val enhancer = loudnessEnhancer ?: return

        try {
            enhancer.setTargetGain(gain)
            enhancer.enabled = gain > 0
            currentVolumeGain = enhancer.targetGain.toInt()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> = Futures.immediateFuture(
            MediaSession.ConnectionResult.accept(
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                    .buildUpon()
                    .addSessionCommands(customCommands)
                    .build(),
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
            ),
        )

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.future(Dispatchers.Default) {
            val started = SystemClock.elapsedRealtime()
            Logger.logInfo(DECODER_LOG_TAG, "Preparing ${mediaItems.size} media items")
            val updatedMediaItems = updatedMediaItemsWithMetadata(mediaItems)
            Logger.logInfo(DECODER_LOG_TAG, "Media item metadata ready in ${SystemClock.elapsedRealtime() - started}ms")
            return@future MediaSession.MediaItemsWithStartPosition(updatedMediaItems, startIndex, startPositionMs)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = serviceScope.future(Dispatchers.Default) {
            val updatedMediaItems = updatedMediaItemsWithMetadata(mediaItems)
            return@future updatedMediaItems.toMutableList()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> = serviceScope.future {
            val command = CustomCommands.fromSessionCommand(customCommand)
                ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)

            when (command) {
                CustomCommands.GET_PLAYBACK_METRICS -> {
                    if (controller.uid != android.os.Process.myUid()) return@future SessionResult(SessionError.ERROR_PERMISSION_DENIED)
                    val player = mediaSession?.player as? ExoPlayer ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                    val counters = player.videoDecoderCounters
                    counters?.ensureUpdated()
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putInt("rendered", counters?.renderedOutputBufferCount ?: 0)
                            putInt("dropped", counters?.droppedBufferCount ?: 0)
                            putInt("skipped", counters?.skippedOutputBufferCount ?: 0)
                            putInt("maxConsecutiveDropped", counters?.maxConsecutiveDroppedBufferCount ?: 0)
                            putInt("droppedToKeyframe", counters?.droppedToKeyframeCount ?: 0)
                            putLong("processingOffsetUs", counters?.totalVideoFrameProcessingOffsetUs ?: 0)
                            putInt("processingOffsetCount", counters?.videoFrameProcessingOffsetCount ?: 0)
                            putLong("positionMs", player.currentPosition)
                            putLong("bufferedDurationMs", player.totalBufferedDuration)
                            putInt("state", player.playbackState)
                            putBoolean("playing", player.isPlaying)
                            putString("decoderMode", decoderManager.activeVideoMode?.name)
                            putInt("width", player.videoFormat?.width ?: 0)
                            putInt("height", player.videoFormat?.height ?: 0)
                            putFloat("sourceFps", player.videoFormat?.frameRate ?: 0f)
                            putBoolean("interpolationEnabled", interpolation.enabled)
                        },
                    )
                }

                CustomCommands.UNDO_INTRO_OUTRO_SKIP -> {
                    if (controller.uid != android.os.Process.myUid()) return@future SessionResult(SessionError.ERROR_PERMISSION_DENIED)
                    val token = args.getLong(CustomCommands.UNDO_TOKEN_KEY, 0L)
                    val record = pendingUndo
                    val player = mediaSession?.player
                    if (record != null &&
                        record.token == token &&
                        SystemClock.elapsedRealtime() <= record.expireElapsed &&
                        player != null &&
                        player.currentMediaItem?.mediaId == record.mediaId
                    ) {
                        automaticSkipState.manualSeek(record.fromMs)
                        seekAutomatically(player, record.fromMs)
                        pendingUndo = null
                        clearSkipExtras()
                        return@future SessionResult(SessionResult.RESULT_SUCCESS)
                    }
                    return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                }

                CustomCommands.SET_FRAME_INTERPOLATION -> {
                    val enabled = args.getBoolean("enabled", interpolation.enabled)
                    val target = args.getInt("target", interpolation.targetFps)
                    if (!InterpolationState.isSupportedTargetFps(target)) return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                    preferencesRepository.updatePlayerPreferences { it.copy(frameInterpolationEnabled = enabled, frameInterpolationTargetFps = target) }
                    interpolation.setTargetFps(target)
                    interpolation.setEnabled(enabled)
                    refreshInterpolationAvailability()
                    return@future SessionResult(SessionResult.RESULT_SUCCESS, interpolation.snapshot())
                }

                CustomCommands.GET_FRAME_INTERPOLATION -> {
                    refreshInterpolationAvailability()
                    return@future SessionResult(SessionResult.RESULT_SUCCESS, interpolation.snapshot())
                }

                CustomCommands.ADD_SUBTITLE_TRACK -> {
                    val subtitleUri = args.getString(CustomCommands.SUBTITLE_TRACK_URI_KEY)?.toUri()
                        ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)

                    val player = session.player
                    val currentMediaItem = player.currentMediaItem
                        ?: return@future SessionResult(SessionError.ERROR_INVALID_STATE)
                    val subtitleId = subtitleUri.toString()
                    val loadedTrack = player.currentTracks.externalSubtitleTrack(subtitleId)
                    if (loadedTrack != null) {
                        pendingSubtitleSelection = null
                        player.selectTrack(loadedTrack.mediaTrackGroup)
                        return@future SessionResult(SessionResult.RESULT_SUCCESS)
                    }

                    val newSubConfiguration = uriToSubtitleConfiguration(
                        uri = subtitleUri,
                        subtitleEncoding = playerPreferences.subtitleTextEncoding,
                    )
                    mediaRepository.addExternalSubtitleToMedium(
                        uri = currentMediaItem.mediaId,
                        subtitleUri = subtitleUri,
                    )
                    if (player.currentMediaItem?.mediaId != currentMediaItem.mediaId) {
                        return@future SessionResult(SessionError.ERROR_INVALID_STATE)
                    }
                    // Select the actual track once its source is prepared, even when remembering
                    // track selections is off or unsupported text tracks precede this one.
                    pendingSubtitleSelection = currentMediaItem.mediaId to subtitleId
                    player.addAdditionalSubtitleConfiguration(newSubConfiguration)
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.ADD_AUDIO_TRACK -> {
                    val uri = args.getString(CustomCommands.AUDIO_TRACK_URI_KEY)?.toUri()
                        ?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT || it.scheme == ContentResolver.SCHEME_FILE }
                        ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                    val player = session.player
                    val mediaItem = player.currentMediaItem
                        ?: return@future SessionResult(SessionError.ERROR_INVALID_STATE)
                    val hasAudio = withContext(Dispatchers.IO) {
                        runCatching {
                            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                                MediaInfoBuilder().from(descriptor).build()?.let { info ->
                                    try {
                                        info.audioStreams.isNotEmpty()
                                    } finally {
                                        info.release()
                                    }
                                }
                            } == true
                        }.getOrDefault(false)
                    }
                    if (!hasAudio) return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                    if (player.currentMediaItem?.mediaId != mediaItem.mediaId) {
                        return@future SessionResult(SessionError.ERROR_INVALID_STATE)
                    }
                    mediaRepository.addExternalAudioToMedium(mediaItem.mediaId, uri)
                    val currentItem = player.currentMediaItem?.takeIf { it.mediaId == mediaItem.mediaId }
                        ?: return@future SessionResult(SessionError.ERROR_INVALID_STATE)
                    val audio = (currentItem.mediaMetadata.externalAudio + uri).distinct()
                    pendingAudioSelection = currentItem.mediaId to uri
                    val updatedItem = currentItem.withExternalAudio(audio).copy(positionMs = player.currentPosition)
                    val index = player.currentMediaItemIndex
                    player.addMediaItem(index + 1, updatedItem)
                    player.seekTo(index + 1, player.currentPosition)
                    player.removeMediaItem(index)
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.SET_SKIP_SILENCE_ENABLED -> {
                    val enabled = args.getBoolean(CustomCommands.SKIP_SILENCE_ENABLED_KEY)
                    mediaSession?.player?.playerSpecificSkipSilenceEnabled = enabled
                    mediaSession?.sessionExtras = Bundle(mediaSession?.sessionExtras ?: Bundle.EMPTY).apply {
                        putBoolean(CustomCommands.SKIP_SILENCE_ENABLED_KEY, enabled)
                    }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_SKIP_SILENCE_ENABLED -> {
                    val enabled = mediaSession?.player?.playerSpecificSkipSilenceEnabled ?: false
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putBoolean(CustomCommands.SKIP_SILENCE_ENABLED_KEY, enabled)
                        },
                    )
                }

                CustomCommands.SET_IS_SCRUBBING_MODE_ENABLED -> {
                    val enabled = args.getBoolean(CustomCommands.IS_SCRUBBING_MODE_ENABLED_KEY)
                    scrubbing = enabled
                    mediaSession?.player?.setIsScrubbingModeEnabled(enabled)
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.IS_LOUDNESS_GAIN_SUPPORTED -> {
                    val isSupported = loudnessEnhancer != null
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putBoolean(CustomCommands.IS_LOUDNESS_GAIN_SUPPORTED_KEY, isSupported)
                        },
                    )
                }

                CustomCommands.SET_LOUDNESS_GAIN -> {
                    val gain = args.getInt(CustomCommands.LOUDNESS_GAIN_KEY, 0)
                    setEnhancerTargetGain(gain)
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_LOUDNESS_GAIN -> {
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putInt(CustomCommands.LOUDNESS_GAIN_KEY, currentVolumeGain)
                        },
                    )
                }

                CustomCommands.SET_VIDEO_DECODER_MODE -> {
                    val mode = args.decoderMode(CustomCommands.VIDEO_DECODER_MODE_KEY)
                        ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                    val player = mediaSession?.player as? ExoPlayer
                        ?: return@future SessionResult(SessionError.ERROR_INVALID_STATE)
                    decoderRecoveryManager.onUserSelection(DecoderTrackType.VIDEO, mode)
                    selectDecoder(DecoderTrackType.VIDEO, mode)
                    serviceScope.launch { handleUnsupportedTrack(player.currentTracks, DecoderTrackType.VIDEO) }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.SET_AUDIO_DECODER_MODE -> {
                    val mode = args.decoderMode(CustomCommands.AUDIO_DECODER_MODE_KEY)
                        ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                    val player = mediaSession?.player as? ExoPlayer
                        ?: return@future SessionResult(SessionError.ERROR_INVALID_STATE)
                    decoderRecoveryManager.onUserSelection(DecoderTrackType.AUDIO, mode)
                    selectDecoder(DecoderTrackType.AUDIO, mode)
                    serviceScope.launch { handleUnsupportedTrack(player.currentTracks, DecoderTrackType.AUDIO) }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.TRY_DECODER_FALLBACK -> {
                    val retry = decoderRecoveryManager.confirmFallback()
                        ?: return@future SessionResult(SessionError.ERROR_INVALID_STATE)
                    if (!retryDecoderWith(retry)) {
                        decoderRecoveryManager.onNonDecoderError()
                        return@future SessionResult(SessionError.ERROR_INVALID_STATE)
                    }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_SUBTITLE_DELAY -> {
                    val subtitleDelay = mediaSession?.player?.playerSpecificSubtitleDelayMilliseconds ?: 0
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putLong(CustomCommands.SUBTITLE_DELAY_KEY, subtitleDelay)
                        },
                    )
                }

                CustomCommands.SET_SUBTITLE_DELAY -> {
                    val subtitleDelay = args.getLong(CustomCommands.SUBTITLE_DELAY_KEY)
                    mediaSession?.player?.playerSpecificSubtitleDelayMilliseconds = subtitleDelay
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_SUBTITLE_SPEED -> {
                    val subtitleSpeed = mediaSession?.player?.playerSpecificSubtitleSpeed ?: 0f
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putFloat(CustomCommands.SUBTITLE_SPEED_KEY, subtitleSpeed)
                        },
                    )
                }

                CustomCommands.SET_SUBTITLE_SPEED -> {
                    val subtitleSpeed = args.getFloat(CustomCommands.SUBTITLE_SPEED_KEY)
                    mediaSession?.player?.playerSpecificSubtitleSpeed = subtitleSpeed
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.STOP_PLAYER_SESSION -> {
                    savePositionAndStopSession()
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(dev.anilbeesetti.nextplayer.core.common.PrimaryLanguage.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        PlaybackUpdateGuard.setSessionActive(true)
        PlaybackUpdateGuard.setSessionActive(true)
        serviceScope.launch {
            preferencesRepository.playerPreferences.map {
                Triple(it.autoSkipIntroOutro, it.customSkipIntroOutro, it.customIntroSeconds to it.customOutroSeconds)
            }.distinctUntilChanged().collect { (automatic, custom, _) ->
                if (automatic || custom) {
                    mediaSession?.player?.let {
                        updateChapterSegments(it)
                        refreshSkipSegments()
                        if (it.isPlaying) startOutroMonitor()
                        if (automatic && it.playbackState == Player.STATE_READY) requestAnalysisForCurrentItem()
                    }
                    if (!automatic) {
                        analysisRequestJob?.cancel()
                        analysisRequestJob = null
                        analysisCoordinator.cancel()
                        analysisRequestedFor = null
                    }
                } else {
                    resetAnalysisState()
                }
            }
        }
        decoderManager = DecoderManager()
        val renderersFactory = HdrRenderersFactory(applicationContext)
            .setDecoderManager(decoderManager)
            // Motion compensation needs the following source frame. Submitting scheduled output
            // earlier returns bounded texture slots sooner while EGL retains the original PTS.
            .setVideoRendererEarlySchedulingThresholdUs(100_000L)

        trackSelector = DefaultTrackSelector(applicationContext).apply {
            setParameters(
                buildUponParameters()
                    .setTunnelingEnabled(playerPreferences.enableTunneledPlayback)
                    .setPreferredAudioLanguage(playerPreferences.preferredAudioLanguage)
                    .setPreferredTextLanguage(playerPreferences.preferredSubtitleLanguage),
            )
        }

        val player = ExoPlayer.Builder(applicationContext)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(
                ExternalAudioMediaSourceFactory(
                    DefaultMediaSourceFactory(applicationContext)
                        .setDataSourceFactory(dataSourceFactory)
                        .setSubtitleParserFactory(PlayerSubtitleParserFactory),
                ),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                playerPreferences.requireAudioFocus,
            )
            .setHandleAudioBecomingNoisy(playerPreferences.pauseOnHeadsetDisconnect)
            .build()
            .also {
                it.addListener(playbackStateListener)
                it.pauseAtEndOfMediaItems = !playerPreferences.autoplay
                it.repeatMode = when (playerPreferences.loopMode) {
                    LoopMode.OFF -> Player.REPEAT_MODE_OFF
                    LoopMode.ONE -> Player.REPEAT_MODE_ONE
                    LoopMode.ALL -> Player.REPEAT_MODE_ALL
                }
            }

        decoderManager.attach(player)
        interpolation.setTargetFps(InterpolationState.restoreTargetFps(playerPreferences.frameInterpolationTargetFps))
        interpolation.setEnabled(playerPreferences.frameInterpolationEnabled)
        interpolationPipelineAvailable = !playerPreferences.enableTunneledPlayback
        if (interpolationPipelineAvailable) {
            // Install before prepare so MediaCodec creates its video graph even when initially off.
            player.setVideoEffects(listOf(MotionInterpolationEffect(interpolation)))
            player.setVideoFrameMetadataListener { timeUs, releaseTimeNs, _, _ -> interpolation.onFrameSubmitted(timeUs, releaseTimeNs, System.nanoTime()) }
        }
        refreshInterpolationAvailability()
        player.addAnalyticsListener(decoderAnalyticsListener)

        try {
            mediaSession = MediaSession.Builder(this, player).apply {
                setSessionActivity(
                    PendingIntent.getActivity(
                        this@PlayerService,
                        0,
                        Intent(this@PlayerService, PlayerActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                setCallback(mediaSessionCallback)
                setCustomLayout(
                    listOf(
                        CommandButton.Builder(ICON_UNDEFINED)
                            .setCustomIconResId(coreUiR.drawable.ic_close)
                            .setDisplayName(getString(coreUiR.string.stop_player_session))
                            .setSessionCommand(CustomCommands.STOP_PLAYER_SESSION.sessionCommand)
                            .setEnabled(true)
                            .build(),
                    ),
                )
            }.build()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            serviceScope.launch { savePositionAndStopSession() }
        }
    }

    private fun updateChapterSegments(player: Player) {
        val duration = player.duration.validDurationOrNull() ?: return
        if (player.currentTimeline.isEmpty) return
        val offset = player.currentTimeline.getPeriod(player.currentPeriodIndex, Timeline.Period()).positionInWindowMs
        val markers = player.currentTracks.chapters(duration, offset, distinctStarts = false).map {
            ChapterSkipResolver.ChapterMarker(
                title = it.title?.value.orEmpty(),
                startMs = it.startTimeMs,
                endMs = it.endTimeMs.takeIf { end -> end != C.TIME_UNSET },
            )
        }
        val resolved = ChapterSkipResolver.resolve(markers, duration).map {
            AutomaticSkipState.Segment(it.kind.name, it.interval)
        }
        if (resolved != chapterSegments) {
            chapterSegments = resolved
            refreshSkipSegments()
            Logger.logInfo(DECODER_LOG_TAG, "Chapter skip intervals: ${resolved.map { it.key }}")
        }
    }

    private fun refreshSkipSegments() {
        val segments = if (playerPreferences.autoSkipIntroOutro) chapterSegments.toMutableList() else mutableListOf()
        if (playerPreferences.autoSkipIntroOutro && lastOutcomeMediaId == mediaSession?.player?.currentMediaItem?.mediaId) {
            listOf(
                DetectedSegmentEntity.KIND_INTRO to lastOutcome?.intro,
                DetectedSegmentEntity.KIND_OUTRO to lastOutcome?.outro,
            ).forEach { (kind, interval) ->
                if (interval != null && segments.none { it.kind == kind } &&
                    (interval.referenceCount >= 2 || interval.sourcesMask and DetectedSources.CHAPTER != 0)
                ) {
                    segments += AutomaticSkipState.Segment(kind, interval)
                }
            }
        }
        val duration = mediaSession?.player?.duration ?: C.TIME_UNSET
        automaticSkipState.update(CustomSkipSegments.merge(segments, playerPreferences, duration))
    }

    private fun requestAnalysisForCurrentItem() {
        val player = mediaSession?.player ?: return
        if (!playerPreferences.autoSkipIntroOutro) return
        updateChapterSegments(player)
        val item = player.currentMediaItem ?: return
        if (analysisRequestedFor == item.mediaId) return
        val duration = player.duration.validDurationOrNull() ?: return
        if (duration < AnalysisCoordinator.MIN_ANALYSIS_DURATION_MS) return
        val generation = playbackGeneration
        analysisRequestedFor = item.mediaId
        analysisRequestJob = serviceScope.launch {
            // Opening the player must win over background codec and disk work.
            delay(3_000)
            if (generation != playbackGeneration || !playerPreferences.autoSkipIntroOutro) return@launch
            val video = withContext(ioDispatcher) { mediaRepository.getVideoByUri(item.mediaId) }
                ?: return@launch
            val references = withContext(ioDispatcher) { mediaRepository.fetchVideos(video.parentPath) }
                .asSequence()
                .filter { it.uriString != item.mediaId }
                .filter { it.duration >= AnalysisCoordinator.MIN_ANALYSIS_DURATION_MS }
                .sortedBy { kotlin.math.abs(it.duration - duration) }
                .take(AnalysisCoordinator.MAX_REFERENCES)
                .map { ReferenceCandidate(Uri.parse(it.uriString), it.size, it.dateModified * 1000, it.duration) }
                .toList()
            if (references.isEmpty() || generation != playbackGeneration ||
                player.currentMediaItem?.mediaId != item.mediaId || !playerPreferences.autoSkipIntroOutro
            ) return@launch
            analysisCoordinator.request(
                uri = Uri.parse(item.mediaId),
                contentSize = video.size,
                contentModified = video.dateModified * 1000,
                durationMs = duration,
                references = references,
                chapterIntroEndMs = chapterSegments.firstOrNull { it.kind == "INTRO" }?.interval?.endMs,
                chapterOutroStartMs = chapterSegments.firstOrNull { it.kind == "OUTRO" }?.interval?.startMs,
            ) { outcome ->
                serviceScope.launch {
                    if (generation == playbackGeneration && playerPreferences.autoSkipIntroOutro &&
                        player.currentMediaItem?.mediaId == item.mediaId
                    ) {
                        lastOutcome = outcome
                        lastOutcomeMediaId = item.mediaId
                        refreshSkipSegments()
                    }
                }
            }
        }
    }

    private fun startOutroMonitor() {
        if (outroMonitorJob?.isActive == true) return
        outroMonitorJob = serviceScope.launch {
            while (isActive) {
                delay(OUTRO_POLL_INTERVAL_MS)
                if (!playerPreferences.autoSkipIntroOutro && !playerPreferences.customSkipIntroOutro) continue
                val player = mediaSession?.player ?: continue
                if (player.currentMediaItem?.mediaId == null || !player.isCurrentMediaItemSeekable) continue
                val segment = automaticSkipState.candidate(
                    positionMs = player.currentPosition,
                    playing = player.isPlaying,
                    ready = player.playbackState == Player.STATE_READY,
                    scrubbing = scrubbing,
                ) ?: continue
                if (segment.interval.endIsEof && player.repeatMode != Player.REPEAT_MODE_OFF) continue
                executeSkip(player, segment)
            }
        }
    }

    private fun stopOutroMonitor() {
        outroMonitorJob?.cancel()
        outroMonitorJob = null
    }

    // Fixed-rate interpolation may never output the last source timestamp, and Media3 only ends
    // the final item after it does. Pass frames through for the last moments of that item.
    private fun updateInterpolationEndGuard(player: Player) {
        interpolation.endingSoon = InterpolationEndGuard.isEndingSoon(
            durationMs = player.duration,
            positionMs = player.currentPosition,
            hasNext = player.hasNextMediaItem(),
            repeatMode = player.repeatMode,
        )
    }

    private fun startEndGuardMonitor() {
        if (endGuardJob?.isActive == true) return
        endGuardJob = serviceScope.launch {
            while (isActive) {
                mediaSession?.player?.let(::updateInterpolationEndGuard)
                delay(END_GUARD_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopEndGuardMonitor() {
        endGuardJob?.cancel()
        endGuardJob = null
    }

    private fun seekAutomatically(player: Player, positionMs: Long) {
        automaticSeek = true
        try {
            player.seekTo(positionMs)
        } finally {
            automaticSeek = false
        }
    }

    private fun executeSkip(player: Player, segment: AutomaticSkipState.Segment) {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val fromMs = player.currentPosition
        val targetMs = segment.interval.endMs
        automaticSkipState.suppress(segment)
        val token = ++skipTokenCounter
        val undoable = !segment.interval.endIsEof
        pendingUndo = if (undoable) SkipRecord(mediaId, fromMs, token, SystemClock.elapsedRealtime() + UNDO_WINDOW_MS) else null
        seekAutomatically(player, targetMs)
        mediaSession?.let { session ->
            session.sessionExtras = Bundle(session.sessionExtras).apply {
                putString(CustomCommands.SKIP_KIND_KEY, segment.kind)
                putLong(CustomCommands.UNDO_TOKEN_KEY, if (undoable) token else 0)
                putLong(CustomCommands.SKIP_UNDO_EXPIRE_AT_KEY, if (undoable) System.currentTimeMillis() + UNDO_WINDOW_MS else 0)
            }
        }
        Logger.logInfo(DECODER_LOG_TAG, "Automatic skip ${segment.kind}: $fromMs -> $targetMs sources=${segment.interval.sourcesMask}")
    }

    private fun clearSkipExtras() {
        mediaSession?.let { session ->
            session.sessionExtras = Bundle(session.sessionExtras).apply {
                remove(CustomCommands.SKIP_KIND_KEY)
                remove(CustomCommands.UNDO_TOKEN_KEY)
                remove(CustomCommands.SKIP_UNDO_EXPIRE_AT_KEY)
            }
        }
    }

    private fun resetAnalysisState() {
        playbackGeneration++
        analysisRequestJob?.cancel()
        analysisRequestJob = null
        analysisCoordinator.cancel()
        stopOutroMonitor()
        pendingUndo = null
        lastOutcome = null
        lastOutcomeMediaId = null
        analysisRequestedFor = null
        chapterSegments = emptyList()
        automaticSkipState.reset()
        scrubbing = false
        clearSkipExtras()
    }

    private suspend fun savePositionAndStopSession() {
        mediaSession?.run {
            player.currentMediaItem?.let { mediaItem ->
                mediaRepository.updateMediumPosition(
                    uri = mediaItem.mediaId,
                    position = player.currentPosition,
                )
            }
            player.clearMediaItems()
            player.stop()
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        artworkLoadJob?.cancel()
        analysisCoordinator.cancel()
        stopOutroMonitor()
        stopEndGuardMonitor()
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        mediaSession?.run {
            player.clearMediaItems()
            player.stop()
            player.removeListener(playbackStateListener)
            decoderManager.detach()
            player.release()
            release()
            mediaSession = null
        }
        subtitleCacheDir.deleteFiles()
        // Drop the network connection before the scope dies, so a share isn't held open for the
        // lifetime of the process after playback ends.
        runBlocking { dataSourceFactory.release() }
        serviceScope.cancel()
        PlaybackUpdateGuard.setSessionActive(false)
        PlaybackUpdateGuard.setSessionActive(false)
    }

    private fun Long.validDurationOrNull(): Long? = takeIf { it != C.TIME_UNSET && it >= 0 }

    private companion object {
        const val OUTRO_POLL_INTERVAL_MS = 250L
        const val END_GUARD_POLL_INTERVAL_MS = 250L
        const val UNDO_WINDOW_MS = 6_000L
    }

    private suspend fun updatedMediaItemsWithMetadata(
        mediaItems: List<MediaItem>,
    ): List<MediaItem> = supervisorScope {
        mediaItems.map { mediaItem ->
            async {
                val uri = mediaItem.mediaId.toUri()
                val isNetwork = mediaItem.isNetworkMediaItem()

                val video = if (isNetwork) null else mediaRepository.getVideoByUri(uri = mediaItem.mediaId)
                val videoState = mediaRepository.getVideoState(uri = mediaItem.mediaId)

                val externalSubs = videoState?.externalSubs ?: emptyList()
                val savedAudio = videoState?.externalAudio.orEmpty()
                val externalAudio = withContext(Dispatchers.IO) {
                    savedAudio.filter { uri ->
                        runCatching { contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } == true }
                            .getOrDefault(false)
                    }
                }
                val localSubs = if (!isNetwork) {
                    // A saved playback state's path field contains the URI, not a filesystem path.
                    (video?.path?.takeIf { it.isNotBlank() } ?: getPath(uri))?.let {
                        File(it).getLocalSubtitles(
                            context = this@PlayerService,
                            excludeSubsList = externalSubs,
                        )
                    } ?: emptyList()
                } else {
                    emptyList()
                }

                val existingSubConfigurations = mediaItem.localConfiguration?.subtitleConfigurations ?: emptyList()
                val defaultLocalSubtitle = localSubs.firstOrNull().takeUnless {
                    existingSubConfigurations.any { it.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 }
                }
                val subConfigurations = (localSubs + externalSubs).map { subtitleUri ->
                    uriToSubtitleConfiguration(
                        uri = subtitleUri,
                        subtitleEncoding = playerPreferences.subtitleTextEncoding,
                        isSelected = subtitleUri == defaultLocalSubtitle,
                    )
                }

                // Local items get a placeholder now and their real artwork in the background;
                // network items have no thumbnail to extract, so keep any supplied artwork.
                val artworkUri = if (isNetwork) {
                    mediaItem.mediaMetadata.artworkUri ?: getDefaultArtworkUri()
                } else {
                    getDefaultArtworkUri()
                }

                val title = mediaItem.mediaMetadata.title ?: video?.nameWithExtension ?: getFilenameFromUri(uri)
                val positionMs = mediaItem.mediaMetadata.positionMs ?: videoState?.position
                val videoScale = mediaItem.mediaMetadata.videoZoom ?: videoState?.videoScale
                val playbackSpeed = mediaItem.mediaMetadata.playbackSpeed ?: videoState?.playbackSpeed
                val audioTrackIndex = mediaItem.mediaMetadata.audioTrackIndex ?: videoState?.audioTrackIndex
                val subtitleTrackIndex = mediaItem.mediaMetadata.subtitleTrackIndex ?: videoState?.subtitleTrackIndex
                val subtitleDelay = mediaItem.mediaMetadata.subtitleDelayMilliseconds ?: videoState?.subtitleDelayMilliseconds
                val subtitleSpeed = mediaItem.mediaMetadata.subtitleSpeed ?: videoState?.subtitleSpeed

                mediaItem.buildUpon().apply {
                    setSubtitleConfigurations(existingSubConfigurations + subConfigurations)
                    setMediaMetadata(
                        MediaMetadata.Builder().apply {
                            setTitle(title)
                            setArtworkUri(artworkUri)
                            setExtras(
                                positionMs = positionMs,
                                videoScale = videoScale,
                                playbackSpeed = playbackSpeed,
                                audioTrackIndex = audioTrackIndex.takeIf { externalAudio == savedAudio },
                                subtitleTrackIndex = subtitleTrackIndex,
                                subtitleDelayMilliseconds = subtitleDelay,
                                subtitleSpeed = subtitleSpeed,
                                videoDecoderMode = mediaItem.mediaMetadata.videoDecoderMode,
                                audioDecoderMode = mediaItem.mediaMetadata.audioDecoderMode,
                            )
                        }.build(),
                    )
                }.build().withExternalAudio(externalAudio)
            }
        }.awaitAll()
    }

    private fun publishDecoderState() {
        val session = mediaSession ?: return
        val recoveryState = decoderRecoveryManager.state
        session.sessionExtras = Bundle(session.sessionExtras).apply {
            putString(CustomCommands.VIDEO_DECODER_MODE_KEY, decoderManager.activeVideoMode?.name)
            putString(CustomCommands.AUDIO_DECODER_MODE_KEY, decoderManager.activeAudioMode?.name)
            putString(CustomCommands.DECODER_RECOVERY_STATUS_KEY, recoveryState.status.name)
            putString(CustomCommands.DECODER_RECOVERY_TRACK_TYPE_KEY, recoveryState.trackType?.name)
            putString(CustomCommands.UNSUPPORTED_DECODER_MODE_KEY, recoveryState.unsupportedMode?.name)
        }
    }

    private fun retryDecoderWith(retry: DecoderRetry): Boolean {
        val player = mediaSession?.player as? ExoPlayer ?: return false
        val shouldPrepare = player.playerError != null
        selectDecoder(retry.trackType, retry.mode)
        if (shouldPrepare && player.mediaItemCount > 0) {
            player.prepare()
        }
        return true
    }

    private fun handleUnsupportedTracks(tracks: Tracks) {
        handleUnsupportedTrack(tracks, DecoderTrackType.VIDEO)
        handleUnsupportedTrack(tracks, DecoderTrackType.AUDIO)
    }

    private fun handleUnsupportedTrack(tracks: Tracks, trackType: DecoderTrackType) {
        val mediaTrackType = trackType.mediaTrackType
        val groups = tracks.groups.filter { it.type == mediaTrackType }
        val hasTrack = groups.isNotEmpty() || trackSelector.unmappedTrackCount(mediaTrackType) > 0
        if (!hasTrack || groups.any { it.isSupported(true) }) return

        handleDecoderFailure(trackType)
    }

    private fun handleDecoderFailure(trackType: DecoderTrackType) {
        val mode = when (trackType) {
            DecoderTrackType.VIDEO -> decoderManager.videoMode
            DecoderTrackType.AUDIO -> decoderManager.audioMode
        }
        val retry = decoderRecoveryManager.onDecoderFailure(trackType, mode)
        publishDecoderState()
        if (retry == null) return
        serviceScope.launch {
            if (!retryDecoderWith(retry)) decoderRecoveryManager.onNonDecoderError()
        }
    }

    private fun selectDecoder(trackType: DecoderTrackType, mode: DecoderMode) {
        when (trackType) {
            DecoderTrackType.VIDEO -> decoderManager.selectVideoDecoder(mode)
            DecoderTrackType.AUDIO -> decoderManager.selectAudioDecoder(mode)
        }
        val player = mediaSession?.player
        val mediaItem = player?.currentMediaItem
        if (mediaItem != null) {
            val updatedItem = when (trackType) {
                DecoderTrackType.VIDEO -> mediaItem.copy(videoDecoderMode = mode)
                DecoderTrackType.AUDIO -> mediaItem.copy(audioDecoderMode = mode)
            }
            player.replaceMediaItem(player.currentMediaItemIndex, updatedItem)
        }
        publishDecoderState()
    }

    private fun restoreDecoderChoices(mediaItem: MediaItem?) {
        val videoMode = mediaItem?.mediaMetadata?.videoDecoderMode ?: DecoderMode.AUTO
        val audioMode = mediaItem?.mediaMetadata?.audioDecoderMode ?: DecoderMode.AUTO
        decoderRecoveryManager.onUserSelection(DecoderTrackType.VIDEO, videoMode)
        decoderRecoveryManager.onUserSelection(DecoderTrackType.AUDIO, audioMode)
        selectDecoder(DecoderTrackType.VIDEO, videoMode)
        selectDecoder(DecoderTrackType.AUDIO, audioMode)
    }

    private fun DefaultTrackSelector.unmappedTrackCount(trackType: Int): Int {
        val trackGroups = currentMappedTrackInfo?.unmappedTrackGroups ?: return 0
        return (0 until trackGroups.length).count { index ->
            trackGroups[index].type == trackType
        }
    }

    private fun PlaybackException.decoderTrackType(): DecoderTrackType? {
        val playbackError = this as? ExoPlaybackException ?: return null
        val formatTrackType = MimeTypes.getTrackType(playbackError.rendererFormat?.sampleMimeType)
        val mediaTrackType = formatTrackType.takeIf {
            it == C.TRACK_TYPE_VIDEO || it == C.TRACK_TYPE_AUDIO
        } ?: trackSelector.currentMappedTrackInfo
            ?.takeIf { playbackError.rendererIndex in 0 until it.rendererCount }
            ?.getRendererType(playbackError.rendererIndex)

        return when (mediaTrackType) {
            C.TRACK_TYPE_VIDEO -> DecoderTrackType.VIDEO
            C.TRACK_TYPE_AUDIO -> DecoderTrackType.AUDIO
            else -> null
        }
    }

    private fun getDefaultArtworkUri(): Uri = Uri.Builder().apply {
        val defaultArtwork = R.drawable.artwork_default
        scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        authority(resources.getResourcePackageName(defaultArtwork))
        appendPath(resources.getResourceTypeName(defaultArtwork))
        appendPath(resources.getResourceEntryName(defaultArtwork))
    }.build()

    private fun loadArtworkForCurrentMediaItem() {
        artworkLoadJob?.cancel()
        artworkLoadJob = serviceScope.launch(Dispatchers.Main) {
            val player = mediaSession?.player ?: return@launch
            val currentMediaItem = player.currentMediaItem ?: return@launch
            if (currentMediaItem.mediaMetadata.artworkData != null) return@launch

            val artworkUri = loadArtworkForMediaItem(currentMediaItem)
                ?: getDefaultArtworkUri()

            val updatedPlayer = mediaSession?.player ?: return@launch
            val updatedMediaItem = updatedPlayer.currentMediaItem ?: return@launch
            if (updatedMediaItem.mediaId != currentMediaItem.mediaId) return@launch

            updatedPlayer.replaceMediaItem(
                updatedPlayer.currentMediaItemIndex,
                updatedMediaItem.withArtwork(artworkUri),
            )
        }
    }
    private suspend fun loadArtworkForMediaItem(mediaItem: MediaItem): Uri? = withContext(Dispatchers.IO) {
        val defaultArtwork = getDefaultArtworkUri()
        val uri = mediaItem.mediaMetadata.artworkUri
            ?.takeUnless { it == defaultArtwork }
            ?: if (mediaItem.isNetworkMediaItem()) {
                return@withContext null
            } else {
                mediaItem.mediaId.toUri()
            }
        return@withContext try {
            val request = ImageRequest.Builder(this@PlayerService)
                .data(uri)
                .size(512, 512)
                .build()
            imageLoader.execute(request)
            val diskCache = imageLoader.diskCache ?: return@withContext null
            return@withContext diskCache.openSnapshot(uri.toString())?.use { snapshot ->
                snapshot.data.toFile().toUri()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    private fun MediaItem.isNetworkMediaItem(): Boolean {
        val uri = localConfiguration?.uri ?: return false
        return uri.scheme?.lowercase() in REMOTE_SCHEMES || NetworkUri.isNetworkUri(uri)
    }

    private fun MediaItem.withArtwork(uri: Uri): MediaItem = buildUpon()
        .setMediaMetadata(
            mediaMetadata.buildUpon()
                .setArtworkUri(uri)
                .build(),
        )
        .build()
}

internal val PlaybackException.isDecoderFailure: Boolean
    get() = when (errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
        -> true

        else -> false
    }

private val DecoderTrackType.mediaTrackType: Int
    get() = when (this) {
        DecoderTrackType.VIDEO -> C.TRACK_TYPE_VIDEO
        DecoderTrackType.AUDIO -> C.TRACK_TYPE_AUDIO
    }

private const val DECODER_LOG_TAG = "Decoder"

@get:UnstableApi
@set:UnstableApi
private var Player.playerSpecificSkipSilenceEnabled: Boolean
    @OptIn(UnstableApi::class)
    get() = when (this) {
        is ExoPlayer -> this.skipSilenceEnabled
        else -> false
    }
    set(value) {
        when (this) {
            is ExoPlayer -> this.skipSilenceEnabled = value
        }
    }

@get:UnstableApi
@set:UnstableApi
private var Player.playerSpecificSubtitleDelayMilliseconds: Long
    @OptIn(UnstableApi::class)
    get() = when (this) {
        is ExoPlayer -> this.subtitleDelayMilliseconds
        else -> 0L
    }
    set(value) {
        when (this) {
            is ExoPlayer -> this.subtitleDelayMilliseconds = value
        }
    }

@get:UnstableApi
@set:UnstableApi
private var Player.playerSpecificSubtitleSpeed: Float
    @OptIn(UnstableApi::class)
    get() = when (this) {
        is ExoPlayer -> this.subtitleSpeed
        else -> 0f
    }
    set(value) {
        when (this) {
            is ExoPlayer -> this.subtitleSpeed = value
        }
    }

/** Schemes Media3 streams over the network itself, as opposed to reading from local storage. */
private val REMOTE_SCHEMES = setOf("http", "https", "rtsp")

// MergingMediaPeriod prepends a numeric child index to each Format.id. External
// audio can add another merge, so strip all leading indices but preserve the URI.
private val mergedTrackIdPrefix = Regex("^(?:[0-9]+:)+")

private fun Tracks.externalSubtitleTrack(id: String): Tracks.Group? = groups.firstOrNull {
    it.type == C.TRACK_TYPE_TEXT && it.isSupported &&
        it.getTrackFormat(0).id?.replaceFirst(mergedTrackIdPrefix, "") == id
}
