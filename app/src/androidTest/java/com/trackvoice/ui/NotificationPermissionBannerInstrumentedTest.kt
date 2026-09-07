package com.trackvoice.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
    fun grantedStatusNotificationCardShowsPersistedSwitchAndUpdatesIt() {
        var enabled by mutableStateOf(true)
        composeRule.setContent {
            TrackVoiceTheme {
                CompositionLocalProvider(
                    LocalTrackTalkStrings provides TrackTalkStrings.forLanguage(
                        AppLanguage.KOREAN,
                        "en",
                    ),
                ) {
                    StatusNotificationCard(
                        enabled = enabled,
                        permissionGranted = true,
                        requiresRuntimePermission = true,
                        onToggle = { enabled = it },
                        onRequestPermission = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("상단바 상태 알림").assertIsDisplayed()
        composeRule.onNodeWithText("현재 안내 상태를 상단바에서 확인합니다.").assertIsDisplayed()
        composeRule.onAllNodes(isToggleable()).assertCountEquals(1)
        composeRule.onAllNodes(isToggleable()).onFirst().performClick()
        composeRule.runOnIdle { assertEquals(false, enabled) }
    }

    @Test
    fun missingStatusNotificationPermissionShowsSettingsActionAndInvokesExistingRequestCallback() {
        var requestCount = 0
        composeRule.setContent {
            TrackVoiceTheme {
                CompositionLocalProvider(
                    LocalTrackTalkStrings provides TrackTalkStrings.forLanguage(
                        AppLanguage.KOREAN,
                        "en",
                    ),
                ) {
                    StatusNotificationCard(
                        enabled = true,
                        permissionGranted = false,
                        requiresRuntimePermission = true,
                        onToggle = {},
                        onRequestPermission = { requestCount += 1 },
                    )
                }
            }
        }

        composeRule.onNodeWithText("상단바 상태 알림").assertIsDisplayed()
        composeRule.onNodeWithText("알림을 표시하려면 권한이 필요합니다.").assertIsDisplayed()
        composeRule.onAllNodes(isToggleable()).assertCountEquals(0)
        composeRule.onNodeWithText("권한 설정").assertIsDisplayed().performClick()
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
    fun requiredBadgeStaysAttachedToTitleInsteadOfAction() {
        composeRule.setContent {
            TrackVoiceTheme {
                CompositionLocalProvider(
                    LocalTrackTalkStrings provides TrackTalkStrings.forLanguage(
                        AppLanguage.KOREAN,
                        "en",
                    ),
                ) {
                    RequiredPermissionBanner {}
                }
            }
        }

        val titleBounds = composeRule.onNodeWithText("음악 감지 권한 필요")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val badgeBounds = composeRule.onNodeWithText("필수")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val actionBounds = composeRule.onNodeWithText("권한 설정")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val expectedGap = with(composeRule.density) { 8.dp.toPx() }

        assertTrue(
            "Required badge must stay beside the title",
            badgeBounds.left - titleBounds.right <= expectedGap + 1f,
        )
        assertTrue(
            "Required badge must remain separate from the settings action",
            badgeBounds.right < actionBounds.left,
        )
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
                    Box(modifier = Modifier.width(280.dp)) {
                        RequiredPermissionBanner {}
                    }
                }
            }
        }

        composeRule.onNodeWithText("Music detection").assertIsDisplayed()
        composeRule.onNodeWithText("Open settings").assertIsDisplayed()
        val badgeBounds = composeRule.onNodeWithText("Required")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val actionBounds = composeRule.onNodeWithText("Open settings")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "Required badge must remain horizontal instead of wrapping one character per line",
            badgeBounds.width > badgeBounds.height,
        )
        assertTrue(
            "Required badge must remain separate from the settings action on a narrow screen",
            badgeBounds.right < actionBounds.left,
        )
    }
}
