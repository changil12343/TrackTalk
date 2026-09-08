package com.trackvoice.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

        expectedCopy(language).forEach { text ->
            composeRule.onNodeWithText(text)
                .performScrollTo()
                .assertIsDisplayed()
        }

        if (language == AppLanguage.ENGLISH) {
            composeRule.onAllNodesWithText("Announce on this device.").assertCountEquals(0)
        }
    }

    private fun expectedCopy(language: AppLanguage): List<String> = when (language) {
        AppLanguage.KOREAN -> listOf(
            "기기별 안내와 자동 켜짐을 설정합니다.",
            "이 기기에서 사용",
            "이 기기에서 안내합니다.",
            "연결 시 자동 켜기",
            "연결되면 안내를 켭니다.",
            "화면 자동화",
            "화면 끄면 자동 켜기",
            "화면이 꺼지면 안내를 켭니다.",
            "화면 켜면 원래대로",
            "자동으로 켜진 안내를 해제합니다.",
            "화면 끄면 Bluetooth에서만 켜기",
            "Bluetooth가 연결된 경우에만 안내를 켭니다.",
        )
        AppLanguage.ENGLISH -> listOf(
            "Set announcements and automation per device.",
            "Use on this device",
            "Auto-enable on connect",
            "Turn on when connected.",
            "Screen automation",
            "Enable on screen off",
            "Turn on when the screen turns off.",
            "Restore on wake",
            "Restore the previous state.",
            "Require Bluetooth",
            "Only auto-enable with Bluetooth audio.",
        )
        AppLanguage.SYSTEM -> error("The copy test chooses an explicit UI language.")
    }
}
