package dev.anilbeesetti.nextplayer.feature.player

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.data.repository.MediaRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.domain.GetSortedPlaylistUseCase
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.LoopMode
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.core.model.Video
import dev.anilbeesetti.nextplayer.core.model.VideoContentScale
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import dev.anilbeesetti.nextplayer.feature.player.model.DecoderServiceState
import dev.anilbeesetti.nextplayer.feature.player.state.SubtitleOptionsEvent
import dev.anilbeesetti.nextplayer.feature.player.state.VideoZoomEvent
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import dev.anilbeesetti.nextplayer.core.cast.CastSessionManager
import dev.anilbeesetti.nextplayer.core.cast.model.CastMedia
import dev.anilbeesetti.nextplayer.core.cast.model.CastState
import dev.anilbeesetti.nextplayer.core.cast.model.DlnaDevice
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class PlayerViewModel(
    private val mediaRepository: MediaRepository,
    private val castSessionManager: CastSessionManager,
    private val preferencesRepository: PreferencesRepository,
    private val getSortedPlaylistUseCase: GetSortedPlaylistUseCase,
    @InjectedParam internal var output: Output,
) : MviViewModel<PlayerUiState, PlayerAction>() {

    data class Output(
        val navigateUp: () -> Unit,
        val selectSubtitle: () -> Unit,
        val selectAudio: () -> Unit,
        val playInBackground: () -> Unit,
        val setVideoDecoderMode: (DecoderMode) -> Unit,
        val setAudioDecoderMode: (DecoderMode) -> Unit,
        val tryDecoderFallback: () -> Unit,
    )

    private val stateInternal = MutableStateFlow(
        PlayerUiState(
            playerPreferences = preferencesRepository.playerPreferences.value,
            applicationPreferences = preferencesRepository.applicationPreferences.value,
        ),
    )
    override val state: StateFlow<PlayerUiState> = stateInternal.asStateFlow()

    /** Live SSDP discovery of DLNA renderers, consumed by the cast device sheet. */
    val castDevices: Flow<List<DlnaDevice>> get() = castSessionManager.discoverDevices()

    suspend fun probeCastDevice(host: String): DlnaDevice? = castSessionManager.probeDevice(host)

    init {
        viewModelScope.launch {
            preferencesRepository.playerPreferences.collect { prefs ->
                stateInternal.update { it.copy(playerPreferences = prefs) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect { prefs ->
                stateInternal.update { it.copy(applicationPreferences = prefs) }
            }
        }
        viewModelScope.launch {
            castSessionManager.state.collect { castState ->
                stateInternal.update { it.copy(castState = castState) }
            }
        }
    }

    private fun startCast(device: DlnaDevice, uri: String, title: String, durationMs: Long) {
        viewModelScope.launch {
            val media = buildCastMedia(uri, title, durationMs)
            castSessionManager.startCast(device = device, media = media)
        }
    }

    /** Returns (possiblyIncompatible, mime) from the renderer's declared sink formats. */
    suspend fun castCompatibility(
        device: DlnaDevice,
        uri: String,
        title: String,
        durationMs: Long,
    ): Pair<Boolean?, String> {
        val media = buildCastMedia(uri, title, durationMs)
        return castSessionManager.isLikelyIncompatible(device, media) to media.mime
    }

    private suspend fun buildCastMedia(uri: String, title: String, durationMs: Long): CastMedia {
        val size = mediaRepository.getVideoByUri(uri)?.size
        return CastMedia(
            title = title,
            uri = uri,
            mime = castMimeFor(uri),
            sizeBytes = size,
            durationMs = durationMs,
        )
    }

    private fun castMimeFor(uri: String): String {
        val extension = uri.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (extension) {
            "mkv", "webm" -> "video/x-matroska"
            "ts", "m2ts" -> "video/mp2t"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "flv" -> "video/x-flv"
            "wmv" -> "video/x-ms-wmv"
            "3gp" -> "video/3gpp"
            else -> "video/mp4"
        }
    }

    override fun onAction(action: PlayerAction) {
        when (action) {
            is PlayerAction.NavigateUp -> output.navigateUp()
            is PlayerAction.SelectSubtitle -> output.selectSubtitle()
            is PlayerAction.SelectAudio -> output.selectAudio()
            is PlayerAction.PlayInBackground -> output.playInBackground()
            is PlayerAction.SetVideoDecoderMode -> output.setVideoDecoderMode(action.mode)
            is PlayerAction.SetAudioDecoderMode -> output.setAudioDecoderMode(action.mode)
            is PlayerAction.TryDecoderFallback -> output.tryDecoderFallback()
            is PlayerAction.UpdateDecoderServiceState -> stateInternal.update { it.copy(decoderServiceState = action.state) }
            is PlayerAction.CastStart -> startCast(action.device, action.uri, action.title, action.durationMs)
            is PlayerAction.CastTogglePlayPause -> viewModelScope.launch {
                if (castSessionManager.state.value.isPlaying) castSessionManager.pause() else castSessionManager.play()
            }
            is PlayerAction.CastSeekTo -> viewModelScope.launch { castSessionManager.seekTo(action.positionMs) }
            is PlayerAction.CastSetVolume -> viewModelScope.launch { castSessionManager.setVolume(action.value) }
            is PlayerAction.CastStop -> viewModelScope.launch { castSessionManager.stopCast() }
            is PlayerAction.CastClearError -> castSessionManager.clearError()
            is PlayerAction.UpdateSkipUndo -> stateInternal.update {
                it.copy(
                    skipUndoKind = action.kind,
                    skipUndoToken = action.token,
                    skipUndoExpireAt = action.expireAt,
                )
            }
            is PlayerAction.UpdatePlayWhenReady -> stateInternal.update { it.copy(playWhenReady = action.playWhenReady) }
            is PlayerAction.UpdatePlayerBrightness -> updatePlayerBrightness(action.value)
            is PlayerAction.SetLoopMode -> setLoopMode(action.loopMode)
            is PlayerAction.ToggleTimeDisplay -> toggleTimeDisplay()
            is PlayerAction.OnVideoZoomEvent -> onVideoZoomEvent(action.event)
            is PlayerAction.OnSubtitleOptionEvent -> onSubtitleOptionEvent(action.event)
        }
    }

    suspend fun getPlaylistFromUri(uri: Uri): List<Video> {
        return getSortedPlaylistUseCase.invoke(uri)
    }

    private fun updateVideoZoom(uri: String, zoom: Float) {
        viewModelScope.launch {
            mediaRepository.updateMediumZoom(uri, zoom)
        }
    }

    private fun updatePlayerBrightness(value: Float) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(playerBrightness = value) }
        }
    }

    private fun updateVideoContentScale(contentScale: VideoContentScale) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(playerVideoZoom = contentScale) }
        }
    }

    private fun setLoopMode(loopMode: LoopMode) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(loopMode = loopMode) }
        }
    }

    private fun toggleTimeDisplay() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(showRemainingTime = !it.showRemainingTime) }
        }
    }

    private fun onVideoZoomEvent(event: VideoZoomEvent) {
        when (event) {
            is VideoZoomEvent.ContentScaleChanged -> {
                updateVideoContentScale(event.contentScale)
            }
            is VideoZoomEvent.ZoomChanged -> {
                updateVideoZoom(event.mediaItem.mediaId, event.zoom)
            }
        }
    }

    private fun onSubtitleOptionEvent(event: SubtitleOptionsEvent) {
        when (event) {
            is SubtitleOptionsEvent.DelayChanged -> {
                updateSubtitleDelay(event.mediaItem.mediaId, event.delay)
            }
            is SubtitleOptionsEvent.SpeedChanged -> {
                updateSubtitleSpeed(event.mediaItem.mediaId, event.speed)
            }
        }
    }

    private fun updateSubtitleDelay(uri: String, delay: Long) {
        viewModelScope.launch {
            mediaRepository.updateSubtitleDelay(uri, delay)
        }
    }

    private fun updateSubtitleSpeed(uri: String, speed: Float) {
        viewModelScope.launch {
            mediaRepository.updateSubtitleSpeed(uri, speed)
        }
    }
}

@Stable
data class PlayerUiState(
    val playerPreferences: PlayerPreferences = PlayerPreferences(),
    val applicationPreferences: ApplicationPreferences = ApplicationPreferences(),
    val playWhenReady: Boolean = true,
    val decoderServiceState: DecoderServiceState = DecoderServiceState(),
    val skipUndoKind: String? = null,
    val skipUndoToken: Long = 0L,
    val skipUndoExpireAt: Long = 0L,
    val castState: CastState = CastState(),
)

sealed interface PlayerAction {
    data object NavigateUp : PlayerAction
    data object SelectSubtitle : PlayerAction
    data object SelectAudio : PlayerAction
    data object PlayInBackground : PlayerAction
    data class SetVideoDecoderMode(val mode: DecoderMode) : PlayerAction
    data class SetAudioDecoderMode(val mode: DecoderMode) : PlayerAction
    data object TryDecoderFallback : PlayerAction
    data class UpdateDecoderServiceState(val state: DecoderServiceState) : PlayerAction
    data class UpdateSkipUndo(val kind: String?, val token: Long, val expireAt: Long) : PlayerAction
    data class CastStart(val device: DlnaDevice, val uri: String, val title: String, val durationMs: Long) : PlayerAction
    data object CastTogglePlayPause : PlayerAction
    data class CastSeekTo(val positionMs: Long) : PlayerAction
    data class CastSetVolume(val value: Int) : PlayerAction
    data object CastStop : PlayerAction
    data object CastClearError : PlayerAction
    data class UpdatePlayWhenReady(val playWhenReady: Boolean) : PlayerAction
    data class UpdatePlayerBrightness(val value: Float) : PlayerAction
    data class SetLoopMode(val loopMode: LoopMode) : PlayerAction
    data object ToggleTimeDisplay : PlayerAction
    data class OnVideoZoomEvent(val event: VideoZoomEvent) : PlayerAction
    data class OnSubtitleOptionEvent(val event: SubtitleOptionsEvent) : PlayerAction
}
