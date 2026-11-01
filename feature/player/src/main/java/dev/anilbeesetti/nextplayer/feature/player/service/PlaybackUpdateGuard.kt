package dev.anilbeesetti.nextplayer.feature.player.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keep update dialogs out of active video and background audio sessions, including buffering. */
object PlaybackUpdateGuard {
    private val active = MutableStateFlow(false)
    val sessionActive = active.asStateFlow()
    internal fun setSessionActive(value: Boolean) { active.value = value }
}
