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
    private var pausedSurfaceSizeLocked = false
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updatePausedSurfaceSize()
        }

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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // SurfaceView otherwise uses the fixed buffer size as its preferred View size.
        // Compose measures with AT_MOST: the display must still fill the fitted video bounds.
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            getDefaultSize(suggestedMinimumHeight, heightMeasureSpec),
        )
    }

    fun setPlayer(player: Player) {
        if (attachedPlayer === player) return
        detachPlayer()
        attachedPlayer = player
        player.addListener(playerListener)
        if (player.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) player.setVideoSurfaceView(this)
        updatePausedSurfaceSize()
    }

    /** Keep the queued frame through layout resizes while no new video frames are arriving.
     * Resizing the buffer queue while paused discards its visible buffer on some devices,
     * including the SDR effects Surface. Scaling the View can reuse that buffer unchanged.
     * Resume normal layout sizing once playback resumes; never seek or briefly play to redraw.
     */
    private fun updatePausedSurfaceSize() {
        val player = attachedPlayer
        val keepFrame = player != null && !player.playWhenReady &&
            player.playbackState == Player.STATE_READY
        if (keepFrame && !pausedSurfaceSizeLocked) {
            val frame = holder.surfaceFrame
            if (frame.width() > 0 && frame.height() > 0) {
                holder.setFixedSize(frame.width(), frame.height())
                pausedSurfaceSizeLocked = true
            }
        } else if (!keepFrame && pausedSurfaceSizeLocked) {
            pausedSurfaceSizeLocked = false
            holder.setSizeFromLayout()
        }
    }

    private fun detachPlayer() {
        attachedPlayer?.let { player ->
            player.removeListener(playerListener)
            if (player.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) player.clearVideoSurfaceView(this)
        }
        attachedPlayer = null
        updatePausedSurfaceSize()
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

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        onSurfaceAvailable(holder.surface)
        updatePausedSurfaceSize()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = onSurfaceDestroyed(holder.surface)

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (Build.VERSION.SDK_INT == 34) {
            syncGroup?.markSyncReady()
            syncGroup = null
        }
    }
}
