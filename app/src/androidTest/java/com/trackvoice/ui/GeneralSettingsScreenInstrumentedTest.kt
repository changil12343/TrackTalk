package com.trackvoice.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trackvoice.data.AnnouncementOutputPolicy
import com.trackvoice.data.AnnouncementReadField
import com.trackvoice.data.AnnouncementTiming
import com.trackvoice.data.AppLanguage
import com.trackvoice.data.MusicTreatment
import com.trackvoice.data.TrackStartBehavior
import com.trackvoice.data.UserSettings
import com.trackvoice.data.BETA_VISIBLE_ANNOUNCEMENT_READ_FIELDS
import com.trackvoice.ui.theme.TrackVoiceTheme
import com.trackvoice.test.TrackTalkComposeTestActivity
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeneralSettingsScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TrackTalkComposeTestActivity>()

    @Test
    fun statusNotificationControlIsNoLongerInAnnouncementAndVoiceSettings() {
        composeRule.setContent {
            TrackVoiceTheme {
                CompositionLocalProvider(
                    LocalTrackTalkStrings provides TrackTalkStrings.forLanguage(
                        AppLanguage.KOREAN,
                        "en",
                    ),
                ) {
                    GeneralSettingsScreen(
                        settings = UserSettings(appLanguage = AppLanguage.KOREAN),
                        isPremium = true,
                        onUpdate = {},
                        onOpenPremium = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("기본 동작").assertIsDisplayed()
        composeRule.onNodeWithText("상단바 상태 알림").assertDoesNotExist()
    }

    @Test
    fun selectingMusicDuringGuideShowsPauseOptionAndPersistsIt() {
        var settings by mutableStateOf(
            UserSettings(
                appLanguage = AppLanguage.KOREAN,
                outputPolicy = AnnouncementOutputPolicy.ALL_OUTPUTS,
                timing = AnnouncementTiming.IMMEDIATE,
                trackStartBehavior = TrackStartBehavior.PLAY_IMMEDIATELY,
                musicTreatment = MusicTreatment.KEEP,
                useContentTypeSettings = false,
            ),
        )

        composeRule.setContent {
            TrackVoiceTheme {
                CompositionLocalProvider(
                    LocalTrackTalkStrings provides TrackTalkStrings.forLanguage(
                        AppLanguage.KOREAN,
                        "en",
                    ),
                ) {
                    GeneralSettingsScreen(
                        settings = settings,
                        isPremium = true,
                        onUpdate = { transform -> settings = transform(settings) },
                        onOpenPremium = {},
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag(GENERAL_SETTINGS_SCREEN_TAG).performScrollToNode(hasText("안내 중 음악"))
        composeRule.onNodeWithText("그대로 재생").performClick()
        val inOptionsMenu = hasAnyAncestor(isPopup())
        composeRule.onNode(hasText("그대로 재생") and inOptionsMenu).assertIsDisplayed()
        composeRule.onNode(hasText("음량 줄이기") and inOptionsMenu).assertIsDisplayed()
        composeRule.onNode(hasText("일시정지 후 안내") and inOptionsMenu).assertIsDisplayed()
        composeRule.onNode(hasText("일시정지 후 안내") and inOptionsMenu).performClick()

        composeRule.runOnIdle {
            assertEquals(MusicTreatment.PAUSE, settings.musicTreatment)
        }
    }

    @Test
    fun selectingDelayedTimingShowsAndPersistsAnnouncementDelay() {
        var settings by mutableStateOf(
            UserSettings(
                appLanguage = AppLanguage.KOREAN,
                outputPolicy = AnnouncementOutputPolicy.ALL_OUTPUTS,
                timing = AnnouncementTiming.IMMEDIATE,
                trackStartBehavior = TrackStartBehavior.ANNOUNCE_THEN_PLAY,
                musicTreatment = MusicTreatment.PAUSE,
                useContentTypeSettings = false,
            ),
        )

        fun setScreen() {
            composeRule.setContent {
                TrackVoiceTheme {
                    CompositionLocalProvider(
                        LocalTrackTalkStrings provides TrackTalkStrings.forLanguage(
                            AppLanguage.KOREAN,
                            "en",
                        ),
                    ) {
                        GeneralSettingsScreen(
                            settings = settings,
                            isPremium = true,
                            onUpdate = { transform -> settings = transform(settings) },
                            onOpenPremium = {},
                        )
                    }
                }
            }
        }

        setScreen()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(GENERAL_SETTINGS_SCREEN_TAG)
            .performScrollToNode(hasText("언제 읽을까요?"))
        composeRule.onNodeWithText("바로 읽기").performClick()
        composeRule.onNodeWithText("몇 초 후 읽기").performClick()

        composeRule.onNodeWithText("읽기 전 대기").performScrollTo().assertIsDisplayed()
        val sliderMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        composeRule.onAllNodes(sliderMatcher).assertCountEquals(2)
        composeRule.onAllNodes(sliderMatcher)
            .onFirst()
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(2f)
            }
        composeRule.onNodeWithText("2초").assertIsDisplayed()

        composeRule.runOnIdle {
            check(settings.delaySeconds == 2)
        }
        composeRule.onNodeWithText("읽기 전 대기").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("2초").assertIsDisplayed()
    }

    @Test
    fun immediateTimingHidesAnnouncementDelayControl() {
        composeRule.setContent {
            TrackVoiceTheme {
                CompositionLocalProvider(
                    LocalTrackTalkStrings provides TrackTalkStrings.forLanguage(
                        AppLanguage.KOREAN,
                        "en",
                    ),
                ) {
                    GeneralSettingsScreen(
                        settings = UserSettings(
                            appLanguage = AppLanguage.KOREAN,
                            outputPolicy = AnnouncementOutputPolicy.ALL_OUTPUTS,
                            timing = AnnouncementTiming.IMMEDIATE,
                            trackStartBehavior = TrackStartBehavior.ANNOUNCE_THEN_PLAY,
                            musicTreatment = MusicTreatment.PAUSE,
                            useContentTypeSettings = false,
                        ),
                        isPremium = true,
                        onUpdate = {},
                        onOpenPremium = {},
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag(GENERAL_SETTINGS_SCREEN_TAG)
            .performScrollToNode(hasText("언제 읽을까요?"))
        composeRule.onNodeWithText("읽기 전 대기").assertDoesNotExist()
    }

    @Test
    fun deselectingAVisibleFieldDoesNotFollowItToTheInactiveTail() {
        val strings = TrackTalkStrings.forLanguage(AppLanguage.KOREAN, "en")
        var settings by mutableStateOf(
                listOf(
                    AnnouncementReadField.ALBUM,
                    AnnouncementReadField.TRACK_NUMBER,
                    AnnouncementReadField.TITLE,
                    AnnouncementReadField.ARTIST,
                ),
        )
        composeRule.setContent {
            TrackVoiceTheme {
                CompositionLocalProvider(
                    LocalTrackTalkStrings provides strings,
                ) {
                    ContentReadOrderPicker(
                        title = "read fields",
                        availableFields = BETA_VISIBLE_ANNOUNCEMENT_READ_FIELDS,
                        selectedFields = settings,
                        onUpdate = { settings = it },
                    )
                }
            }
        }

        composeRule.waitForIdle()
        val row = composeRule.onNodeWithTag(CONTENT_READ_ORDER_PICKER_TAG)
        val scrollRange = row.fetchSemanticsNode().config[
            SemanticsProperties.HorizontalScrollAxisRange
        ]
        // The beta-visible fields may fit on a wide emulator. The regression is
        // that deselection never advances the row; it does not require a
        // scroll range on every device width.
        assertEquals(0f, scrollRange.value(), 0.01f)

        val fieldToggles = composeRule.onAllNodes(
            hasClickAction(),
            useUnmergedTree = true,
        )
        fieldToggles.assertCountEquals(BETA_VISIBLE_ANNOUNCEMENT_READ_FIELDS.size)
        fieldToggles.onFirst().performClick()
        composeRule.waitForIdle()

        val afterDeselect = row.fetchSemanticsNode().config[
            SemanticsProperties.HorizontalScrollAxisRange
        ]
        assertEquals(0f, afterDeselect.value(), 0.01f)
        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    AnnouncementReadField.TITLE,
                    AnnouncementReadField.ARTIST,
                ),
                settings,
            )
        }

        composeRule.onNodeWithTag(
            "$CONTENT_READ_ORDER_PICKER_TAG:${AnnouncementReadField.TRACK_NUMBER.name}",
        ).assertDoesNotExist()

        // Re-enabling appends the field to the inactive tail; that ordering is
        // covered by AnnouncementReadFieldsTest. The UI regression above only
        // needs to prove that deselecting a leading chip does not scroll the
        // row toward its new inactive position.
    }
}
