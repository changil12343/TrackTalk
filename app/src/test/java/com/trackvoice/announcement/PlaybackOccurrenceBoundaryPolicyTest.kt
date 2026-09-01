package com.trackvoice.announcement

import com.trackvoice.media.PlaybackEvent
import com.trackvoice.media.PlaybackStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackOccurrenceBoundaryPolicyTest {
    @Test
    fun unrelatedNotificationPostedThenSessionRefreshDoesNotAnnounceSameTrack() {
        val harness = announcedHarness(track("A"))

        assertFalse(harness.observe(track("A"), sessionKey = "ytm-session"))
    }

    @Test
    fun unrelatedNotificationRemovedThenSessionRefreshDoesNotAnnounceSameTrack() {
        val harness = announcedHarness(track("A"))

        assertFalse(harness.observe(track("A"), sessionKey = "ytm-session"))
    }

    @Test
    fun listenerReconnectUsesSameCurrentTrackAsBaseline() {
        val track = track("A")
        val harness = announcedHarness(track)
        harness.listenerReconnected()

        assertFalse(harness.observe(track, sessionKey = "replacement-session"))
    }

    @Test
    fun controllerReplacementAndGenerationChangeDoNotCreateOccurrence() {
        val harness = announcedHarness(track("A"))

        // Controller generation is deliberately absent from occurrence policy.
        assertFalse(harness.observe(track("A"), sessionKey = "same-framework-session"))
    }

    @Test
    fun duplicateMetadataAndAlbumArtworkEnrichmentDoNotAnnounce() {
        val original = track("A").copy(album = "Original album")
        val harness = announcedHarness(original)

        assertFalse(harness.observe(original.copy(observedAt = 2L), "ytm-session"))
        assertFalse(
            harness.observe(
                original.copy(
                    album = "Corrected album",
                    mediaId = "refreshed-a",
                    observedAt = 3L,
                ),
                "ytm-session",
            ),
        )
    }

    @Test
    fun stoppedBoundaryFromAnotherSessionCannotAuthorizeSameTrackRefresh() {
        val harness = announcedHarness(track("A"))
        harness.boundary = PlaybackOccurrenceBoundary(
            sessionKey = "unrelated-youtube-session",
            track = track("unrelated-video", source = "com.google.android.youtube"),
        )

        assertFalse(harness.observe(track("A"), sessionKey = "ytm-session"))
    }

    @Test
    fun trueAToBTransitionAnnouncesBOnce() {
        val harness = announcedHarness(track("A"))

        assertTrue(harness.observe(track("B"), "ytm-session"))
        assertFalse(harness.observe(track("B").copy(observedAt = 3L), "ytm-session"))
    }

    @Test
    fun trueAToBToAReturnAnnouncesAAgain() {
        val harness = announcedHarness(track("A"))

        assertTrue(harness.observe(track("B"), "ytm-session"))
        assertTrue(harness.observe(track("A").copy(observedAt = 3L), "ytm-session"))
    }

    @Test
    fun processRecreationRestoresAAsBaselineWithoutImmediateAnnouncement() {
        val persisted = track("A")
        val recreated = Harness().apply { restorePersisted(persisted) }

        assertFalse(recreated.observe(persisted.copy(observedAt = 2L), "new-session"))
    }

    @Test
    fun exactStoppedTrackInExactSessionRemainsEligibleForConfirmedRestart() {
        val track = track("A")
        val harness = announcedHarness(track)
        harness.boundary = PlaybackOccurrenceBoundary("ytm-session", track.copy(playbackState = PlaybackStatus.STOPPED))

        assertTrue(harness.observe(track.copy(playbackPosition = 0L), "ytm-session"))
    }

    private class Harness {
        private val suppressor = DuplicateSuppressor()
        var boundary: PlaybackOccurrenceBoundary? = null

        fun restorePersisted(event: PlaybackEvent) {
            suppressor.restoreAnnounced(event, now = 1L)
        }

        fun listenerReconnected() {
            boundary = null
        }

        fun observe(event: PlaybackEvent, sessionKey: String): Boolean {
            val sameTrackRestart = PlaybackOccurrenceBoundaryPolicy.allowsSameTrackRestart(
                boundary = boundary,
                currentSessionKey = sessionKey,
                currentTrack = event,
            )
            val shouldAnnounce = suppressor.shouldAnnounce(
                event = event,
                allowRepeat = false,
                now = event.observedAt.coerceAtLeast(2L),
                isNewPlaybackOccurrence = sameTrackRestart,
            )
            if (shouldAnnounce) {
                suppressor.markAnnounced(event, event.observedAt.coerceAtLeast(2L))
                boundary = null
            }
            return shouldAnnounce
        }
    }

    private fun announcedHarness(event: PlaybackEvent) = Harness().apply {
        restorePersisted(event)
    }

    private fun track(
        name: String,
        source: String = "com.google.android.apps.youtube.music",
    ) = PlaybackEvent(
        sourcePackageName = source,
        sourceAppName = source,
        title = "Track $name",
        artist = "Artist $name",
        album = "Album $name",
        albumArtist = "Artist $name",
        trackNumber = null,
        totalTracks = null,
        discNumber = null,
        duration = 180_000L,
        mediaId = "track-${name.lowercase()}",
        playbackState = PlaybackStatus.PLAYING,
        playbackPosition = 60_000L,
        observedAt = 2L,
    )
}
