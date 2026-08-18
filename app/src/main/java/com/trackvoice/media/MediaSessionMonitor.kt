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
    private var lastPublishedSelection: PublishedSelection? = null
    private var eventSequenceNumber = 0L

    val activeSessionCount: Int get() = sessions.size

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
        if (started) return
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

    fun stop() {
        resumeRequestId += 1
        if (!started) return
        started = false
        runCatching { manager.removeOnActiveSessionsChangedListener(activeSessionsListener) }
        sessions.values.forEach { tracked ->
            runCatching { tracked.controller.unregisterCallback(tracked.callback) }
        }
        sessions.clear()
        selectedSessionKey = null
    }

    fun refresh() {
        if (!started) return
        runCatching {
            updateControllers(manager.getActiveSessions(listenerComponent).orEmpty())
            publish(MediaEventType.ACTIVE_SESSIONS)
        }
    }

    fun pauseSelectedIfPlaying(
        expectedEvent: PlaybackEvent? = null,
        expectedSessionKey: String? = null,
        onPauseRequested: ((elapsedRealtimeNanos: Long) -> Unit)? = null,
    ): PlaybackPauseToken? {
        // A new announcement owns the pause/resume lifecycle. Do not let a
        // delayed retry from a previous announcement play the track again.
        resumeRequestId += 1
        val tracked = selectedTrackedSession() ?: return null
        val event = if (expectedEvent != null) {
            if (!matchesExpectedPlayingTrack(tracked.controller, expectedSessionKey, expectedEvent)) return null
            expectedEvent
        } else {
            runCatching { mapper.map(tracked.controller) }.getOrNull() ?: return null
        }
        if (!event.isPlaying || !event.hasTitle) return null
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
        return if (paused) {
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
            )
        } else {
            null
        }
    }

    fun resumePlayback(token: PlaybackPauseToken) {
        val requestId = ++resumeRequestId
        // Media apps commonly publish a short-lived metadata snapshot while
        // handling pause/play. Wait for the same track to become identifiable
        // again instead of abandoning auto-resume on the first mismatch.
        retryResume(token, requestId, delayMs = 0L, attemptsRemaining = 6)
    }

    fun toggleSelectedPlayback(): Boolean? {
        // A manual tap is an explicit user decision and cancels any automatic
        // resume that may still be queued for an earlier announcement.
        resumeRequestId += 1
        val tracked = selectedTrackedSession() ?: return null
        val event = runCatching { mapper.map(tracked.controller) }.getOrNull() ?: return null
        return when {
            event.isPlaying -> runCatching {
                tracked.controller.transportControls.pause()
                false
            }.getOrNull()

            event.hasTitle -> runCatching {
                tracked.controller.transportControls.play()
                retryResume(
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
                    ),
                    resumeRequestId,
                    180L,
                    attemptsRemaining = 5,
                )
                true
            }.getOrNull()

            else -> null
        }
    }

    fun isSelectedPlaybackPlaying(): Boolean? = selectedTrackedSession()?.let {
        runCatching { mapper.map(it.controller).isPlaying }.getOrNull()
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

    private fun trackedSession(token: PlaybackPauseToken): TrackedSession? =
        sessions[token.sessionKey]
            ?: sessions.values.firstOrNull { it.controller.packageName == token.sourcePackageName }

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

    private fun retryResume(
        token: PlaybackPauseToken,
        requestId: Long,
        delayMs: Long,
        attemptsRemaining: Int,
    ) {
        handler.postDelayed({
            if (!started || requestId != resumeRequestId) return@postDelayed
            val tracked = trackedSession(token)
            val event = tracked?.let { runCatching { mapper.map(it.controller) }.getOrNull() }
            if (tracked != null && event != null && matchesPausedTrack(event, token)) {
                // Sending PLAY even when the state is still PLAYING is safe and
                // covers players that publish the delayed PAUSED callback after
                // this request. Keep a few retries for that asynchronous race.
                if (!event.isPlaying || delayMs == 0L) {
                    runCatching { tracked.controller.transportControls.play() }
                }
            }
            if (attemptsRemaining > 1) {
                retryResume(
                    token = token,
                    requestId = requestId,
                    delayMs = if (delayMs == 0L) 180L else (delayMs * 2).coerceAtMost(1_000L),
                    attemptsRemaining = attemptsRemaining - 1,
                )
            }
        }, delayMs)
    }

    private fun matchesPausedTrack(event: PlaybackEvent, token: PlaybackPauseToken): Boolean {
        if (token.sourcePackageName.isNotBlank() && event.sourcePackageName != token.sourcePackageName) {
            return false
        }

        val tokenMediaId = token.mediaId?.trim()?.takeIf { it.isNotEmpty() }
        val eventMediaId = event.mediaId?.trim()?.takeIf { it.isNotEmpty() }
        // If both IDs are present, a different ID is a different track. This
        // prevents a delayed retry from restarting a song the user selected.
        if (tokenMediaId != null && eventMediaId != null) return tokenMediaId == eventMediaId
        if (TrackFingerprint.announcement(event) == token.fingerprint) return true

        // Some players temporarily clear the media ID while rebuilding their
        // metadata. Fall back to the captured track fields only in that gap.
        if (!sameText(token.title, event.title)) return false
        if (!compatibleText(token.artist, event.artist)) return false
        if (!compatibleText(token.album, event.album)) return false
        if (token.trackNumber != null && event.trackNumber != null && token.trackNumber != event.trackNumber) {
            return false
        }
        if (token.discNumber != null && event.discNumber != null && token.discNumber != event.discNumber) {
            return false
        }
        return true
    }

    private fun sameText(expected: String?, actual: String?): Boolean =
        !expected.isNullOrBlank() && !actual.isNullOrBlank() && normalize(expected) == normalize(actual)

    private fun compatibleText(expected: String?, actual: String?): Boolean =
        expected.isNullOrBlank() || actual.isNullOrBlank() || normalize(expected) == normalize(actual)

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

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

    private data class PublishedSelection(
        val sessionKey: String,
        val event: PlaybackEvent,
    )
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
)
