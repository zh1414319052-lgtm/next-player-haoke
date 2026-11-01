package dev.anilbeesetti.nextplayer.core.cast.model

/** A DLNA MediaRenderer discovered on the local network (or added manually). */
data class DlnaDevice(
    val name: String,
    val udn: String,
    /** Base the device was described at, e.g. http://192.168.1.20:49152 */
    val locationUrl: String,
    /** Absolute AVTransport control URL. */
    val avTransportUrl: String,
    /** Absolute AVTransport SCPD (service description) URL, for sink protocol info. */
    val avTransportScpdUrl: String? = null,
    /** Absolute RenderingControl control URL, when the device exposes one. */
    val renderingControlUrl: String?,
    /** Raw sink protocol info from the device, when available. */
    val sinkProtocolInfo: Set<String> = emptySet(),
)

/** Remote transport state reported by the renderer. */
enum class CastTransportState {
    PLAYING,
    PAUSED,
    STOPPED,
    TRANSITIONING,
    NO_MEDIA,
    UNKNOWN,
}

data class CastMedia(
    val title: String,
    val uri: String,
    val mime: String,
    val sizeBytes: Long?,
    val durationMs: Long?,
)

data class CastError(
    /** True when the failure pattern points to the TV being unable to decode the media. */
    val possiblyIncompatible: Boolean,
    val message: String,
)

/** What the UI observes while a cast session exists. */
data class CastState(
    val device: DlnaDevice? = null,
    val media: CastMedia? = null,
    val transportState: CastTransportState = CastTransportState.NO_MEDIA,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val volume: Int? = null,
    val error: CastError? = null,
) {
    val isActive: Boolean get() = device != null
    val isPlaying: Boolean get() = transportState == CastTransportState.PLAYING
}
