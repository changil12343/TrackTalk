package com.trackvoice.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trackvoice.announcement.AudioDeviceKind
import com.trackvoice.announcement.ConnectedAudioDevice
import com.trackvoice.data.AppLanguage
import com.trackvoice.data.UserSettings
import com.trackvoice.test.TrackTalkComposeTestActivity
import com.trackvoice.ui.theme.TrackVoiceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceAutomationCopyInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TrackTalkComposeTestActivity>()

    @Test
    fun koreanDeviceAutomationCopyRendersAt360Dp() {
        assertDeviceAutomationCopy(
            language = AppLanguage.KOREAN,
            width = 360.dp,
        )
    }

    @Test
    fun englishDeviceAutomationCopyRendersAt390Dp() {
        assertDeviceAutomationCopy(
            language = AppLanguage.ENGLISH,
            width = 390.dp,
        )
    }

    private fun assertDeviceAutomationCopy(language: AppLanguage, width: Dp) {
        val strings = TrackTalkStrings.forLanguage(language, "en")
        composeRule.setContent {
            TrackVoiceTheme {
                CompositionLocalProvider(LocalTrackTalkStrings provides strings) {
                    Box(Modifier.width(width)) {
                        DeviceSettingsScreen(
                            settings = UserSettings(appLanguage = language),
                            connectedDevices = listOf(
                                ConnectedAudioDevice(
                                    key = "test-bluetooth-device",
                                    productName = "Test headphones",
                                    kind = AudioDeviceKind.BLUETOOTH,
                                ),
                            ),
                            deviceSettings = emptyMap(),
                            isPremium = true,
                            onUpdate = {},
                            onUpdateDevice = {},
                            onOpenPremium = {},
                            onOpenDiagnostics = {},
                            onFeedback = {},
                        )
                    }
                }
            }
        }

        listOf(
            strings.deviceAutomationSummary,
            strings.useOnThisDevice,
            strings.useOnThisDeviceSummary,
            strings.autoEnableOnConnect,
            strings.autoEnableOnConnectSummary,
            strings.screenAutomation,
            strings.screenOffEnable,
            strings.screenOffEnableSummary,
            strings.screenOnRestore,
            strings.screenOnRestoreSummary,
            strings.bluetoothOnly,
            strings.bluetoothOnlySummary,
        ).forEach { text ->
            composeRule.onNodeWithText(text)
                .performScrollTo()
                .assertIsDisplayed()
        }
    }
}
