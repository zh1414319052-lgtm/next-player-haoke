package dev.anilbeesetti.nextplayer.core.datastore.migration

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import dev.anilbeesetti.nextplayer.core.datastore.datasource.PlayerPreferencesDataSource
import dev.anilbeesetti.nextplayer.core.datastore.serializer.PlayerPreferencesSerializer
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FrameInterpolationTargetMigrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun retiredAndInvalidTargetsArePersistedAsSixtyWithoutChangingOtherPreferences() = runBlocking {
        for (target in listOf(90, 120, -1)) {
            for (enabled in listOf(false, true)) {
                val original = PlayerPreferences(
                    frameInterpolationTargetFps = target,
                    frameInterpolationEnabled = enabled,
                    autoplay = false,
                    defaultPlaybackSpeed = 1.25f,
                    preferredAudioLanguage = "en",
                    preferredSubtitleLanguage = "zh-Hant",
                )
                val expected = original.copy(frameInterpolationTargetFps = 60)
                val file = temporaryFolder.newFile("preferences-$target-$enabled.json")
                file.writeText(Json.encodeToString(original))

                withStore(file) { store -> assertEquals(expected, store.data.first()) }

                assertEquals(expected, Json.decodeFromString<PlayerPreferences>(file.readText()))
                withStore(file) { store ->
                    val reopened = store.data.first()
                    assertEquals(expected, reopened)
                    assertFalse(FrameInterpolationTargetMigration.shouldMigrate(reopened))
                }
            }
        }
    }

    @Test fun originalOffAndDoubleRatePreferencesDoNotNeedMigration() = runBlocking {
        for (target in listOf(0, 60)) {
            for (enabled in listOf(false, true)) {
                val original = PlayerPreferences(frameInterpolationTargetFps = target, frameInterpolationEnabled = enabled)
                assertFalse(FrameInterpolationTargetMigration.shouldMigrate(original))
                assertEquals(original, FrameInterpolationTargetMigration.migrate(original))
            }
        }
    }

    @Test fun laterPreferenceUpdatesCannotPersistARetiredTarget() = runBlocking {
        val file = File(temporaryFolder.root, "updated-preferences.json")
        withStore(file) { store ->
            val source = PlayerPreferencesDataSource(store)
            source.update { it.copy(frameInterpolationTargetFps = 90, frameInterpolationEnabled = true, autoplay = false) }
            val updated = source.preferences.first()
            assertEquals(60, updated.frameInterpolationTargetFps)
            assertEquals(true, updated.frameInterpolationEnabled)
            assertEquals(false, updated.autoplay)
            source.update { it.copy(frameInterpolationTargetFps = 0) }
            assertEquals(updated.copy(frameInterpolationTargetFps = 0), source.preferences.first())
        }
        assertEquals(0, Json.decodeFromString<PlayerPreferences>(file.readText()).frameInterpolationTargetFps)
    }

    private suspend fun withStore(file: File, block: suspend (DataStore<PlayerPreferences>) -> Unit) {
        val job = SupervisorJob()
        val store = DataStoreFactory.create(
            serializer = PlayerPreferencesSerializer,
            migrations = listOf(FrameInterpolationTargetMigration),
            scope = CoroutineScope(job + Dispatchers.IO),
            produceFile = { file },
        )
        try {
            block(store)
        } finally {
            job.cancelAndJoin()
        }
    }
}
