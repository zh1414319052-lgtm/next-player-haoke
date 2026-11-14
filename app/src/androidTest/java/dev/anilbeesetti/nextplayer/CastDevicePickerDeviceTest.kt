package dev.anilbeesetti.nextplayer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anilbeesetti.nextplayer.core.cast.model.CastState
import dev.anilbeesetti.nextplayer.core.cast.model.DlnaDevice
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.feature.player.ui.CastDeviceSheet
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CastDevicePickerDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun duplicateAdvertisementsManualAdditionRefreshAndReopenKeepUniqueSelectableRows() {
        val tv = device("Living room", "uuid:23938114-6002-7277-3000-683943DC7E0A")
        val manual = device("Manual TV", "uuid:manual")
        val updates = MutableStateFlow(listOf(tv, tv, tv.copy(udn = tv.udn.lowercase())))
        val visible = mutableStateOf(true)
        var starts = 0
        var cancellations = 0
        var selected: DlnaDevice? = null
        val discoveries = flow {
            starts++
            try {
                emitAll(updates)
            } finally {
                cancellations++
            }
        }
        compose.setContent {
            NextPlayerTheme(darkTheme = true, dynamicColor = false) {
                if (visible.value) {
                    CastDeviceSheet(
                        deviceFlow = discoveries,
                        castState = CastState(),
                        probeDevice = { manual },
                        checkCompatibility = { false to "video/mp4" },
                        startCast = { selected = it },
                        stopCast = {},
                        onDismiss = { visible.value = false },
                    )
                }
            }
        }
        compose.onAllNodesWithText(tv.name).assertCountEquals(1)
        compose.onNode(hasSetTextAction()).performTextInput("192.168.1.21")
        compose.onNodeWithText(compose.activity.getString(R.string.cast_add)).performClick()
        compose.onNodeWithText(manual.name).assertIsDisplayed()
        val updated = tv.copy(name = "Living room updated", avTransportUrl = "http://127.0.0.1/new-control")
        compose.runOnIdle { updates.value = listOf(updated, updated) }
        compose.onAllNodesWithText(updated.name).assertCountEquals(1)
        compose.onNodeWithText(manual.name).assertIsDisplayed()
        compose.onNodeWithText(updated.name).performClick()
        compose.runOnIdle {
            assertEquals(updated, selected)
            visible.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, cancellations)
            visible.value = true
        }
        compose.onAllNodesWithText(updated.name).assertCountEquals(1)
        compose.runOnIdle { assertEquals(2, starts) }
    }

    @Test
    fun failedScanAndFailedManualProbeAllowRetryWithoutCrashing() {
        val manual = device("Retry TV", "uuid:retry")
        var probes = 0
        val failingScan = flow<List<DlnaDevice>> { throw IOException("Synthetic scan failure") }
        compose.setContent {
            NextPlayerTheme(darkTheme = true, dynamicColor = false) {
                CastDeviceSheet(
                    deviceFlow = failingScan,
                    castState = CastState(),
                    probeDevice = {
                        probes++
                        if (probes == 1) throw IOException("Synthetic invalid address")
                        manual
                    },
                    checkCompatibility = { false to "video/mp4" },
                    startCast = {},
                    stopCast = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.cast_no_devices)).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("192.168.1.21")
        val add = compose.onNodeWithText(compose.activity.getString(R.string.cast_add))
        add.performClick()
        add.assertIsEnabled().performClick()
        compose.onNodeWithText(manual.name).assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, probes) }
    }

    @Test
    fun incompatibleDeviceStillRequiresConfirmationBeforeStartingCast() {
        val tv = device("Compatibility TV", "uuid:confirm")
        var selected: DlnaDevice? = null
        val discoveries = flowOf(listOf(tv, tv))
        compose.setContent {
            NextPlayerTheme(darkTheme = true, dynamicColor = false) {
                CastDeviceSheet(
                    deviceFlow = discoveries,
                    castState = CastState(),
                    probeDevice = { null },
                    checkCompatibility = { true to "video/x-matroska" },
                    startCast = { selected = it },
                    stopCast = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText(tv.name).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.cast_likely_incompatible_title)).assertIsDisplayed()
        compose.runOnIdle { assertNull(selected) }
        compose.onNodeWithText(compose.activity.getString(R.string.cast_try_anyway)).performClick()
        compose.runOnIdle { assertEquals(tv, selected) }
    }

    private fun device(name: String, udn: String) = DlnaDevice(
        name = name,
        udn = udn,
        locationUrl = "http://127.0.0.1/dd.xml",
        avTransportUrl = "http://127.0.0.1/control",
        renderingControlUrl = null,
    )
}
