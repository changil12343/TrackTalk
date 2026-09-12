package com.trackvoice.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trackvoice.BuildConfig
import com.trackvoice.data.AppLanguage
import com.trackvoice.test.TrackTalkComposeTestActivity
import com.trackvoice.ui.theme.TrackVoiceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppInfoCardInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TrackTalkComposeTestActivity>()

    @Test
    fun appInfoShowsOnlyVersionAndKeepsFeedbackAction() {
        var feedbackCount = 0
        composeRule.setContent {
            TrackVoiceTheme {
                CompositionLocalProvider(
                    LocalTrackTalkStrings provides TrackTalkStrings.forLanguage(
                        AppLanguage.KOREAN,
                        "en",
                    ),
                ) {
                    AppInfoCard(onFeedback = { feedbackCount += 1 })
                }
            }
        }

        composeRule.onNodeWithText("앱 정보").assertIsDisplayed()
        composeRule.onNodeWithText("버전").assertIsDisplayed()
        composeRule.onNodeWithText("v${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        composeRule.onNodeWithText("개인정보 처리방침").assertIsDisplayed()
        composeRule.onNodeWithText("출시 전에 개인정보 처리방침 URL을 설정해야 합니다.").assertIsDisplayed()
        composeRule.onNodeWithText("피드백 보내기").assertIsDisplayed()
        composeRule.onNodeWithText("의견이나 문제를 이메일로 보내 주세요.").assertIsDisplayed()
        composeRule.onNodeWithText("빌드 번호").assertDoesNotExist()
        composeRule.onNodeWithText("개발자").assertDoesNotExist()
        composeRule.onNodeWithText("yiri20").assertDoesNotExist()

        composeRule.onAllNodes(hasClickAction()).onFirst().performClick()
        composeRule.runOnIdle { assertEquals(1, feedbackCount) }
    }

    @Test
    fun englishAppInfoCopyRemainsCompact() {
        composeRule.setContent {
            TrackVoiceTheme {
                CompositionLocalProvider(
                    LocalTrackTalkStrings provides TrackTalkStrings.forLanguage(
                        AppLanguage.ENGLISH,
                        "ko",
                    ),
                ) {
                    AppInfoCard(onFeedback = {})
                }
            }
        }

        composeRule.onNodeWithText("Version").assertIsDisplayed()
        composeRule.onNodeWithText("Privacy policy").assertIsDisplayed()
        composeRule.onNodeWithText("A privacy policy URL must be configured before release.").assertIsDisplayed()
        composeRule.onNodeWithText("Send feedback").assertIsDisplayed()
        composeRule.onNodeWithText("Send feedback or report a problem by email.").assertIsDisplayed()
        composeRule.onNodeWithText("Build number").assertDoesNotExist()
        composeRule.onNodeWithText("Developer").assertDoesNotExist()
    }
}
