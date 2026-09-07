package com.trackvoice.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeCardAlignmentInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TrackTalkComposeTestActivity>()

    @Test
    fun koreanHomeCardsShareContentGuidelineAt360Dp() {
        assertHomeCardContentAlignment(
            language = AppLanguage.KOREAN,
            width = 360.dp,
            screenshotName = "home-card-alignment-ko-360.png",
        )
    }

    @Test
    fun englishHomeCardsShareContentGuidelineAt390Dp() {
        assertHomeCardContentAlignment(
            language = AppLanguage.ENGLISH,
            width = 390.dp,
            screenshotName = "home-card-alignment-en-390.png",
        )
    }

    private fun assertHomeCardContentAlignment(
        language: AppLanguage,
        width: Dp,
        screenshotName: String,
    ) {
        val strings = TrackTalkStrings.forLanguage(language, "en")
        val settings = UserSettings()
        val event = playbackEvent()
        composeRule.setContent {
            TrackVoiceTheme {
                CompositionLocalProvider(LocalTrackTalkStrings provides strings) {
                    Box(Modifier.width(width)) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatusCard(enabled = true, effectiveEnabled = true, onToggle = {})
                            StatusNotificationCard(
                                enabled = true,
                                permissionGranted = true,
                                requiresRuntimePermission = true,
                                onToggle = {},
                                onRequestPermission = {},
                            )
                            CurrentTrackCard(
                                event = event,
                                corePermissionGranted = true,
                                settings = settings,
                                announcementConfiguration = AnnouncementPolicy.resolveConfiguration(
                                    settings,
                                    PlaybackCollection.UNKNOWN,
                                ),
                                onTogglePlayback = {},
                                onOpenAnnouncementSettings = {},
                            )
                        }
                    }
                }
            }
        }

        val statusTitle = bounds(strings.homeVoiceGuide)
        val notificationTitle = bounds(strings.statusShortcut)
        val currentTrackTitle = bounds(strings.currentTrack)
        assertSameGuideline(statusTitle.left, notificationTitle.left, currentTrackTitle.left)

        val statusBody = bounds(strings.statusSummary(effectiveEnabled = true, enabled = true))
        val notificationBody = bounds(strings.statusShortcutSummary)
        val currentTrackLabel = bounds(strings.appField)
        assertSameGuideline(statusBody.left, notificationBody.left, currentTrackLabel.left)
        assertSameGuideline(
            currentTrackLabel.left,
            bounds(strings.announcementLabel).left,
            bounds(strings.readingOrderLabel).left,
        )

        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outputDirectory = context.getExternalFilesDir("screenshots") ?: context.filesDir
        outputDirectory.mkdirs()
        File(outputDirectory, screenshotName).outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private fun bounds(text: String) = composeRule.onNodeWithText(text)
        .assertIsDisplayed()
        .fetchSemanticsNode()
        .boundsInRoot

    private fun assertSameGuideline(vararg leftPositions: Float) {
        val expected = leftPositions.first()
        leftPositions.drop(1).forEach { actual ->
            assertEquals(expected, actual, 1f)
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
        mediaId = "alignment-test-track",
        playbackState = PlaybackStatus.PLAYING,
        playbackPosition = 2_000L,
        observedAt = 1L,
    )
}
