package com.trackvoice.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.trackvoice.diagnostics.TrackTalkDebugLog
import com.trackvoice.service.TrackVoiceNotificationListenerService
import java.util.Locale
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class MediaSessionMonitor(
    context: Context,
    private val onUpdate: (MediaMonitorUpdate) -> Unit,
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(
        appContext,
        TrackVoiceNotificationListenerService::class.java,
    )
    private val handler = Handler(Looper.getMainLooper())
    private val mapper = TrackMetadataMapper(::resolveAppName)
    private val sessions = linkedMapOf<String, TrackedSession>()
    private var started = false
    private var selectedSessionKey: String? = null
    private var resumeRequestId = 0L
    private var activeResumeRequest: ActiveResumeRequest? = null
    private var retainedPausedController: RetainedPausedController? = null
    private var lastPublishedSelection: PublishedSelection? = null
    private var eventSequenceNumber = 0L

    val activeSessionCount: Int get() = runOnMonitorThread { sessions.size }

    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        if (!started) return@OnActiveSessionsChangedListener
        runCatching {
            updateControllers(controllers.orEmpty())
            TrackTalkDebugLog.event(
                "ACTIVE_SESSIONS_CHANGED",
                "timestamp" to System.currentTimeMillis(),
                "controllerCount" to controllers.orEmpty().size,
                "thread" to Thread.currentThread().name,
            )
            publish(MediaEventType.ACTIVE_SESSIONS)
        }
    }

    fun start() {
        runOnMonitorThread {
            if (started) return@runOnMonitorThread
            started = true
            runCatching {
                manager.addOnActiveSessionsChangedListener(
                    activeSessionsListener,
                    listenerComponent,
                    handler,
                )
                updateControllers(manager.getActiveSessions(listenerComponent).orEmpty())
                publish(MediaEventType.INITIAL)
            }.onFailure {
                started = false
                sessions.clear()
                selectedSessionKey = null
            }
        }
    }

    fun stop() {
        runOnMonitorThread {
            cancelActiveResume("MONITOR_STOPPED")
            retainedPausedController = null
            resumeRequestId += 1
            if (!started) return@runOnMonitorThread
            started = false
            runCatching { manager.removeOnActiveSessionsChangedListener(activeSessionsListener) }
            sessions.values.forEach { tracked ->
                runCatching { tracked.controller.unregisterCallback(tracked.callback) }
            }
            sessions.clear()
            selectedSessionKey = null
        }
    }

    fun refresh() {
        runOnMonitorThread {
            if (!started) return@runOnMonitorThread
            runCatching {
                updateControllers(manager.getActiveSessions(listenerComponent).orEmpty())
                publish(MediaEventType.ACTIVE_SESSIONS)
            }
        }
    }

    fun pauseSelectedIfPlaying(
        expectedEvent: PlaybackEvent? = null,
        expectedSessionKey: String? = null,
        onPauseRequested: ((elapsedRealtimeNanos: Long) -> Unit)? = null,
    ): PlaybackPauseToken? = runOnMonitorThread {
        // A new announcement owns the pause/resume lifecycle. Do not let a
        // delayed retry from a previous announcement play the track again.
        cancelActiveResume("NEW_TRACKTALK_PAUSE")
        retainedPausedController = null
        resumeRequestId += 1
        val tracked = selectedTrackedSession() ?: return@runOnMonitorThread null
        val event = if (expectedEvent != null) {
            if (!matchesExpectedPlayingTrack(tracked.controller, expectedSessionKey, expectedEvent)) {
                return@runOnMonitorThread null
            }
            expectedEvent
        } else {
            runCatching { mapper.map(tracked.controller) }.getOrNull()
                ?: return@runOnMonitorThread null
        }
        if (!event.isPlaying || !event.hasTitle) return@runOnMonitorThread null
        val pauseRequestedAtNanos = SystemClock.elapsedRealtimeNanos()
        // Issue the media command before debug logging. The timestamp still
        // marks the request boundary, while Logcat I/O can no longer delay the
        // command that protects the opening of the confirmed track.
        val paused = runCatching {
            tracked.controller.transportControls.pause()
            true
        }.getOrDefault(false)
        if (paused) {
            // Diagnostics must never turn a successfully issued PAUSE into a
            // missing resume token if a test/listener callback happens to fail.
            runCatching { onPauseRequested?.invoke(pauseRequestedAtNanos) }
            TrackTalkDebugLog.event(
                "PLAYBACK_PAUSE_REQUESTED",
                "elapsedRealtimeNanos" to pauseRequestedAtNanos,
                "source" to event.sourcePackageName,
                "mediaId" to event.mediaId,
            )
        }
        if (paused) {
            PlaybackPauseToken(
                sessionKey = sessionKey(tracked.controller),
                fingerprint = TrackFingerprint.announcement(event),
                sourcePackageName = event.sourcePackageName,
                mediaId = event.mediaId,
                title = event.title,
                artist = event.artist,
                album = event.album,
                trackNumber = event.trackNumber,
                discNumber = event.discNumber,
                queueItemId = PlaybackRestoreTrackMatcher.queueItemIdForPause(event),
                pauseRequestedAtElapsedNanos = pauseRequestedAtNanos,
            ).also { token ->
                // Some providers temporarily remove an otherwise valid paused
                // session from getActiveSessions(). Keep the exact controller
                // that accepted TrackTalk's PAUSE so restoration does not
                // depend on the provider publishing a fresh session first.
                retainedPausedController = RetainedPausedController(
                    sessionKey = token.sessionKey,
                    sourcePackageName = token.sourcePackageName,
                    controller = tracked.controller,
                )
            }
        } else {
            null
        }
    }

    fun resumePlayback(
        token: PlaybackPauseToken,
        announcementCycleId: Long? = null,
        onEvent: (PlaybackRestoreEvent) -> Unit = {},
    ) {
        runOnMonitorThread {
            cancelActiveResume("REPLACED_BY_NEW_RESTORE")
            val requestId = ++resumeRequestId
            val restoreRequestedAtNanos = SystemClock.elapsedRealtimeNanos()
            val controller = resolveRestoreSession(token)?.controller
            val initialState = controller?.playbackState?.state
            val request = ActiveResumeRequest(
                requestId = requestId,
                announcementCycleId = announcementCycleId,
                token = token,
                callback = onEvent,
                initialStateWasPlaying = initialState == PlaybackState.STATE_PLAYING,
                sessionRecoveryDeadlineElapsedNanos = restoreRequestedAtNanos +
                    SESSION_RECOVERY_TIMEOUT_MS * NANOS_PER_MILLISECOND,
            )
            activeResumeRequest = request

            // The first PLAY must be issued before this method returns. A
            // NotificationListener/controller teardown can stop the monitor on the
            // same main-loop turn; the former zero-delay Handler post was cancelled
            // before it ever reached TransportControls.play().
            attemptRestore(request)
        }
    }

    fun cancelPendingResume(reason: String) {
        runOnMonitorThread {
            cancelActiveResume(reason)
            retainedPausedController = null
            resumeRequestId += 1
        }
    }

    fun toggleSelectedPlayback(): Boolean? = runOnMonitorThread {
        // A manual tap is an explicit user decision and cancels any automatic
        // resume that may still be queued for an earlier announcement.
        cancelActiveResume("USER_TOGGLE")
        retainedPausedController = null
        resumeRequestId += 1
        val tracked = selectedTrackedSession() ?: return@runOnMonitorThread null
        val event = runCatching { mapper.map(tracked.controller) }.getOrNull()
            ?: return@runOnMonitorThread null
        when {
            event.isPlaying -> runCatching {
                tracked.controller.transportControls.pause()
                false
            }.getOrNull()

            event.hasTitle -> runCatching {
                resumePlayback(
                    PlaybackPauseToken(
                        sessionKey = sessionKey(tracked.controller),
                        fingerprint = TrackFingerprint.announcement(event),
                        sourcePackageName = event.sourcePackageName,
                        mediaId = event.mediaId,
                        title = event.title,
                        artist = event.artist,
                        album = event.album,
                        trackNumber = event.trackNumber,
                        discNumber = event.discNumber,
                        queueItemId = PlaybackRestoreTrackMatcher.queueItemIdForPause(event),
                        pauseRequestedAtElapsedNanos = SystemClock.elapsedRealtimeNanos(),
                    ),
                )
                true
            }.getOrNull()

            else -> null
        }
    }

    fun isSelectedPlaybackPlaying(): Boolean? = runOnMonitorThread {
        selectedTrackedSession()?.let {
            runCatching { mapper.map(it.controller).isPlaying }.getOrNull()
        }
    }

    private fun updateControllers(controllers: List<MediaController>) {
        val nextKeys = controllers.map { sessionKey(it) }.toSet()
        sessions.keys.toList().filterNot(nextKeys::contains).forEach { key ->
            sessions.remove(key)?.let { tracked ->
                TrackTalkDebugLog.event(
                    "CONTROLLER_UNREGISTERED",
                    "sessionKey" to key,
                    "package" to tracked.controller.packageName,
                    "thread" to Thread.currentThread().name,
                )
                runCatching { tracked.controller.unregisterCallback(tracked.callback) }
            }
        }

        controllers.forEach { controller ->
            val key = sessionKey(controller)
            val existing = sessions[key]
            if (existing == null) {
                val sessionCallback = callbackFor(key)
                runCatching { controller.registerCallback(sessionCallback, handler) }
                    .onFailure { return@forEach }
                val now = System.currentTimeMillis()
                sessions[key] = TrackedSession(controller, sessionCallback, now, now, now)
                TrackTalkDebugLog.event(
                    "CONTROLLER_REGISTERED",
                    "sessionKey" to key,
                    "package" to controller.packageName,
                    "controllerToken" to controller.sessionToken.hashCode(),
                    "thread" to Thread.currentThread().name,
                )
            } else {
                if (existing.controller !== controller) {
                    TrackTalkDebugLog.event(
                        "ACTIVE_SESSION_REFRESH",
                        "sessionKey" to key,
                        "package" to controller.packageName,
                        "sameSessionToken" to (existing.controller.sessionToken == controller.sessionToken),
                        "controllerInstanceChanged" to true,
                    )
                    TrackTalkDebugLog.event(
                        "CONTROLLER_RESELECTED",
                        "sessionKey" to key,
                        "package" to controller.packageName,
                        "sameSessionToken" to (existing.controller.sessionToken == controller.sessionToken),
                        "thread" to Thread.currentThread().name,
                    )
                    runCatching { existing.controller.unregisterCallback(existing.callback) }
                    val sessionCallback = callbackFor(key)
                    runCatching { controller.registerCallback(sessionCallback, handler) }
                        .onFailure { return@forEach }
                    existing.callback = sessionCallback
                }
                existing.controller = controller
            }
        }
    }

    private fun callbackFor(sessionKey: String): MediaController.Callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            publish(MediaEventType.METADATA, sessionKey)
        }

        override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
            observeActiveResumeState(sessionKey, state)
            publish(MediaEventType.PLAYBACK_STATE, sessionKey)
        }

        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) {
            publish(MediaEventType.QUEUE, sessionKey)
        }

        override fun onSessionDestroyed() {
            if (!started) return
            sessions.remove(sessionKey)?.let { tracked ->
                TrackTalkDebugLog.event(
                    "CONTROLLER_UNREGISTERED",
                    "sessionKey" to sessionKey,
                    "package" to tracked.controller.packageName,
                    "reason" to "SESSION_DESTROYED",
                    "thread" to Thread.currentThread().name,
                )
                runCatching { tracked.controller.unregisterCallback(tracked.callback) }
                if (selectedSessionKey == sessionKey) selectedSessionKey = null
                publish(MediaEventType.ACTIVE_SESSIONS)
            }
        }
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
            runCatching { manager.getMediaKeyEventSession() }.getOrNull()
        } else null
        val snapshots = sessions.map { (key, tracked) ->
            runCatching {
                SessionSnapshot(
                    sessionKey = key,
                    event = mapper.map(tracked.controller, now),
                    isMediaKeySession = mediaKeyToken != null && mediaKeyToken == tracked.controller.sessionToken,
                    lastMetadataChangedAt = tracked.lastMetadataChangedAt,
                    lastPlaybackStateChangedAt = tracked.lastPlaybackStateChangedAt,
                    lastObservedAt = tracked.lastObservedAt,
                )
            }.getOrNull()
        }.mapNotNull { it }
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
        runCatching {
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
                ),
            )
        }
    }

    private fun sessionKey(controller: MediaController): String =
        "${controller.packageName}:${controller.sessionToken.hashCode()}"

    private fun selectedTrackedSession(): TrackedSession? =
        selectedSessionKey?.let(sessions::get)
            ?: sessions.values.maxByOrNull { it.lastObservedAt }

    private fun resolveRestoreSession(token: PlaybackPauseToken): RestoreSessionCandidate? {
        val exact = sessions[token.sessionKey]
        val ordered = buildList {
            exact?.let(::add)
            sessions.values
                .asSequence()
                .filter { it !== exact }
                .filter { it.controller.packageName == token.sourcePackageName }
                .sortedByDescending { it.lastObservedAt }
                .forEach(::add)
        }
        val candidates = ordered.mapNotNull { tracked ->
            val event = runCatching { mapper.map(tracked.controller) }.getOrNull() ?: return@mapNotNull null
            RestoreSessionCandidate(
                controller = tracked.controller,
                event = event,
                match = PlaybackRestoreTrackMatcher.classify(event, token),
                retained = false,
            )
        }
        val activeCandidate = candidates.firstOrNull { it.match == PlaybackRestoreTrackMatch.MATCH }
            ?: candidates.firstOrNull { it.match == PlaybackRestoreTrackMatch.INSUFFICIENT }
            ?: candidates.firstOrNull()
        if (activeCandidate != null) return activeCandidate

        val retained = retainedPausedController?.takeIf {
            it.sessionKey == token.sessionKey && it.sourcePackageName == token.sourcePackageName
        } ?: return null
        val retainedEvent = runCatching { mapper.map(retained.controller) }.getOrNull() ?: return null
        return RestoreSessionCandidate(
            controller = retained.controller,
            event = retainedEvent,
            match = PlaybackRestoreTrackMatcher.classify(retainedEvent, token),
            retained = true,
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
        val candidate = resolveRestoreSession(request.token)
        if (candidate == null) {
            waitForRestorableSession(request, "SESSION_UNAVAILABLE")
            return
        }
        when (candidate.match) {
            PlaybackRestoreTrackMatch.INSUFFICIENT -> {
                waitForRestorableSession(request, "SESSION_IDENTITY_INCOMPLETE")
                return
            }

            PlaybackRestoreTrackMatch.MISMATCH -> {
                completeRestore(request, PlaybackRestoreEventType.CANCELLED, "TRACK_CHANGED")
                return
            }

            PlaybackRestoreTrackMatch.MATCH -> Unit
        }
        request.waitingForSession = false
        request.lastSessionWaitReason = null

        val requestedAtNanos = SystemClock.elapsedRealtimeNanos()
        val attempt = synchronized(request) {
            if (request.commandInFlight || request.playCommandCount >= MAX_PLAY_COMMANDS) return
            if (request.playCommandCount > 0 && !request.retryAllowed) return
            request.commandInFlight = true
            request.retryAllowed = false
            request.playCommandCount += 1
            request.lastPlayRequestedAtElapsedNanos = requestedAtNanos
            request.playCommandCount
        }
        val retry = attempt > 1
        val issued = try {
            runCatching {
                candidate.controller.transportControls.play()
                true
            }.getOrDefault(false)
        } finally {
            request.commandInFlight = false
        }
        emitRestoreEvent(
            request = request,
            type = if (retry) {
                PlaybackRestoreEventType.PLAY_RETRY_REQUESTED
            } else {
                PlaybackRestoreEventType.PLAY_REQUESTED
            },
            reason = when {
                !issued -> "COMMAND_FAILED"
                candidate.retained -> "COMMAND_ISSUED_RETAINED_CONTROLLER"
                else -> "COMMAND_ISSUED"
            },
            elapsedRealtimeNanos = requestedAtNanos,
            attempt = attempt,
            sessionKey = sessionKey(candidate.controller),
            mediaId = candidate.event.mediaId,
        )
        scheduleRestoreCheck(
            request,
            if (request.initialStateWasPlaying && !retry) LATE_PAUSE_CONFIRMATION_MS else PLAY_CONFIRMATION_MS,
        )
    }

    private fun observeActiveResume() {
        val request = activeResumeRequest ?: return
        if (!isActive(request)) return
        val candidate = resolveRestoreSession(request.token) ?: run {
            waitForRestorableSession(request, "SESSION_UNAVAILABLE_CALLBACK")
            return
        }
        when (candidate.match) {
            PlaybackRestoreTrackMatch.INSUFFICIENT -> {
                waitForRestorableSession(request, "SESSION_IDENTITY_INCOMPLETE_CALLBACK")
                return
            }

            PlaybackRestoreTrackMatch.MISMATCH -> {
                completeRestore(request, PlaybackRestoreEventType.CANCELLED, "TRACK_CHANGED")
                return
            }

            PlaybackRestoreTrackMatch.MATCH -> Unit
        }
        val event = candidate.event
        if (event.isPlaying) {
            request.playingObserved = true
            // When resume starts before the media app publishes TrackTalk's
            // delayed PAUSED acknowledgement, one early PLAYING callback is not
            // final. The bounded confirmation handles that race. Otherwise a
            // PLAYING callback is the authoritative acknowledgement.
            if (!request.initialStateWasPlaying || request.playCommandCount > 1) {
                completeRestore(request, PlaybackRestoreEventType.PLAYING_CONFIRMED, "CALLBACK")
            }
        } else if (request.waitingForSession && request.playCommandCount < MAX_PLAY_COMMANDS) {
            // YouTube Music and other providers can temporarily destroy their
            // MediaSession while TrackTalk owns a pause. The active-session or
            // metadata callback is the fastest safe recovery signal: issue the
            // first PLAY (or sole retry) only after the same logical track is
            // identifiable again.
            attemptRestore(request)
        } else if (event.playbackState == PlaybackStatus.PAUSED) {
            request.pausedObservedAfterRequest = true
        }
    }

    /**
     * Preserve the ordered state carried by MediaController's callback. If we
     * only remap controller.playbackState later, a quick user PAUSE can replace
     * the preceding PLAYING value before TrackTalk observes it, causing the
     * bounded retry to override that newer user intent.
     */
    private fun observeActiveResumeState(
        callbackSessionKey: String,
        state: PlaybackState?,
    ) {
        val request = activeResumeRequest ?: return
        if (!isActive(request) || state == null) return
        val tracked = sessions[callbackSessionKey] ?: return
        if (
            callbackSessionKey != request.token.sessionKey &&
            tracked.controller.packageName != request.token.sourcePackageName
        ) {
            return
        }
        val event = runCatching { mapper.map(tracked.controller) }.getOrNull() ?: return
        when (PlaybackRestoreTrackMatcher.classify(event, request.token)) {
            PlaybackRestoreTrackMatch.INSUFFICIENT -> {
                waitForRestorableSession(request, "SESSION_IDENTITY_INCOMPLETE_STATE_CALLBACK")
                return
            }

            PlaybackRestoreTrackMatch.MISMATCH -> {
                completeRestore(request, PlaybackRestoreEventType.CANCELLED, "TRACK_CHANGED")
                return
            }

            PlaybackRestoreTrackMatch.MATCH -> Unit
        }
        when (state.state) {
            PlaybackState.STATE_PLAYING -> {
                request.playingObserved = true
                if (!request.initialStateWasPlaying || request.playCommandCount > 1) {
                    completeRestore(request, PlaybackRestoreEventType.PLAYING_CONFIRMED, "STATE_CALLBACK")
                }
            }

            PlaybackState.STATE_PAUSED -> {
                val stateUpdatedAtNanos = state.lastPositionUpdateTime
                    .takeIf { it > 0L }
                    ?.times(NANOS_PER_MILLISECOND)
                val pausedStateCreatedAfterPlay = stateUpdatedAtNanos != null &&
                    request.lastPlayRequestedAtElapsedNanos > 0L &&
                    stateUpdatedAtNanos >= request.lastPlayRequestedAtElapsedNanos
                TrackTalkDebugLog.event(
                    "PLAYBACK_RESTORE_PAUSE_CLASSIFICATION",
                    "announcementCycleId" to request.announcementCycleId,
                    "restoreRequestId" to request.requestId,
                    "initialStateWasPlaying" to request.initialStateWasPlaying,
                    "playingObserved" to request.playingObserved,
                    "stateUpdatedAtElapsedNanos" to stateUpdatedAtNanos,
                    "lastPlayRequestedAtElapsedNanos" to request.lastPlayRequestedAtElapsedNanos,
                    "pausedStateCreatedAfterPlay" to pausedStateCreatedAfterPlay,
                )
                // A PAUSED callback can arrive after PLAY even though its
                // PlaybackState was created for TrackTalk's earlier PAUSE.
                // Android exposes that state's elapsedRealtime timestamp, so
                // only a state created at/after PLAY is newer intent. Preserve
                // the legacy delayed-PAUSE race when restore began while the
                // provider still reported PLAYING.
                if (
                    !request.initialStateWasPlaying &&
                    request.playCommandCount > 0 &&
                    request.playingObserved
                ) {
                    completeRestore(
                        request,
                        PlaybackRestoreEventType.CANCELLED,
                        "PAUSED_CALLBACK_AFTER_PLAY_REQUEST",
                    )
                } else if (
                    !request.initialStateWasPlaying &&
                    request.playCommandCount > 0 &&
                    pausedStateCreatedAfterPlay
                ) {
                    schedulePauseIntentConfirmation(request, stateUpdatedAtNanos)
                } else {
                    request.pausedObservedAfterRequest = true
                }
            }
        }
    }

    private fun scheduleRestoreCheck(request: ActiveResumeRequest, delayMs: Long) {
        val checkGeneration = ++request.checkGeneration
        handler.postDelayed({
            if (!isActive(request) || request.checkGeneration != checkGeneration) return@postDelayed
            confirmRestore(request)
        }, delayMs)
    }

    private fun schedulePauseIntentConfirmation(
        request: ActiveResumeRequest,
        stateUpdatedAtElapsedNanos: Long,
    ) {
        val checkGeneration = ++request.pauseIntentCheckGeneration
        request.pendingPauseStateUpdatedAtElapsedNanos = stateUpdatedAtElapsedNanos
        TrackTalkDebugLog.event(
            "PLAYBACK_RESTORE_PAUSE_INTENT",
            "announcementCycleId" to request.announcementCycleId,
            "restoreRequestId" to request.requestId,
            "stage" to "PENDING",
            "stateUpdatedAtElapsedNanos" to stateUpdatedAtElapsedNanos,
            "confirmationDelayMs" to PAUSE_INTENT_CONFIRMATION_MS,
        )
        handler.postDelayed({
            if (
                !isActive(request) ||
                request.pauseIntentCheckGeneration != checkGeneration ||
                request.pendingPauseStateUpdatedAtElapsedNanos != stateUpdatedAtElapsedNanos
            ) {
                return@postDelayed
            }
            val candidate = resolveRestoreSession(request.token) ?: return@postDelayed
            when (candidate.match) {
                PlaybackRestoreTrackMatch.MISMATCH -> {
                    completeRestore(request, PlaybackRestoreEventType.CANCELLED, "TRACK_CHANGED")
                }

                PlaybackRestoreTrackMatch.INSUFFICIENT -> Unit

                PlaybackRestoreTrackMatch.MATCH -> {
                    if (candidate.event.isPlaying) {
                        completeRestore(
                            request,
                            PlaybackRestoreEventType.PLAYING_CONFIRMED,
                            "PAUSE_INTENT_GRACE",
                        )
                        return@postDelayed
                    }
                    val currentState = candidate.controller.playbackState
                    val currentStateUpdatedAtNanos = currentState?.lastPositionUpdateTime
                        ?.takeIf { it > 0L }
                        ?.times(NANOS_PER_MILLISECOND)
                    if (
                        currentState?.state == PlaybackState.STATE_PAUSED &&
                        currentStateUpdatedAtNanos != null &&
                        currentStateUpdatedAtNanos >= stateUpdatedAtElapsedNanos
                    ) {
                        TrackTalkDebugLog.event(
                            "PLAYBACK_RESTORE_PAUSE_INTENT",
                            "announcementCycleId" to request.announcementCycleId,
                            "restoreRequestId" to request.requestId,
                            "stage" to "CONFIRMED",
                            "stateUpdatedAtElapsedNanos" to currentStateUpdatedAtNanos,
                        )
                        completeRestore(
                            request,
                            PlaybackRestoreEventType.CANCELLED,
                            "PAUSED_CALLBACK_AFTER_PLAY_REQUEST",
                        )
                    }
                }
            }
        }, PAUSE_INTENT_CONFIRMATION_MS)
    }

    private fun confirmRestore(request: ActiveResumeRequest) {
        if (!isActive(request)) return
        val candidate = resolveRestoreSession(request.token)
        if (candidate == null) {
            waitForRestorableSession(request, "SESSION_UNAVAILABLE_CONFIRMATION")
            return
        }
        when (candidate.match) {
            PlaybackRestoreTrackMatch.INSUFFICIENT -> {
                waitForRestorableSession(request, "SESSION_IDENTITY_INCOMPLETE_CONFIRMATION")
                return
            }

            PlaybackRestoreTrackMatch.MISMATCH -> {
                completeRestore(request, PlaybackRestoreEventType.CANCELLED, "TRACK_CHANGED")
                return
            }

            PlaybackRestoreTrackMatch.MATCH -> Unit
        }
        request.waitingForSession = false
        request.lastSessionWaitReason = null
        val event = candidate.event
        if (event.isPlaying) {
            completeRestore(request, PlaybackRestoreEventType.PLAYING_CONFIRMED, "CONFIRMATION")
            return
        }

        // If PLAYING was already acknowledged after a normal paused restore,
        // a subsequent PAUSED state is a newer user/player decision. Never let
        // a stale retry override it. The only exception is the known ordering
        // where resume began while the provider still reported PLAYING and its
        // delayed acknowledgement of TrackTalk's own PAUSE arrived afterward.
        val delayedTrackTalkPause = request.initialStateWasPlaying &&
            request.playCommandCount == 1 &&
            request.pausedObservedAfterRequest
        if (request.playingObserved && !delayedTrackTalkPause) {
            completeRestore(request, PlaybackRestoreEventType.CANCELLED, "NEW_PAUSE_AFTER_PLAYING")
            return
        }
        if (request.playCommandCount < MAX_PLAY_COMMANDS) {
            request.retryAllowed = true
            attemptRestore(request)
        } else {
            completeRestore(request, PlaybackRestoreEventType.FAILED, "PLAYER_REMAINED_PAUSED")
        }
    }

    private fun waitForRestorableSession(request: ActiveResumeRequest, reason: String) {
        if (!isActive(request)) return
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val remainingNanos = request.sessionRecoveryDeadlineElapsedNanos - nowNanos
        if (remainingNanos <= 0L) {
            completeRestore(request, PlaybackRestoreEventType.FAILED, "SESSION_RECOVERY_TIMEOUT")
            return
        }
        if (request.waitingForSession) {
            if (request.lastSessionWaitReason != reason) {
                request.lastSessionWaitReason = reason
                emitRestoreEvent(request, PlaybackRestoreEventType.WAITING_FOR_SESSION, reason)
            }
            return
        }
        if (request.playCommandCount > 0) {
            // A command addressed to a session that then disappeared may have
            // been lost. Permit exactly the one bounded retry when the same
            // logical track is identifiable again.
            request.retryAllowed = true
        }
        request.waitingForSession = true
        request.lastSessionWaitReason = reason
        emitRestoreEvent(request, PlaybackRestoreEventType.WAITING_FOR_SESSION, reason)
        val remainingMs = ((remainingNanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND)
            .coerceAtLeast(1L)
        // One deadline only. Active-session and metadata callbacks attempt
        // recovery immediately; no position/state polling is introduced.
        scheduleRestoreCheck(request, remainingMs)
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
        if (retainedPausedController?.sessionKey == request.token.sessionKey) {
            retainedPausedController = null
        }
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
        runCatching { request.callback(event) }
    }

    private fun <T> runOnMonitorThread(block: () -> T): T {
        if (Looper.myLooper() == handler.looper) return block()
        val task = FutureTask<T> { block() }
        check(handler.post(task)) { "MediaSession monitor looper is unavailable" }
        return task.get(MONITOR_THREAD_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun resolveAppName(packageName: String): String = runCatching {
        appContext.packageManager.getApplicationLabel(
            appContext.packageManager.getApplicationInfo(packageName, 0),
        ).toString()
    }.getOrDefault(packageName)

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
        var controller: MediaController,
        var callback: MediaController.Callback,
        var lastMetadataChangedAt: Long,
        var lastPlaybackStateChangedAt: Long,
        var lastObservedAt: Long,
    )

    private data class ActiveResumeRequest(
        val requestId: Long,
        val announcementCycleId: Long?,
        val token: PlaybackPauseToken,
        val callback: (PlaybackRestoreEvent) -> Unit,
        val initialStateWasPlaying: Boolean,
        val sessionRecoveryDeadlineElapsedNanos: Long,
        @Volatile var playCommandCount: Int = 0,
        @Volatile var commandInFlight: Boolean = false,
        @Volatile var retryAllowed: Boolean = false,
        @Volatile var lastPlayRequestedAtElapsedNanos: Long = 0L,
        var pauseIntentCheckGeneration: Long = 0L,
        var pendingPauseStateUpdatedAtElapsedNanos: Long? = null,
        var playingObserved: Boolean = false,
        var pausedObservedAfterRequest: Boolean = false,
        var waitingForSession: Boolean = false,
        var lastSessionWaitReason: String? = null,
        var checkGeneration: Long = 0L,
        var completed: Boolean = false,
    )

    private data class RestoreSessionCandidate(
        val controller: MediaController,
        val event: PlaybackEvent,
        val match: PlaybackRestoreTrackMatch,
        val retained: Boolean,
    )

    private data class RetainedPausedController(
        val sessionKey: String,
        val sourcePackageName: String,
        val controller: MediaController,
    )

    private data class PublishedSelection(
        val sessionKey: String,
        val event: PlaybackEvent,
    )

    private companion object {
        const val MAX_PLAY_COMMANDS = 2
        const val PLAY_CONFIRMATION_MS = 300L
        const val LATE_PAUSE_CONFIRMATION_MS = 500L
        const val PAUSE_INTENT_CONFIRMATION_MS = 150L
        const val SESSION_RECOVERY_TIMEOUT_MS = 6_000L
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
)

enum class PlaybackRestoreEventType {
    PLAY_REQUESTED,
    PLAY_RETRY_REQUESTED,
    WAITING_FOR_SESSION,
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
    MISMATCH,
    INSUFFICIENT,
}

object PlaybackRestoreTrackMatcher {
    fun matches(event: PlaybackEvent, token: PlaybackPauseToken): Boolean =
        classify(event, token) == PlaybackRestoreTrackMatch.MATCH

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

        val tokenQueueItemId = token.queueItemId
        val eventQueueItemId = event.activeQueuePosition?.let(event.queue::getOrNull)?.queueItemId
        if (tokenQueueItemId != null && eventQueueItemId != null && tokenQueueItemId != eventQueueItemId) {
            return PlaybackRestoreTrackMatch.MISMATCH
        }

        val tokenMediaId = token.mediaId.normalizedRestoreIdentity()
        val eventMediaId = event.mediaId.normalizedRestoreIdentity()
        if (tokenMediaId != null && eventMediaId != null && tokenMediaId == eventMediaId) {
            return PlaybackRestoreTrackMatch.MATCH
        }
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
        if (!compatibleRestoreText(token.album, event.album)) return PlaybackRestoreTrackMatch.MISMATCH
        if (token.trackNumber != null && event.trackNumber != null && token.trackNumber != event.trackNumber) {
            return PlaybackRestoreTrackMatch.MISMATCH
        }
        if (token.discNumber != null && event.discNumber != null && token.discNumber != event.discNumber) {
            return PlaybackRestoreTrackMatch.MISMATCH
        }
        return PlaybackRestoreTrackMatch.MATCH
    }

    private fun sameRestoreText(expected: String?, actual: String?): Boolean =
        expected.normalizedRestoreIdentity()?.let { it == actual.normalizedRestoreIdentity() } == true

    private fun compatibleRestoreText(expected: String?, actual: String?): Boolean =
        expected.normalizedRestoreIdentity() == null ||
            actual.normalizedRestoreIdentity() == null ||
            expected.normalizedRestoreIdentity() == actual.normalizedRestoreIdentity()

    private fun String?.normalizedRestoreIdentity(): String? = this
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.replace(Regex("\\s+"), " ")
        ?.takeIf { it.isNotEmpty() }
}
