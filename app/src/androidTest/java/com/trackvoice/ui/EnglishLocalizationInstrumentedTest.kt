package com.trackvoice.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trackvoice.announcement.AnnouncementPolicy
import com.trackvoice.announcement.TtsState
import com.trackvoice.data.AnnouncementReadField
import com.trackvoice.data.AppLanguage
import com.trackvoice.data.UserSettings
import com.trackvoice.data.VoiceLanguage
import com.trackvoice.media.PlaybackCollection
import com.trackvoice.media.PlaybackEvent
import com.trackvoice.media.PlaybackStatus
import com.trackvoice.monetization.PremiumState
import com.trackvoice.test.TrackTalkComposeTestActivity
import com.trackvoice.ui.theme.TrackVoiceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnglishLocalizationInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TrackTalkComposeTestActivity>()

    @Test
    fun homeUsesNaturalEnglishAndPreservesLongTitleLayout() {
        val settings = UserSettings(
            appLanguage = AppLanguage.ENGLISH,
            defaultReadFields = listOf(AnnouncementReadField.TITLE),
        )
        val event = PlaybackEvent(
            sourcePackageName = "com.example.player",
            sourceAppName = "Example Music",
            title = "The Black Hawk War, Or, How to Demolish an Entire Civilization and Still Feel Good About Yourself",
            artist = "Sufjan Stevens",
            album = "Illinois",
            albumArtist = null,
            trackNumber = null,
            totalTracks = null,
            discNumber = null,
            duration = 420_000L,
            mediaId = "long-title",
            playbackState = PlaybackStatus.PLAYING,
            playbackPosition = 1_000L,
            observedAt = 1L,
        )

        composeRule.setContent {
            TrackVoiceTheme {
                CompositionLocalProvider(
                    LocalTrackTalkStrings provides TrackTalkStrings.forLanguage(AppLanguage.ENGLISH, "ko"),
                ) {
                    HomeScreen(
                        settings = settings,
                        mediaEvent = event,
                        announcementConfiguration = AnnouncementPolicy.resolveConfiguration(
                            settings,
                            PlaybackCollection.UNKNOWN,
                        ),
                        effectiveEnabled = true,
                        notificationAccess = true,
                        notificationPermissionGranted = true,
                        premiumState = PremiumState(isPremium = true),
                        onToggle = {},
                        onTogglePlayback = {},
                        onOpenPermission = {},
                        onRequestNotificationPermission = {},
                        onToggleStatusNotification = {},
                        onOpenAnnouncementSettings = {},
                        onOpenPremium = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Voice announcements").assertIsDisplayed()
        composeRule.onNodeWithText("Now playing").assertIsDisplayed()
        composeRule.onNodeWithText("Spoken info").assertIsDisplayed()
        composeRule.onNodeWithText(event.title!!).assertIsDisplayed()
    }

    @Test
    fun announcementAndVoicePanesUseEnglishWithoutChangingSpeechLanguage() {
        var pane by mutableStateOf(GuideSettingsPane.GUIDE)
        val settings = UserSettings(
            appLanguage = AppLanguage.ENGLISH,
            voiceLanguage = VoiceLanguage.AUTO,
            defaultReadFields = listOf(AnnouncementReadField.TITLE),
        )

        composeRule.setContent {
            TrackVoiceTheme {
                CompositionLocalProvider(
                    LocalTrackTalkStrings provides TrackTalkStrings.forLanguage(AppLanguage.ENGLISH, "ko"),
                ) {
                    GuideSettingsScreen(
                        settings = settings,
                        voices = emptyList(),
                        ttsStatus = TtsState(),
                        isPremium = true,
                        onUpdate = {},
                        onTest = {},
                        onPreviewVoice = {},
                        onOpenPremium = {},
                        selectedPaneName = pane.name,
                        onPaneSelected = { pane = it },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Announcements").assertIsDisplayed()
        composeRule.onAllNodesWithText("App language").assertCountEquals(0)
        composeRule.onNodeWithText("Basic behavior").assertIsDisplayed()
        composeRule.onNodeWithText("Voice").performClick()
        composeRule.onNodeWithText("Speech language").assertIsDisplayed()
        composeRule.onNodeWithText("Auto-detect from title").assertIsDisplayed()
        composeRule.onNodeWithText("Automatically match language").assertIsDisplayed()
    }
}
