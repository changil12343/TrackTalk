package com.trackvoice.announcement

import com.trackvoice.media.PlaybackEvent
import com.trackvoice.media.PlaybackStatus
import com.trackvoice.media.QueueItemSnapshot
import com.trackvoice.media.TrackFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackEndPrearmCoordinatorTest {
    @Test
    fun availableDurationExtrapolatesPositionWithPlaybackSpeed() {
        val result = TrackEndPrearmPlanner.plan(
            event(
                durationMs = 180_000L,
                positionMs = 170_000L,
                positionUpdatedAtElapsedMs = 1_000L,
                playbackSpeed = 1.5f,
            ),
            nowElapsedMs = 3_000L,
        ) as TrackEndPrearmPlanResult.Available

        assertEquals(173_000L, result.plan.estimatedPositionMs)
        assertEquals(7_000L, result.plan.remainingMs)
        assertEquals(10_000L, result.plan.predictedTrackEndElapsedMs)
        assertEquals(8_000L, result.plan.prearmAtElapsedMs)
    }

    @Test
    fun missingOrInvalidDurationLeavesTheReactivePathAvailable() {
        val missingKey = TrackEndPrearmPlanner.plan(
            event(durationMetadataPresent = false),
            nowElapsedMs = 1_000L,
        ) as TrackEndPrearmPlanResult.Unavailable
        val zeroDuration = TrackEndPrearmPlanner.plan(
            event(durationMs = 0L),
            nowElapsedMs = 1_000L,
        ) as TrackEndPrearmPlanResult.Unavailable
        val liveLikeDuration = TrackEndPrearmPlanner.plan(
            event(durationMs = TrackEndPrearmTiming.MAX_USABLE_DURATION_MS + 1L),
            nowElapsedMs = 1_000L,
        ) as TrackEndPrearmPlanResult.Unavailable

        assertEquals(DurationPredictionUnavailableReason.DURATION_KEY_MISSING, missingKey.reason)
        assertEquals(DurationPredictionUnavailableReason.DURATION_INVALID, zeroDuration.reason)
        assertEquals(DurationPredictionUnavailableReason.LIVE_OR_INDEFINITE, liveLikeDuration.reason)
    }

    @Test
    fun pausedSnapshotDoesNotAdvanceOrKeepAPredictionAlive() {
        val coordinator = DurationPrearmCoordinator()
        val identity = identity()
        val scheduled = coordinator.reconcile(
            identity = identity,
            event = event(positionMs = 100_000L),
            nowElapsedMs = 1_000L,
        )
        assertTrue(scheduled.action is DurationPrearmAction.Schedule)

        val paused = coordinator.reconcile(
            identity = identity,
            event = event(playbackStatus = PlaybackStatus.PAUSED, positionMs = 100_000L),
            nowElapsedMs = 20_000L,
        )

        assertEquals("DURATION_NOT_PLAYING", paused.cancelled?.reason)
        assertEquals(DurationPredictionUnavailableReason.NOT_PLAYING, (paused.action as DurationPrearmAction.Unavailable).reason)
        assertNull(coordinator.current())
    }

    @Test
    fun prearmProducesOnlyPreparationStateAndNeverASpeechAction() {
        val coordinator = DurationPrearmCoordinator()
        val identity = identity()
        val result = coordinator.reconcile(
            identity = identity,
            event = event(positionMs = 178_500L),
            nowElapsedMs = 1_000L,
        )
        val action = result.action as DurationPrearmAction.PrearmNow

        val prearmed = coordinator.markPrearmed(action.prediction.token, nowElapsedMs = 1_000L)

        assertNotNull(prearmed)
        assertEquals(action.prediction.token, prearmed?.token)
        assertNotNull(prearmed?.prearmedAtElapsedMs)
        // The duration API exposes Schedule/PrearmNow/Unavailable only. It has no TTS, focus,
        // duck, or transport action, so the normal confirmed-track gate remains the sole speaker.
    }

    @Test
    fun manualNextMakesALateOldPredictionStale() {
        val coordinator = DurationPrearmCoordinator()
        val identityA = identity(trackKey = "track-a")
        val first = coordinator.reconcile(identityA, event(mediaId = "track-a"), 1_000L)
        val predictionA = (first.action as DurationPrearmAction.Schedule).prediction

        val cancelled = coordinator.cancel("TRACK_IDENTITY_CHANGED")
        val identityB = identity(trackKey = "track-b")
        val second = coordinator.reconcile(identityB, event(mediaId = "track-b"), 1_000L)
        val predictionB = (second.action as DurationPrearmAction.Schedule).prediction

        assertEquals(predictionA.token, cancelled?.prediction?.token)
        assertFalse(coordinator.isCurrent(predictionA.token, identityA))
        assertTrue(coordinator.isCurrent(predictionB.token, identityB))
    }

    @Test
    fun seekReplacesTheOldPredictionFromTheNewPosition() {
        val coordinator = DurationPrearmCoordinator()
        val identity = identity()
        val first = coordinator.reconcile(
            identity,
            event(positionMs = 120_000L, positionUpdatedAtElapsedMs = 1_000L),
            1_000L,
        )
        val firstPrediction = (first.action as DurationPrearmAction.Schedule).prediction

        val afterSeek = coordinator.reconcile(
            identity,
            event(positionMs = 40_000L, positionUpdatedAtElapsedMs = 2_000L),
            2_000L,
        )
        val replacement = (afterSeek.action as DurationPrearmAction.Schedule).prediction

        assertEquals("PLAYBACK_POSITION_CHANGED", afterSeek.cancelled?.reason)
        assertNotEquals(firstPrediction.token, replacement.token)
        assertTrue(replacement.plan.predictedTrackEndElapsedMs > firstPrediction.plan.predictedTrackEndElapsedMs)
        assertFalse(coordinator.isCurrent(firstPrediction.token, identity))
    }

    @Test
    fun durationMetadataChangeReplacesTheOldPrediction() {
        val coordinator = DurationPrearmCoordinator()
        val identity = identity()
        val first = coordinator.reconcile(
            identity,
            event(durationMs = 180_000L, positionMs = 100_000L),
            1_000L,
        )
        val firstPrediction = (first.action as DurationPrearmAction.Schedule).prediction

        val updated = coordinator.reconcile(
            identity,
            event(durationMs = 240_000L, positionMs = 100_000L),
            1_000L,
        )
        val replacement = (updated.action as DurationPrearmAction.Schedule).prediction

        assertEquals("DURATION_CHANGED", updated.cancelled?.reason)
        assertNotEquals(firstPrediction.token, replacement.token)
        assertEquals(140_000L, replacement.plan.remainingMs)
        assertFalse(coordinator.isCurrent(firstPrediction.token, identity))
    }

    @Test
    fun meaningfulPlaybackSpeedChangeReplacesTheOldPrediction() {
        val coordinator = DurationPrearmCoordinator()
        val identity = identity()
        val first = coordinator.reconcile(
            identity,
            event(positionMs = 100_000L, playbackSpeed = 1f),
            1_000L,
        )
        val firstPrediction = (first.action as DurationPrearmAction.Schedule).prediction

        val updated = coordinator.reconcile(
            identity,
            event(positionMs = 100_000L, playbackSpeed = 1.25f),
            1_000L,
        )
        val replacement = (updated.action as DurationPrearmAction.Schedule).prediction

        assertEquals("PLAYBACK_SPEED_CHANGED", updated.cancelled?.reason)
        assertNotEquals(firstPrediction.token, replacement.token)
        assertEquals(1.25f, replacement.plan.playbackSpeed)
        assertFalse(coordinator.isCurrent(firstPrediction.token, identity))
    }

    @Test
    fun resumeSchedulesFromTheCurrentPositionAfterPausedState() {
        val coordinator = DurationPrearmCoordinator()
        val identity = identity()
        coordinator.reconcile(identity, event(positionMs = 100_000L), 1_000L)
        coordinator.reconcile(
            identity,
            event(playbackStatus = PlaybackStatus.PAUSED, positionMs = 100_000L),
            2_000L,
        )

        val resumed = coordinator.reconcile(
            identity,
            event(positionMs = 101_000L, positionUpdatedAtElapsedMs = 3_000L),
            3_000L,
        )

        assertTrue(resumed.action is DurationPrearmAction.Schedule)
        assertTrue(coordinator.current()?.plan?.estimatedPositionMs == 101_000L)
    }

    @Test
    fun controllerReplacementInvalidatesTheOldPredictionEvenWithSameSessionKey() {
        val coordinator = DurationPrearmCoordinator()
        val firstIdentity = identity(controllerGeneration = 11L)
        val first = coordinator.reconcile(firstIdentity, event(), 1_000L)
        val firstPrediction = (first.action as DurationPrearmAction.Schedule).prediction

        val replacementIdentity = identity(controllerGeneration = 12L)
        val replacement = coordinator.reconcile(replacementIdentity, event(), 1_000L)
        val replacementPrediction = (replacement.action as DurationPrearmAction.Schedule).prediction

        assertEquals("CONTROLLER_GENERATION_CHANGED", replacement.cancelled?.reason)
        assertFalse(coordinator.isCurrent(firstPrediction.token, firstIdentity))
        assertTrue(coordinator.isCurrent(replacementPrediction.token, replacementIdentity))
    }

    @Test
    fun duplicateMetadataForTheSameTrackDoesNotCreateAnotherPrediction() {
        val coordinator = DurationPrearmCoordinator()
        val identity = identity()
        val first = coordinator.reconcile(identity, event(), 1_000L)
        val firstPrediction = (first.action as DurationPrearmAction.Schedule).prediction

        val duplicate = coordinator.reconcile(identity, event(), 1_050L)

        assertNull(duplicate.cancelled)
        assertTrue(duplicate.action is DurationPrearmAction.Unchanged)
        assertEquals(firstPrediction.token, coordinator.current()?.token)
    }

    @Test
    fun albumOnlyEnrichmentKeepsTheSameDurationPrediction() {
        val coordinator = DurationPrearmCoordinator()
        val mixedFrame = event(mediaId = "").copy(
            title = "So Cruel",
            artist = "U2",
            album = "Room On Fire",
        )
        val corrected = mixedFrame.copy(album = "Achtung Baby")
        val firstIdentity = identity(trackKey = TrackFingerprint.core(mixedFrame))
        val correctedIdentity = identity(trackKey = TrackFingerprint.core(corrected))
        val first = coordinator.reconcile(firstIdentity, mixedFrame, 1_000L)
        val firstPrediction = (first.action as DurationPrearmAction.Schedule).prediction

        val enrichment = coordinator.reconcile(correctedIdentity, corrected, 1_050L)

        assertEquals(firstIdentity, correctedIdentity)
        assertNull(enrichment.cancelled)
        assertTrue(enrichment.action is DurationPrearmAction.Unchanged)
        assertEquals(firstPrediction.token, coordinator.current()?.token)
    }

    @Test
    fun transientQueueIdProjectionKeepsTheSameDurationPrediction() {
        val coordinator = DurationPrearmCoordinator()
        val queue = listOf(
            QueueItemSnapshot(null, "Previous", "Other artist", queueItemId = 108L),
            QueueItemSnapshot(null, "track-a", "Artist", queueItemId = 133L),
        )
        val current = event(mediaId = "").copy(
            title = "track-a",
            queue = queue,
            activeQueuePosition = 1,
        )
        val identity = identity(trackKey = TrackFingerprint.core(current))
        val first = coordinator.reconcile(identity, current, 1_000L)
        val firstPrediction = (first.action as DurationPrearmAction.Schedule).prediction

        val transient = coordinator.reconcile(
            identity = identity.copy(trackKey = TrackFingerprint.core(current.copy(activeQueuePosition = 0))),
            event = current.copy(activeQueuePosition = 0),
            nowElapsedMs = 1_050L,
        )

        assertNull(transient.cancelled)
        assertTrue(transient.action is DurationPrearmAction.Unchanged)
        assertEquals(firstPrediction.token, coordinator.current()?.token)
    }

    @Test
    fun listenerRecreationChangesMonitorGenerationAndCannotReuseOldTimer() {
        val coordinator = DurationPrearmCoordinator()
        val beforeRestart = identity(monitorGeneration = 4L)
        val first = coordinator.reconcile(beforeRestart, event(), 1_000L)
        val oldPrediction = (first.action as DurationPrearmAction.Schedule).prediction

        val afterRestart = identity(monitorGeneration = 5L)
        val restored = coordinator.reconcile(afterRestart, event(), 1_000L)
        val newPrediction = (restored.action as DurationPrearmAction.Schedule).prediction

        assertEquals("MONITOR_GENERATION_CHANGED", restored.cancelled?.reason)
        assertFalse(coordinator.isCurrent(oldPrediction.token, beforeRestart))
        assertTrue(coordinator.isCurrent(newPrediction.token, afterRestart))
    }

    private fun identity(
        trackKey: String = "track-a",
        monitorGeneration: Long = 1L,
        controllerGeneration: Long = 1L,
    ) = DurationPrearmIdentity(
        monitorGeneration = monitorGeneration,
        logicalSessionGeneration = 1L,
        sessionKey = "session-a",
        controllerGeneration = controllerGeneration,
        sourcePackageName = "player",
        trackKey = trackKey,
    )

    private fun event(
        mediaId: String = "track-a",
        durationMs: Long? = 180_000L,
        durationMetadataPresent: Boolean = true,
        positionMs: Long = 100_000L,
        positionUpdatedAtElapsedMs: Long? = 1_000L,
        playbackStatus: PlaybackStatus = PlaybackStatus.PLAYING,
        playbackSpeed: Float? = 1f,
    ) = PlaybackEvent(
        sourcePackageName = "player",
        sourceAppName = "Player",
        title = mediaId,
        artist = "Artist",
        album = "Album",
        albumArtist = null,
        trackNumber = null,
        totalTracks = null,
        discNumber = null,
        duration = durationMs,
        mediaId = mediaId,
        playbackState = playbackStatus,
        playbackPosition = positionMs,
        playbackStateUpdateElapsedMs = positionUpdatedAtElapsedMs,
        observedAt = 1_000L,
        durationMetadataPresent = durationMetadataPresent,
        playbackSpeed = playbackSpeed,
    )
}
