package dev.anilbeesetti.nextplayer.core.datastore.migration

import androidx.datastore.core.DataMigration
import dev.anilbeesetti.nextplayer.core.model.FrameInterpolationTarget
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences

/** Persist the retired 90 fps setting as 60 fps without changing playback preferences. */
object FrameInterpolationTargetMigration : DataMigration<PlayerPreferences> {
    override suspend fun shouldMigrate(currentData: PlayerPreferences): Boolean =
        !FrameInterpolationTarget.isSupported(currentData.frameInterpolationTargetFps)

    override suspend fun migrate(currentData: PlayerPreferences): PlayerPreferences =
        FrameInterpolationTarget.normalize(currentData)

    override suspend fun cleanUp() = Unit
}
