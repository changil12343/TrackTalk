package com.trackvoice.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackvoice.announcement.AnnouncementPolicy
import com.trackvoice.data.AppLanguage
import com.trackvoice.data.UserSettings
import com.trackvoice.media.PlaybackCollection
import com.trackvoice.media.PlaybackEvent
import com.trackvoice.media.PlaybackStatus
import com.trackvoice.test.TrackTalkComposeTestActivity
import com.trackvoice.ui.theme.TrackVoiceTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CurrentPlaybackCardInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TrackTalkComposeTestActivity>()

    @Test
    fun announcementAndReadingAreSeparateTouchTargetsWithTheExistingDestination() {
        var destinationCalls = 0
        setCard(width = 360.dp, language = AppLanguage.KOREAN) { destinationCalls++ }

        composeRule.onNodeWithTag("currentPlaybackAnnouncementRow")
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle { assertEquals(1, destinationCalls) }

        composeRule.onNodeWithTag("currentPlaybackReadingRow")
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle { assertEquals(2, destinationCalls) }
    }

    @Test
    fun currentPlaybackCardRendersAt360DpInKorean() {
        setCard(width = 360.dp, language = AppLanguage.KOREAN, onOpenSettings = {})
        saveCardScreenshot("current-playback-card-ko-360.png")
    }

    @Test
    fun currentPlaybackCardRendersAt390DpInEnglish() {
        setCard(width = 390.dp, language = AppLanguage.ENGLISH, onOpenSettings = {})
        saveCardScreenshot("current-playback-card-en-390.png")
    }

    private fun setCard(
        width: Dp,
        language: AppLanguage,
        onOpenSettings: () -> Unit,
    ) {
        val settings = UserSettings()
        composeRule.setContent {
            TrackVoiceTheme {
                CompositionLocalProvider(
                    LocalTrackTalkStrings provides TrackTalkStrings.forLanguage(language, "en"),
                ) {
                    Box(
                        modifier = androidx.compose.ui.Modifier.width(width),
                    ) {
                        CurrentTrackCard(
                            settings = settings,
                            event = playbackEvent(),
                            corePermissionGranted = true,
                            announcementConfiguration = AnnouncementPolicy.resolveConfiguration(
                                settings,
                                PlaybackCollection.UNKNOWN,
                            ),
                            onTogglePlayback = {},
                            onOpenAnnouncementSettings = onOpenSettings,
                        )
                    }
                }
            }
        }
    }

    private fun saveCardScreenshot(fileName: String) {
        val bitmap = composeRule.onRoot()
            .captureToImage()
            .asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outputDirectory = context.getExternalFilesDir("screenshots") ?: context.filesDir
        outputDirectory.mkdirs()
        File(outputDirectory, fileName).outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private fun playbackEvent() = PlaybackEvent(
        sourcePackageName = "com.example.music",
        sourceAppName = "YouTube Music",
        title = "A deliberately long track title that wraps without disturbing the card alignment",
        artist = "A deliberately long artist name",
        album = "A deliberately long album title",
        albumArtist = null,
        trackNumber = null,
        totalTracks = null,
        discNumber = null,
        duration = 240_000L,
        mediaId = "visual-test-track",
        playbackState = PlaybackStatus.PLAYING,
        playbackPosition = 2_000L,
        observedAt = 1L,
    )
}
