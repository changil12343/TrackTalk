package com.trackvoice.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trackvoice.announcement.AnnouncementPolicy
import com.trackvoice.data.AppLanguage
import com.trackvoice.data.UserSettings
import com.trackvoice.media.PlaybackCollection
import com.trackvoice.monetization.PremiumState
import com.trackvoice.test.TrackTalkComposeTestActivity
import com.trackvoice.ui.theme.TrackVoiceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomePermissionMatrixInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TrackTalkComposeTestActivity>()

    @Test
    fun requiredMissingShowsOnlyRequiredPromptAndTruthfulPlaybackState() {
        setHome(requiredGranted = false, notificationGranted = false, premium = false)

        composeRule.onNodeWithText("음악 감지 권한 필요").assertIsDisplayed()
        composeRule.onNodeWithText("필수").assertIsDisplayed()
        composeRule.onAllNodesWithText("권한 설정").assertCountEquals(1)
        composeRule.onAllNodesWithText("상단바 상태 알림").assertCountEquals(0)
        composeRule.onNodeWithText("권한을 설정하면 현재 재생 정보가 표시됩니다.").assertIsDisplayed()
        composeRule.onAllNodesWithText("재생 중인 음악이 없습니다.").assertCountEquals(0)
        composeRule.onAllNodesWithText("TrackTalk Plus").assertCountEquals(0)
    }

    @Test
    fun requiredGrantedRevealsOnlyCompactOptionalPrompt() {
        setHome(requiredGranted = true, notificationGranted = false, premium = false)

        composeRule.onAllNodesWithText("음악 감지 권한 필요").assertCountEquals(0)
        composeRule.onNodeWithText("상단바 상태 알림").assertIsDisplayed()
        composeRule.onNodeWithText("선택").assertIsDisplayed()
        composeRule.onNodeWithText("TrackTalk Plus").assertIsDisplayed()
        composeRule.onNodeWithText("재생 중인 음악이 없습니다.").assertIsDisplayed()
        composeRule.onAllNodesWithText("권한을 설정하면 현재 재생 정보가 표시됩니다.").assertCountEquals(0)
    }

    @Test
    fun allPermissionsGrantedWithPlusHasNoPermissionOrPromotionCards() {
        setHome(requiredGranted = true, notificationGranted = true, premium = true)

        composeRule.onAllNodesWithText("음악 감지 권한 필요").assertCountEquals(0)
        composeRule.onAllNodesWithText("상단바 상태 알림").assertCountEquals(0)
        composeRule.onAllNodesWithText("TrackTalk Plus").assertCountEquals(0)
        composeRule.onNodeWithText("재생 중인 음악이 없습니다.").assertIsDisplayed()
    }

    @Test
    fun optionalPermissionRevokedKeepsCoreHomeAvailable() {
        setHome(requiredGranted = true, notificationGranted = false, premium = true)

        composeRule.onAllNodesWithText("음악 감지 권한 필요").assertCountEquals(0)
        composeRule.onNodeWithText("상단바 상태 알림").assertIsDisplayed()
        composeRule.onNodeWithText("재생 중인 음악이 없습니다.").assertIsDisplayed()
        composeRule.onAllNodesWithText("권한을 설정하면 현재 재생 정보가 표시됩니다.").assertCountEquals(0)
    }

    private fun setHome(requiredGranted: Boolean, notificationGranted: Boolean, premium: Boolean) {
        composeRule.setContent {
            TrackVoiceTheme {
                CompositionLocalProvider(
                    LocalTrackTalkStrings provides TrackTalkStrings.forLanguage(
                        AppLanguage.KOREAN,
                        "en",
                    ),
                ) {
                    HomeScreen(
                        settings = UserSettings(),
                        mediaEvent = null,
                        announcementConfiguration = AnnouncementPolicy.resolveConfiguration(
                            UserSettings(),
                            PlaybackCollection.UNKNOWN,
                        ),
                        effectiveEnabled = true,
                        notificationAccess = requiredGranted,
                        notificationPermissionGranted = notificationGranted,
                        premiumState = PremiumState(isPremium = premium),
                        onToggle = {},
                        onTogglePlayback = {},
                        onOpenPermission = {},
                        onRequestNotificationPermission = {},
                        onOpenAnnouncementSettings = {},
                        onOpenPremium = {},
                    )
                }
            }
        }
    }
}
