package com.trackvoice.announcement

import com.trackvoice.data.AnnouncementReadField
import com.trackvoice.data.AppSettings
import com.trackvoice.data.UserSettings
import com.trackvoice.media.NextTrackPrefetch
import com.trackvoice.media.PlaybackCollection
import com.trackvoice.media.PlaybackEvent
import com.trackvoice.media.PlaybackStatus
import com.trackvoice.media.QueueItemSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedNextAnnouncementTest {
    @Test
    fun matchingConfirmedTrackReusesPreparedText() {
        val settings = titleOnlySettings()
        val prepared = NextTrackAnnouncementPreparation.prepare(preparedNext(), settings)!!

        val reused = NextTrackAnnouncementPreparation.reusableText(
            prepared = prepared,
            actual = event("b", "Next", "Artist", "Album", listOf(item("b", "Next"), item("c", "Following"))),
            sessionKey = "session",
            settings = settings,
        )

        assertEquals("Next.", reused)
    }

    @Test
    fun changedReadingConfigurationRejectsPreparedText() {
        val prepared = NextTrackAnnouncementPreparation.prepare(preparedNext(), titleOnlySettings())!!
        val changed = titleOnlySettings().copy(
            defaultReadFields = listOf(AnnouncementReadField.TITLE, AnnouncementReadField.ARTIST),
        )

        val reused = NextTrackAnnouncementPreparation.reusableText(
            prepared,
            event("b", "Next", "Artist", "Album", listOf(item("b", "Next"))),
            "session",
            changed,
        )

        assertNull(reused)
    }

    @Test
    fun changedSelectedMetadataRejectsPreparedText() {
        val settings = titleOnlySettings().copy(
            defaultReadFields = listOf(AnnouncementReadField.TITLE, AnnouncementReadField.ARTIST),
        )
        val prepared = NextTrackAnnouncementPreparation.prepare(preparedNext(), settings)!!

        val reused = NextTrackAnnouncementPreparation.reusableText(
            prepared,
            event("b", "Next", "Different Artist", "Album", listOf(item("b", "Next"))),
            "session",
            settings,
        )

        assertNull(reused)
    }

    @Test
    fun rapidSkipToDifferentTrackRejectsPreparedText() {
        val settings = titleOnlySettings()
        val prepared = NextTrackAnnouncementPreparation.prepare(preparedNext(), settings)!!

        val reused = NextTrackAnnouncementPreparation.reusableText(
            prepared,
            event("c", "Following", "Artist", "Album", listOf(item("c", "Following"))),
            "session",
            settings,
        )

        assertNull(reused)
    }

    @Test
    fun preparedTextBypassesArtificialHundredMillisecondFallback() {
        var slowFallbackCalled = false

        val resolved = PreparedAnnouncementTextResolver.resolve("Prepared.") {
            Thread.sleep(100L)
            slowFallbackCalled = true
            "Slow."
        }

        assertEquals("Prepared.", resolved)
        assertFalse(slowFallbackCalled)
    }

    @Test
    fun policyStillAppliesEligibilityBeforeUsingPreparedText() {
        val settings = titleOnlySettings()
        val decision = AnnouncementPolicy.decide(
            event = event("b", "Next", "Artist", "Album", listOf(item("b", "Next"))),
            userSettings = settings,
            appSettings = AppSettings("player", "Player", enabled = true),
            effectiveEnabled = false,
            externalAudioOutput = true,
            collectionOverride = PlaybackCollection.UNKNOWN,
            preparedText = "Prepared.",
        )

        assertFalse(decision.shouldAnnounce)
        assertNull(decision.text)
    }

    @Test
    fun policyUsesAlreadyValidatedPreparedTextWithoutReformatting() {
        val settings = titleOnlySettings()
        val decision = AnnouncementPolicy.decide(
            event = event("b", "Different local title", "Artist", "Album", listOf(item("b", "Next"))),
            userSettings = settings,
            appSettings = AppSettings("player", "Player", enabled = true),
            effectiveEnabled = true,
            externalAudioOutput = true,
            collectionOverride = PlaybackCollection.UNKNOWN,
            preparedText = "Prepared.",
        )

        assertTrue(decision.shouldAnnounce)
        assertEquals("Prepared.", decision.text)
    }

    private fun preparedNext() = NextTrackPrefetch.prepare(
        event = event(
            "a",
            "Current",
            "Artist",
            "Album",
            listOf(item("a", "Current"), item("b", "Next")),
        ),
        sessionKey = "session",
        preparedAt = 1_000L,
    )!!

    private fun titleOnlySettings() = UserSettings(
        enabled = true,
        defaultReadFields = listOf(AnnouncementReadField.TITLE),
    )

    private fun event(
        mediaId: String,
        title: String,
        artist: String,
        album: String,
        queue: List<QueueItemSnapshot>,
    ) = PlaybackEvent(
        sourcePackageName = "player",
        sourceAppName = "Player",
        title = title,
        artist = artist,
        album = album,
        albumArtist = null,
        trackNumber = null,
        totalTracks = null,
        discNumber = null,
        duration = 180_000L,
        mediaId = mediaId,
        playbackState = PlaybackStatus.PLAYING,
        playbackPosition = 0L,
        queue = queue,
        observedAt = 1_000L,
        queueTitle = "Up next",
        activeQueuePosition = 0,
    )

    private fun item(mediaId: String, title: String) = QueueItemSnapshot(
        mediaId = mediaId,
        title = title,
        artist = "Artist",
        album = "Album",
        queueItemId = mediaId.last().code.toLong(),
    )
}
