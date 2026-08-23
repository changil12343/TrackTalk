package com.trackvoice.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trackvoice.data.AppLanguage
import com.trackvoice.test.TrackTalkComposeTestActivity
import com.trackvoice.ui.theme.TrackVoiceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationPermissionBannerInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TrackTalkComposeTestActivity>()

    @Test
    fun compactBannerShowsLocalizedActionAndInvokesExistingRequestCallback() {
        var requestCount = 0
        composeRule.setContent {
            TrackVoiceTheme {
                CompositionLocalProvider(
                    LocalTrackTalkStrings provides TrackTalkStrings.forLanguage(
                        AppLanguage.KOREAN,
                        "en",
                    ),
                ) {
                    NotificationPermissionBanner { requestCount += 1 }
                }
            }
        }

        composeRule.onNodeWithText("상단바 상태 알림").assertIsDisplayed()
        composeRule.onNodeWithText("허용").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, requestCount) }
    }

    @Test
    fun requiredBannerShowsRequiredCopyAndInvokesSettingsCallback() {
        var openSettingsCount = 0
        composeRule.setContent {
            TrackVoiceTheme {
                CompositionLocalProvider(
                    LocalTrackTalkStrings provides TrackTalkStrings.forLanguage(
                        AppLanguage.KOREAN,
                        "en",
                    ),
                ) {
                    RequiredPermissionBanner { openSettingsCount += 1 }
                }
            }
        }

        composeRule.onNodeWithText("음악 감지 권한 필요").assertIsDisplayed()
        composeRule.onNodeWithText("필수").assertIsDisplayed()
        composeRule.onNodeWithText("권한 설정").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, openSettingsCount) }
    }

    @Test
    fun englishRequiredBadgeRemainsReadableBesideLongPermissionCopy() {
        composeRule.setContent {
            TrackVoiceTheme {
                CompositionLocalProvider(
                    LocalTrackTalkStrings provides TrackTalkStrings.forLanguage(
                        AppLanguage.ENGLISH,
                        "en",
                    ),
                ) {
                    RequiredPermissionBanner {}
                }
            }
        }

        composeRule.onNodeWithText("Music detection").assertIsDisplayed()
        composeRule.onNodeWithText("Open settings").assertIsDisplayed()
        val badgeBounds = composeRule.onNodeWithText("Required")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "Required badge must remain horizontal instead of wrapping one character per line",
            badgeBounds.width > badgeBounds.height,
        )
    }
}
