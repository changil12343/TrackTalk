package com.trackvoice.media

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.DeadObjectException
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import com.trackvoice.announcement.PlaybackRestoreWatchdogPolicy
import com.trackvoice.diagnostics.TrackTalkDebugLog
import com.trackvoice.service.TrackVoiceNotificationListenerService
import java.util.Locale
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class MediaSessionMonitor(
    context: Context,
    private val onUpdate: (MediaMonitorUpdate) -> Unit,
) {
    private var pauseAcknowledgementExpiryMs =
        PlaybackRestoreWatchdogPolicy.PAUSE_ACKNOWLEDGEMENT_EXPIRY_MS

    internal constructor(
        context: Context,
        onUpdate: (MediaMonitorUpdate) -> Unit,
        pauseAcknowledgementExpiryMs: Long,
    ) : this(context, onUpdate) {
        this.pauseAcknowledgementExpiryMs = pauseAcknowledgementExpiryMs
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(
        appContext,
        TrackVoiceNotificationListenerService::class.java,
    )
    private val handler = Handler(Looper.getMainLooper())
    private val mapper = TrackMetadataMapper(::resolveAppName)
    private val sessions = linkedMapOf<String, TrackedSession>()
    private val controllerGenerations = ControllerGenerationRegistry()
    private var started = false
    private var sessionRecoveryScheduled = false
    private var pendingMediaNotificationReconcile: PendingMediaNotificationReconcile? = null
    private var monitorLifecycleGeneration = 0L
    private var selectedSessionKey: String? = null
    private var resumeRequestId = 0L
    private var activeResumeRequest: ActiveResumeRequest? = null
    private var lastPublishedSelection: PublishedSelection? = null
    private var eventSequenceNumber = 0L

    val activeSessionCount: Int get() = runOnMonitorThread { sessions.size }

    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        if (!started) return@OnActiveSessionsChangedListener
        val activeControllers = controllers.orEmpty()
        TrackTalkDebugLog.event(
            "ACTIVE_SESSIONS_CHANGED",
            "timestamp" to System.currentTimeMillis(),
            "controllerCount" to activeControllers.size,
            "thread" to Thread.currentThread().name,
        )
        updateControllers(activeControllers, reason = "ACTIVE_SESSIONS_CHANGED")
        publish(MediaEventType.ACTIVE_SESSIONS)
    }

    fun start() {
        runOnMonitorThread {
            if (started) return@runOnMonitorThread
            started = true
            monitorLifecycleGeneration += 1
            val listenerRegistered = mediaSessionCall(
                operation = "ADD_ACTIVE_SESSIONS_LISTENER",
            ) {
                manager.addOnActiveSessionsChangedListener(
                    activeSessionsListener,
                    listenerComponent,
                    handler,
                )
                true
            } ?: false
            if (!listenerRegistered) {
                started = false
                sessions.clear()
                controllerGenerations.invalidateAll()
                selectedSessionKey = null
                return@runOnMonitorThread
            }
            refreshControllersFromSystem(reason = "INITIAL_START", recovery = true, eventType = MediaEventType.INITIAL)
        }
    }

    fun stop() {
        runOnMonitorThread {
            cancelPendingMediaNotificationReconcile("MONITOR_STOPPED")
            cancelActiveResume("MONITOR_STOPPED")
            resumeRequestId += 1
            if (!started) {
                monitorLifecycleGeneration += 1
                controllerGenerations.invalidateAll()
                return@runOnMonitorThread
            }
            started = false
            monitorLifecycleGeneration += 1
            sessionRecoveryScheduled = false
            mediaSessionCall(operation = "REMOVE_ACTIVE_SESSIONS_LISTENER") {
                manager.removeOnActiveSessionsChangedListener(activeSessionsListener)
            }
            sessions.values.toList().forEach { tracked ->
                detachTrackedSession(
                    sessionKey = tracked.sessionKey,
                    generation = tracked.generation,
                    reason = "MONITOR_STOPPED",
                )
            }
            controllerGenerations.invalidateAll()
            selectedSessionKey = null
        }
    }

    fun refresh() {
        runOnMonitorThread {
            if (!started) return@runOnMonitorThread
            refreshControllersFromSystem(reason = "MANUAL_REFRESH")
        }
    }

    /**
     * Reconcile one eligible media-notification hint through the monitor's existing serialized
     * session snapshot path. The hint has no metadata, occurrence, or transport authority.
     */
    fun reconcileFromMediaNotificationHint(sourcePackageName: String) {
        runOnMonitorThread {
            if (!started) {
                logMediaNotificationReconcileCancelled(sourcePackageName, "MONITOR_NOT_STARTED")
                return@runOnMonitorThread
            }
            val selected = selectedTrackedSession()
            if (selected == null) {
                logMediaNotificationReconcileCancelled(sourcePackageName, "NO_SELECTED_SESSION")
                return@runOnMonitorThread
            }
            if (selected.controller.packageName != sourcePackageName) {
                logMediaNotificationReconcileCancelled(sourcePackageName, "SOURCE_PACKAGE_MISMATCH")
                return@runOnMonitorThread
            }

            val existing = pendingMediaNotificationReconcile
            if (existing != null) {
                TrackTalkDebugLog.event(
                    "MEDIA_RECONCILE_COALESCED",
                    "package" to sourcePackageName,
                    "sessionKey" to selected.sessionKey,
                    "controllerGeneration" to selected.generation,
                    "pendingSessionKey" to existing.sessionKey,
                )
                return@runOnMonitorThread
            }

            val pending = PendingMediaNotificationReconcile(
                sourcePackageName = sourcePackageName,
                sessionKey = selected.sessionKey,
                controllerGeneration = selected.generation,
                monitorLifecycleGeneration = monitorLifecycleGeneration,
            )
            pendingMediaNotificationReconcile = pending
            TrackTalkDebugLog.event(
                "MEDIA_RECONCILE_HINT",
                "package" to sourcePackageName,
                "sessionKey" to selected.sessionKey,
                "controllerGeneration" to selected.generation,
                "monitorLifecycleGeneration" to monitorLifecycleGeneration,
            )
            handler.post {
                runPendingMediaNotificationReconcile(pending)
            }
        }
    }

    fun pauseSelectedIfPlaying(
        expectedEvent: PlaybackEvent? = null,
        expectedSessionKey: String? = null,
        announcementCycleId: Long? = null,
        onPauseRequested: ((elapsedRealtimeNanos: Long) -> Unit)? = null,
    ): PlaybackPauseToken? = runOnMonitorThread {
        // A new announcement owns the pause/resume lifecycle. Do not let a
        // delayed completion from a previous announcement play the track again.
        cancelActiveResume("NEW_TRACKTALK_PAUSE")
        resumeRequestId += 1
        val tracked = selectedTrackedSession() ?: return@runOnMonitorThread null
        val event = if (expectedEvent != null) {
            val expectedTrackMatches = mediaSessionCall(
                operation = "MATCH_EXPECTED_PLAYING_TRACK",
                sessionKey = tracked.sessionKey,
                onFailure = {
                    invalidateFailedTrackedSession(tracked, "MATCH_EXPECTED_PLAYING_TRACK")
                },
            ) {
                matchesExpectedPlayingTrack(tracked.controller, expectedSessionKey, expectedEvent)
            }
            if (expectedTrackMatches != true) {
                return@runOnMonitorThread null
            }
            expectedEvent
        } else {
            readTrackedEvent(tracked, "MAP_PAUSE_CANDIDATE") ?: return@runOnMonitorThread null
        }
        if (!event.isPlaying || !event.hasTitle) return@runOnMonitorThread null
        val pauseRequestedAtNanos = SystemClock.elapsedRealtimeNanos()
        // Issue the media command before debug logging. The timestamp still
        // marks the request boundary, while Logcat I/O can no longer delay the
        // command that protects the opening of the confirmed track.
        val paused = mediaSessionCall(
            operation = "TRANSPORT_PAUSE",
            sessionKey = tracked.sessionKey,
            onFailure = {
                invalidateFailedTrackedSession(tracked, "TRANSPORT_PAUSE")
            },
        ) {
            tracked.controller.transportControls.pause()
            true
        } ?: false
        if (paused) {
            // Diagnostics must never turn a successfully issued PAUSE into a
            // missing resume token if a test/listener callback happens to fail.
            try {
                onPauseRequested?.invoke(pauseRequestedAtNanos)
            } catch (failure: RuntimeException) {
                TrackTalkDebugLog.event(
                    "PAUSE_DIAGNOSTIC_CALLBACK_FAILED",
                    "announcementCycleId" to announcementCycleId,
                    "failure" to failure.javaClass.simpleName,
                )
            }
            TrackTalkDebugLog.event(
                "PLAYBACK_PAUSE_REQUESTED",
                "announcementCycleId" to announcementCycleId,
                "elapsedRealtimeNanos" to pauseRequestedAtNanos,
                "source" to event.sourcePackageName,
                "mediaId" to event.mediaId,
            )
        }
        if (paused) {
            val pauseCoreFingerprint = TrackFingerprint.core(event)
            val playingCallbackBaselineElapsedNanos = tracked
                .lastPlayingCallbackStateUpdatedAtElapsedMs
                ?.takeIf {
                    tracked.lastPlayingCallbackCoreFingerprint == pauseCoreFingerprint
                }
                ?.times(NANOS_PER_MILLISECOND)
            PlaybackPauseToken(
                sessionKey = tracked.sessionKey,
                fingerprint = pauseCoreFingerprint,
                sourcePackageName = event.sourcePackageName,
                mediaId = event.mediaId,
                title = event.title,
                artist = event.artist,
                album = event.album,
                trackNumber = event.trackNumber,
                discNumber = event.discNumber,
                queueItemId = PlaybackRestoreTrackMatcher.queueItemIdForPause(event),
                pauseRequestedAtElapsedNanos = pauseRequestedAtNanos,
                playbackStateUpdatedAtPauseElapsedNanos = playingCallbackBaselineElapsedNanos,
                eventSequenceNumberAtPauseCommand = eventSequenceNumber,
                controllerGeneration = tracked.generation,
                monitorLifecycleGeneration = monitorLifecycleGeneration,
            )
        } else {
            null
        }
    }

    fun resumePlayback(
        token: PlaybackPauseToken,
        announcementCycleId: Long? = null,
        ownedPauseAcknowledged: Boolean = false,
        onEvent: (PlaybackRestoreEvent) -> Unit = {},
    ) {
        runOnMonitorThread {
            cancelActiveResume("REPLACED_BY_NEW_RESTORE")
            val requestId = ++resumeRequestId
            val request = ActiveResumeRequest(
                requestId = requestId,
                announcementCycleId = announcementCycleId,
                token = token,
                callback = onEvent,
                ownedPauseAcknowledged = ownedPauseAcknowledged,
            )
            activeResumeRequest = request
            TrackTalkDebugLog.event(
                "PLAYBACK_RESTORE_REQUESTED",
                "announcementGeneration" to announcementCycleId,
                "controllerGeneration" to token.controllerGeneration,
                "reason" to "TTS_TRANSACTION_FINISHED",
            )
            attemptRestore(request)
        }
    }

    fun cancelPendingResume(reason: String) {
        runOnMonitorThread {
            cancelActiveResume(reason)
            resumeRequestId += 1
        }
    }

    fun toggleSelectedPlayback(): Boolean? = runOnMonitorThread {
        // A manual tap is an explicit user decision and cancels any automatic
        // resume that may still be queued for an earlier announcement.
        cancelActiveResume("USER_TOGGLE")
        resumeRequestId += 1
        val tracked = selectedTrackedSession() ?: return@runOnMonitorThread null
        val event = readTrackedEvent(tracked, "MAP_USER_TOGGLE_CANDIDATE")
            ?: return@runOnMonitorThread null
        when {
            event.isPlaying -> mediaSessionCall(
                operation = "TRANSPORT_USER_PAUSE",
                sessionKey = tracked.sessionKey,
                onFailure = {
                    invalidateFailedTrackedSession(tracked, "TRANSPORT_USER_PAUSE")
                },
            ) {
                TrackTalkDebugLog.event(
                    "USER_PLAYBACK_PAUSE_REQUESTED",
                    "controllerGeneration" to tracked.generation,
                    "reason" to "EXPLICIT_USER_TOGGLE",
                )
                tracked.controller.transportControls.pause()
                false
            }

            event.hasTitle -> mediaSessionCall(
                operation = "TRANSPORT_USER_PLAY",
                sessionKey = tracked.sessionKey,
                onFailure = {
                    invalidateFailedTrackedSession(tracked, "TRANSPORT_USER_PLAY")
                },
            ) {
                TrackTalkDebugLog.event(
                    "USER_PLAYBACK_PLAY_REQUESTED",
                    "controllerGeneration" to tracked.generation,
                    "reason" to "EXPLICIT_USER_TOGGLE",
                )
                tracked.controller.transportControls.play()
                true
            }

            else -> null
        }
    }

    fun isSelectedPlaybackPlaying(): Boolean? = runOnMonitorThread {
        selectedTrackedSession()?.let {
            readTrackedEvent(it, "MAP_SELECTED_PLAYBACK_STATE")?.isPlaying
        }
    }

    private fun runPendingMediaNotificationReconcile(
        pending: PendingMediaNotificationReconcile,
    ) {
        if (pendingMediaNotificationReconcile != pending) {
            logMediaNotificationReconcileCancelled(
                sourcePackageName = pending.sourcePackageName,
                reason = "SUPERSEDED",
            )
            return
        }
        pendingMediaNotificationReconcile = null
        if (!started || pending.monitorLifecycleGeneration != monitorLifecycleGeneration) {
            logMediaNotificationReconcileCancelled(
                sourcePackageName = pending.sourcePackageName,
                reason = "MONITOR_GENERATION_CHANGED",
            )
            return
        }
        val selected = selectedTrackedSession()
        if (
            selected == null ||
                selected.sessionKey != pending.sessionKey ||
                selected.generation != pending.controllerGeneration ||
                selected.controller.packageName != pending.sourcePackageName
        ) {
            logMediaNotificationReconcileCancelled(
                sourcePackageName = pending.sourcePackageName,
                reason = "SESSION_OR_CONTROLLER_CHANGED",
            )
            return
        }
        TrackTalkDebugLog.event(
            "MEDIA_RECONCILE_START",
            "package" to pending.sourcePackageName,
            "sessionKey" to pending.sessionKey,
            "controllerGeneration" to pending.controllerGeneration,
            "monitorLifecycleGeneration" to pending.monitorLifecycleGeneration,
        )
        refreshControllersFromSystem(
            reason = "MEDIA_NOTIFICATION_RECONCILE",
            eventType = MediaEventType.MEDIA_NOTIFICATION_RECONCILE,
        )
    }

    private fun cancelPendingMediaNotificationReconcile(reason: String) {
        val pending = pendingMediaNotificationReconcile ?: return
        pendingMediaNotificationReconcile = null
        logMediaNotificationReconcileCancelled(pending.sourcePackageName, reason)
    }

    private fun logMediaNotificationReconcileCancelled(
        sourcePackageName: String,
        reason: String,
    ) {
        TrackTalkDebugLog.event(
            "MEDIA_RECONCILE_CANCELLED",
            "package" to sourcePackageName,
            "reason" to reason,
        )
    }

    private fun refreshControllersFromSystem(
        reason: String,
        recovery: Boolean = false,
        eventType: MediaEventType = MediaEventType.ACTIVE_SESSIONS,
    ) {
        if (!started) return
        if (recovery) {
            TrackTalkDebugLog.event(
                "SESSION_RECOVERY_STARTED",
                "reason" to reason,
                "activeSessionCount" to sessions.size,
            )
        }
        val controllers = mediaSessionCall(operation = "GET_ACTIVE_SESSIONS:$reason") {
            manager.getActiveSessions(listenerComponent).orEmpty()
        }
        if (controllers == null) {
            if (eventType == MediaEventType.MEDIA_NOTIFICATION_RECONCILE) {
                TrackTalkDebugLog.event(
                    "MEDIA_RECONCILE_FAILED",
                    "reason" to "ACTIVE_SESSION_READ_FAILED",
                )
            }
            if (recovery) {
                TrackTalkDebugLog.event(
                    "SESSION_RECOVERY_DONE",
                    "reason" to reason,
                    "recovered" to false,
                    "activeSessionCount" to sessions.size,
                )
            } else {
                scheduleSessionRecovery("ACTIVE_SESSION_READ_FAILED:$reason")
            }
            return
        }
        updateControllers(controllers, reason = reason)
        publish(eventType)
        if (recovery) {
            TrackTalkDebugLog.event(
                "SESSION_RECOVERY_DONE",
                "reason" to reason,
                "recovered" to true,
                "activeSessionCount" to sessions.size,
            )
        }
    }

    /**
     * A recovery is queued onto the monitor looper so a dying controller cannot mutate the
     * session map while its callback or a snapshot iteration is still executing. This is not a
     * polling loop: one framework failure/destroy event schedules one immediate reconciliation.
     */
    private fun scheduleSessionRecovery(reason: String) {
        if (!started || sessionRecoveryScheduled) return
        val scheduledForLifecycleGeneration = monitorLifecycleGeneration
        sessionRecoveryScheduled = true
        handler.post {
            sessionRecoveryScheduled = false
            if (!started || scheduledForLifecycleGeneration != monitorLifecycleGeneration) {
                TrackTalkDebugLog.event(
                    "STALE_EVENT_DROPPED",
                    "event" to "SESSION_RECOVERY",
                    "scheduledLifecycleGeneration" to scheduledForLifecycleGeneration,
                    "currentLifecycleGeneration" to monitorLifecycleGeneration,
                    "reason" to reason,
                )
                return@post
            }
            refreshControllersFromSystem(reason = reason, recovery = true)
        }
    }

    private fun updateControllers(controllers: List<MediaController>, reason: String) {
        val controllersByKey = linkedMapOf<String, MediaController>()
        controllers.forEach { controller ->
            val key = mediaSessionCall(operation = "READ_CONTROLLER_SESSION_KEY") {
                sessionKey(controller)
            }
            if (key == null) {
                scheduleSessionRecovery("CONTROLLER_KEY_READ_FAILED:$reason")
            } else {
                controllersByKey[key] = controller
            }
        }

        sessions.values
            .toList()
            .filter { it.sessionKey !in controllersByKey }
            .forEach { tracked ->
                detachTrackedSession(
                    sessionKey = tracked.sessionKey,
                    generation = tracked.generation,
                    reason = "ABSENT_FROM_ACTIVE_SESSIONS:$reason",
                )
            }

        controllersByKey.forEach { (key, controller) ->
            val existing = sessions[key]
            when {
                existing == null -> attachController(key, controller, reason)
                existing.controller !== controller -> {
                    val sameFrameworkSession = mediaSessionCall(
                        operation = "COMPARE_CONTROLLER_SESSION_TOKEN",
                        sessionKey = key,
                    ) {
                        existing.controller.sessionToken == controller.sessionToken
                    } == true
                    if (sameFrameworkSession) {
                        // getActiveSessions() may create a fresh MediaController wrapper for the
                        // exact same framework token. The already registered controller remains
                        // valid and keeps its callback generation; wrapper object identity is not
                        // a playback/session boundary and cannot revoke an owned-pause lease.
                        TrackTalkDebugLog.event(
                            "CONTROLLER_WRAPPER_REFRESH_RETAINED",
                            "sessionKey" to key,
                            "package" to packageFromSessionKey(key),
                            "generation" to existing.generation,
                            "reason" to reason,
                        )
                    } else {
                        TrackTalkDebugLog.event(
                            "ACTIVE_SESSION_REFRESH",
                            "sessionKey" to key,
                            "package" to packageFromSessionKey(key),
                            "controllerInstanceChanged" to true,
                            "sameFrameworkSession" to false,
                            "reason" to reason,
                        )
                        detachTrackedSession(
                            sessionKey = key,
                            generation = existing.generation,
                            reason = "CONTROLLER_REPLACED:$reason",
                        )
                        attachController(key, controller, "CONTROLLER_REPLACED:$reason")
                    }
                }
            }
        }
    }

    private fun attachController(
        sessionKey: String,
        controller: MediaController,
        reason: String,
    ) {
        val generation = controllerGenerations.attach(sessionKey)
        val callback = callbackFor(sessionKey, generation)
        val now = System.currentTimeMillis()
        val tracked = TrackedSession(
            sessionKey = sessionKey,
            generation = generation,
            controller = controller,
            callback = callback,
            lastMetadataChangedAt = now,
            lastPlaybackStateChangedAt = now,
            lastObservedAt = now,
        )
        // Register after publishing this generation to the map. A provider is allowed to deliver
        // its current state immediately from registerCallback().
        sessions[sessionKey] = tracked
        val registered = mediaSessionCall(
            operation = "REGISTER_CONTROLLER_CALLBACK",
            sessionKey = sessionKey,
        ) {
            controller.registerCallback(callback, handler)
            true
        } ?: false
        if (!registered) {
            sessions[sessionKey]
                ?.takeIf { it.generation == generation }
                ?.let { sessions.remove(sessionKey) }
            controllerGenerations.invalidate(sessionKey, generation)
            scheduleSessionRecovery("CONTROLLER_ATTACH_FAILED:$reason")
            return
        }
        TrackTalkDebugLog.event(
            "CONTROLLER_GENERATION",
            "stage" to "ATTACHED",
            "sessionKey" to sessionKey,
            "generation" to generation,
            "reason" to reason,
        )
        TrackTalkDebugLog.event(
            "CONTROLLER_ATTACH",
            "sessionKey" to sessionKey,
            "package" to packageFromSessionKey(sessionKey),
            "generation" to generation,
            "reason" to reason,
            "thread" to Thread.currentThread().name,
        )
    }

    private fun detachTrackedSession(
        sessionKey: String,
        generation: Long,
        reason: String,
    ): Boolean {
        val tracked = sessions[sessionKey]
        if (
            tracked == null ||
            tracked.generation != generation ||
            !controllerGenerations.isCurrent(sessionKey, generation)
        ) {
            TrackTalkDebugLog.event(
                "STALE_EVENT_DROPPED",
                "event" to "CONTROLLER_DETACH",
                "sessionKey" to sessionKey,
                "callbackGeneration" to generation,
                "currentGeneration" to controllerGenerations.currentGeneration(sessionKey),
                "reason" to reason,
            )
            return false
        }
        val unregistered = mediaSessionCall(
            operation = "UNREGISTER_CONTROLLER_CALLBACK",
            sessionKey = sessionKey,
        ) {
            tracked.controller.unregisterCallback(tracked.callback)
            true
        } ?: false
        activeResumeRequest
            ?.takeIf {
                it.token.sessionKey == sessionKey &&
                    it.token.controllerGeneration == generation
            }
            ?.let { request ->
                rejectRestore(request, "CONTROLLER_INVALIDATED:$reason")
            }
        sessions.remove(sessionKey)
        controllerGenerations.invalidate(sessionKey, generation)
        if (selectedSessionKey == sessionKey) selectedSessionKey = null
        TrackTalkDebugLog.event(
            "CONTROLLER_DETACH",
            "sessionKey" to sessionKey,
            "package" to packageFromSessionKey(sessionKey),
            "generation" to generation,
            "reason" to reason,
            "callbackUnregistered" to unregistered,
            "thread" to Thread.currentThread().name,
        )
        TrackTalkDebugLog.event(
            "CONTROLLER_GENERATION",
            "stage" to "INVALIDATED",
            "sessionKey" to sessionKey,
            "generation" to generation,
            "reason" to reason,
        )
        if (!unregistered) scheduleSessionRecovery("CONTROLLER_DETACH_FAILED:$reason")
        return true
    }

    private fun callbackFor(
        sessionKey: String,
        generation: Long,
    ): MediaController.Callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            withCurrentControllerCallback(sessionKey, generation, "METADATA_CHANGED") {
                publish(MediaEventType.METADATA, sessionKey)
            }
        }

        override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
            withCurrentControllerCallback(sessionKey, generation, "PLAYBACK_STATE_CHANGED") {
                recordPlayingCallbackBaseline(sessionKey, state)
                observeActiveResumeState(sessionKey, state)
                publish(MediaEventType.PLAYBACK_STATE, sessionKey)
            }
        }

        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) {
            withCurrentControllerCallback(sessionKey, generation, "QUEUE_CHANGED") {
                publish(MediaEventType.QUEUE, sessionKey)
            }
        }

        override fun onSessionDestroyed() {
            withCurrentControllerCallback(sessionKey, generation, "SESSION_DESTROYED") {
                TrackTalkDebugLog.event(
                    "SESSION_DESTROYED",
                    "sessionKey" to sessionKey,
                    "generation" to generation,
                    "package" to packageFromSessionKey(sessionKey),
                )
                detachTrackedSession(
                    sessionKey = sessionKey,
                    generation = generation,
                    reason = "SESSION_DESTROYED",
                )
                scheduleSessionRecovery("SESSION_DESTROYED")
            }
        }
    }

    private inline fun withCurrentControllerCallback(
        sessionKey: String,
        generation: Long,
        event: String,
        block: () -> Unit,
    ) {
        val tracked = sessions[sessionKey]
        if (
            !started ||
            tracked == null ||
            tracked.generation != generation ||
            !controllerGenerations.isCurrent(sessionKey, generation)
        ) {
            TrackTalkDebugLog.event(
                "STALE_EVENT_DROPPED",
                "event" to event,
                "sessionKey" to sessionKey,
                "callbackGeneration" to generation,
                "currentGeneration" to controllerGenerations.currentGeneration(sessionKey),
                "monitorStarted" to started,
            )
            return
        }
        block()
    }

    private fun publish(eventType: MediaEventType, changedSessionKey: String? = null) {
        if (!started) return
        val observedAtElapsedNanos = SystemClock.elapsedRealtimeNanos()
        val now = System.currentTimeMillis()
        val sequence = ++eventSequenceNumber
        TrackTalkDebugLog.event(
            when (eventType) {
                MediaEventType.ACTIVE_SESSIONS -> "ACTIVE_SESSIONS_CHANGED"
                MediaEventType.METADATA -> "METADATA_CALLBACK"
                MediaEventType.PLAYBACK_STATE -> "PLAYBACK_STATE_CALLBACK"
                MediaEventType.QUEUE -> "QUEUE_CALLBACK"
                MediaEventType.MEDIA_NOTIFICATION_RECONCILE -> "MEDIA_RECONCILE_SNAPSHOT"
                else -> "MEDIA_CALLBACK"
            },
            "timestamp" to now,
            "eventSequenceNumber" to sequence,
            "sessionKey" to changedSessionKey,
            "thread" to Thread.currentThread().name,
        )
        changedSessionKey?.let { key ->
            sessions[key]?.let { tracked ->
                when (eventType) {
                    MediaEventType.METADATA -> tracked.lastMetadataChangedAt = now
                    MediaEventType.PLAYBACK_STATE -> tracked.lastPlaybackStateChangedAt = now
                    else -> Unit
                }
                if (eventType != MediaEventType.ACTIVE_SESSIONS) {
                    tracked.lastObservedAt = now
                }
            }
        }

        val mediaKeyToken = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mediaSessionCall(
                operation = "GET_MEDIA_KEY_EVENT_SESSION",
                onFailure = { scheduleSessionRecovery("MEDIA_KEY_SESSION_READ_FAILED") },
            ) {
                manager.getMediaKeyEventSession()
            }
        } else null
        val failedSnapshots = mutableListOf<TrackedSession>()
        val snapshots = sessions.values.toList().mapNotNull { tracked ->
            val snapshot = mediaSessionCall(
                operation = "MAP_SESSION_SNAPSHOT",
                sessionKey = tracked.sessionKey,
            ) {
                SessionSnapshot(
                    sessionKey = tracked.sessionKey,
                    event = mapper.map(tracked.controller, now),
                    isMediaKeySession = mediaKeyToken != null && mediaKeyToken == tracked.controller.sessionToken,
                    lastMetadataChangedAt = tracked.lastMetadataChangedAt,
                    lastPlaybackStateChangedAt = tracked.lastPlaybackStateChangedAt,
                    lastObservedAt = tracked.lastObservedAt,
                    controllerGeneration = tracked.generation,
                )
            }
            if (snapshot == null) failedSnapshots += tracked
            snapshot
        }
        failedSnapshots.forEach { tracked ->
            detachTrackedSession(
                sessionKey = tracked.sessionKey,
                generation = tracked.generation,
                reason = "SESSION_SNAPSHOT_READ_FAILED",
            )
        }
        if (failedSnapshots.isNotEmpty()) {
            scheduleSessionRecovery("SESSION_SNAPSHOT_READ_FAILED")
        }
        val selected = ActiveSessionSelector.select(snapshots)
        observeActiveResume()
        val previousSelection = lastPublishedSelection
        if (previousSelection != null && selected != null && (
                eventType == MediaEventType.ACTIVE_SESSIONS ||
                    previousSelection.sessionKey != selected.sessionKey
                )
        ) {
            TrackTalkDebugLog.event(
                "ACTIVE_SESSION_REFRESH",
                "eventSequenceNumber" to sequence,
                "oldSessionKey" to previousSelection.sessionKey,
                "newSessionKey" to selected.sessionKey,
                "oldPackage" to previousSelection.event.sourcePackageName,
                "newPackage" to selected.event.sourcePackageName,
                "sameSessionToken" to (previousSelection.sessionKey == selected.sessionKey),
                "sameLogicalTrack" to sameLogicalTrack(previousSelection.event, selected.event),
                "eventType" to eventType,
            )
        }
        selectedSessionKey = selected?.sessionKey
        lastPublishedSelection = selected?.let { PublishedSelection(it.sessionKey, it.event) }
        if (previousSelection != null && selected != null && (
                eventType == MediaEventType.ACTIVE_SESSIONS ||
                    previousSelection.sessionKey != selected.sessionKey
                )
        ) {
            TrackTalkDebugLog.event(
                "CONTROLLER_SELECTED",
                "eventSequenceNumber" to sequence,
                "sessionKey" to selected.sessionKey,
                "package" to selected.event.sourcePackageName,
                "sameLogicalTrack" to (previousSelection?.let { sameLogicalTrack(it.event, selected.event) } ?: false),
                "thread" to Thread.currentThread().name,
            )
        }
        if (previousSelection == null && selected != null) {
            TrackTalkDebugLog.event(
                "CONTROLLER_SELECTED",
                "eventSequenceNumber" to sequence,
                "sessionKey" to selected.sessionKey,
                "package" to selected.event.sourcePackageName,
                "sameLogicalTrack" to false,
                "thread" to Thread.currentThread().name,
            )
        }
        TrackTalkDebugLog.event(
            "media_update",
            "eventSequenceNumber" to sequence,
            "type" to eventType,
            "activeSessions" to sessions.size,
            "source" to selected?.event?.sourcePackageName,
            "mediaId" to selected?.event?.mediaId,
            "title" to selected?.event?.title,
            "artist" to selected?.event?.artist,
            "album" to selected?.event?.album,
            "trackNumber" to selected?.event?.trackNumber,
            "trackNumberSource" to selected?.event?.trackNumberSource,
            "queueTitle" to selected?.event?.queueTitle,
            "queueSize" to selected?.event?.queue?.size,
            "activeQueuePosition" to selected?.event?.activeQueuePosition,
            "queueAlbumCoverage" to selected?.event?.queue?.count { !it.album.isNullOrBlank() },
            "queueTrackCoverage" to selected?.event?.queue?.count { it.trackNumber != null },
            "playing" to selected?.event?.isPlaying,
            "observedAt" to now,
            "thread" to Thread.currentThread().name,
        )
        onUpdate(
            MediaMonitorUpdate(
                selected = selected,
                activeSessionCount = sessions.size,
                eventType = eventType,
                observedAt = now,
                observedAtElapsedNanos = observedAtElapsedNanos,
                eventSequenceNumber = sequence,
                selectedSessionKey = selected?.sessionKey,
                callbackThread = Thread.currentThread().name,
                selectedControllerGeneration = selected?.controllerGeneration,
            ),
        )
    }

    /**
     * MediaController's synchronous PLAYING snapshot may contain a position-
     * adjusted update time from the read itself. Only an actual ordered
     * PLAYING callback supplies the baseline that a provider may legitimately
     * reuse in its later PAUSED callback.
     */
    private fun recordPlayingCallbackBaseline(
        sessionKey: String,
        state: PlaybackState?,
    ) {
        if (state?.state != PlaybackState.STATE_PLAYING) return
        val tracked = sessions[sessionKey] ?: return
        val stateUpdatedAtElapsedMs = state.lastPositionUpdateTime.takeIf { it > 0L }
        if (stateUpdatedAtElapsedMs == null) {
            tracked.lastPlayingCallbackStateUpdatedAtElapsedMs = null
            tracked.lastPlayingCallbackCoreFingerprint = null
            return
        }
        val event = readTrackedEvent(tracked, "MAP_PLAYING_CALLBACK_BASELINE") ?: return
        if (!event.isPlaying) return
        tracked.lastPlayingCallbackStateUpdatedAtElapsedMs = stateUpdatedAtElapsedMs
        tracked.lastPlayingCallbackCoreFingerprint = TrackFingerprint.core(event)
    }

    private fun sessionKey(controller: MediaController): String =
        "${controller.packageName}:${controller.sessionToken.hashCode()}"

    private fun packageFromSessionKey(sessionKey: String): String = sessionKey.substringBefore(':')

    /**
     * Android MediaController calls cross a process/binder boundary. Keep the failure isolation
     * here, immediately around that boundary, rather than swallowing exceptions around monitor
     * state transitions or controller policy.
     */
    private fun <T> mediaSessionCall(
        operation: String,
        sessionKey: String? = null,
        onFailure: (() -> Unit)? = null,
        block: () -> T,
    ): T? = try {
        block()
    } catch (failure: DeadObjectException) {
        logSessionReadFailure(operation, sessionKey, failure)
        onFailure?.invoke()
        null
    } catch (failure: RemoteException) {
        logSessionReadFailure(operation, sessionKey, failure)
        onFailure?.invoke()
        null
    } catch (failure: SecurityException) {
        logSessionReadFailure(operation, sessionKey, failure)
        onFailure?.invoke()
        null
    } catch (failure: IllegalStateException) {
        logSessionReadFailure(operation, sessionKey, failure)
        onFailure?.invoke()
        null
    } catch (failure: RuntimeException) {
        // A dead remote MediaSession can surface as an unchecked binder failure. This catch is
        // deliberately limited to direct framework reads/registers/transport commands above.
        logSessionReadFailure(operation, sessionKey, failure)
        onFailure?.invoke()
        null
    }

    private fun logSessionReadFailure(
        operation: String,
        sessionKey: String?,
        failure: Throwable,
    ) {
        TrackTalkDebugLog.event(
            "SESSION_READ_FAILED",
            "operation" to operation,
            "sessionKey" to sessionKey,
            "package" to sessionKey?.let(::packageFromSessionKey),
            "failure" to failure.javaClass.simpleName,
        )
    }

    private fun invalidateFailedTrackedSession(
        tracked: TrackedSession,
        reason: String,
    ) {
        detachTrackedSession(
            sessionKey = tracked.sessionKey,
            generation = tracked.generation,
            reason = "$reason:SESSION_READ_FAILED",
        )
        scheduleSessionRecovery("$reason:SESSION_READ_FAILED")
    }

    private fun readTrackedEvent(
        tracked: TrackedSession,
        operation: String,
    ): PlaybackEvent? {
        val event = mediaSessionCall(
            operation = operation,
            sessionKey = tracked.sessionKey,
        ) {
            mapper.map(tracked.controller)
        }
        if (event != null) return event
        invalidateFailedTrackedSession(tracked, operation)
        return null
    }

    private fun selectedTrackedSession(): TrackedSession? =
        selectedSessionKey?.let(sessions::get)
            ?: sessions.values.maxByOrNull { it.lastObservedAt }

    private fun resolveRestoreSession(token: PlaybackPauseToken): RestoreSessionCandidate? {
        if (token.monitorLifecycleGeneration != monitorLifecycleGeneration) return null
        if (selectedSessionKey != token.sessionKey) return null
        val tracked = sessions[token.sessionKey] ?: return null
        if (
            tracked.generation != token.controllerGeneration ||
                !controllerGenerations.isCurrent(token.sessionKey, token.controllerGeneration)
        ) {
            return null
        }
        val event = readTrackedEvent(tracked, "MAP_RESTORE_CANDIDATE") ?: return null
        return RestoreSessionCandidate(
            sessionKey = tracked.sessionKey,
            controller = tracked.controller,
            event = event,
            match = PlaybackRestoreTrackMatcher.classify(event, token),
        )
    }

    /**
     * The controller has just accepted [expected] from this selected session.
     * Re-reading and remapping the complete queue before PAUSE adds avoidable
     * transition latency. Validate only the authoritative state and compact
     * metadata identity here; a mismatch is a hard stop, never a reason to
     * pause whichever newer track happens to be current.
     */
    private fun matchesExpectedPlayingTrack(
        controller: MediaController,
        expectedSessionKey: String?,
        expected: PlaybackEvent,
    ): Boolean {
        if (!expected.isPlaying || !expected.hasTitle) return false
        if (expectedSessionKey != null && sessionKey(controller) != expectedSessionKey) return false
        if (controller.packageName != expected.sourcePackageName) return false
        if (controller.playbackState?.state != PlaybackState.STATE_PLAYING) return false

        val metadata = controller.metadata
        val currentMediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).normalizedIdentity()
        val expectedMediaId = expected.mediaId.normalizedIdentity()
        if (currentMediaId.isNotBlank() && expectedMediaId.isNotBlank() && currentMediaId != expectedMediaId) {
            return false
        }

        val currentTitle = (
            metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ).normalizedIdentity()
        val expectedTitle = expected.title.normalizedIdentity()
        if (currentTitle.isNotBlank() && expectedTitle.isNotBlank() && currentTitle != expectedTitle) return false

        val currentArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).normalizedIdentity()
        val expectedArtist = expected.artist.normalizedIdentity()
        return currentArtist.isBlank() || expectedArtist.isBlank() || currentArtist == expectedArtist
    }

    private fun String?.normalizedIdentity(): String = this
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.replace(Regex("\\s+"), " ")
        .orEmpty()

    private fun attemptRestore(request: ActiveResumeRequest) {
        if (!isActive(request)) return
        val candidate = restoreCandidateOrReject(request, "RESTORE_ATTEMPT") ?: return
        if (candidate.event.isPlaying) {
            if (request.ownedPauseAcknowledged) {
                request.playingObserved = true
                completeRestore(
                    request,
                    PlaybackRestoreEventType.PLAYING_CONFIRMED,
                    "ALREADY_PLAYING_AFTER_OWNED_PAUSE",
                )
            } else {
                waitForOwnedPauseAcknowledgement(request, "PLAYER_STILL_PLAYING")
            }
            return
        }
        if (candidate.event.playbackState != PlaybackStatus.PAUSED) {
            rejectRestore(request, "NON_PAUSED_RESTORE_STATE_${candidate.event.playbackState}")
            return
        }
        if (!request.ownedPauseAcknowledged) {
            if (!isOwnedPauseAcknowledgement(candidate.event, request.token)) {
                waitForOwnedPauseAcknowledgement(request, "PAUSED_STATE_NOT_ATTRIBUTABLE_YET")
                return
            }
            request.ownedPauseAcknowledged = true
            request.waitingForOwnedPause = false
            TrackTalkDebugLog.event(
                "RESTORE_PAUSE_ACKNOWLEDGED",
                "announcementGeneration" to request.announcementCycleId,
                "controllerGeneration" to request.token.controllerGeneration,
                "stateUpdatedAtElapsedNanos" to candidate.event.playbackStateUpdateElapsedMs
                    ?.times(NANOS_PER_MILLISECOND),
                "source" to candidate.event.sourcePackageName,
            )
        }
        if (request.playCommandCount > 0 || request.commandInFlight) return

        val requestedAtNanos = SystemClock.elapsedRealtimeNanos()
        request.commandInFlight = true
        request.playCommandCount = 1
        request.lastPlayRequestedAtElapsedNanos = requestedAtNanos
        TrackTalkDebugLog.event(
            "PLAYBACK_RESTORE_ALLOWED",
            "announcementGeneration" to request.announcementCycleId,
            "controllerGeneration" to request.token.controllerGeneration,
            "reason" to "VALID_OWNED_PAUSE_LEASE",
        )
        val issued = try {
            mediaSessionCall(
                operation = "TRANSPORT_RESTORE_PLAY",
                sessionKey = candidate.sessionKey,
                onFailure = {
                    sessions[candidate.sessionKey]?.let { tracked ->
                        invalidateFailedTrackedSession(tracked, "TRANSPORT_RESTORE_PLAY")
                    }
                },
            ) {
                candidate.controller.transportControls.play()
                true
            } ?: false
        } finally {
            request.commandInFlight = false
        }
        emitRestoreEvent(
            request = request,
            type = PlaybackRestoreEventType.PLAY_REQUESTED,
            reason = if (issued) "COMMAND_ISSUED" else "COMMAND_FAILED",
            elapsedRealtimeNanos = requestedAtNanos,
            attempt = 1,
            sessionKey = candidate.sessionKey,
            mediaId = candidate.event.mediaId,
        )
        if (!issued) {
            completeRestore(request, PlaybackRestoreEventType.FAILED, "COMMAND_FAILED")
            return
        }
        scheduleRestoreCheck(request, PLAY_CONFIRMATION_MS)
    }

    private fun waitForOwnedPauseAcknowledgement(
        request: ActiveResumeRequest,
        reason: String,
    ) {
        if (request.waitingForOwnedPause) return
        request.waitingForOwnedPause = true
        TrackTalkDebugLog.event(
            "RESTORE_WAITING_FOR_PAUSE_ACK",
            "announcementGeneration" to request.announcementCycleId,
            "controllerGeneration" to request.token.controllerGeneration,
            "reason" to reason,
            "expiryMs" to pauseAcknowledgementExpiryMs,
        )
        val checkGeneration = ++request.checkGeneration
        handler.postDelayed({
            if (!isActive(request) || request.checkGeneration != checkGeneration) return@postDelayed
            TrackTalkDebugLog.event(
                "RESTORE_LEASE_EXPIRED",
                "announcementGeneration" to request.announcementCycleId,
                "controllerGeneration" to request.token.controllerGeneration,
                "reason" to "PAUSE_ACKNOWLEDGEMENT_EXPIRED",
            )
            completeRestore(
                request,
                PlaybackRestoreEventType.CANCELLED,
                "PAUSE_ACKNOWLEDGEMENT_EXPIRED",
            )
        }, pauseAcknowledgementExpiryMs)
    }

    private fun observeActiveResume() {
        val request = activeResumeRequest ?: return
        if (!isActive(request)) return
        val candidate = restoreCandidateOrReject(request, "RESTORE_OBSERVATION") ?: return
        val event = candidate.event
        if (event.isPlaying) {
            request.playingObserved = true
            if (request.playCommandCount > 0) {
                completeRestore(request, PlaybackRestoreEventType.PLAYING_CONFIRMED, "CALLBACK")
            }
        } else if (event.playbackState == PlaybackStatus.PAUSED && request.playCommandCount == 0) {
            attemptRestore(request)
        }
    }

    /**
     * Preserve the ordered state carried by MediaController's callback. If we
     * only remap controller.playbackState later, a quick user PAUSE can replace
     * the preceding PLAYING value before TrackTalk observes it. Ordered callbacks
     * let the restore lease fail safe before any later transport action.
     */
    private fun observeActiveResumeState(
        callbackSessionKey: String,
        state: PlaybackState?,
    ) {
        val request = activeResumeRequest ?: return
        if (!isActive(request) || state == null) return
        val tracked = sessions[callbackSessionKey] ?: return
        if (
            callbackSessionKey != request.token.sessionKey ||
                tracked.generation != request.token.controllerGeneration
        ) {
            rejectRestore(request, "CONTROLLER_OR_SESSION_CHANGED_DURING_RESTORE")
            return
        }
        val event = readTrackedEvent(tracked, "MAP_RESTORE_STATE_CALLBACK") ?: return
        when (PlaybackRestoreTrackMatcher.classify(event, request.token)) {
            PlaybackRestoreTrackMatch.INSUFFICIENT -> {
                rejectRestore(request, "SESSION_IDENTITY_INCOMPLETE_STATE_CALLBACK")
                return
            }

            PlaybackRestoreTrackMatch.MISMATCH -> {
                rejectRestore(request, "TRACK_CHANGED")
                return
            }

            PlaybackRestoreTrackMatch.MATCH,
            PlaybackRestoreTrackMatch.METADATA_ENRICHMENT,
            -> Unit
        }
        when (state.state) {
            PlaybackState.STATE_PLAYING -> {
                request.playingObserved = true
                if (request.playCommandCount > 0) {
                    completeRestore(request, PlaybackRestoreEventType.PLAYING_CONFIRMED, "STATE_CALLBACK")
                }
            }

            PlaybackState.STATE_PAUSED -> {
                val stateUpdatedAtNanos = state.lastPositionUpdateTime
                    .takeIf { it > 0L }
                    ?.times(NANOS_PER_MILLISECOND)
                TrackTalkDebugLog.event(
                    "PLAYBACK_RESTORE_PAUSE_CLASSIFICATION",
                    "announcementCycleId" to request.announcementCycleId,
                    "restoreRequestId" to request.requestId,
                    "controllerGeneration" to request.token.controllerGeneration,
                    "stateUpdatedAtElapsedNanos" to stateUpdatedAtNanos,
                    "pauseRequestedAtElapsedNanos" to request.token.pauseRequestedAtElapsedNanos,
                    "playingBaselineAtPauseElapsedNanos" to
                        request.token.playbackStateUpdatedAtPauseElapsedNanos,
                    "pauseCommandEventSequenceNumber" to
                        request.token.eventSequenceNumberAtPauseCommand,
                    "callbackEventSequenceNumber" to (eventSequenceNumber + 1L),
                    "lastPlayRequestedAtElapsedNanos" to request.lastPlayRequestedAtElapsedNanos,
                )
                if (request.playCommandCount == 0) {
                    val callbackObservedAtElapsedNanos = SystemClock.elapsedRealtimeNanos()
                    val callbackEventSequenceNumber = eventSequenceNumber + 1L
                    val rejectionReason = ownedPauseCallbackRejectionReason(
                        token = request.token,
                        stateUpdatedAtElapsedNanos = stateUpdatedAtNanos,
                        callbackObservedAtElapsedNanos = callbackObservedAtElapsedNanos,
                        callbackEventSequenceNumber = callbackEventSequenceNumber,
                    )
                    if (rejectionReason == null) {
                        request.ownedPauseAcknowledged = true
                        request.waitingForOwnedPause = false
                        TrackTalkDebugLog.event(
                            "RESTORE_PAUSE_ACKNOWLEDGED",
                            "announcementGeneration" to request.announcementCycleId,
                            "controllerGeneration" to request.token.controllerGeneration,
                            "stateUpdatedAtElapsedNanos" to stateUpdatedAtNanos,
                            "callbackObservedAtElapsedNanos" to callbackObservedAtElapsedNanos,
                            "callbackEventSequenceNumber" to callbackEventSequenceNumber,
                            "evidence" to pauseCallbackAcknowledgementEvidence(
                                token = request.token,
                                stateUpdatedAtElapsedNanos = stateUpdatedAtNanos,
                            ),
                            "source" to event.sourcePackageName,
                        )
                        attemptRestore(request)
                    } else {
                        TrackTalkDebugLog.event(
                            "RESTORE_PAUSE_ACK_REJECTED",
                            "announcementGeneration" to request.announcementCycleId,
                            "controllerGeneration" to request.token.controllerGeneration,
                            "reason" to rejectionReason,
                            "stateUpdatedAtElapsedNanos" to stateUpdatedAtNanos,
                            "callbackObservedAtElapsedNanos" to callbackObservedAtElapsedNanos,
                            "callbackEventSequenceNumber" to callbackEventSequenceNumber,
                        )
                        waitForOwnedPauseAcknowledgement(request, "STALE_OR_AMBIGUOUS_PAUSED_CALLBACK")
                    }
                } else {
                    val newerThanPlay = stateUpdatedAtNanos == null ||
                        stateUpdatedAtNanos >= request.lastPlayRequestedAtElapsedNanos
                    if (newerThanPlay) rejectRestore(request, "PAUSED_AFTER_PLAY_REQUEST")
                }
            }

            PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_NONE,
            PlaybackState.STATE_ERROR,
            -> rejectRestore(request, "NON_PLAYABLE_STATE_CALLBACK_${state.state}")
        }
    }

    private fun scheduleRestoreCheck(request: ActiveResumeRequest, delayMs: Long) {
        val checkGeneration = ++request.checkGeneration
        handler.postDelayed({
            if (!isActive(request) || request.checkGeneration != checkGeneration) return@postDelayed
            confirmRestore(request)
        }, delayMs)
    }

    private fun confirmRestore(request: ActiveResumeRequest) {
        if (!isActive(request)) return
        val candidate = restoreCandidateOrReject(request, "RESTORE_CONFIRMATION") ?: return
        val event = candidate.event
        if (event.isPlaying) {
            if (request.playCommandCount > 0) {
                completeRestore(
                    request,
                    PlaybackRestoreEventType.PLAYING_CONFIRMED,
                    "CONFIRMATION",
                )
            } else {
                waitForOwnedPauseAcknowledgement(request, "PLAYER_STILL_PLAYING")
            }
            return
        }
        if (event.playbackState == PlaybackStatus.PAUSED && request.playCommandCount == 0) {
            attemptRestore(request)
        } else {
            completeRestore(request, PlaybackRestoreEventType.FAILED, "PLAYER_REMAINED_PAUSED")
        }
    }

    private fun restoreCandidateOrReject(
        request: ActiveResumeRequest,
        stage: String,
    ): RestoreSessionCandidate? {
        val candidate = resolveRestoreSession(request.token) ?: run {
            rejectRestore(request, "${stage}_LEASE_SESSION_INVALID")
            return null
        }
        when (candidate.match) {
            PlaybackRestoreTrackMatch.MATCH,
            PlaybackRestoreTrackMatch.METADATA_ENRICHMENT,
            -> Unit
            PlaybackRestoreTrackMatch.MISMATCH -> {
                rejectRestore(request, "${stage}_TRACK_CHANGED")
                return null
            }
            PlaybackRestoreTrackMatch.INSUFFICIENT -> {
                rejectRestore(request, "${stage}_TRACK_IDENTITY_INCOMPLETE")
                return null
            }
        }
        when (candidate.event.playbackState) {
            PlaybackStatus.STOPPED,
            PlaybackStatus.NONE,
            PlaybackStatus.BUFFERING,
            -> {
                rejectRestore(request, "${stage}_STATE_${candidate.event.playbackState}")
                return null
            }
            PlaybackStatus.PLAYING,
            PlaybackStatus.PAUSED,
            -> Unit
        }
        return candidate
    }

    private fun isOwnedPauseAcknowledgement(
        event: PlaybackEvent,
        token: PlaybackPauseToken,
    ): Boolean {
        if (event.playbackState != PlaybackStatus.PAUSED) return false
        val stateUpdatedAtNanos = event.playbackStateUpdateElapsedMs
            ?.takeIf { it > 0L }
            ?.times(NANOS_PER_MILLISECOND)
            ?: return false
        return stateUpdatedAtNanos >= token.pauseRequestedAtElapsedNanos
    }

    /**
     * Command provenance comes from TrackTalk's local callback ordering plus
     * the exact controller/session/track checks performed by the caller.
     * Provider timestamps may be reused and are only auxiliary evidence: an
     * explicit regression behind a known same-track PLAYING callback is stale,
     * while a missing baseline or timestamp cannot veto an ordered callback.
     */
    private fun ownedPauseCallbackRejectionReason(
        token: PlaybackPauseToken,
        stateUpdatedAtElapsedNanos: Long?,
        callbackObservedAtElapsedNanos: Long,
        callbackEventSequenceNumber: Long,
    ): String? = when {
        callbackObservedAtElapsedNanos <= token.pauseRequestedAtElapsedNanos ->
            "CALLBACK_NOT_AFTER_PAUSE_COMMAND"
        callbackEventSequenceNumber <= token.eventSequenceNumberAtPauseCommand ->
            "PRE_COMMAND_LOCAL_EVENT"
        stateUpdatedAtElapsedNanos != null &&
            token.playbackStateUpdatedAtPauseElapsedNanos != null &&
            stateUpdatedAtElapsedNanos < token.playbackStateUpdatedAtPauseElapsedNanos ->
            "SOURCE_TIMESTAMP_BEFORE_PLAYING_BASELINE"
        else -> null
    }

    private fun pauseCallbackAcknowledgementEvidence(
        token: PlaybackPauseToken,
        stateUpdatedAtElapsedNanos: Long?,
    ): String = when {
        stateUpdatedAtElapsedNanos != null &&
            stateUpdatedAtElapsedNanos >= token.pauseRequestedAtElapsedNanos ->
            "STATE_TIMESTAMP_AFTER_COMMAND"
        stateUpdatedAtElapsedNanos != null && token.playbackStateUpdatedAtPauseElapsedNanos != null ->
            "ORDERED_CALLBACK_FROM_PLAYING_BASELINE"
        else -> "LOCAL_POST_COMMAND_CALLBACK"
    }

    private fun rejectRestore(request: ActiveResumeRequest, reason: String) {
        if (!isActive(request)) return
        TrackTalkDebugLog.event(
            "PLAYBACK_RESTORE_REJECTED",
            "announcementGeneration" to request.announcementCycleId,
            "controllerGeneration" to request.token.controllerGeneration,
            "reason" to reason,
        )
        completeRestore(request, PlaybackRestoreEventType.CANCELLED, reason)
    }

    private fun isActive(request: ActiveResumeRequest): Boolean =
        started && activeResumeRequest === request && request.requestId == resumeRequestId && !request.completed

    private fun cancelActiveResume(reason: String) {
        activeResumeRequest?.let { request ->
            completeRestore(request, PlaybackRestoreEventType.CANCELLED, reason)
        }
    }

    private fun completeRestore(
        request: ActiveResumeRequest,
        type: PlaybackRestoreEventType,
        reason: String,
    ) {
        if (activeResumeRequest !== request || request.completed) return
        request.completed = true
        request.checkGeneration += 1
        activeResumeRequest = null
        emitRestoreEvent(request, type, reason)
    }

    private fun emitRestoreEvent(
        request: ActiveResumeRequest,
        type: PlaybackRestoreEventType,
        reason: String,
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
        attempt: Int = request.playCommandCount,
        sessionKey: String? = null,
        mediaId: String? = request.token.mediaId,
    ) {
        val event = PlaybackRestoreEvent(
            announcementCycleId = request.announcementCycleId,
            requestId = request.requestId,
            type = type,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            attempt = attempt,
            sessionKey = sessionKey,
            sourcePackageName = request.token.sourcePackageName,
            mediaId = mediaId,
            reason = reason,
        )
        TrackTalkDebugLog.event(
            "PLAYBACK_RESTORE_EVENT",
            "announcementCycleId" to event.announcementCycleId,
            "restoreRequestId" to event.requestId,
            "stage" to event.type,
            "elapsedRealtimeNanos" to event.elapsedRealtimeNanos,
            "attempt" to event.attempt,
            "sessionKey" to event.sessionKey,
            "source" to event.sourcePackageName,
            "mediaId" to event.mediaId,
            "reason" to event.reason,
        )
        try {
            request.callback(event)
        } catch (failure: RuntimeException) {
            TrackTalkDebugLog.event(
                "PLAYBACK_RESTORE_CALLBACK_FAILED",
                "announcementCycleId" to event.announcementCycleId,
                "restoreRequestId" to event.requestId,
                "failure" to failure.javaClass.simpleName,
            )
        }
    }

    private fun <T> runOnMonitorThread(block: () -> T): T {
        if (Looper.myLooper() == handler.looper) return block()
        val task = FutureTask<T> { block() }
        check(handler.post(task)) { "MediaSession monitor looper is unavailable" }
        return task.get(MONITOR_THREAD_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun resolveAppName(packageName: String): String = try {
        appContext.packageManager.getApplicationLabel(
            appContext.packageManager.getApplicationInfo(packageName, 0),
        ).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    } catch (_: SecurityException) {
        packageName
    }

    private fun sameLogicalTrack(previous: PlaybackEvent, current: PlaybackEvent): Boolean {
        if (previous.sourcePackageName != current.sourcePackageName) return false
        val previousMediaId = previous.mediaId.normalizedOrNull()
        val currentMediaId = current.mediaId.normalizedOrNull()
        if (previousMediaId != null && currentMediaId != null) return previousMediaId == currentMediaId
        val previousTitle = previous.title.normalizedOrNull()
        val currentTitle = current.title.normalizedOrNull()
        if (previousTitle == null || currentTitle == null || previousTitle != currentTitle) return false
        return compatibleTrackMetadata(previous.artist, current.artist) &&
            compatibleTrackMetadata(previous.album, current.album)
    }

    private fun String?.normalizedOrNull(): String? = this
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.replace(Regex("\\s+"), " ")
        ?.takeIf { it.isNotEmpty() }

    private fun compatibleTrackMetadata(previous: String?, current: String?): Boolean =
        previous.normalizedOrNull() == null ||
            current.normalizedOrNull() == null ||
            previous.normalizedOrNull() == current.normalizedOrNull()

    private data class TrackedSession(
        val sessionKey: String,
        val generation: Long,
        var controller: MediaController,
        val callback: MediaController.Callback,
        var lastMetadataChangedAt: Long,
        var lastPlaybackStateChangedAt: Long,
        var lastObservedAt: Long,
        var lastPlayingCallbackStateUpdatedAtElapsedMs: Long? = null,
        var lastPlayingCallbackCoreFingerprint: String? = null,
    )

    private data class ActiveResumeRequest(
        val requestId: Long,
        val announcementCycleId: Long?,
        val token: PlaybackPauseToken,
        val callback: (PlaybackRestoreEvent) -> Unit,
        var ownedPauseAcknowledged: Boolean,
        @Volatile var playCommandCount: Int = 0,
        @Volatile var commandInFlight: Boolean = false,
        @Volatile var lastPlayRequestedAtElapsedNanos: Long = 0L,
        var playingObserved: Boolean = false,
        var waitingForOwnedPause: Boolean = false,
        var checkGeneration: Long = 0L,
        var completed: Boolean = false,
    )

    private data class RestoreSessionCandidate(
        val sessionKey: String,
        val controller: MediaController,
        val event: PlaybackEvent,
        val match: PlaybackRestoreTrackMatch,
    )

    private data class PendingMediaNotificationReconcile(
        val sourcePackageName: String,
        val sessionKey: String,
        val controllerGeneration: Long,
        val monitorLifecycleGeneration: Long,
    )

    private data class PublishedSelection(
        val sessionKey: String,
        val event: PlaybackEvent,
    )

    private companion object {
        const val PLAY_CONFIRMATION_MS = 300L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MONITOR_THREAD_CALL_TIMEOUT_MS = 10_000L
    }
}

data class PlaybackPauseToken(
    val sessionKey: String,
    val fingerprint: String,
    val sourcePackageName: String = "",
    val mediaId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val queueItemId: Long? = null,
    val pauseRequestedAtElapsedNanos: Long = 0L,
    /** Exact provider PLAYING callback timestamp captured before TrackTalk PAUSE. */
    val playbackStateUpdatedAtPauseElapsedNanos: Long? = null,
    /** Monitor callback sequence already published when TrackTalk issued PAUSE. */
    val eventSequenceNumberAtPauseCommand: Long = 0L,
    /** In-memory controller callback generation that accepted TrackTalk's PAUSE. */
    val controllerGeneration: Long = 0L,
    /** In-memory monitor lifecycle that issued TrackTalk's PAUSE. */
    val monitorLifecycleGeneration: Long = 0L,
)

enum class PlaybackRestoreEventType {
    PLAY_REQUESTED,
    PLAYING_CONFIRMED,
    CANCELLED,
    FAILED,
}

data class PlaybackRestoreEvent(
    val announcementCycleId: Long?,
    val requestId: Long,
    val type: PlaybackRestoreEventType,
    val elapsedRealtimeNanos: Long,
    val attempt: Int,
    val sessionKey: String?,
    val sourcePackageName: String,
    val mediaId: String?,
    val reason: String,
)

enum class PlaybackRestoreTrackMatch {
    MATCH,
    METADATA_ENRICHMENT,
    MISMATCH,
    INSUFFICIENT,
}

object PlaybackRestoreTrackMatcher {
    fun matches(event: PlaybackEvent, token: PlaybackPauseToken): Boolean =
        classify(event, token) in setOf(
            PlaybackRestoreTrackMatch.MATCH,
            PlaybackRestoreTrackMatch.METADATA_ENRICHMENT,
        )

    /**
     * Capture queue identity only when the queue's reported active item is
     * consistent with the metadata being paused. YouTube Music can publish a
     * new title/artist one callback before activeQueuePosition advances. A
     * queue ID taken from that mixed snapshot belongs to the previous track
     * and must not later turn normal metadata settlement into TRACK_CHANGED.
     */
    fun queueItemIdForPause(event: PlaybackEvent): Long? {
        val item = event.activeQueuePosition?.let(event.queue::getOrNull) ?: return null
        val queueItemId = item.queueItemId ?: return null
        val eventMediaId = event.mediaId.normalizedRestoreIdentity()
        val itemMediaId = item.mediaId.normalizedRestoreIdentity()
        if (eventMediaId != null && itemMediaId != null) {
            return queueItemId.takeIf { eventMediaId == itemMediaId }
        }

        val eventTitle = event.title.normalizedRestoreIdentity() ?: return null
        val itemTitle = item.title.normalizedRestoreIdentity() ?: return null
        if (eventTitle != itemTitle) return null
        if (!compatibleRestoreText(event.artist, item.artist)) return null
        return queueItemId
    }

    fun classify(event: PlaybackEvent, token: PlaybackPauseToken): PlaybackRestoreTrackMatch {
        if (token.sourcePackageName.isNotBlank() && event.sourcePackageName != token.sourcePackageName) {
            return PlaybackRestoreTrackMatch.MISMATCH
        }

        val tokenMediaId = token.mediaId.normalizedRestoreIdentity()
        val eventMediaId = event.mediaId.normalizedRestoreIdentity()
        if (tokenMediaId != null && eventMediaId != null && tokenMediaId == eventMediaId) {
            if (!compatibleRestoreText(token.title, event.title)) {
                return PlaybackRestoreTrackMatch.MISMATCH
            }
            if (!compatibleRestoreText(token.artist, event.artist)) {
                return PlaybackRestoreTrackMatch.MISMATCH
            }
            return if (
                differentRestoreText(token.album, event.album)
            ) {
                PlaybackRestoreTrackMatch.METADATA_ENRICHMENT
            } else {
                PlaybackRestoreTrackMatch.MATCH
            }
        }
        if (TrackFingerprint.core(event) == token.fingerprint) {
            return if (differentRestoreText(token.album, event.album)) {
                PlaybackRestoreTrackMatch.METADATA_ENRICHMENT
            } else {
                PlaybackRestoreTrackMatch.MATCH
            }
        }

        // A queue item ID is provider projection/occurrence evidence, not core
        // track identity. YouTube Music can briefly point activeQueueItemId at
        // the previous queue row while stable title/artist still identify the
        // paused track. Let an exact core match win, then consult queue identity
        // only when the provider's current queue row is coherent with its
        // current metadata.
        val tokenQueueItemId = token.queueItemId
        val eventQueueItemId = queueItemIdForPause(event)
        if (tokenQueueItemId != null && eventQueueItemId != null && tokenQueueItemId != eventQueueItemId) {
            return PlaybackRestoreTrackMatch.MISMATCH
        }

        // Accept tokens created by older in-process callers/tests while the
        // production pause token now records only core identity.
        if (TrackFingerprint.announcement(event) == token.fingerprint) {
            return PlaybackRestoreTrackMatch.MATCH
        }

        // Providers can clear, replace, or canonicalize MEDIA_ID while the
        // visible logical track stays unchanged. Queue identity (when present)
        // and title/artist are safer than treating every ID enrichment as a
        // user-selected replacement track.
        if (event.title.normalizedRestoreIdentity() == null) {
            return PlaybackRestoreTrackMatch.INSUFFICIENT
        }
        if (!sameRestoreText(token.title, event.title)) return PlaybackRestoreTrackMatch.MISMATCH
        if (!compatibleRestoreText(token.artist, event.artist)) return PlaybackRestoreTrackMatch.MISMATCH
        if (token.trackNumber != null && event.trackNumber != null && token.trackNumber != event.trackNumber) {
            return PlaybackRestoreTrackMatch.MISMATCH
        }
        if (token.discNumber != null && event.discNumber != null && token.discNumber != event.discNumber) {
            return PlaybackRestoreTrackMatch.MISMATCH
        }
        return if (differentRestoreText(token.album, event.album)) {
            PlaybackRestoreTrackMatch.METADATA_ENRICHMENT
        } else {
            PlaybackRestoreTrackMatch.MATCH
        }
    }

    private fun sameRestoreText(expected: String?, actual: String?): Boolean =
        expected.normalizedRestoreIdentity()?.let { it == actual.normalizedRestoreIdentity() } == true

    private fun compatibleRestoreText(expected: String?, actual: String?): Boolean =
        expected.normalizedRestoreIdentity() == null ||
            actual.normalizedRestoreIdentity() == null ||
            expected.normalizedRestoreIdentity() == actual.normalizedRestoreIdentity()

    private fun differentRestoreText(expected: String?, actual: String?): Boolean =
        expected.normalizedRestoreIdentity() != actual.normalizedRestoreIdentity()

    private fun String?.normalizedRestoreIdentity(): String? = this
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.replace(Regex("\\s+"), " ")
        ?.takeIf { it.isNotEmpty() }
}
