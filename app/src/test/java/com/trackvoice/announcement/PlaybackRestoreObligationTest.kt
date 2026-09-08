package com.trackvoice.announcement

import com.trackvoice.media.PlaybackEvent
import com.trackvoice.media.PlaybackPauseToken
import com.trackvoice.media.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRestoreObligationTest {
    @Test
    fun ttsBeforePauseAcknowledgementStaysPendingThenRestoresExactlyOnce() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        assertTrue(obligation.bindSpeech(lease.id, generation = 7L))

        assertSame(
            lease,
            obligation.markTtsCompleted(
                lease.id,
                PlaybackRestoreTrigger.TTS_COMPLETED,
                speechGeneration = 7L,
            ),
        )
        assertEquals(PlaybackRestoreReadiness.WAITING_FOR_PAUSE_ACK, obligation.readiness(lease.id))
        assertNull(obligation.claimRestoreIfReady(lease.id))
        assertEquals(PlaybackRestoreCycleState.ARMED, lease.state)

        assertEquals(
            PlaybackRestorePlayerObservation.OWNED_PAUSE_CONFIRMED,
            obligation.observePauseCallback(lease.id, 1_800L, 1_790L),
        )
        assertEquals(PlaybackRestoreReadiness.READY, obligation.readiness(lease.id))
        assertSame(lease, obligation.claimRestoreIfReady(lease.id))
        assertNull(obligation.claimRestoreIfReady(lease.id))
        assertEquals(
            PlaybackRestorePlayerObservation.IGNORED,
            obligation.observePauseCallback(lease.id, 1_900L, 1_890L, eventSequenceNumber = 3L),
        )
        assertNull(
            obligation.markTtsCompleted(
                lease.id,
                PlaybackRestoreTrigger.TTS_COMPLETED,
                speechGeneration = 7L,
            ),
        )
    }

    @Test
    fun pauseAcknowledgementBeforeTtsWaitsThenRestoresExactlyOnce() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        assertEquals(
            PlaybackRestorePlayerObservation.OWNED_PAUSE_CONFIRMED,
            obligation.observePauseCallback(lease.id, 200L, 180L),
        )
        assertEquals(PlaybackRestoreReadiness.WAITING_FOR_TTS, obligation.readiness(lease.id))
        assertNull(obligation.claimRestoreIfReady(lease.id))

        assertSame(lease, obligation.markTtsCompleted(lease.id, PlaybackRestoreTrigger.TTS_COMPLETED))
        assertSame(lease, obligation.claimRestoreIfReady(lease.id))
        assertNull(obligation.claimRestoreIfReady(lease.id))
    }

    @Test
    fun missingPauseAcknowledgementCanOnlyExpireWithoutRestoreAuthority() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        assertSame(lease, obligation.markTtsCompleted(lease.id, PlaybackRestoreTrigger.TTS_COMPLETED))
        assertEquals(PlaybackRestoreReadiness.WAITING_FOR_PAUSE_ACK, obligation.readiness(lease.id))
        assertNull(obligation.claimRestoreIfReady(lease.id))

        assertSame(lease, obligation.cancel(lease.id))
        assertNull(obligation.activeLease())
        assertNull(obligation.claimRestoreIfReady(lease.id))
    }

    @Test
    fun stalePauseCannotAcknowledgeOrUnlockRestore() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        assertSame(lease, obligation.markTtsCompleted(lease.id, PlaybackRestoreTrigger.TTS_COMPLETED))

        assertEquals(
            PlaybackRestorePlayerObservation.DUPLICATE_OR_STALE,
            obligation.observePauseCallback(lease.id, 200L, 70L),
        )
        assertEquals(PlaybackRestoreReadiness.WAITING_FOR_PAUSE_ACK, obligation.readiness(lease.id))
        assertNull(obligation.claimRestoreIfReady(lease.id))
    }

    @Test
    fun metadataRemapOfPausedStateCannotAcknowledgeBeforePlaybackStateCallback() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        assertSame(lease, obligation.markTtsCompleted(lease.id, PlaybackRestoreTrigger.TTS_COMPLETED))

        assertEquals(
            PlaybackRestorePlayerObservation.DUPLICATE_OR_STALE,
            obligation.observePlayerState(
                cycleId = lease.id,
                playbackStatus = PlaybackStatus.PAUSED,
                observedAtElapsedNanos = 200L,
                stateUpdatedAtElapsedNanos = 180L,
                isPlaybackStateCallback = false,
                eventSequenceNumber = 2L,
            ),
        )
        assertEquals(PlaybackRestoreReadiness.WAITING_FOR_PAUSE_ACK, obligation.readiness(lease.id))
        assertNull(obligation.claimRestoreIfReady(lease.id))
    }

    @Test
    fun orderedPausedCallbackMayReuseThePlayingBaselineTimestamp() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        assertSame(lease, obligation.markTtsCompleted(lease.id, PlaybackRestoreTrigger.TTS_COMPLETED))

        assertEquals(
            PlaybackRestorePlayerObservation.OWNED_PAUSE_CONFIRMED,
            obligation.observePauseCallback(
                cycleId = lease.id,
                observedAtElapsedNanos = 1_800L,
                stateUpdatedAtElapsedNanos = 80L,
            ),
        )
        assertEquals(
            PlaybackPauseAcknowledgementEvidence.ORDERED_CALLBACK_FROM_PLAYING_BASELINE,
            lease.pauseAcknowledgementEvidence,
        )
        assertSame(lease, obligation.claimRestoreIfReady(lease.id))
    }

    @Test
    fun samsungPostCommandPauseWithoutSameTrackPlayingBaselineRestoresExactlyOnce() {
        val pauseIssuedAt = 1_000_000_000L
        val pauseEventWatermark = 40L
        val obligation = armed(
            pauseToken = token().copy(
                pauseRequestedAtElapsedNanos = pauseIssuedAt,
                playbackStateUpdatedAtPauseElapsedNanos = null,
                eventSequenceNumberAtPauseCommand = pauseEventWatermark,
            ),
        )
        val lease = obligation.activeLease()!!
        assertSame(lease, obligation.markTtsCompleted(lease.id, PlaybackRestoreTrigger.TTS_COMPLETED))
        assertEquals(PlaybackRestoreReadiness.WAITING_FOR_PAUSE_ACK, obligation.readiness(lease.id))

        assertEquals(
            PlaybackRestorePlayerObservation.OWNED_PAUSE_CONFIRMED,
            obligation.observePauseCallback(
                cycleId = lease.id,
                observedAtElapsedNanos = pauseIssuedAt + 1_794_000_000L,
                stateUpdatedAtElapsedNanos = pauseIssuedAt - 189_000_000L,
                eventSequenceNumber = pauseEventWatermark + 1L,
            ),
        )
        assertEquals(
            PlaybackPauseAcknowledgementEvidence.LOCAL_POST_COMMAND_CALLBACK,
            lease.pauseAcknowledgementEvidence,
        )
        assertNull(lease.pauseAcknowledgementRejectReason)
        assertEquals(PlaybackRestoreReadiness.READY, obligation.readiness(lease.id))
        assertSame(lease, obligation.claimRestoreIfReady(lease.id))
        assertNull(obligation.claimRestoreIfReady(lease.id))
    }

    @Test
    fun orderedPausedCallbackOlderThanPlayingBaselineCannotAcknowledge() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        assertSame(lease, obligation.markTtsCompleted(lease.id, PlaybackRestoreTrigger.TTS_COMPLETED))

        assertEquals(
            PlaybackRestorePlayerObservation.DUPLICATE_OR_STALE,
            obligation.observePauseCallback(
                cycleId = lease.id,
                observedAtElapsedNanos = 1_800L,
                stateUpdatedAtElapsedNanos = 79L,
            ),
        )
        assertTrue(!lease.pauseAcknowledged)
        assertEquals(
            PlaybackPauseAcknowledgementRejectReason.SOURCE_TIMESTAMP_BEFORE_PLAYING_BASELINE,
            lease.pauseAcknowledgementRejectReason,
        )
        assertNull(obligation.claimRestoreIfReady(lease.id))
    }

    @Test
    fun queuedPausedCallbackFromBeforeThePauseSequenceCannotAcknowledge() {
        val obligation = armed()
        val lease = obligation.activeLease()!!

        assertEquals(
            PlaybackRestorePlayerObservation.DUPLICATE_OR_STALE,
            obligation.observePauseCallback(
                cycleId = lease.id,
                observedAtElapsedNanos = 200L,
                stateUpdatedAtElapsedNanos = 180L,
                eventSequenceNumber = 1L,
            ),
        )
        assertTrue(!lease.pauseAcknowledged)
        assertEquals(
            PlaybackPauseAcknowledgementRejectReason.PRE_COMMAND_LOCAL_EVENT,
            lease.pauseAcknowledgementRejectReason,
        )
    }

    @Test
    fun legitimateRestoreUsesOneCurrentLeaseExactlyOnce() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        assertTrue(obligation.bindSpeech(lease.id, generation = 7L))
        assertSame(lease, obligation.markTtsStarted(lease.id, generation = 7L))
        assertNull(validity(obligation, lease.id))
        assertEquals(
            PlaybackRestorePlayerObservation.OWNED_PAUSE_CONFIRMED,
            obligation.observePauseCallback(lease.id, 200L, 180L),
        )

        assertSame(
            lease,
            obligation.markTtsCompleted(
                lease.id,
                PlaybackRestoreTrigger.TTS_COMPLETED,
                speechGeneration = 7L,
            ),
        )
        assertEquals(PlaybackRestoreReadiness.READY, obligation.readiness(lease.id))
        assertSame(lease, obligation.claimRestoreIfReady(lease.id))
        assertNull(
            obligation.markTtsCompleted(
                lease.id,
                PlaybackRestoreTrigger.TTS_COMPLETED,
                speechGeneration = 7L,
            ),
        )
        assertNull(obligation.claimRestoreIfReady(lease.id))
    }

    @Test
    fun actualTtsStartDisarmsSpeechWatchdogAndRestoresExactlyOnceAfterLongSpeech() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        val generation = 7L
        val oldDurationEstimate = PlaybackRestoreWatchdogPolicy.timeoutMs(textLength = 46, speechRate = 1f)

        assertEquals(9_140L, oldDurationEstimate)
        assertTrue(obligation.bindSpeech(lease.id, generation))
        assertEquals(
            PlaybackRestorePlayerObservation.OWNED_PAUSE_CONFIRMED,
            obligation.observePauseCallback(lease.id, 200L, 180L),
        )
        assertTrue(obligation.shouldExpireUnstartedSpeechWatchdog(lease.id, generation))

        // Even after the old character-count deadline, an observed Android
        // onStart keeps the valid owned lease until its real terminal callback.
        assertSame(lease, obligation.markTtsStarted(lease.id, generation))
        assertTrue(lease.ttsStarted)
        assertTrue(!obligation.shouldExpireUnstartedSpeechWatchdog(lease.id, generation))

        assertSame(
            lease,
            obligation.markTtsCompleted(
                lease.id,
                PlaybackRestoreTrigger.TTS_COMPLETED,
                speechGeneration = generation,
            ),
        )
        assertSame(lease, obligation.claimRestoreIfReady(lease.id))
        assertNull(obligation.claimRestoreIfReady(lease.id))
    }

    @Test
    fun unstartedSpeechWatchdogStillDiscardsAbandonedLeaseWithoutRestore() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        val generation = 7L
        assertTrue(obligation.bindSpeech(lease.id, generation))

        assertTrue(obligation.shouldExpireUnstartedSpeechWatchdog(lease.id, generation))
        assertSame(lease, obligation.cancel(lease.id))
        assertNull(obligation.activeLease())
        assertNull(
            obligation.markTtsCompleted(
                lease.id,
                PlaybackRestoreTrigger.TTS_COMPLETED,
                speechGeneration = generation,
            ),
        )
        assertNull(obligation.claimRestoreIfReady(lease.id))
    }

    @Test
    fun explicitCancellationDuringStartedTtsDropsTerminalRestore() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        val generation = 7L
        assertTrue(obligation.bindSpeech(lease.id, generation))
        assertSame(lease, obligation.markTtsStarted(lease.id, generation))
        obligation.observePauseCallback(lease.id, 200L, 180L)

        // The controller uses this same cancellation boundary for observable
        // user intent, STOP, track/session replacement, and listener teardown.
        assertSame(lease, obligation.cancel(lease.id))
        assertNull(
            obligation.markTtsCompleted(
                lease.id,
                PlaybackRestoreTrigger.TTS_COMPLETED,
                speechGeneration = generation,
            ),
        )
        assertNull(obligation.claimRestoreIfReady(lease.id))
    }

    @Test
    fun staleTtsStartAfterLeaseCancelledCannotRearmRestore() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        val generation = 7L
        assertTrue(obligation.bindSpeech(lease.id, generation))
        assertSame(lease, obligation.markTtsStarted(lease.id, generation))
        assertSame(lease, obligation.cancel(lease.id))
        assertNull(obligation.activeLease())
        assertNull(obligation.markTtsStarted(lease.id, generation))
        assertNull(obligation.markTtsCompleted(lease.id, PlaybackRestoreTrigger.TTS_COMPLETED, generation))
        assertNull(obligation.claimRestoreIfReady(lease.id))
    }

    @Test
    fun currentTtsErrorAndInterruptionMayConsumeOwnedLease() {
        val error = armed()
        val errorLease = error.activeLease()!!
        error.observePauseCallback(errorLease.id, 200L, 180L)
        assertSame(errorLease, error.markTtsCompleted(errorLease.id, PlaybackRestoreTrigger.TTS_ERROR))
        assertSame(errorLease, error.claimRestoreIfReady(errorLease.id))

        val interrupted = armed()
        val interruptedLease = interrupted.activeLease()!!
        interrupted.observePauseCallback(interruptedLease.id, 200L, 180L)
        assertSame(
            interruptedLease,
            interrupted.markTtsCompleted(interruptedLease.id, PlaybackRestoreTrigger.TTS_INTERRUPTED),
        )
        assertSame(interruptedLease, interrupted.claimRestoreIfReady(interruptedLease.id))
    }

    @Test
    fun cancelledDelayedWorkWatchdogAndLifecycleCannotConsumeLease() {
        listOf(
            PlaybackRestoreTrigger.WATCHDOG_TIMEOUT,
            PlaybackRestoreTrigger.ANNOUNCEMENT_CANCELLED,
            PlaybackRestoreTrigger.NON_PAUSE_MODE,
            PlaybackRestoreTrigger.CONTROLLER_DETACH,
            PlaybackRestoreTrigger.CONTROLLER_CLOSE,
        ).forEach { trigger ->
            val obligation = armed()
            val lease = obligation.activeLease()!!
            assertNull(trigger.name, obligation.markTtsCompleted(lease.id, trigger))
        }
    }

    @Test
    fun staleTtsCallbackCannotConsumeLeaseReboundToNewSpeech() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        assertTrue(obligation.bindSpeech(lease.id, generation = 10L))
        assertTrue(!obligation.bindSpeech(lease.id, generation = 11L))
        obligation.cancel(lease.id)

        assertNull(
            obligation.markTtsCompleted(
                lease.id,
                PlaybackRestoreTrigger.TTS_INTERRUPTED,
                speechGeneration = 10L,
            ),
        )
        assertNull(
            obligation.markTtsCompleted(
                lease.id,
                PlaybackRestoreTrigger.TTS_COMPLETED,
                speechGeneration = 11L,
            ),
        )
    }

    @Test
    fun userStopAfterTrackTalkPauseInvalidatesRestore() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        assertTrue(obligation.bindSpeech(lease.id, generation = 7L))
        assertSame(lease, obligation.markTtsStarted(lease.id, generation = 7L))
        assertEquals(
            PlaybackRestorePlayerObservation.NEWER_PAUSE_OR_STOP_INTENT,
            obligation.observePlayerState(
                lease.id,
                PlaybackStatus.STOPPED,
                observedAtElapsedNanos = 300L,
                stateUpdatedAtElapsedNanos = null,
            ),
        )
        assertEquals("STOPPED_DURING_RESTORE_LEASE", obligation.newerPlaybackIntentReason(lease.id))
        assertNull(obligation.markTtsCompleted(lease.id, PlaybackRestoreTrigger.TTS_COMPLETED))
        assertEquals("PLAYBACK_STOPPED", validity(obligation, lease.id, event = track(PlaybackStatus.STOPPED)))
    }

    @Test
    fun playbackNoneInvalidatesRestoreEvenWithoutFrameworkTimestamp() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        assertEquals(
            PlaybackRestorePlayerObservation.NEWER_PAUSE_OR_STOP_INTENT,
            obligation.observePlayerState(
                lease.id,
                PlaybackStatus.NONE,
                observedAtElapsedNanos = 300L,
                stateUpdatedAtElapsedNanos = null,
            ),
        )
        assertNull(obligation.markTtsCompleted(lease.id, PlaybackRestoreTrigger.TTS_COMPLETED))
    }

    @Test
    fun repeatedPausedPlaybackStateCallbackDuringTtsDoesNotCancelRestore() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        assertEquals(
            PlaybackRestorePlayerObservation.OWNED_PAUSE_CONFIRMED,
            obligation.observePauseCallback(lease.id, 200L, 180L),
        )
        assertEquals(
            PlaybackRestorePlayerObservation.DUPLICATE_OR_STALE,
            obligation.observePauseCallback(lease.id, 300L, 280L, eventSequenceNumber = 3L),
        )
        assertNull(obligation.newerPlaybackIntentReason(lease.id))
        assertSame(lease, obligation.markTtsCompleted(lease.id, PlaybackRestoreTrigger.TTS_COMPLETED))
        assertSame(lease, obligation.claimRestoreIfReady(lease.id))
    }

    @Test
    fun postAcknowledgementQueueRefreshOfPausedSnapshotDoesNotCancelRestore() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        assertEquals(
            PlaybackRestorePlayerObservation.OWNED_PAUSE_CONFIRMED,
            obligation.observePauseCallback(lease.id, 200L, 180L),
        )

        assertEquals(
            PlaybackRestorePlayerObservation.DUPLICATE_OR_STALE,
            obligation.observePlayerState(
                cycleId = lease.id,
                playbackStatus = PlaybackStatus.PAUSED,
                observedAtElapsedNanos = 300L,
                stateUpdatedAtElapsedNanos = 280L,
                isPlaybackStateCallback = false,
                eventSequenceNumber = 3L,
            ),
        )
        assertNull(obligation.newerPlaybackIntentReason(lease.id))
        assertSame(lease, obligation.markTtsCompleted(lease.id, PlaybackRestoreTrigger.TTS_COMPLETED))
        assertSame(lease, obligation.claimRestoreIfReady(lease.id))
    }

    @Test
    fun duplicateOwnedPauseCallbackIsHarmless() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        assertEquals(
            PlaybackRestorePlayerObservation.OWNED_PAUSE_CONFIRMED,
            obligation.observePauseCallback(lease.id, 200L, 180L),
        )
        assertEquals(
            PlaybackRestorePlayerObservation.DUPLICATE_OR_STALE,
            obligation.observePauseCallback(lease.id, 260L, 180L, eventSequenceNumber = 3L),
        )
        assertNull(obligation.newerPlaybackIntentReason(lease.id))
    }

    @Test
    fun untimestampedPostCommandPausedCallbackCanUseLocalCommandProvenance() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        assertEquals(
            PlaybackRestorePlayerObservation.OWNED_PAUSE_CONFIRMED,
            obligation.observePauseCallback(lease.id, 210L, null, eventSequenceNumber = 3L),
        )
        assertEquals(
            PlaybackPauseAcknowledgementEvidence.LOCAL_POST_COMMAND_CALLBACK,
            lease.pauseAcknowledgementEvidence,
        )
        assertEquals(PlaybackRestoreReadiness.WAITING_FOR_TTS, obligation.readiness(lease.id))
    }

    @Test
    fun externalPlaybackDuringTtsPreventsRedundantTrackTalkPlay() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        assertTrue(obligation.bindSpeech(lease.id, generation = 7L))
        assertSame(lease, obligation.markTtsStarted(lease.id, generation = 7L))
        obligation.observePauseCallback(lease.id, 200L, 180L)
        assertEquals(
            PlaybackRestorePlayerObservation.INTERVENING_PLAYBACK,
            obligation.observePlayerState(lease.id, PlaybackStatus.PLAYING, 300L, 280L),
        )
        assertNull(obligation.markTtsCompleted(lease.id, PlaybackRestoreTrigger.TTS_COMPLETED))
    }

    @Test
    fun sessionDestroyedOrRemovedInvalidatesLeaseContext() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        assertTrue(obligation.bindSpeech(lease.id, generation = 7L))
        assertSame(lease, obligation.markTtsStarted(lease.id, generation = 7L))
        assertEquals(
            "SESSION_IDENTITY_CHANGED",
            validity(obligation, lease.id, sessionKey = null, controllerGeneration = null),
        )
        assertSame(lease, obligation.cancel(lease.id))
        assertNull(
            obligation.markTtsCompleted(
                lease.id,
                PlaybackRestoreTrigger.TTS_COMPLETED,
                speechGeneration = 7L,
            ),
        )
    }

    @Test
    fun controllerReplacementInvalidatesLeaseContext() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        assertTrue(obligation.bindSpeech(lease.id, generation = 7L))
        assertSame(lease, obligation.markTtsStarted(lease.id, generation = 7L))
        assertEquals(
            "CONTROLLER_GENERATION_CHANGED",
            validity(obligation, lease.id, controllerGeneration = 12L),
        )
        assertSame(lease, obligation.cancel(lease.id))
        assertNull(
            obligation.markTtsCompleted(
                lease.id,
                PlaybackRestoreTrigger.TTS_COMPLETED,
                speechGeneration = 7L,
            ),
        )
    }

    @Test
    fun listenerReconnectInvalidatesLeaseContext() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        assertEquals(
            "MONITOR_GENERATION_CHANGED",
            validity(obligation, lease.id, monitorGeneration = 6L),
        )
    }

    @Test
    fun logicalSessionOrTrackReplacementInvalidatesLeaseContext() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        assertTrue(obligation.bindSpeech(lease.id, generation = 7L))
        assertSame(lease, obligation.markTtsStarted(lease.id, generation = 7L))
        assertEquals(
            "SESSION_GENERATION_CHANGED",
            validity(obligation, lease.id, sessionGeneration = 4L),
        )
        assertEquals(
            "TRACK_IDENTITY_CHANGED",
            validity(obligation, lease.id, event = track().copy(mediaId = "b", title = "Song B")),
        )
        assertSame(lease, obligation.cancel(lease.id))
        assertNull(
            obligation.markTtsCompleted(
                lease.id,
                PlaybackRestoreTrigger.TTS_COMPLETED,
                speechGeneration = 7L,
            ),
        )
    }

    @Test
    fun harmlessMetadataEnrichmentKeepsExactLease() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        val enriched = track().copy(mediaId = "canonical-a", album = "Filled Album")

        assertTrue(obligation.matchesActiveTrack(enriched))
        assertNull(validity(obligation, lease.id, event = enriched))
    }

    @Test
    fun albumOnlyMixedFrameCorrectionKeepsLeaseAndCanRestore() {
        val mixedFrame = track().copy(
            mediaId = null,
            title = "So Cruel",
            artist = "U2",
            album = "Room On Fire",
        )
        val pauseToken = token().copy(
            fingerprint = com.trackvoice.media.TrackFingerprint.core(mixedFrame),
            mediaId = null,
            title = mixedFrame.title,
            artist = mixedFrame.artist,
            album = mixedFrame.album,
        )
        val obligation = PlaybackRestoreObligation()
        val cycleId = obligation.reserveCycleId()
        val lease = obligation.arm(
            cycleId = cycleId,
            pauseToken = pauseToken,
            track = mixedFrame,
            monitorGeneration = 5L,
            sessionGeneration = 3L,
            controllerGeneration = 11L,
            armedAtElapsedNanos = 100L,
            transitionAtElapsedNanos = 50L,
        )
        val corrected = mixedFrame.copy(album = "Achtung Baby", playbackState = PlaybackStatus.PAUSED)

        assertTrue(obligation.matchesActiveTrack(corrected))
        assertNull(validity(obligation, cycleId, event = corrected))
        assertEquals(
            PlaybackRestorePlayerObservation.OWNED_PAUSE_CONFIRMED,
            obligation.observePauseCallback(cycleId, 500L, 480L),
        )
        assertSame(lease, obligation.markTtsCompleted(cycleId, PlaybackRestoreTrigger.TTS_COMPLETED))
        assertSame(lease, obligation.claimRestoreIfReady(cycleId))
    }

    @Test
    fun processRecreationHasNoRestoreLeaseEvenWhenTrackHistoryExists() {
        val newProcessObligation = PlaybackRestoreObligation()
        assertNull(newProcessObligation.activeLease())
        assertEquals(
            "NO_ACTIVE_LEASE",
            validity(newProcessObligation, cycleId = 1L, event = track(PlaybackStatus.PAUSED)),
        )
        assertNull(newProcessObligation.markTtsCompleted(1L, PlaybackRestoreTrigger.TTS_COMPLETED))
    }

    @Test
    fun invalidatedAnnouncementDropsLateCompletion() {
        val obligation = armed()
        val lease = obligation.activeLease()!!
        obligation.bindSpeech(lease.id, generation = 9L)
        assertSame(lease, obligation.markTtsStarted(lease.id, generation = 9L))
        assertSame(lease, obligation.cancel(lease.id))

        assertNull(
            obligation.markTtsCompleted(
                lease.id,
                PlaybackRestoreTrigger.TTS_COMPLETED,
                speechGeneration = 9L,
            ),
        )
    }

    @Test
    fun leaseRecordsEphemeralPauseOwnershipIdentities() {
        val obligation = armed()
        val lease = obligation.activeLease()!!

        assertTrue(lease.trackTalkActuallyPausedPlayback)
        assertEquals(5L, lease.monitorGeneration)
        assertEquals(3L, lease.sessionGeneration)
        assertEquals(11L, lease.controllerGeneration)
        assertEquals("session-a", lease.sessionIdentity)
        assertEquals("fingerprint-a", lease.trackIdentity)
    }

    @Test
    fun idlePlaybackEventsCannotCreateRestoreOwnership() {
        val obligation = PlaybackRestoreObligation()
        assertEquals(
            PlaybackRestorePlayerObservation.IGNORED,
            obligation.observePlayerState(1L, PlaybackStatus.PAUSED, 200L, 180L),
        )
        assertNull(obligation.markTtsCompleted(1L, PlaybackRestoreTrigger.TTS_COMPLETED))
    }

    @Test
    fun watchdogUsesBoundedSpeechLengthAndRateEstimate() {
        assertEquals(PlaybackRestoreWatchdogPolicy.MIN_TIMEOUT_MS, PlaybackRestoreWatchdogPolicy.timeoutMs(5, 1f))
        assertEquals(
            PlaybackRestoreWatchdogPolicy.MAX_TIMEOUT_MS,
            PlaybackRestoreWatchdogPolicy.timeoutMs(1_000, 0.5f),
        )
        assertTrue(
            PlaybackRestoreWatchdogPolicy.timeoutMs(200, 0.8f) >
                PlaybackRestoreWatchdogPolicy.timeoutMs(20, 1.5f),
        )
        assertEquals(8_000L, PlaybackRestoreWatchdogPolicy.PAUSE_ACKNOWLEDGEMENT_EXPIRY_MS)
    }

    private fun armed(
        pauseToken: PlaybackPauseToken = token(),
    ): PlaybackRestoreObligation = PlaybackRestoreObligation().apply {
        val id = reserveCycleId()
        arm(
            cycleId = id,
            pauseToken = pauseToken,
            track = track(),
            monitorGeneration = 5L,
            sessionGeneration = 3L,
            controllerGeneration = 11L,
            armedAtElapsedNanos = 100L,
            transitionAtElapsedNanos = 50L,
        )
    }

    private fun validity(
        obligation: PlaybackRestoreObligation,
        cycleId: Long,
        monitorGeneration: Long = 5L,
        sessionGeneration: Long = 3L,
        sessionKey: String? = "session-a",
        controllerGeneration: Long? = 11L,
        event: PlaybackEvent? = track(),
    ): String? = obligation.validateLease(
        cycleId = cycleId,
        monitorGeneration = monitorGeneration,
        sessionGeneration = sessionGeneration,
        sessionKey = sessionKey,
        controllerGeneration = controllerGeneration,
        event = event,
    )

    private fun PlaybackRestoreObligation.observePauseCallback(
        cycleId: Long,
        observedAtElapsedNanos: Long,
        stateUpdatedAtElapsedNanos: Long?,
        eventSequenceNumber: Long = 2L,
    ): PlaybackRestorePlayerObservation = observePlayerState(
        cycleId = cycleId,
        playbackStatus = PlaybackStatus.PAUSED,
        observedAtElapsedNanos = observedAtElapsedNanos,
        stateUpdatedAtElapsedNanos = stateUpdatedAtElapsedNanos,
        isPlaybackStateCallback = true,
        eventSequenceNumber = eventSequenceNumber,
    )

    private fun token() = PlaybackPauseToken(
        sessionKey = "session-a",
        fingerprint = "fingerprint-a",
        sourcePackageName = "music.app",
        mediaId = "temporary-a",
        title = "Song A",
        artist = "Artist",
        pauseRequestedAtElapsedNanos = 90L,
        playbackStateUpdatedAtPauseElapsedNanos = 80L,
        eventSequenceNumberAtPauseCommand = 1L,
        controllerGeneration = 11L,
        monitorLifecycleGeneration = 4L,
    )

    private fun track(status: PlaybackStatus = PlaybackStatus.PAUSED) = PlaybackEvent(
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
        playbackState = status,
        playbackPosition = 0L,
        observedAt = 1L,
    )
}
