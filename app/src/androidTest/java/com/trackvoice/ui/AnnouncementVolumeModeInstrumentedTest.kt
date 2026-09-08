package com.trackvoice.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trackvoice.announcement.TtsState
import com.trackvoice.data.AnnouncementVolumeMode
import com.trackvoice.data.AppLanguage
import com.trackvoice.data.UserSettings
import com.trackvoice.test.TrackTalkComposeTestActivity
import com.trackvoice.ui.theme.TrackVoiceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnnouncementVolumeModeInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TrackTalkComposeTestActivity>()

    @Test
    fun followMediaHidesSliderAndCustomModeRestoresStoredValue() {
        var settings by mutableStateOf(
            UserSettings(
                appLanguage = AppLanguage.KOREAN,
                announcementVolumeMode = AnnouncementVolumeMode.FOLLOW_MEDIA,
                volume = 0.81f,
            ),
        )

        composeRule.setContent {
            TrackVoiceTheme {
                CompositionLocalProvider(
                    LocalTrackTalkStrings provides TrackTalkStrings.forLanguage(AppLanguage.KOREAN, "en"),
                ) {
                    VoiceSettingsScreen(
                        settings = settings,
                        voices = emptyList(),
                        ttsStatus = TtsState(),
                        isPremium = true,
                        onUpdate = { transform -> settings = transform(settings) },
                        onTest = {},
                        onPreviewVoice = {},
                        onOpenPremium = {},
                    )
                }
            }
        }

        val sliderMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        composeRule.onNodeWithText("안내 음성 음량").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("음악 볼륨 따르기").assertIsDisplayed()
        composeRule.onNodeWithText("휴대폰의 미디어 볼륨을 따릅니다.").assertIsDisplayed()
        composeRule.onNodeWithText("직접 설정 음량").assertDoesNotExist()
        composeRule.onAllNodes(sliderMatcher).assertCountEquals(2)

        selectMode(currentLabel = "음악 볼륨 따르기", optionLabel = "직접 설정")
        composeRule.onNodeWithText("직접 설정 음량").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("81%").assertIsDisplayed()
        composeRule.onAllNodes(sliderMatcher).assertCountEquals(3)
        composeRule.runOnIdle {
            assertEquals(AnnouncementVolumeMode.CUSTOM, settings.announcementVolumeMode)
            assertEquals(0.81f, settings.volume, 0f)
        }

        selectMode(currentLabel = "직접 설정", optionLabel = "음악 볼륨 따르기")
        composeRule.onNodeWithText("휴대폰의 미디어 볼륨을 따릅니다.").assertIsDisplayed()
        composeRule.onNodeWithText("직접 설정 음량").assertDoesNotExist()
        composeRule.onAllNodes(sliderMatcher).assertCountEquals(2)
        composeRule.runOnIdle {
            assertEquals(AnnouncementVolumeMode.FOLLOW_MEDIA, settings.announcementVolumeMode)
            assertEquals(0.81f, settings.volume, 0f)
        }

        selectMode(currentLabel = "음악 볼륨 따르기", optionLabel = "직접 설정")
        composeRule.onNodeWithText("81%").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(AnnouncementVolumeMode.CUSTOM, settings.announcementVolumeMode)
            assertEquals(0.81f, settings.volume, 0f)
        }
    }

    private fun selectMode(currentLabel: String, optionLabel: String) {
        composeRule.onNodeWithText(currentLabel).performClick()
        composeRule.onNode(
            hasText(optionLabel) and hasAnyAncestor(isPopup()),
        ).performClick()
    }
}
