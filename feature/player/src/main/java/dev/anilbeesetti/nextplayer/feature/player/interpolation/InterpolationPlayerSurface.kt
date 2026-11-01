package dev.anilbeesetti.nextplayer.feature.player.interpolation

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.window.SurfaceSyncGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player

/** Own the displayed Surface explicitly so its frame-rate hint describes generated output frames. */
@Composable
internal fun InterpolationPlayerSurface(
    player: Player,
    modifier: Modifier,
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: (Surface) -> Unit,
) {
    AndroidView(
        factory = { InterpolationVideoSurfaceView(it, onSurfaceAvailable, onSurfaceDestroyed) },
        modifier = modifier,
        onRelease = { it.releasePlayer() },
        update = { it.setPlayer(player) },
    )
}

private class InterpolationVideoSurfaceView(
    context: Context,
    private val onSurfaceAvailable: (Surface) -> Unit,
    private val onSurfaceDestroyed: (Surface) -> Unit,
) : SurfaceView(context), SurfaceHolder.Callback {
    private var attachedPlayer: Player? = null
    private var syncGroup: SurfaceSyncGroup? = null
    private var released = false
    private val playerListener = object : Player.Listener {
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
            if (availableCommands.contains(Player.COMMAND_SET_VIDEO_SURFACE)) attachedPlayer?.setVideoSurfaceView(this@InterpolationVideoSurfaceView)
        }

        override fun onSurfaceSizeChanged(width: Int, height: Int) {
            // Preserve Media3 PlayerSurface's Android 14 Compose resizing workaround (b/334901521).
            if (Build.VERSION.SDK_INT == 34 && !Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
                post {
                    if (released) return@post
                    rootSurfaceControl?.let { root ->
                        syncGroup?.markSyncReady()
                        val group = SurfaceSyncGroup("next-interpolation-surface-resize")
                        if (group.add(root) {}) {
                            syncGroup = group
                            invalidate()
                            root.applyTransactionOnDraw(SurfaceControl.Transaction())
                        } else {
                            group.markSyncReady()
                        }
                    }
                }
            }
        }
    }

    init {
        holder.addCallback(this)
    }

    fun setPlayer(player: Player) {
        if (attachedPlayer === player) return
        detachPlayer()
        attachedPlayer = player
        player.addListener(playerListener)
        if (player.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) player.setVideoSurfaceView(this)
    }

    private fun detachPlayer() {
        attachedPlayer?.let { player ->
            player.removeListener(playerListener)
            if (player.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) player.clearVideoSurfaceView(this)
        }
        attachedPlayer = null
    }

    fun releasePlayer() {
        released = true
        onSurfaceDestroyed(holder.surface)
        detachPlayer()
        holder.removeCallback(this)
        if (Build.VERSION.SDK_INT == 34) syncGroup?.markSyncReady()
        syncGroup = null
    }

    override fun surfaceCreated(holder: SurfaceHolder) = onSurfaceAvailable(holder.surface)

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = onSurfaceAvailable(holder.surface)

    override fun surfaceDestroyed(holder: SurfaceHolder) = onSurfaceDestroyed(holder.surface)

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (Build.VERSION.SDK_INT == 34) {
            syncGroup?.markSyncReady()
            syncGroup = null
        }
    }
}
