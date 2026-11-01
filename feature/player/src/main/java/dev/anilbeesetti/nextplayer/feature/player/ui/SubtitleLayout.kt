package dev.anilbeesetti.nextplayer.feature.player.ui

/** Reserve measured controls only for cues without an authored line position. */
internal fun subtitleBottomPaddingFraction(
    viewportHeightPx: Int,
    controlsHeightPx: Int,
    isInPictureInPictureMode: Boolean,
): Float {
    // Media3's normal caption baseline remains unchanged when controls are absent.
    val normalPadding = 0.08f
    if (isInPictureInPictureMode || viewportHeightPx <= 0 || controlsHeightPx <= 0) return normalPadding
    return (controlsHeightPx.toFloat() / viewportHeightPx).coerceIn(normalPadding, 0.5f)
}
