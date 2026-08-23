package com.trackvoice.announcement

import com.trackvoice.media.PlaybackEvent
import com.trackvoice.media.PlaybackPauseToken
import com.trackvoice.media.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRestoreObligationTest {
    @Test
    fun normalTtsCompletionRequestsRestoreExactlyOnce() {
        val obligation = armed()
        val cycle = obligation.activeCycle()!!
        assertTrue(obligation.bindSpeech(cycle.id, generation = 7L))

        assertSame(
            cycle,
            obligation.requestRestore(cycle.id, PlaybackRestoreTrigger.TTS_COMPLETED, speechGeneration = 7L),
        )
        assertNull(
            obligation.requestRestore(cycle.id, PlaybackRestoreTrigger.TTS_COMPLETED, speechGeneration = 7L),
        )
    }

    @Test
    fun ttsErrorAndCancellationBothReleaseOwnedPause() {
        val error = armed()
        val errorCycle = error.activeCycle()!!
        assertSame(errorCycle, error.requestRestore(errorCycle.id, PlaybackRestoreTrigger.TTS_ERROR))

        val cancelled = armed()
        val cancelledCycle = cancelled.activeCycle()!!
        assertSame(
            cancelledCycle,
            cancelled.requestRestore(cancelledCycle.id, PlaybackRestoreTrigger.ANNOUNCEMENT_CANCELLED),
        )
    }

    @Test
    fun lostCompletionWatchdogTargetsOnlyCurrentCycle() {
        val obligation = armed()
        val cycle = obligation.activeCycle()!!

        assertNull(obligation.requestRestore(cycle.id + 1L, PlaybackRestoreTrigger.WATCHDOG_TIMEOUT))
        assertSame(cycle, obligation.requestRestore(cycle.id, PlaybackRestoreTrigger.WATCHDOG_TIMEOUT))
    }

    @Test
    fun staleSpeechCallbackCannotRestoreCycleReboundToNewSpeech() {
        val obligation = armed()
        val cycle = obligation.activeCycle()!!
        obligation.bindSpeech(cycle.id, generation = 10L)
        obligation.bindSpeech(cycle.id, generation = 11L)

        assertNull(
            obligation.requestRestore(cycle.id, PlaybackRestoreTrigger.TTS_INTERRUPTED, speechGeneration = 10L),
        )
        assertSame(
            cycle,
            obligation.requestRestore(cycle.id, PlaybackRestoreTrigger.TTS_COMPLETED, speechGeneration = 11L),
        )
    }

    @Test
    fun trackReplacementCancelsWithoutCreatingRestoreRequest() {
        val obligation = armed()
        val cycle = obligation.activeCycle()!!
        assertFalse(obligation.matchesActiveTrack(track().copy(mediaId = "b", title = "Other")))

        assertSame(cycle, obligation.cancel(cycle.id))
        assertNull(obligation.activeCycle())
        assertNull(obligation.requestRestore(cycle.id, PlaybackRestoreTrigger.TTS_COMPLETED))
    }

    @Test
    fun harmlessSessionRepresentationRefreshKeepsObligation() {
        val obligation = armed()
        val refreshed = track().copy(mediaId = "canonical-a", album = "Filled Album")

        assertTrue(obligation.matchesActiveTrack(refreshed))
        assertSame(obligation.activeCycle(), obligation.activeCycle())
    }

    @Test
    fun duplicatePausedCallbacksRecordOnlyFirstObservation() {
        val obligation = armed()
        val cycle = obligation.activeCycle()!!

        assertTrue(obligation.markPlayerPaused(cycle.id, 200L))
        assertFalse(obligation.markPlayerPaused(cycle.id, 300L))
        assertEquals(200L, obligation.activeCycle()?.pausedObservedAtElapsedNanos)
    }

    @Test
    fun samePausedStateTimestampFromControllerRecreationIsHarmless() {
        val obligation = armed()
        val cycle = obligation.activeCycle()!!

        assertEquals(
            PlaybackRestorePlayerObservation.OWNED_PAUSE_CONFIRMED,
            obligation.observePlayerState(cycle.id, PlaybackStatus.PAUSED, 200L, 180L),
        )
        assertEquals(
            PlaybackRestorePlayerObservation.DUPLICATE_OR_STALE,
            obligation.observePlayerState(cycle.id, PlaybackStatus.PAUSED, 260L, 180L),
        )
        assertNull(obligation.newerPlaybackIntentReason(cycle.id))
        assertSame(cycle, obligation.requestRestore(cycle.id, PlaybackRestoreTrigger.TTS_COMPLETED))
    }

    @Test
    fun newerPauseDuringTtsSuppressesAutomaticRestore() {
        val obligation = armed()
        val cycle = obligation.activeCycle()!!

        assertEquals(
            PlaybackRestorePlayerObservation.OWNED_PAUSE_CONFIRMED,
            obligation.observePlayerState(cycle.id, PlaybackStatus.PAUSED, 200L, 180L),
        )
        assertEquals(
            PlaybackRestorePlayerObservation.NEWER_PAUSE_OR_STOP_INTENT,
            obligation.observePlayerState(cycle.id, PlaybackStatus.PAUSED, 300L, 280L),
        )
        assertEquals(
            "NEWER_PAUSED_STATE_AFTER_OWNED_PAUSE",
            obligation.newerPlaybackIntentReason(cycle.id),
        )
        assertNull(obligation.requestRestore(cycle.id, PlaybackRestoreTrigger.TTS_COMPLETED))
    }

    @Test
    fun externalPlaybackDuringTtsPreventsRedundantTrackTalkPlay() {
        val obligation = armed()
        val cycle = obligation.activeCycle()!!

        obligation.observePlayerState(cycle.id, PlaybackStatus.PAUSED, 200L, 180L)
        assertEquals(
            PlaybackRestorePlayerObservation.INTERVENING_PLAYBACK,
            obligation.observePlayerState(cycle.id, PlaybackStatus.PLAYING, 300L, 280L),
        )
        assertEquals("PLAYING_AFTER_OWNED_PAUSE", obligation.newerPlaybackIntentReason(cycle.id))
        assertNull(obligation.requestRestore(cycle.id, PlaybackRestoreTrigger.TTS_COMPLETED))
    }

    @Test
    fun externalPlaybackThenPauseAroundCompletionPreservesNewerPause() {
        val obligation = armed()
        val cycle = obligation.activeCycle()!!

        obligation.observePlayerState(cycle.id, PlaybackStatus.PAUSED, 200L, 180L)
        obligation.observePlayerState(cycle.id, PlaybackStatus.PLAYING, 300L, 280L)
        assertEquals(
            PlaybackRestorePlayerObservation.NEWER_PAUSE_OR_STOP_INTENT,
            obligation.observePlayerState(cycle.id, PlaybackStatus.PAUSED, 400L, 380L),
        )
        assertEquals("PLAYING_AFTER_OWNED_PAUSE", obligation.newerPlaybackIntentReason(cycle.id))
        assertNull(obligation.requestRestore(cycle.id, PlaybackRestoreTrigger.WATCHDOG_TIMEOUT))
    }

    @Test
    fun interruptionWhileIdleCannotCreateRestoreOwnership() {
        val obligation = PlaybackRestoreObligation()

        assertEquals(
            PlaybackRestorePlayerObservation.IGNORED,
            obligation.observePlayerState(1L, PlaybackStatus.PAUSED, 200L, 180L),
        )
        assertNull(obligation.requestRestore(1L, PlaybackRestoreTrigger.TTS_COMPLETED))
    }

    @Test
    fun userPauseAfterInterveningPlaybackIsRecognizedAsNewIntent() {
        val obligation = armed()
        val cycle = obligation.activeCycle()!!

        assertTrue(obligation.markPlayerPaused(cycle.id, 200L))
        assertTrue(obligation.markPlayerPlayingAfterOwnedPause(cycle.id, 300L))
        assertTrue(obligation.hasInterveningPlaybackIntent(cycle.id))

        obligation.cancel(cycle.id)
        assertNull(obligation.requestRestore(cycle.id, PlaybackRestoreTrigger.TTS_COMPLETED))
    }

    @Test
    fun stalePlayingBeforeOwnedPauseAcknowledgementIsNotUserIntent() {
        val obligation = armed()
        val cycle = obligation.activeCycle()!!

        assertFalse(obligation.markPlayerPlayingAfterOwnedPause(cycle.id, 150L))
        assertTrue(obligation.markPlayerPaused(cycle.id, 200L))
        assertFalse(obligation.hasInterveningPlaybackIntent(cycle.id))
    }

    @Test
    fun routeCancellationStillRequestsReleaseOfOwnedPause() {
        val obligation = armed()
        val cycle = obligation.activeCycle()!!

        assertSame(
            cycle,
            obligation.requestRestore(cycle.id, PlaybackRestoreTrigger.ANNOUNCEMENT_CANCELLED),
        )
        assertNull(obligation.requestRestore(cycle.id, PlaybackRestoreTrigger.ANNOUNCEMENT_CANCELLED))
    }

    @Test
    fun restoreRequestedCycleCannotBeReboundByNewSpeech() {
        val obligation = armed()
        val cycle = obligation.activeCycle()!!
        assertSame(cycle, obligation.requestRestore(cycle.id, PlaybackRestoreTrigger.TTS_COMPLETED))

        assertFalse(obligation.bindSpeech(cycle.id, generation = 99L))
    }

    @Test
    fun watchdogUsesBoundedSpeechLengthAndRateEstimate() {
        assertEquals(PlaybackRestoreWatchdogPolicy.MIN_TIMEOUT_MS, PlaybackRestoreWatchdogPolicy.timeoutMs(5, 1f))
        assertEquals(PlaybackRestoreWatchdogPolicy.MAX_TIMEOUT_MS, PlaybackRestoreWatchdogPolicy.timeoutMs(1_000, 0.5f))
        assertTrue(
            PlaybackRestoreWatchdogPolicy.timeoutMs(200, 0.8f) >
                PlaybackRestoreWatchdogPolicy.timeoutMs(20, 1.5f),
        )
    }

    private fun armed(): PlaybackRestoreObligation = PlaybackRestoreObligation().apply {
        val event = track()
        val id = reserveCycleId()
        arm(
            cycleId = id,
            pauseToken = token(),
            track = event,
            sessionGeneration = 3L,
            armedAtElapsedNanos = 100L,
            transitionAtElapsedNanos = 50L,
        )
    }

    private fun token() = PlaybackPauseToken(
        sessionKey = "session-a",
        fingerprint = "fingerprint-a",
        sourcePackageName = "music.app",
        mediaId = "temporary-a",
        title = "Song A",
        artist = "Artist",
        pauseRequestedAtElapsedNanos = 90L,
    )

    private fun track() = PlaybackEvent(
        sourcePackageName = "music.app",
        sourceAppName = "Music",
        title = "Song A",
        artist = "Artist",
        album = null,
        albumArtist = null,
        trackNumber = null,
        totalTracks = null,
        discNumber = null,
        duration = 180_000L,
        mediaId = "temporary-a",
        playbackState = PlaybackStatus.PAUSED,
        playbackPosition = 0L,
        observedAt = 1L,
    )
}
