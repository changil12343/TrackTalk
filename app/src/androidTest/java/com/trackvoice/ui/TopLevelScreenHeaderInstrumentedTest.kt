package com.trackvoice.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
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
class TopLevelScreenHeaderInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TrackTalkComposeTestActivity>()

    @Test
    fun announcementAndVoiceHeaderUsesTheApprovedShortCopyInBothLanguages() {
        assertEquals(
            "안내·음성",
            TrackTalkStrings.forLanguage(AppLanguage.KOREAN, "en").sectionTitle(AppSection.GENERAL),
        )
        assertEquals(
            "Announcements & voice",
            TrackTalkStrings.forLanguage(AppLanguage.ENGLISH, "ko").sectionTitle(AppSection.GENERAL),
        )
    }

    @Test
    fun koreanTopLevelHeadersKeepStatusInTheSameRowAt360Dp() {
        assertTopLevelHeaders(
            language = AppLanguage.KOREAN,
            width = 360.dp,
        )
    }

    @Test
    fun englishTopLevelHeadersKeepStatusInTheSameRowAt390Dp() {
        assertTopLevelHeaders(
            language = AppLanguage.ENGLISH,
            width = 390.dp,
        )
    }

    private fun assertTopLevelHeaders(language: AppLanguage, width: Dp) {
        val strings = TrackTalkStrings.forLanguage(language, "en")
        val sections = listOf(
            AppSection.HOME,
            AppSection.GENERAL,
            AppSection.APPS,
            AppSection.DEVICES,
        )
        val titles = sections.map(strings::sectionTitle)

        composeRule.setContent {
            TrackVoiceTheme {
                CompositionLocalProvider(LocalTrackTalkStrings provides strings) {
                    Box(Modifier.width(width)) {
                        Column {
                            sections.forEach { section ->
                                TopLevelScreenHeader(
                                    title = strings.sectionTitle(section),
                                    enabled = true,
                                    notificationAccess = true,
                                )
                            }
                        }
                    }
                }
            }
        }

        composeRule.onAllNodesWithText(strings.on).assertCountEquals(sections.size)
        titles.forEachIndexed { index, title ->
            val titleBounds = composeRule.onNodeWithText(title)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
            val statusBounds = composeRule.onAllNodesWithText(strings.on)[index]
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot

            assertTrue(
                "The trailing status must remain clear of the title: $title",
                titleBounds.right < statusBounds.left,
            )
            assertEquals(
                "The title and status must share a vertical center: $title",
                titleBounds.center.y,
                statusBounds.center.y,
                1f,
            )
        }
    }
}
