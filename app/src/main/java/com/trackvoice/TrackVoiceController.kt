package com.trackvoice

import android.content.Context
import com.trackvoice.announcement.AnnouncementPolicy
import com.trackvoice.announcement.AnnouncementFormatter
import com.trackvoice.announcement.AnnouncementTrackMatcher
import com.trackvoice.announcement.AudioFocusManager
import com.trackvoice.announcement.AudioOutputDetector
import com.trackvoice.announcement.AudioRouteResolution
import com.trackvoice.announcement.DuplicateSuppressor
import com.trackvoice.announcement.RepeatCycleDetector
import com.trackvoice.announcement.AudioDeviceMonitor
import com.trackvoice.announcement.AnnouncementPlaybackPlanner
import com.trackvoice.announcement.AnnouncementAudioTiming
import com.trackvoice.announcement.NextTrackAnnouncementPreparation
import com.trackvoice.announcement.PreparedNextAnnouncement
import com.trackvoice.announcement.PreparedTtsVoicePlan
import com.trackvoice.announcement.PlaybackRestoreObligation
import com.trackvoice.announcement.PlaybackRestoreCycleState
import com.trackvoice.announcement.PlaybackRestoreTrigger
import com.trackvoice.announcement.PlaybackRestoreWatchdogPolicy
import com.trackvoice.announcement.ConnectedAudioDevice
import com.trackvoice.announcement.LegacyMusicVolumeRecovery
import com.trackvoice.announcement.InstalledVoice
import com.trackvoice.announcement.TtsEngine
import com.trackvoice.announcement.TtsState
import com.trackvoice.announcement.VoicePreviewPolicy
import com.trackvoice.announcement.shouldReadAlbum
import com.trackvoice.data.AnnouncementMode
import com.trackvoice.data.AnnouncementReadField
import com.trackvoice.data.AppGuideEnablementPolicy
import com.trackvoice.data.AppSettings
import com.trackvoice.data.DataStoreRepository
import com.trackvoice.data.PersistedAnnouncement
import com.trackvoice.data.UserSettings
import com.trackvoice.diagnostics.TrackTalkDebugLog
import com.trackvoice.diagnostics.DiagnosticMessage
import com.trackvoice.data.AudioDeviceSettings
import com.trackvoice.monetization.PremiumState
import com.trackvoice.monetization.forPremiumEntitlement
import android.content.Intent
import android.service.media.MediaBrowserService
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.trackvoice.media.MediaEventType
import com.trackvoice.media.MediaMonitorUpdate
import com.trackvoice.media.MediaSessionMonitor
import com.trackvoice.media.PlaybackRestoreEvent
import com.trackvoice.media.PlaybackRestoreEventType
import com.trackvoice.media.PlaybackEvent
import com.trackvoice.media.PlaybackStatus
import com.trackvoice.media.TrackFingerprint
import com.trackvoice.media.TrackNumberSource
import com.trackvoice.media.PlaybackCollection
import com.trackvoice.media.PlaybackCollectionResolver
import com.trackvoice.media.AlbumTrackNumberResolver
import com.trackvoice.media.TemporalPlaybackContextResolver
import com.trackvoice.media.NextTrackPrefetch
import com.trackvoice.media.PreparedNextTrack
import com.trackvoice.metadata.ExternalMetadataCacheEntry
import com.trackvoice.metadata.ExternalMetadataCachePolicy
import com.trackvoice.metadata.ExternalMetadataStatus
import com.trackvoice.metadata.ExternalTrackMetadata
import com.trackvoice.metadata.ExternalTrackMetadataQuery
import com.trackvoice.metadata.ExternalTrackMetadataResolver
import com.trackvoice.metadata.isDurationCompatible
import com.trackvoice.metadata.ItunesTrackMetadataResolver
import com.trackvoice.metadata.toCacheEntry
import com.trackvoice.metadata.toResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class MediaUiState(
    val currentEvent: PlaybackEvent? = null,
    val effectiveEnabled: Boolean = true,
    val currentMode: AnnouncementMode = AnnouncementMode.SMART,
    val currentCollection: PlaybackCollection = PlaybackCollection.UNKNOWN,
    val lastDetectedAt: Long? = null,
)

data class DiagnosticsState(
    val notificationListenerConnected: Boolean = false,
    val activeSessionCount: Int = 0,
    val selectedSourcePackage: String? = null,
    val lastMetadataEventAt: Long? = null,
    val lastPlaybackStateEventAt: Long? = null,
    val lastAnnouncementAt: Long? = null,
    val lastAnnouncementSucceeded: Boolean? = null,
    val lastAnnouncementMessage: DiagnosticMessage = DiagnosticMessage.NEVER_ANNOUNCED,
    val ttsState: TtsState = TtsState(),
)

private data class QueuedMediaUpdate(
    val monitorGeneration: Long,
    val update: MediaMonitorUpdate,
)

private fun PlaybackEvent.logicalIdentity(): String = listOf(
    sourcePackageName,
    mediaId.orEmpty(),
    title.orEmpty().trim(),
    artist.orEmpty().trim(),
    album.orEmpty().trim(),
).joinToString("|")

private fun Long?.elapsedMillisUntil(endElapsedNanos: Long): Double? = this?.let { start ->
    (endElapsedNanos - start).coerceAtLeast(0L) / 1_000_000.0
}

/** A route retry must never resume an announcement for a different app or track. */
internal fun isRouteRetryStillCurrent(
    pendingEvent: PlaybackEvent,
    currentEvent: PlaybackEvent?,
): Boolean = currentEvent != null && AnnouncementTrackMatcher.matches(
    pendingEvent,
    currentEvent,
    requireSameSource = true,
)

private fun PersistedAnnouncement.logicalIdentity(): String = listOf(
    sourcePackageName,
    mediaId.orEmpty(),
    title.orEmpty().trim(),
    artist.orEmpty().trim(),
    album.orEmpty().trim(),
).joinToString("|")

private fun PlaybackEvent.toPersistedAnnouncement(announcedAt: Long): PersistedAnnouncement =
    PersistedAnnouncement(
        sourcePackageName = sourcePackageName,
        sourceAppName = sourceAppName,
        title = title,
        artist = artist,
        album = album,
        trackNumber = trackNumber,
        discNumber = discNumber,
        duration = duration,
        mediaId = mediaId,
        trackNumberReliable = trackNumberReliable,
        trackNumberSource = trackNumberSource.name,
        announcedAt = announcedAt,
    )

private fun PersistedAnnouncement.toPlaybackEvent(): PlaybackEvent = PlaybackEvent(
    sourcePackageName = sourcePackageName,
    sourceAppName = sourceAppName,
    title = title,
    artist = artist,
    album = album,
    albumArtist = null,
    trackNumber = trackNumber,
    totalTracks = null,
    discNumber = discNumber,
    duration = duration,
    mediaId = mediaId,
    playbackState = PlaybackStatus.PLAYING,
    playbackPosition = null,
    queue = emptyList(),
    observedAt = announcedAt,
    queueTitle = null,
    activeQueuePosition = null,
    queueOrderChanged = false,
    shuffleState = com.trackvoice.media.ShuffleState.UNKNOWN,
    trackNumberReliable = trackNumberReliable,
    trackNumberSource = runCatching { TrackNumberSource.valueOf(trackNumberSource) }
        .getOrDefault(TrackNumberSource.UNSPECIFIED),
)

class TrackVoiceController(
    context: Context,
    val repository: DataStoreRepository,
    private val premiumState: StateFlow<PremiumState>,
    private val externalMetadataResolver: ExternalTrackMetadataResolver = ItunesTrackMetadataResolver(),
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistenceMutex = Mutex()
    private val mediaUpdateQueue = Channel<QueuedMediaUpdate>(Channel.UNLIMITED)
    private val announcementHistoryReady = CompletableDeferred<Unit>()
    private val ttsEngine = TtsEngine(appContext)
    private val audioFocusManager = AudioFocusManager(appContext)
    private val outputDetector = AudioOutputDetector(appContext)
    private val audioDeviceMonitor = AudioDeviceMonitor(appContext, ::handleAudioDevices)
    private val duplicateSuppressor = DuplicateSuppressor()
    private val temporalContextResolver = TemporalPlaybackContextResolver()
    private val pendingFingerprints = mutableSetOf<String>()
    private var pendingJob: Job? = null
    private var pendingAnnouncementEvent: PlaybackEvent? = null
    private var pendingAnnouncementToken = 0L
    private var preparedAnnouncement: PreparedAnnouncement? = null
    private var preparedNextTrack: PreparedNextTrack? = null
    private var preparedNextAnnouncement: PreparedNextAnnouncement? = null
    private var monitor: MediaSessionMonitor? = null
    private val playbackRestoreObligation = PlaybackRestoreObligation()
    private var playbackRestoreWatchdogJob: Job? = null
    private var activeSpeechTrack: PlaybackEvent? = null
    private var lastAnnouncedTrack: PlaybackEvent? = null
    private var lastAnnouncedAt: Long = Long.MIN_VALUE
    private var lastAnnouncedSessionKey: String? = null
    private var selectedSessionKey: String? = null
    private var lastEventSessionKey: String? = null
    /** Set only after the monitor observes that every active media session ended. */
    private var hardPlaybackBoundaryPending = false
    private var hardPlaybackBoundarySessionKey: String? = null
    private var hardPlaybackBoundaryAllowsSameSession = false
    private var lastActualTrackChangeAtMs: Long? = null
    private var lastActualTrackChangeAtElapsedNanos: Long? = null
    private var lastActualTrackChangeUsedPrefetch = false
    private var pausedObservedForTransitionAtElapsedNanos: Long? = null
    private var activeSpeechTransitionAtElapsedNanos: Long? = null
    private var monitorGeneration = 0L
    private var logicalSessionGeneration = 0L
    private var speechGeneration = 0L
    private var monitorStartJob: Job? = null
    private var screenAutoActivated = false
    private var deviceAutoActivated = false
    private var audioDeviceSnapshotGeneration = 0L
    private var latestConnectedAudioDevices: List<ConnectedAudioDevice> = emptyList()
    private val externalMetadataCache = mutableMapOf<String, ExternalMetadataCacheEntry>()
    private val externalMetadataLookupJobs = mutableMapOf<String, Job>()

    private val _mediaState = MutableStateFlow(MediaUiState())
    private val _diagnostics = MutableStateFlow(DiagnosticsState())
    private val _connectedAudioDevices = MutableStateFlow<List<ConnectedAudioDevice>>(emptyList())
    val mediaState: StateFlow<MediaUiState> = _mediaState.asStateFlow()
    val diagnostics: StateFlow<DiagnosticsState> = _diagnostics.asStateFlow()
    val connectedAudioDevices: StateFlow<List<ConnectedAudioDevice>> = _connectedAudioDevices.asStateFlow()
    val userSettings = repository.userSettings.stateIn(
        scope,
        SharingStarted.Eagerly,
        UserSettings(),
    )
    val appSettings = repository.appSettings.stateIn(
        scope,
        SharingStarted.Eagerly,
        emptyMap(),
    )
    val audioDeviceSettings = repository.audioDeviceSettings.stateIn(
        scope,
        SharingStarted.Eagerly,
        emptyMap(),
    )
    val ttsState: StateFlow<TtsState> = ttsEngine.state
    val installedVoices: StateFlow<List<InstalledVoice>> = ttsEngine.voices

    init {
        // Recover only a stale volume written by older builds. Current
        // announcements use system audio focus ducking and never mutate the
        // user's global media volume.
        LegacyMusicVolumeRecovery(appContext)
        scope.launch {
            for (queuedUpdate in mediaUpdateQueue) {
                if (queuedUpdate.monitorGeneration != monitorGeneration) {
                    TrackTalkDebugLog.event(
                        "STALE_MEDIA_EVENT_IGNORED",
                        "eventSequenceNumber" to queuedUpdate.update.eventSequenceNumber,
                        "eventGeneration" to queuedUpdate.monitorGeneration,
                        "currentGeneration" to monitorGeneration,
                        "selectedSessionKey" to queuedUpdate.update.selectedSessionKey,
                    )
                    continue
                }
                processMediaUpdate(queuedUpdate.update)
            }
        }
        scope.launch(Dispatchers.IO) {
            val persisted = runCatching { repository.currentPersistedAnnouncement() }.getOrNull()
            withContext(Dispatchers.Main.immediate) {
                if (persisted != null) restorePersistedAnnouncement(persisted)
                TrackTalkDebugLog.event(
                    "DUPLICATE_STATE_READ",
                    "stage" to "RESTORE",
                    "historyPresent" to (persisted != null),
                    "logicalTrack" to persisted?.logicalIdentity(),
                    "announcedAt" to persisted?.announcedAt,
                )
                announcementHistoryReady.complete(Unit)
            }
        }
        scope.launch(Dispatchers.IO) { repository.migrateTtsVolumeDefault() }
        scope.launch(Dispatchers.IO) {
            repository.migrateContentReadDefaults()
            repository.migrateContentReadOrder()
            repository.migrateLegacyAppAnnouncementSettings()
            repository.migratePlaybackContextSettings()
        }
        scope.launch(Dispatchers.IO) { repository.migrateAudioOutputPolicy() }
        scope.launch {
            userSettings.collectLatest { settings ->
                val effectiveSettings = settings.forPremiumEntitlement(premiumState.value.isPremium)
                _mediaState.value = _mediaState.value.copy(
                    effectiveEnabled = effectiveSettings.enabled || screenAutoActivated || deviceAutoActivated,
                )
            }
        }
        scope.launch {
            premiumState.collectLatest { state ->
                val settings = userSettings.value.forPremiumEntitlement(state.isPremium)
                _mediaState.value = _mediaState.value.copy(
                    effectiveEnabled = settings.enabled || screenAutoActivated || deviceAutoActivated,
                )
                evaluateDeviceAutoActivation(latestConnectedAudioDevices, audioDeviceSettings.value)
            }
        }
        discoverSupportedMediaApps()
        audioDeviceMonitor.start()
        scope.launch {
            audioDeviceSettings.collectLatest { evaluateDeviceAutoActivation(latestConnectedAudioDevices, it) }
        }
        scope.launch {
            ttsEngine.state.collectLatest { state ->
                _diagnostics.value = _diagnostics.value.copy(ttsState = state)
            }
        }
    }

    fun attachNotificationListener() {
        _diagnostics.value = _diagnostics.value.copy(notificationListenerConnected = true)
        TrackTalkDebugLog.event(
            "SESSION_STATE_PRESERVATION",
            "stage" to "ATTACH",
            "duplicateHistoryPresent" to (lastAnnouncedTrack != null),
            "lastAnnouncedSessionKey" to lastAnnouncedSessionKey,
            "logicalSessionGeneration" to logicalSessionGeneration,
        )
    }

    fun setNotificationAccessGranted(granted: Boolean) {
        if (granted) attachNotificationListener()
        else detachNotificationListener(preservePlaybackHistory = false)
    }

    fun detachNotificationListener(preservePlaybackHistory: Boolean = true) {
        TrackTalkDebugLog.event(
            "SESSION_STATE_PRESERVATION",
            "stage" to "DETACH",
            "duplicateHistoryPreserved" to (preservePlaybackHistory && lastAnnouncedTrack != null),
            "lastAnnouncedSessionKey" to lastAnnouncedSessionKey,
        )
        speechGeneration += 1
        activeSpeechTrack = null
        cancelPendingAnnouncement()
        finishAnnouncementAudio(trigger = PlaybackRestoreTrigger.CONTROLLER_DETACH)
        monitorGeneration += 1
        monitorStartJob?.cancel()
        monitorStartJob = null
        monitor?.stop()
        monitor = null
        selectedSessionKey = null
        lastEventSessionKey = null
        if (!preservePlaybackHistory) {
            lastAnnouncedTrack = null
            lastAnnouncedAt = Long.MIN_VALUE
            lastAnnouncedSessionKey = null
            duplicateSuppressor.clear()
            temporalContextResolver.reset()
            hardPlaybackBoundaryPending = false
            hardPlaybackBoundarySessionKey = null
            hardPlaybackBoundaryAllowsSameSession = false
            persistenceScope.launch {
                persistenceMutex.withLock { repository.clearPersistedAnnouncement() }
            }
        }
        _diagnostics.value = _diagnostics.value.copy(notificationListenerConnected = false)
        _mediaState.value = _mediaState.value.copy(
            currentEvent = null,
            currentMode = userSettings.value.defaultMode,
            lastDetectedAt = null,
        )
        _diagnostics.value = _diagnostics.value.copy(
            activeSessionCount = 0,
            selectedSourcePackage = null,
        )
    }

    fun attachMediaSessionMonitor(serviceContext: Context) {
        if (monitor != null || monitorStartJob?.isActive == true) return
        val generation = ++monitorGeneration
        TrackTalkDebugLog.event(
            "SESSION_GENERATION_CHANGED",
            "stage" to "MONITOR_ATTACH_REQUESTED",
            "monitorGeneration" to generation,
            "logicalSessionGeneration" to logicalSessionGeneration,
        )
        monitorStartJob = scope.launch {
            announcementHistoryReady.await()
            if (monitor != null || generation != monitorGeneration) return@launch
            monitor = MediaSessionMonitor(
                context = serviceContext,
                onUpdate = { update ->
                    mediaUpdateQueue.trySend(QueuedMediaUpdate(generation, update))
                },
            ).also { it.start() }
            TrackTalkDebugLog.event(
                "SESSION_GENERATION_CHANGED",
                "stage" to "MONITOR_ATTACHED",
                "monitorGeneration" to generation,
                "logicalSessionGeneration" to logicalSessionGeneration,
            )
        }
    }

    fun setAutoActivated(value: Boolean) {
        screenAutoActivated = value
        _mediaState.value = _mediaState.value.copy(
            effectiveEnabled = effectiveSettings().enabled || screenAutoActivated || deviceAutoActivated,
        )
    }

    fun refreshMediaSessions() = monitor?.refresh()

    fun refreshSupportedMediaApps() = discoverSupportedMediaApps()

    fun togglePlayback(): Boolean? {
        // A UI/tile playback command is explicit user intent. It must own the
        // next state instead of a delayed automatic restore from old speech.
        cancelPlaybackRestoreWithoutResume("USER_TOGGLE")
        return monitor?.toggleSelectedPlayback()
    }

    fun isPlaybackPlaying(): Boolean? = monitor?.isSelectedPlaybackPlaying()

    fun setEnabled(enabled: Boolean) {
        scope.launch { repository.setEnabled(enabled) }
    }

    fun updateUserSettings(transform: (UserSettings) -> UserSettings) {
        scope.launch { repository.updateUserSettings(transform) }
    }

    fun updateAppSettings(settings: AppSettings) {
        scope.launch { repository.updateAppSettings(settings) }
    }

    fun updateAudioDeviceSettings(settings: AudioDeviceSettings) {
        scope.launch { repository.updateAudioDeviceSettings(settings) }
    }

    fun speakTest() {
        speak(AnnouncementFormatter.testText(effectiveSettings().voiceLanguage))
    }

    fun speakVoicePreview(voiceName: String) {
        if (voiceName.isBlank()) return
        val settings = effectiveSettings()
        val voiceLocaleTag = ttsEngine.voices.value
            .firstOrNull { it.name == voiceName }
            ?.localeTag
        speak(
            text = VoicePreviewPolicy.sampleFor(
                language = settings.voiceLanguage,
                voiceLocaleTag = voiceLocaleTag,
            ),
            voiceNameOverride = voiceName,
        )
    }

    fun speak(text: String, voiceNameOverride: String? = null) {
        // A manual test should take over any delayed automatic announcement.
        cancelPendingAnnouncement()
        activeSpeechTrack = null
        val generation = ++speechGeneration
        val settings = effectiveSettings()
        val plan = AnnouncementPlaybackPlanner.plan(settings)
        if (!plan.pauseBeforeAnnouncement) {
            // If a previous "announce then play" batch was interrupted, do not
            // carry its pause token into a new "play immediately" announcement.
            finishAnnouncementAudio(trigger = PlaybackRestoreTrigger.NON_PAUSE_MODE)
        }
        // A new batch owns the focus lifecycle. This also releases an old
        // focus request when TTS replaces speech with QUEUE_FLUSH.
        audioFocusManager.abandon()
        val cycleId = if (plan.pauseBeforeAnnouncement) {
            armPlaybackRestore(
                event = _mediaState.value.currentEvent,
                sessionKey = selectedSessionKey,
                transitionAtElapsedNanos = null,
            )
        } else {
            null
        }
        // In pause-until-finished mode, a focus request can pause a media app
        // even when its session was too transient to return a resume token.
        // Do not make audio focus the only pause mechanism; without a token we
        // let TTS play over the current state and avoid leaving music stopped.
        val shouldRequestAudioFocus = plan.requestAudioFocus &&
            (!plan.pauseBeforeAnnouncement || cycleId != null)
        if (shouldRequestAudioFocus && !audioFocusManager.request(plan.shouldDuckMusic)) {
            audioFocusManager.abandon()
            finishAnnouncementAudio(
                trigger = PlaybackRestoreTrigger.AUDIO_FOCUS_FAILED,
                cycleId = cycleId,
            )
            _diagnostics.value = _diagnostics.value.copy(
                lastAnnouncementAt = System.currentTimeMillis(),
                lastAnnouncementSucceeded = false,
                lastAnnouncementMessage = DiagnosticMessage.AUDIO_FOCUS_UNAVAILABLE,
            )
            return
        }
        if (cycleId != null) {
            playbackRestoreObligation.bindSpeech(cycleId, generation)
            schedulePlaybackRestoreWatchdog(
                cycleId = cycleId,
                timeoutMs = PlaybackRestoreWatchdogPolicy.timeoutMs(text.length, settings.speechRate),
            )
            logRestoreCycle(cycleId, "TTS_REQUESTED", _mediaState.value.currentEvent)
        }
        ttsEngine.speak(
            text = text,
            settings = settings,
            voiceNameOverride = voiceNameOverride,
            announcementCycleId = cycleId,
        ) { success, message ->
            if (generation == speechGeneration) {
                if (shouldRequestAudioFocus) audioFocusManager.abandon()
                finishAnnouncementAudio(
                    trigger = restoreTrigger(success, message),
                    cycleId = cycleId,
                    speechGeneration = generation,
                )
                _diagnostics.value = _diagnostics.value.copy(
                    lastAnnouncementAt = System.currentTimeMillis(),
                    lastAnnouncementSucceeded = success,
                    lastAnnouncementMessage = message,
                )
            }
        }
    }

    private fun speakPrepared(
        text: String,
        track: PlaybackEvent,
        fingerprint: String,
        pendingToken: Long,
        transitionAtMs: Long? = null,
        transitionAtElapsedNanos: Long? = null,
        preparedVoicePlan: PreparedTtsVoicePlan? = null,
    ) {
        if (!isPendingAnnouncement(pendingToken, fingerprint)) return
        if (preparedAnnouncement?.fingerprint != fingerprint || preparedAnnouncement?.token != pendingToken) return
        preparedAnnouncement = null
        activeSpeechTrack = track
        activeSpeechTransitionAtElapsedNanos = transitionAtElapsedNanos
        val generation = ++speechGeneration
        val settings = effectiveSettings()
        val cycleId = playbackRestoreObligation.activeCycle()?.id
        if (cycleId != null) {
            playbackRestoreObligation.bindSpeech(cycleId, generation)
            schedulePlaybackRestoreWatchdog(
                cycleId = cycleId,
                timeoutMs = PlaybackRestoreWatchdogPolicy.timeoutMs(text.length, settings.speechRate),
            )
            logRestoreCycle(cycleId, "TTS_REQUESTED", track)
        }
        ttsEngine.speakWithVoicePlan(
            text,
            settings,
            transitionAtMs = transitionAtMs,
            transitionAtElapsedNanos = transitionAtElapsedNanos,
            preparedVoicePlan = preparedVoicePlan,
            announcementCycleId = cycleId,
        ) { success, message ->
            if (generation == speechGeneration) {
                activeSpeechTrack = null
                val completedAtNanos = SystemClock.elapsedRealtimeNanos()
                TrackTalkDebugLog.event(
                    "TRANSITION_TIMING",
                    "stage" to if (success) "T7_TTS_COMPLETED" else "T7_TTS_TERMINATED",
                    "announcementCycleId" to cycleId,
                    "elapsedRealtimeNanos" to completedAtNanos,
                    "transitionElapsedMs" to transitionAtElapsedNanos.elapsedMillisUntil(completedAtNanos),
                    "mediaId" to track.mediaId,
                    "result" to message,
                )
                finishAnnouncementAudio(
                    transitionAtElapsedNanos = transitionAtElapsedNanos,
                    trigger = restoreTrigger(success, message),
                    cycleId = cycleId,
                    speechGeneration = generation,
                )
                activeSpeechTransitionAtElapsedNanos = null
                _diagnostics.value = _diagnostics.value.copy(
                    lastAnnouncementAt = System.currentTimeMillis(),
                    lastAnnouncementSucceeded = success,
                    lastAnnouncementMessage = message,
                )
            }
        }
    }

    private fun restoreTrigger(
        success: Boolean,
        message: DiagnosticMessage,
    ): PlaybackRestoreTrigger = when {
        success -> PlaybackRestoreTrigger.TTS_COMPLETED
        message == DiagnosticMessage.TTS_INTERRUPTED -> PlaybackRestoreTrigger.TTS_INTERRUPTED
        else -> PlaybackRestoreTrigger.TTS_ERROR
    }

    fun onScreenOff() {
        val settings = effectiveSettings()
        if (!settings.autoEnableOnScreenOff) return
        if (settings.bluetoothOnlyForAutoEnable && !outputDetector.hasBluetoothOutput()) return
        setAutoActivated(true)
    }

    fun onScreenOn() {
        if (userSettings.value.restoreEnabledWhenScreenOn) setAutoActivated(false)
    }

    fun close() {
        speechGeneration += 1
        activeSpeechTrack = null
        lastAnnouncedTrack = null
        lastAnnouncedAt = Long.MIN_VALUE
        lastAnnouncedSessionKey = null
        selectedSessionKey = null
        lastActualTrackChangeAtMs = null
        lastActualTrackChangeAtElapsedNanos = null
        lastActualTrackChangeUsedPrefetch = false
        pausedObservedForTransitionAtElapsedNanos = null
        activeSpeechTransitionAtElapsedNanos = null
        hardPlaybackBoundaryPending = false
        hardPlaybackBoundarySessionKey = null
        hardPlaybackBoundaryAllowsSameSession = false
        preparedNextTrack = null
        preparedNextAnnouncement = null
        externalMetadataLookupJobs.values.forEach(Job::cancel)
        externalMetadataLookupJobs.clear()
        externalMetadataCache.clear()
        duplicateSuppressor.clear()
        temporalContextResolver.reset()
        cancelPendingAnnouncement()
        finishAnnouncementAudio(trigger = PlaybackRestoreTrigger.CONTROLLER_CLOSE)
        monitorGeneration += 1
        monitorStartJob?.cancel()
        monitorStartJob = null
        mediaUpdateQueue.close()
        monitor?.stop()
        monitor = null
        audioDeviceMonitor.stop()
        ttsEngine.shutdown()
        audioFocusManager.abandon()
        persistenceScope.cancel()
        scope.coroutineContext.cancel()
    }

    private fun handleAudioDevices(devices: List<ConnectedAudioDevice>) {
        val generation = ++audioDeviceSnapshotGeneration
        latestConnectedAudioDevices = devices
        // Disconnects and reconnects should update automation from the latest
        // route snapshot immediately. Reconciliation may then supply a
        // migrated canonical preference without replaying the same transition.
        evaluateDeviceAutoActivation(devices, audioDeviceSettings.value)
        scope.launch {
            devices.forEach { device ->
                repository.reconcileAudioDeviceSettings(
                    canonicalKey = device.key,
                    displayName = device.productName ?: device.kind.name,
                    legacyKeys = device.legacyKeys,
                )
            }
            val reconciledSettings = repository.currentAudioDeviceSettings()
            if (generation != audioDeviceSnapshotGeneration) return@launch
            _connectedAudioDevices.value = devices
            evaluateDeviceAutoActivation(devices, reconciledSettings)
        }
    }

    private fun evaluateDeviceAutoActivation(
        devices: List<ConnectedAudioDevice>,
        settings: Map<String, AudioDeviceSettings>,
    ) {
        val decision = DeviceAutomationPolicy.decide(
            currentlyActive = deviceAutoActivated,
            isPremium = premiumState.value.isPremium,
            devices = devices,
            settings = settings,
        )
        if (!decision.changed) return
        deviceAutoActivated = decision.active
        _mediaState.value = _mediaState.value.copy(
            effectiveEnabled = effectiveSettings().enabled || screenAutoActivated || deviceAutoActivated,
        )
    }

    private fun discoverSupportedMediaApps() {
        scope.launch(Dispatchers.IO) {
            val intent = Intent(MediaBrowserService.SERVICE_INTERFACE)
            val services = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.queryIntentServices(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.queryIntentServices(intent, PackageManager.MATCH_ALL)
            }
            val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
            val receivers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.queryBroadcastReceivers(
                    mediaButtonIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.queryBroadcastReceivers(mediaButtonIntent, PackageManager.MATCH_ALL)
            }
            (services.mapNotNull { it.serviceInfo?.applicationInfo } +
                receivers.mapNotNull { it.activityInfo?.applicationInfo })
                .distinctBy { it.packageName }
                .filter { info ->
                    info.packageName != appContext.packageName &&
                        appContext.packageManager.getLaunchIntentForPackage(info.packageName) != null
                }
                .forEach { info ->
                    val label = appContext.packageManager.getApplicationLabel(info).toString()
                    repository.ensureApp(info.packageName, label)
                }
            repository.currentAppSettings().keys.forEach { packageName ->
                val info = runCatching {
                    appContext.packageManager.getApplicationInfo(packageName, 0)
                }.getOrNull() ?: return@forEach
                val isSystemComponent = info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 &&
                    appContext.packageManager.getLaunchIntentForPackage(packageName) == null
                if (isSystemComponent) repository.removeApp(packageName)
            }
        }
    }

    private fun armPlaybackRestore(
        event: PlaybackEvent?,
        sessionKey: String?,
        transitionAtElapsedNanos: Long?,
    ): Long? {
        playbackRestoreObligation.activeCycle()?.let { active ->
            if (active.state != PlaybackRestoreCycleState.ARMED) {
                logRestoreCycle(
                    cycleId = active.id,
                    stage = "PAUSE_NOT_REUSED",
                    event = event,
                    reason = "RESTORE_ALREADY_REQUESTED",
                )
                return null
            }
            if (event == null || playbackRestoreObligation.matchesActiveTrack(event)) return active.id
            logRestoreCycle(
                cycleId = active.id,
                stage = "PAUSE_NOT_REUSED",
                event = event,
                reason = "DIFFERENT_LOGICAL_TRACK",
            )
            return null
        }
        val cycleId = playbackRestoreObligation.reserveCycleId()
        val pauseToken = monitor?.pauseSelectedIfPlaying(
            expectedEvent = event,
            expectedSessionKey = sessionKey,
        ) { pauseRequestedAtNanos ->
            TrackTalkDebugLog.event(
                "TRANSITION_TIMING",
                "stage" to "T3_PAUSE_REQUESTED",
                "announcementCycleId" to cycleId,
                "elapsedRealtimeNanos" to pauseRequestedAtNanos,
                "transitionElapsedMs" to transitionAtElapsedNanos.elapsedMillisUntil(pauseRequestedAtNanos),
                "mediaId" to event?.mediaId,
            )
            logRestoreCycle(
                cycleId = cycleId,
                stage = "PAUSE_REQUESTED",
                event = event,
                elapsedRealtimeNanos = pauseRequestedAtNanos,
            )
        } ?: return null
        val armedAtNanos = SystemClock.elapsedRealtimeNanos()
        playbackRestoreObligation.arm(
            cycleId = cycleId,
            pauseToken = pauseToken,
            track = event,
            sessionGeneration = logicalSessionGeneration,
            armedAtElapsedNanos = armedAtNanos,
            transitionAtElapsedNanos = transitionAtElapsedNanos,
        )
        logRestoreCycle(
            cycleId = cycleId,
            stage = "OWNERSHIP_ARMED",
            event = event,
            elapsedRealtimeNanos = armedAtNanos,
            reason = "TRACKTALK_PAUSE_ISSUED",
        )
        schedulePlaybackRestoreWatchdog(
            cycleId = cycleId,
            timeoutMs = PlaybackRestoreWatchdogPolicy.MAX_TIMEOUT_MS,
        )
        return cycleId
    }

    private fun resumePausedPlayback(
        trigger: PlaybackRestoreTrigger = PlaybackRestoreTrigger.ANNOUNCEMENT_CANCELLED,
        cycleId: Long? = playbackRestoreObligation.activeCycle()?.id,
        speechGeneration: Long? = null,
    ) {
        val id = cycleId ?: return
        val cycle = playbackRestoreObligation.requestRestore(id, trigger, speechGeneration) ?: run {
            logRestoreCycle(
                cycleId = id,
                stage = "RESTORE_REQUEST_IGNORED",
                event = playbackRestoreObligation.activeCycle()?.track,
                reason = "STALE_OR_ALREADY_REQUESTED_$trigger",
            )
            return
        }
        playbackRestoreWatchdogJob?.cancel()
        playbackRestoreWatchdogJob = null
        val requestedAtNanos = SystemClock.elapsedRealtimeNanos()
        logRestoreCycle(
            cycleId = cycle.id,
            stage = "RESTORE_REQUESTED",
            event = cycle.track,
            elapsedRealtimeNanos = requestedAtNanos,
            reason = trigger.name,
        )
        val currentMonitor = monitor
        if (currentMonitor == null) {
            completePlaybackRestore(cycle.id, "FAILED_NO_MONITOR")
            return
        }
        currentMonitor.resumePlayback(
            token = cycle.pauseToken,
            announcementCycleId = cycle.id,
            onEvent = ::handlePlaybackRestoreEvent,
        )
    }

    private fun handlePlaybackRestoreEvent(event: PlaybackRestoreEvent) {
        val cycleId = event.announcementCycleId ?: return
        logRestoreCycle(
            cycleId = cycleId,
            stage = event.type.name,
            event = playbackRestoreObligation.activeCycle()?.track,
            elapsedRealtimeNanos = event.elapsedRealtimeNanos,
            reason = "${event.reason};attempt=${event.attempt};session=${event.sessionKey}",
        )
        when (event.type) {
            PlaybackRestoreEventType.PLAYING_CONFIRMED ->
                completePlaybackRestore(cycleId, "PLAYING_CONFIRMED")

            PlaybackRestoreEventType.CANCELLED ->
                completePlaybackRestore(cycleId, "CANCELLED_${event.reason}")

            PlaybackRestoreEventType.FAILED ->
                completePlaybackRestore(cycleId, "FAILED_${event.reason}")

            PlaybackRestoreEventType.PLAY_REQUESTED,
            PlaybackRestoreEventType.PLAY_RETRY_REQUESTED,
            PlaybackRestoreEventType.WAITING_FOR_SESSION,
            -> Unit
        }
    }

    private fun completePlaybackRestore(cycleId: Long, reason: String) {
        val cycle = playbackRestoreObligation.complete(cycleId) ?: return
        playbackRestoreWatchdogJob?.cancel()
        playbackRestoreWatchdogJob = null
        val terminalStage = when {
            reason == "PLAYING_CONFIRMED" -> "RESTORE_COMPLETED"
            reason.startsWith("CANCELLED_") -> "RESTORE_CANCELLED"
            reason.startsWith("FAILED_") -> "RESTORE_FAILED"
            else -> "RESTORE_FINALIZED"
        }
        logRestoreCycle(
            cycleId = cycle.id,
            stage = terminalStage,
            event = cycle.track,
            reason = reason,
        )
    }

    private fun cancelPlaybackRestoreWithoutResume(reason: String) {
        val cycle = playbackRestoreObligation.activeCycle() ?: return
        playbackRestoreObligation.cancel(cycle.id)
        playbackRestoreWatchdogJob?.cancel()
        playbackRestoreWatchdogJob = null
        monitor?.cancelPendingResume(reason)
        logRestoreCycle(
            cycleId = cycle.id,
            stage = "RESTORE_CANCELLED",
            event = cycle.track,
            reason = reason,
        )
    }

    private fun schedulePlaybackRestoreWatchdog(cycleId: Long, timeoutMs: Long) {
        playbackRestoreWatchdogJob?.cancel()
        playbackRestoreWatchdogJob = scope.launch {
            delay(timeoutMs)
            val cycle = playbackRestoreObligation.activeCycle()?.takeIf { it.id == cycleId } ?: return@launch
            logRestoreCycle(
                cycleId = cycle.id,
                stage = "WATCHDOG_TIMEOUT",
                event = cycle.track,
                reason = "TTS_COMPLETION_NOT_RECEIVED_${timeoutMs}MS",
            )
            audioFocusManager.abandon()
            resumePausedPlayback(
                trigger = PlaybackRestoreTrigger.WATCHDOG_TIMEOUT,
                cycleId = cycle.id,
            )
        }
    }

    private fun logRestoreCycle(
        cycleId: Long,
        stage: String,
        event: PlaybackEvent?,
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
        reason: String? = null,
    ) {
        TrackTalkDebugLog.event(
            "ANNOUNCEMENT_RESTORE_CYCLE",
            "announcementCycleId" to cycleId,
            "stage" to stage,
            "elapsedRealtimeNanos" to elapsedRealtimeNanos,
            "source" to event?.sourcePackageName,
            "mediaId" to event?.mediaId,
            "title" to event?.title,
            "logicalSessionGeneration" to logicalSessionGeneration,
            "reason" to reason,
        )
    }

    private fun processMediaUpdate(update: MediaMonitorUpdate) {
        var event = update.selected?.event?.let(::applyExternalMetadataOverride)
        val settings = effectiveSettings()
        val incomingSessionKey = update.selected?.sessionKey
        event?.let { requestExternalMetadata(it) }
        val previousSessionKey = lastEventSessionKey
        if (incomingSessionKey != null && incomingSessionKey != previousSessionKey) {
            logicalSessionGeneration += 1
            lastEventSessionKey = incomingSessionKey
            TrackTalkDebugLog.event(
                "SESSION_GENERATION_CHANGED",
                "stage" to "SELECTED_SESSION",
                "eventSequenceNumber" to update.eventSequenceNumber,
                "oldSessionKey" to previousSessionKey,
                "newSessionKey" to incomingSessionKey,
                "logicalSessionGeneration" to logicalSessionGeneration,
                "thread" to Thread.currentThread().name,
            )
        }
        val previousEvent = _mediaState.value.currentEvent
        var transitionObservation: TransitionObservation? = null
        var prefetchedAnnouncementText: String? = null
        var prefetchedVoicePlan: PreparedTtsVoicePlan? = null
        val resumedAfterHardPlaybackBoundary = hardPlaybackBoundaryPending && (
            hardPlaybackBoundaryAllowsSameSession ||
                hardPlaybackBoundarySessionKey == null ||
                incomingSessionKey == null ||
                incomingSessionKey != hardPlaybackBoundarySessionKey
            )
        val actualTrackChange = previousEvent != null && event != null &&
            !AnnouncementTrackMatcher.matchesForDuplicateSuppression(
                expected = previousEvent,
                current = event,
                requireSameSource = true,
            )
        if (actualTrackChange) {
            // A confirmed replacement track is newer user/provider intent.
            // Never let an old announcement cycle issue PLAY for its stale
            // TrackTalk-owned pause after this boundary.
            cancelPlaybackRestoreWithoutResume("TRACK_CHANGED_DURING_ANNOUNCEMENT")
            val actualTrackChangeAtMs = System.currentTimeMillis()
            val actualTrackChangeAtElapsedNanos = update.observedAtElapsedNanos
                .takeIf { it > 0L }
                ?: SystemClock.elapsedRealtimeNanos()
            lastActualTrackChangeAtMs = actualTrackChangeAtMs
            lastActualTrackChangeAtElapsedNanos = actualTrackChangeAtElapsedNanos
            pausedObservedForTransitionAtElapsedNanos = null
            val prepared = preparedNextTrack
            var prefetchMatched = false
            if (prepared != null) {
                val actualEvent = event ?: return
                if (NextTrackPrefetch.matches(prepared, actualEvent, incomingSessionKey)) {
                    prefetchMatched = true
                    event = NextTrackPrefetch.mergeMissingMetadata(prepared, actualEvent)
                    prefetchedAnnouncementText = preparedNextAnnouncement?.let { preparedAnnouncement ->
                        NextTrackAnnouncementPreparation.reusableText(
                            prepared = preparedAnnouncement,
                            actual = event ?: actualEvent,
                            sessionKey = incomingSessionKey,
                            settings = settings,
                        )?.also { prefetchedVoicePlan = preparedAnnouncement.voicePlan }
                    }
                } else {
                    invalidatePreparedNextTrack("ACTUAL_TRACK_MISMATCH")
                }
            }
            val identityResolvedAtNanos = SystemClock.elapsedRealtimeNanos()
            lastActualTrackChangeUsedPrefetch = prefetchMatched
            transitionObservation = TransitionObservation(
                transitionAtMs = actualTrackChangeAtMs,
                transitionAtElapsedNanos = actualTrackChangeAtElapsedNanos,
                identityResolvedAtElapsedNanos = identityResolvedAtNanos,
                eventSequenceNumber = update.eventSequenceNumber,
                eventType = update.eventType,
                sessionKey = incomingSessionKey,
                previousEvent = previousEvent,
                currentEvent = event ?: return,
                matchedPrefetch = prepared.takeIf { prefetchMatched },
            )
        } else if (preparedNextTrack != null && event != null &&
            !NextTrackPrefetch.anchorMatches(preparedNextTrack!!, event, incomingSessionKey)
        ) {
            invalidatePreparedNextTrack("ANCHOR_CHANGED")
        }
        val newRepeatOneCycle = previousEvent != null && event != null &&
            RepeatCycleDetector.isNewRepeatOneCycle(previousEvent, event)
        if (newRepeatOneCycle) {
            TrackTalkDebugLog.event(
                "REPEAT_CYCLE_DETECTED",
                "source" to event?.sourcePackageName,
                "mediaId" to event?.mediaId,
                "title" to event?.title,
                "previousPositionMs" to previousEvent?.playbackPosition,
                "currentPositionMs" to event?.playbackPosition,
                "durationMs" to event?.duration,
                "repeatMode" to event?.repeatMode,
            )
        }
        TrackTalkDebugLog.event(
            "TRACK_CANDIDATE",
            "timestamp" to update.observedAt,
            "eventSequenceNumber" to update.eventSequenceNumber,
            "thread" to Thread.currentThread().name,
            "callbackThread" to update.callbackThread,
            "monitorGeneration" to monitorGeneration,
            "logicalSessionGeneration" to logicalSessionGeneration,
            "controllerSessionKey" to incomingSessionKey,
            "package" to event?.sourcePackageName,
            "logicalTrack" to event?.logicalIdentity(),
            "playbackState" to event?.playbackState,
        )
        val sameLogicalTrackAsPrevious = previousEvent != null && event != null &&
            AnnouncementTrackMatcher.matchesForDuplicateSuppression(
                expected = previousEvent,
                current = event,
                requireSameSource = true,
            )
        val history = lastAnnouncedTrack
        val sameLogicalTrackAsHistory = history != null && event != null &&
            AnnouncementTrackMatcher.matchesForDuplicateSuppression(
                expected = history,
                current = event,
                requireSameSource = true,
            )
        TrackTalkDebugLog.event(
            "TRACK_IDENTITY_COMPARISON",
            "eventSequenceNumber" to update.eventSequenceNumber,
            "logicalTrack" to event?.logicalIdentity(),
            "previousTrack" to previousEvent?.logicalIdentity(),
            "acceptedTrack" to history?.logicalIdentity(),
            "sameLogicalTrack" to sameLogicalTrackAsHistory,
            "sameLogicalSession" to (lastAnnouncedSessionKey != null && lastAnnouncedSessionKey == incomingSessionKey),
            "samePackage" to (history != null && event != null && history.sourcePackageName == event.sourcePackageName),
            "historyPresent" to (history != null),
            "thread" to Thread.currentThread().name,
        )
        val sameVisibleTrack = previousEvent != null && event != null &&
            AnnouncementTrackMatcher.matchesForDuplicateSuppression(
                expected = previousEvent,
                current = event,
                requireSameSource = true,
            )
        val sameAnnouncedTrack = lastAnnouncedTrack?.let { announced ->
            event?.let {
                AnnouncementTrackMatcher.matchesForDuplicateSuppression(
                    expected = announced,
                    current = it,
                    requireSameSource = true,
                )
            }
        } == true
        val sessionRefresh = event != null && sameAnnouncedTrack && (
            update.eventType == MediaEventType.INITIAL ||
                update.eventType == MediaEventType.ACTIVE_SESSIONS ||
                (selectedSessionKey != null && incomingSessionKey != null && selectedSessionKey != incomingSessionKey)
            )
        if (sessionRefresh) {
            TrackTalkDebugLog.event(
                "ACTIVE_SESSION_REFRESH",
                "oldSessionKey" to selectedSessionKey,
                "newSessionKey" to incomingSessionKey,
                "sameLogicalTrack" to true,
                "samePackage" to true,
                "eventType" to update.eventType,
            )
            TrackTalkDebugLog.event(
                "SESSION_STATE_PRESERVATION",
                "duplicateHistoryPreserved" to true,
                "reason" to "SAME_LOGICAL_TRACK_SESSION_REFRESH",
            )
        } else if (sameVisibleTrack && update.eventType == MediaEventType.ACTIVE_SESSIONS) {
            TrackTalkDebugLog.event(
                "ACTIVE_SESSION_REFRESH",
                "oldSessionKey" to selectedSessionKey,
                "newSessionKey" to incomingSessionKey,
                "sameLogicalTrack" to true,
                "samePackage" to true,
                "eventType" to update.eventType,
            )
        }
        selectedSessionKey = incomingSessionKey ?: selectedSessionKey
        val collection = event?.let { resolveCollection(it, incomingSessionKey) } ?: PlaybackCollection.UNKNOWN
        val mode = AnnouncementPolicy.resolveMode(collection, settings)
        TrackTalkDebugLog.event(
            "controller_media_update",
            "eventType" to update.eventType,
            "source" to event?.sourcePackageName,
            "mediaId" to event?.mediaId,
            "title" to event?.title,
            "artist" to event?.artist,
            "album" to event?.album,
            "collection" to collection,
            "mode" to mode,
            "playing" to event?.isPlaying,
            "observedAt" to update.observedAt,
            "eventSequenceNumber" to update.eventSequenceNumber,
            "logicalSessionGeneration" to logicalSessionGeneration,
        )
        _mediaState.value = _mediaState.value.copy(
            currentEvent = event,
            effectiveEnabled = settings.enabled || screenAutoActivated || deviceAutoActivated,
            currentMode = mode,
            currentCollection = collection,
            lastDetectedAt = update.observedAt.takeIf { event != null },
        )
        _diagnostics.value = _diagnostics.value.copy(
            activeSessionCount = update.activeSessionCount,
            selectedSourcePackage = event?.sourcePackageName,
            lastMetadataEventAt = update.observedAt.takeIf {
                event != null && (
                    update.eventType == MediaEventType.METADATA || update.eventType == MediaEventType.INITIAL
                )
            } ?: _diagnostics.value.lastMetadataEventAt,
            lastPlaybackStateEventAt = update.observedAt.takeIf {
                event != null && (
                    update.eventType == MediaEventType.PLAYBACK_STATE || update.eventType == MediaEventType.INITIAL
                )
            } ?: _diagnostics.value.lastPlaybackStateEventAt,
        )
        if (event != null && appSettings.value[event.sourcePackageName] == null) {
            scope.launch { repository.ensureApp(event.sourcePackageName, event.sourceAppName) }
        }

        if (event == null) {
            // A provider can briefly lose the selected snapshot while active
            // sessions are still present during a controller/queue refresh.
            // Preserve temporal playback evidence across that soft gap. Only
            // an empty active-session set is a hard context boundary.
            val noActiveSessions = update.activeSessionCount == 0
            if (noActiveSessions) {
                temporalContextResolver.reset()
                selectedSessionKey = null
                if (!hardPlaybackBoundaryAllowsSameSession) {
                    hardPlaybackBoundaryPending = true
                    hardPlaybackBoundarySessionKey = lastEventSessionKey
                }
            }
            TrackTalkDebugLog.event(
                "PLAYBACK_CONTEXT_BOUNDARY",
                "reason" to if (noActiveSessions) "NO_ACTIVE_SESSIONS" else "NO_SELECTED_SESSION_SOFT",
                "activeSessions" to update.activeSessionCount,
            )
            TrackTalkDebugLog.event("announcement_cancel", "reason" to "no_selected_event")
            invalidatePreparedNextTrack(
                if (noActiveSessions) "NO_ACTIVE_SESSIONS" else "NO_SELECTED_SESSION",
            )
            cancelPendingAnnouncement()
            return
        }
        if (event.playbackState == PlaybackStatus.STOPPED) {
            // A real STOPPED state is a stronger listening-context boundary
            // than PAUSED. The next start of the same song must be eligible,
            // while pause/resume remains one continuous occurrence.
            hardPlaybackBoundaryPending = true
            hardPlaybackBoundarySessionKey = incomingSessionKey
            hardPlaybackBoundaryAllowsSameSession = true
            TrackTalkDebugLog.event(
                "PLAYBACK_CONTEXT_BOUNDARY",
                "reason" to "STOPPED_STATE",
                "source" to event.sourcePackageName,
                "mediaId" to event.mediaId,
            )
        }
        playbackRestoreObligation.activeCycle()?.let { cycle ->
            if (
                event.isPlaying &&
                playbackRestoreObligation.matchesActiveTrack(event) &&
                playbackRestoreObligation.markPlayerPlayingAfterOwnedPause(
                    cycle.id,
                    SystemClock.elapsedRealtimeNanos(),
                )
            ) {
                logRestoreCycle(
                    cycleId = cycle.id,
                    stage = "PLAYER_PLAYING_DURING_TTS",
                    event = event,
                    reason = "INTERVENING_PLAYBACK_INTENT",
                )
            }
        }
        val newPlaybackOccurrence = actualTrackChange || resumedAfterHardPlaybackBoundary
        if (!event.isPlaying) {
            refreshPreparedNextTrack(event, incomingSessionKey)
            val restoreCycle = playbackRestoreObligation.activeCycle()
            if (
                event.playbackState == PlaybackStatus.PAUSED &&
                restoreCycle != null &&
                playbackRestoreObligation.matchesActiveTrack(event)
            ) {
                val pausedObservedAtNanos = SystemClock.elapsedRealtimeNanos()
                if (playbackRestoreObligation.hasInterveningPlaybackIntent(restoreCycle.id)) {
                    cancelPlaybackRestoreWithoutResume("USER_PAUSED_AFTER_INTERVENING_PLAYBACK")
                } else if (playbackRestoreObligation.markPlayerPaused(restoreCycle.id, pausedObservedAtNanos)) {
                    logRestoreCycle(
                        cycleId = restoreCycle.id,
                        stage = "PLAYER_PAUSED_OBSERVED",
                        event = event,
                        elapsedRealtimeNanos = pausedObservedAtNanos,
                    )
                }
            }
            // A media session commonly reports PAUSED while audio focus is
            // moving to TTS. Once preparation or speech has been committed,
            // keep that batch alive; otherwise a real user pause still cancels
            // the delayed announcement as expected.
            val sameCommittedAnnouncement =
                (preparedAnnouncement != null || activeSpeechTrack != null) &&
                    (
                        preparedAnnouncement != null &&
                            pendingAnnouncementEvent?.let {
                                AnnouncementTrackMatcher.matches(it, event, requireSameSource = false)
                            } == true ||
                            activeSpeechTrack?.let {
                                AnnouncementTrackMatcher.matches(it, event, requireSameSource = false)
                            } == true
                        )
            val transitionAtElapsedNanos = lastActualTrackChangeAtElapsedNanos
                ?: activeSpeechTransitionAtElapsedNanos
            if (
                sameCommittedAnnouncement &&
                transitionAtElapsedNanos != null &&
                pausedObservedForTransitionAtElapsedNanos != transitionAtElapsedNanos
            ) {
                val pausedObservedAtNanos = SystemClock.elapsedRealtimeNanos()
                pausedObservedForTransitionAtElapsedNanos = transitionAtElapsedNanos
                TrackTalkDebugLog.event(
                    "TRANSITION_TIMING",
                    "stage" to "T4_PLAYBACK_PAUSED_OBSERVED",
                    "elapsedRealtimeNanos" to pausedObservedAtNanos,
                    "transitionElapsedMs" to transitionAtElapsedNanos.elapsedMillisUntil(pausedObservedAtNanos),
                    "mediaId" to event.mediaId,
                )
            }
            if (!sameCommittedAnnouncement) cancelPendingAnnouncement()
            TrackTalkDebugLog.event(
                "announcement_pause_state",
                "committed" to sameCommittedAnnouncement,
                "mediaId" to event.mediaId,
                "title" to event.title,
            )
            logTransitionObservation(transitionObservation)
            return
        }
        scheduleAnnouncement(
            event = event,
            collection = collection,
            sessionKey = incomingSessionKey,
            sessionRefresh = sessionRefresh,
            isNewPlaybackOccurrence = newPlaybackOccurrence,
            isNewRepeatCycle = newRepeatOneCycle,
            eventSequenceNumber = update.eventSequenceNumber,
            logicalSessionGeneration = logicalSessionGeneration,
            preparedText = prefetchedAnnouncementText,
            preparedVoicePlan = prefetchedVoicePlan,
        )
        // Debug diagnostics are intentionally emitted after the immediate
        // pause request. Their timestamps still describe T0/T1 precisely, but
        // Logcat I/O cannot lengthen the audible transition window.
        logTransitionObservation(transitionObservation)
        // Preparing the following queue item is predictive work. Keep it out
        // of the confirmed transition's pause-critical path; it still runs
        // immediately after this track has been admitted or suppressed.
        refreshPreparedNextTrack(event, incomingSessionKey)
    }

    private fun logTransitionObservation(observation: TransitionObservation?) {
        observation ?: return
        val current = observation.currentEvent
        val previous = observation.previousEvent
        TrackTalkDebugLog.event(
            "TRANSITION_TIMING",
            "stage" to "T0_TRANSITION_CONFIRMED",
            "elapsedRealtimeNanos" to observation.transitionAtElapsedNanos,
            "eventSequenceNumber" to observation.eventSequenceNumber,
            "mediaId" to current.mediaId,
        )
        TrackTalkDebugLog.event(
            "ACTUAL_TRACK_CHANGE_DETECTED",
            "source" to current.sourcePackageName,
            "previousTitle" to previous.title,
            "currentTitle" to current.title,
            "previousMediaId" to previous.mediaId,
            "currentMediaId" to current.mediaId,
            "eventType" to observation.eventType,
            "observedAt" to current.observedAt,
            "detectedAtMs" to observation.transitionAtMs,
            "deltaSincePreviousObservedMs" to (current.observedAt - previous.observedAt),
            "sessionKey" to observation.sessionKey,
        )
        observation.matchedPrefetch?.let { prepared ->
            TrackTalkDebugLog.event(
                "PREFETCH_MATCH",
                "source" to current.sourcePackageName,
                "queueItemId" to prepared.predicted.queueItemId,
                "title" to prepared.title,
                "quality" to prepared.quality,
                "metadataMerged" to true,
                "preparedAt" to prepared.preparedAt,
                "transitionObservedAt" to current.observedAt,
                "transitionToMergeMs" to observation.transitionAtMs - prepared.preparedAt,
            )
        }
        TrackTalkDebugLog.event(
            "TRANSITION_TIMING",
            "stage" to "T1_IDENTITY_RESOLVED",
            "elapsedRealtimeNanos" to observation.identityResolvedAtElapsedNanos,
            "transitionElapsedMs" to observation.transitionAtElapsedNanos.elapsedMillisUntil(
                observation.identityResolvedAtElapsedNanos,
            ),
            "prefetched" to (observation.matchedPrefetch != null),
            "eventSequenceNumber" to observation.eventSequenceNumber,
            "mediaId" to current.mediaId,
        )
    }

    private fun restorePersistedAnnouncement(persisted: PersistedAnnouncement) {
        val event = persisted.toPlaybackEvent()
        lastAnnouncedTrack = event
        lastAnnouncedAt = persisted.announcedAt
        lastAnnouncedSessionKey = null
        duplicateSuppressor.restoreAnnounced(event, persisted.announcedAt)
    }

    private fun resolveCollection(event: PlaybackEvent, sessionKey: String?): PlaybackCollection {
        val decision = temporalContextResolver.resolve(event, sessionKey)
        val evidence = decision.evidence
        TrackTalkDebugLog.event(
            "TEMPORAL_CONTEXT_INPUT",
            "sessionKey" to sessionKey,
            "trackIdentity" to decision.currentTrackIdentity,
            "previousTrackIdentity" to decision.previousTrackIdentity,
            "albumSameAsPrevious" to decision.albumSameAsPrevious,
            "artistSameAsPrevious" to decision.artistSameAsPrevious,
            "transitionKind" to decision.transitionKind,
            "previousPosition" to decision.previousPosition,
            "previousDuration" to decision.previousDuration,
            "sessionContinuous" to decision.sessionContinuous,
            "queueGeneration" to decision.queueGeneration,
            "queueChanged" to decision.queueChanged,
        )
        TrackTalkDebugLog.event(
            "TEMPORAL_CONTEXT_STATE_BEFORE",
            "sameAlbumNaturalTransitions" to decision.stateBeforeSameAlbumNaturalTransitions,
            "mixedAlbumTransitions" to decision.stateBeforeMixedNaturalTransitions,
            "currentHypothesis" to decision.stateBeforeHypothesis,
            "confidence" to decision.confidence,
        )
        TrackTalkDebugLog.event(
            "TEMPORAL_CONTEXT_UPDATE",
            "evidenceAdded" to decision.evidenceAdded,
            "evidenceRemoved" to decision.evidenceRemoved,
            "resetReason" to decision.resetReason,
        )
        TrackTalkDebugLog.event(
            "TEMPORAL_CONTEXT_STATE_AFTER",
            "hypothesis" to decision.stateAfterHypothesis,
            "confidence" to decision.confidence,
            "effectiveContext" to decision.collection,
        )
        TrackTalkDebugLog.event(
            "PLAYBACK_CONTEXT_EVIDENCE",
            "source" to event.sourcePackageName,
            "mediaId" to event.mediaId,
            "queueTitleSignal" to evidence.queueTitleSignal,
            "queueTitle" to event.queueTitle,
            "queueSize" to evidence.queueSize,
            "activeQueuePosition" to evidence.activeQueuePosition,
            "currentAlbumPresent" to evidence.currentAlbumPresent,
            "queueAlbums" to evidence.queueAlbums.joinToString(",", prefix = "[", postfix = "]"),
            "queueItemsWithAlbums" to evidence.queueItemsWithAlbums,
            "queueItemsWithTrackNumbers" to evidence.queueItemsWithTrackNumbers,
            "canonicalAlbumQueue" to evidence.hasCanonicalAlbumQueue,
            "shuffleState" to evidence.shuffleState,
        )
        TrackTalkDebugLog.event(
            "PLAYBACK_CONTEXT_DECISION",
            "source" to event.sourcePackageName,
            "mediaId" to event.mediaId,
            "detected" to decision.collection,
            "detectedReason" to decision.reason,
            "final" to decision.collection,
            "stateReset" to decision.stateReset,
            "transition" to decision.transition,
            "naturalTransition" to decision.naturalTransition,
            "sameAlbumTransitions" to decision.sameAlbumNaturalTransitions,
            "mixedTransitions" to decision.mixedNaturalTransitions,
        )
        return decision.collection
    }

    private fun scheduleAnnouncement(
        event: PlaybackEvent,
        collection: PlaybackCollection,
        sessionKey: String?,
        sessionRefresh: Boolean,
        isNewPlaybackOccurrence: Boolean,
        isNewRepeatCycle: Boolean,
        eventSequenceNumber: Long = 0L,
        logicalSessionGeneration: Long = 0L,
        routeRetryAttempt: Int = 0,
        routeResolutionOverride: AudioRouteResolution? = null,
        preparedText: String? = null,
        preparedVoicePlan: PreparedTtsVoicePlan? = null,
    ) {
        val settings = effectiveSettings()
        val app = appSettingsFor(event)
        val connectedDevices = _connectedAudioDevices.value
        if (connectedDevices.isNotEmpty() && connectedDevices.none { device ->
                audioDeviceSettings.value[device.key]?.enabled != false
            }
        ) {
            cancelPendingAnnouncement()
            return
        }
        val routeResolution = routeResolutionOverride ?: outputDetector.resolveRoute(routeRetryAttempt)
        // A corroborated Bluetooth conflict is deferred below. Treat it as
        // eligible while validating ordinary settings so it is not discarded
        // as a permanent speaker false negative before the bounded recheck.
        val externalOutput = routeResolution.isExternal || routeResolution.isTransitioning
        val decision = AnnouncementPolicy.decide(
            event = event,
            userSettings = settings,
            appSettings = app,
            effectiveEnabled = settings.enabled || screenAutoActivated || deviceAutoActivated,
            externalAudioOutput = externalOutput,
            collectionOverride = collection,
            preparedText = preparedText,
        )
        if (!decision.shouldAnnounce || decision.text == null) {
            logInitialAnnouncementDecision(
                event = event,
                collection = collection,
                settings = settings,
                app = app,
                decision = decision,
                routeResolution = routeResolution,
                routeRetryAttempt = routeRetryAttempt,
            )
            cancelPendingAnnouncement()
            return
        }

        // Media apps often emit a transient PAUSED/metadata-cleared event
        // while audio focus moves to TTS. Do not schedule the same track again
        // while its announcement is still being spoken.
        if (activeSpeechTrack?.let { AnnouncementTrackMatcher.matches(it, event, requireSameSource = false) } == true) {
            TrackTalkDebugLog.event(
                "duplicate_suppressed",
                "reason" to "active_speech",
                "mediaId" to event.mediaId,
                "sameLogicalTrack" to true,
                "sameLogicalSession" to (lastAnnouncedSessionKey != null && lastAnnouncedSessionKey == sessionKey),
                "samePackage" to (activeSpeechTrack?.sourcePackageName == event.sourcePackageName),
                "historyPresent" to (lastAnnouncedTrack != null),
                "eventSequenceNumber" to eventSequenceNumber,
                "logicalSessionGeneration" to logicalSessionGeneration,
            )
            return
        }

        // MediaSession can emit several callbacks for one playback start:
        // queue description, canonical metadata, focus-induced pause/resume,
        // and a refreshed media ID. Only the current playback occurrence may
        // suppress this event. A previous occurrence of the same song (A -> B
        // -> A) must remain announceable.
        if (
            lastAnnouncedTrack?.let {
                AnnouncementTrackMatcher.matchesForDuplicateSuppression(
                    expected = it,
                    current = event,
                    requireSameSource = true,
                )
            } == true &&
            !isNewPlaybackOccurrence &&
            !(isNewRepeatCycle && settings.allowRepeatAnnouncements)
        ) {
            TrackTalkDebugLog.event(
                "duplicate_suppressed",
                "reason" to when {
                    sessionRefresh -> "SAME_LOGICAL_TRACK_SESSION_REFRESH"
                    isNewRepeatCycle -> "REPEAT_CYCLE_SETTING_OFF"
                    else -> "SAME_PLAYBACK_OCCURRENCE"
                },
                "mediaId" to event.mediaId,
                "lastSessionKey" to lastAnnouncedSessionKey,
                "sessionKey" to sessionKey,
                "sameLogicalTrack" to true,
                "sameLogicalSession" to (lastAnnouncedSessionKey != null && lastAnnouncedSessionKey == sessionKey),
                "samePackage" to (lastAnnouncedTrack?.sourcePackageName == event.sourcePackageName),
                "historyPresent" to true,
                "eventSequenceNumber" to eventSequenceNumber,
                "logicalSessionGeneration" to logicalSessionGeneration,
            )
            return
        }

        val fingerprint = TrackFingerprint.announcement(event)
        // Metadata and queue callbacks for one track can arrive while the
        // first announcement is still waiting for its settlement delay. The
        // full fingerprint may change when album/track-number metadata is
        // filled in, so use the track identity as the pending key too. This
        // prevents a metadata update from cancelling and rescheduling the
        // same announcement before the first one has spoken.
        if (pendingAnnouncementEvent?.let { AnnouncementTrackMatcher.matches(it, event, requireSameSource = false) } == true) {
            val previousPending = pendingAnnouncementEvent
            pendingAnnouncementEvent = event
            val added = newlyAvailableComponents(previousPending, event, decision)
            TrackTalkDebugLog.event(
                "metadata_enriched",
                "sameTrack" to true,
                "mediaId" to event.mediaId,
                "added" to added.joinToString(",", prefix = "[", postfix = "]"),
                "eventSequenceNumber" to eventSequenceNumber,
                "logicalSessionGeneration" to logicalSessionGeneration,
            )
            logAnnouncementComponents(
                event = event,
                decision = decision,
                action = if (needsMetadataSettlement(event, decision)) "WAIT_FOR_METADATA" else "READY",
            )
            return
        }
        if (fingerprint in pendingFingerprints) {
            TrackTalkDebugLog.event(
                "duplicate_suppressed",
                "reason" to "pending_fingerprint",
                "mediaId" to event.mediaId,
                "sameLogicalTrack" to true,
                "sameLogicalSession" to (lastAnnouncedSessionKey != null && lastAnnouncedSessionKey == sessionKey),
                "samePackage" to (pendingAnnouncementEvent?.sourcePackageName == event.sourcePackageName),
                "historyPresent" to (lastAnnouncedTrack != null),
                "eventSequenceNumber" to eventSequenceNumber,
                "logicalSessionGeneration" to logicalSessionGeneration,
            )
            return
        }
        if (!duplicateSuppressor.shouldAnnounce(
            event = event,
            allowRepeat = settings.allowRepeatAnnouncements,
            now = System.currentTimeMillis(),
            announcementText = decision.text,
            isNewPlaybackOccurrence = isNewPlaybackOccurrence,
            isNewRepeatCycle = isNewRepeatCycle,
        )
        ) {
            TrackTalkDebugLog.event(
                "duplicate_suppressed",
                "reason" to "current_playback_occurrence",
                "mediaId" to event.mediaId,
                "sameLogicalTrack" to (lastAnnouncedTrack?.let {
                    AnnouncementTrackMatcher.matchesForDuplicateSuppression(it, event, requireSameSource = true)
                } == true),
                "sameLogicalSession" to (lastAnnouncedSessionKey != null && lastAnnouncedSessionKey == sessionKey),
                "samePackage" to (lastAnnouncedTrack?.sourcePackageName == event.sourcePackageName),
                "historyPresent" to (lastAnnouncedTrack != null),
                "eventSequenceNumber" to eventSequenceNumber,
                "logicalSessionGeneration" to logicalSessionGeneration,
            )
            cancelPendingAnnouncement()
            return
        }

        if (routeResolution.isTransitioning && !settings.outputPolicy.allows(externalAudioOutput = false)) {
            deferAnnouncementForRouteResolution(
                event = event,
                collection = collection,
                sessionKey = sessionKey,
                sessionRefresh = sessionRefresh,
                isNewPlaybackOccurrence = isNewPlaybackOccurrence,
                isNewRepeatCycle = isNewRepeatCycle,
                eventSequenceNumber = eventSequenceNumber,
                logicalSessionGeneration = logicalSessionGeneration,
                routeRetryAttempt = routeRetryAttempt,
                routeResolution = routeResolution,
            )
            return
        }

        val transitionAtElapsedNanos = lastActualTrackChangeAtElapsedNanos
        val eligibilityResolvedAtNanos = SystemClock.elapsedRealtimeNanos()
        cancelPendingAnnouncement()
        pendingAnnouncementEvent = event
        val pendingToken = ++pendingAnnouncementToken
        val metadataSettlementDelay = when {
            decision.formatOptions.readTrackNumber && AlbumTrackNumberResolver.resolve(event) == null ->
                EXTERNAL_METADATA_SETTLE_DELAY_MS
            needsMetadataSettlement(event, decision) -> METADATA_SETTLE_DELAY_MS
            else -> 0L
        }
        val scheduledDelayMs = maxOf(decision.delayMs, metadataSettlementDelay)
        val preparationDelayMs = AnnouncementAudioTiming.preparationDelayMs(
            scheduledDelayMs = scheduledDelayMs,
            decisionDelayMs = decision.delayMs,
        )
        pendingFingerprints += fingerprint
        if (preparationDelayMs == 0L) {
            // Immediate mode has already passed route, entitlement, duplicate,
            // and current-track eligibility. Commit the batch before PAUSE so
            // its resulting callback cannot cancel this announcement, then
            // request audio protection without another coroutine dispatch.
            preparedAnnouncement = PreparedAnnouncement(fingerprint, pendingToken)
            if (!prepareAnnouncementAudio(settings, event, sessionKey, transitionAtElapsedNanos)) {
                if (preparedAnnouncement?.fingerprint == fingerprint) releasePreparedAnnouncement()
                pendingFingerprints -= fingerprint
                if (pendingAnnouncementToken == pendingToken) pendingAnnouncementEvent = null
                return
            }
            if (!isPendingAnnouncement(pendingToken, fingerprint)) {
                finishAnnouncementAudio()
                return
            }
        }
        logInitialAnnouncementDecision(
            event = event,
            collection = collection,
            settings = settings,
            app = app,
            decision = decision,
            routeResolution = routeResolution,
            routeRetryAttempt = routeRetryAttempt,
        )
        logAnnouncementComponents(
            event = event,
            decision = decision,
            action = if (needsMetadataSettlement(event, decision)) "WAIT_FOR_METADATA" else "READY",
        )
        logDuplicateStateRead(
            event = event,
            sessionKey = sessionKey,
            eventSequenceNumber = eventSequenceNumber,
            logicalSessionGeneration = logicalSessionGeneration,
        )
        TrackTalkDebugLog.event(
            "TRANSITION_TIMING",
            "stage" to "T2_ELIGIBILITY_RESOLVED",
            "elapsedRealtimeNanos" to eligibilityResolvedAtNanos,
            "transitionElapsedMs" to transitionAtElapsedNanos.elapsedMillisUntil(eligibilityResolvedAtNanos),
            "prefetched" to lastActualTrackChangeUsedPrefetch,
            "preparedTextReused" to (preparedText != null),
            "eventSequenceNumber" to eventSequenceNumber,
            "mediaId" to event.mediaId,
        )
        TrackTalkDebugLog.event(
            "announcement_scheduled",
            "mediaId" to event.mediaId,
            "title" to event.title,
            "decisionDelayMs" to decision.delayMs,
            "scheduledDelayMs" to scheduledDelayMs,
            "preparationDelayMs" to preparationDelayMs,
            "observedAt" to event.observedAt,
            "eventSequenceNumber" to eventSequenceNumber,
            "logicalSessionGeneration" to logicalSessionGeneration,
        )
        pendingJob = scope.launch {
            try {
                if (preparationDelayMs > 0L) delay(preparationDelayMs)
                if (!isPendingAnnouncement(pendingToken, fingerprint)) return@launch
                if (preparedAnnouncement == null) {
                    // Mark the batch before requesting audio focus or pausing
                    // the player. Those calls synchronously/asynchronously
                    // produce a PAUSED callback; without this marker that
                    // callback cancels the very job that caused it.
                    preparedAnnouncement = PreparedAnnouncement(fingerprint, pendingToken)
                    if (!prepareAnnouncementAudio(settings, event, sessionKey, transitionAtElapsedNanos)) {
                        if (preparedAnnouncement?.fingerprint == fingerprint) {
                            releasePreparedAnnouncement()
                        }
                        return@launch
                    }
                    if (!isPendingAnnouncement(pendingToken, fingerprint)) {
                        finishAnnouncementAudio()
                        return@launch
                    }
                }
                val remainingDelayMs = scheduledDelayMs - preparationDelayMs
                if (remainingDelayMs > 0L) delay(remainingDelayMs)
                if (!isPendingAnnouncement(pendingToken, fingerprint)) return@launch
                val current = _mediaState.value.currentEvent
                val currentMatches = current != null && AnnouncementTrackMatcher.matches(event, current)
                val committedCurrent = currentMatches && (
                    current?.isPlaying == true ||
                        preparedAnnouncement?.fingerprint == fingerprint
                    )
                if (!committedCurrent) return@launch

                // Settings and the audio route can change while the metadata
                // settlement/announcement delay is running. Re-read both at
                // the last possible moment so enabling speaker suppression or
                // switching from Bluetooth to the phone speaker cannot leak
                // a queued announcement.
                val currentEvent = current ?: return@launch
                val routeResolution = resolveRouteBeforeSpeech(
                    settings = effectiveSettings(),
                    event = currentEvent,
                    pendingToken = pendingToken,
                    fingerprint = fingerprint,
                ) ?: return@launch
                val finalEvent = _mediaState.value.currentEvent
                if (!isRouteRetryStillCurrent(currentEvent, finalEvent)) return@launch
                val eventForSpeech = finalEvent ?: return@launch
                val currentSettings = effectiveSettings()
                val currentApp = appSettingsFor(eventForSpeech)
                val currentDecision = AnnouncementPolicy.decide(
                    event = eventForSpeech,
                    userSettings = currentSettings,
                    appSettings = currentApp,
                    effectiveEnabled = currentSettings.enabled || screenAutoActivated || deviceAutoActivated,
                    externalAudioOutput = routeResolution.isExternal || routeResolution.isTransitioning,
                    collectionOverride = _mediaState.value.currentCollection,
                )
                TrackTalkDebugLog.event(
                    "ANNOUNCEMENT_DECISION",
                    "stage" to "FINAL",
                    "mediaId" to eventForSpeech.mediaId,
                    "shouldAnnounce" to currentDecision.shouldAnnounce,
                    "skipReason" to currentDecision.skipReason,
                    "textAvailable" to (currentDecision.text != null),
                    "routeResolution" to routeResolution.state,
                    "routeReason" to routeResolution.reason,
                )
                if (!currentDecision.shouldAnnounce || currentDecision.text == null) return@launch

                logAnnouncementComponents(
                    event = eventForSpeech,
                    decision = currentDecision,
                    action = "FINAL",
                )

                val announcedAt = System.currentTimeMillis()
                lastAnnouncedTrack = eventForSpeech
                lastAnnouncedAt = announcedAt
                lastAnnouncedSessionKey = sessionKey
                hardPlaybackBoundaryPending = false
                hardPlaybackBoundarySessionKey = null
                hardPlaybackBoundaryAllowsSameSession = false
                duplicateSuppressor.markAnnounced(
                    event = eventForSpeech,
                    now = announcedAt,
                    announcementText = currentDecision.text,
                )
                TrackTalkDebugLog.event(
                    "DUPLICATE_STATE_WRITE",
                    "stage" to "ACCEPTED",
                    "historyPresent" to true,
                    "logicalTrack" to eventForSpeech.logicalIdentity(),
                    "announcedAt" to announcedAt,
                    "eventSequenceNumber" to eventSequenceNumber,
                    "logicalSessionGeneration" to logicalSessionGeneration,
                )
                persistenceScope.launch {
                    persistenceMutex.withLock {
                        repository.savePersistedAnnouncement(eventForSpeech.toPersistedAnnouncement(announcedAt))
                    }
                }
                val transitionAtMs = lastActualTrackChangeAtMs
                val ttsRequestedAtNanos = SystemClock.elapsedRealtimeNanos()
                TrackTalkDebugLog.event(
                    "TTS_REQUESTED",
                    "mediaId" to eventForSpeech.mediaId,
                    "title" to eventForSpeech.title,
                    "artist" to eventForSpeech.artist,
                    "album" to eventForSpeech.album,
                    "observedAt" to eventForSpeech.observedAt,
                    "elapsedSinceObservedMs" to (announcedAt - eventForSpeech.observedAt),
                    "transitionToTtsRequestMs" to transitionAtMs?.let { announcedAt - it },
                    "elapsedRealtimeNanos" to ttsRequestedAtNanos,
                    "transitionToTtsRequestMonotonicMs" to transitionAtElapsedNanos.elapsedMillisUntil(ttsRequestedAtNanos),
                    "collection" to currentDecision.collection,
                    "mode" to currentDecision.mode,
                    "eventSequenceNumber" to eventSequenceNumber,
                    "logicalSessionGeneration" to logicalSessionGeneration,
                )
                speakPrepared(
                    currentDecision.text,
                    eventForSpeech,
                    fingerprint,
                    pendingToken,
                    transitionAtMs,
                    transitionAtElapsedNanos,
                    preparedVoicePlan,
                )
                if (transitionAtMs != null) lastActualTrackChangeAtMs = null
                if (transitionAtElapsedNanos != null) {
                    lastActualTrackChangeAtElapsedNanos = null
                    lastActualTrackChangeUsedPrefetch = false
                }
            } finally {
                if (pendingAnnouncementToken == pendingToken) {
                    pendingFingerprints -= fingerprint
                    pendingAnnouncementEvent = null
                    if (preparedAnnouncement?.fingerprint == fingerprint) {
                        releasePreparedAnnouncement()
                    }
                }
            }
        }
    }

    /**
     * A Samsung route callback can temporarily say "speaker" while active
     * Bluetooth evidence still corroborates media playback on a headset. Keep
     * the existing pending-candidate state alive for one bounded recheck
     * instead of permanently dropping the new track as SPEAKER_OUTPUT.
     */
    private fun deferAnnouncementForRouteResolution(
        event: PlaybackEvent,
        collection: PlaybackCollection,
        sessionKey: String?,
        sessionRefresh: Boolean,
        isNewPlaybackOccurrence: Boolean,
        isNewRepeatCycle: Boolean,
        eventSequenceNumber: Long,
        logicalSessionGeneration: Long,
        routeRetryAttempt: Int,
        routeResolution: AudioRouteResolution,
    ) {
        val fingerprint = TrackFingerprint.announcement(event)
        cancelPendingAnnouncement()
        pendingAnnouncementEvent = event
        val pendingToken = ++pendingAnnouncementToken
        pendingFingerprints += fingerprint
        TrackTalkDebugLog.event(
            "ROUTE_RESOLUTION_DEFERRED",
            "mediaId" to event.mediaId,
            "title" to event.title,
            "resolution" to routeResolution.state,
            "reason" to routeResolution.reason,
            "retryAttempt" to routeRetryAttempt,
            "recheckDelayMs" to ROUTE_CONFLICT_RECHECK_DELAY_MS,
            "eventSequenceNumber" to eventSequenceNumber,
        )
        pendingJob = scope.launch {
            try {
                delay(ROUTE_CONFLICT_RECHECK_DELAY_MS)
                if (!isPendingAnnouncement(pendingToken, fingerprint)) return@launch

                val pendingEvent = pendingAnnouncementEvent ?: event
                val currentEvent = _mediaState.value.currentEvent
                val stillCurrent = isRouteRetryStillCurrent(pendingEvent, currentEvent) &&
                    currentEvent?.isPlaying == true
                if (!stillCurrent) {
                    TrackTalkDebugLog.event(
                        "ROUTE_RESOLUTION_DROPPED",
                        "mediaId" to pendingEvent.mediaId,
                        "reason" to "TRACK_CHANGED_OR_PAUSED",
                        "eventSequenceNumber" to eventSequenceNumber,
                    )
                    return@launch
                }

                val retryEvent = currentEvent ?: return@launch
                val retryAttempt = routeRetryAttempt + 1
                val retryResolution = outputDetector.resolveRoute(retryAttempt)
                TrackTalkDebugLog.event(
                    "ROUTE_RESOLUTION_RECHECK",
                    "mediaId" to retryEvent.mediaId,
                    "title" to retryEvent.title,
                    "resolution" to retryResolution.state,
                    "reason" to retryResolution.reason,
                    "retryAttempt" to retryAttempt,
                    "stillCurrent" to true,
                    "eventSequenceNumber" to eventSequenceNumber,
                )
                if (!consumePendingRouteResolution(pendingToken, fingerprint)) return@launch

                scheduleAnnouncement(
                    event = retryEvent,
                    collection = _mediaState.value.currentCollection.takeIf { it != PlaybackCollection.UNKNOWN }
                        ?: collection,
                    sessionKey = selectedSessionKey ?: sessionKey,
                    sessionRefresh = sessionRefresh,
                    isNewPlaybackOccurrence = isNewPlaybackOccurrence,
                    isNewRepeatCycle = isNewRepeatCycle,
                    eventSequenceNumber = eventSequenceNumber,
                    logicalSessionGeneration = logicalSessionGeneration,
                    routeRetryAttempt = retryAttempt,
                    routeResolutionOverride = retryResolution,
                )
            } finally {
                if (pendingAnnouncementToken == pendingToken) {
                    pendingFingerprints -= fingerprint
                    pendingAnnouncementEvent = null
                    pendingJob = null
                }
            }
        }
    }

    /** Releases a deferred route candidate before it is re-scheduled normally. */
    private fun consumePendingRouteResolution(token: Long, fingerprint: String): Boolean {
        if (!isPendingAnnouncement(token, fingerprint)) return false
        pendingAnnouncementToken += 1
        pendingJob = null
        pendingAnnouncementEvent = null
        pendingFingerprints -= fingerprint
        return true
    }

    /**
     * The route may change while a normal metadata/timing delay is pending.
     * Recheck a corroborated conflict once immediately before speech so it
     * cannot turn a legitimate Bluetooth announcement into a late duplicate
     * or a speaker leak.
     */
    private suspend fun resolveRouteBeforeSpeech(
        settings: UserSettings,
        event: PlaybackEvent,
        pendingToken: Long,
        fingerprint: String,
    ): AudioRouteResolution? {
        val initialResolution = outputDetector.resolveRoute()
        if (
            !initialResolution.isTransitioning ||
            settings.outputPolicy.allows(externalAudioOutput = false)
        ) {
            return initialResolution
        }

        TrackTalkDebugLog.event(
            "ROUTE_RESOLUTION_DEFERRED",
            "stage" to "FINAL",
            "mediaId" to event.mediaId,
            "resolution" to initialResolution.state,
            "reason" to initialResolution.reason,
            "retryAttempt" to 0,
            "recheckDelayMs" to ROUTE_CONFLICT_RECHECK_DELAY_MS,
        )
        delay(ROUTE_CONFLICT_RECHECK_DELAY_MS)
        if (!isPendingAnnouncement(pendingToken, fingerprint)) return null

        val currentEvent = _mediaState.value.currentEvent
        if (!isRouteRetryStillCurrent(event, currentEvent)) {
            TrackTalkDebugLog.event(
                "ROUTE_RESOLUTION_DROPPED",
                "stage" to "FINAL",
                "mediaId" to event.mediaId,
                "reason" to "TRACK_CHANGED",
            )
            return null
        }

        val retryResolution = outputDetector.resolveRoute(retryAttempt = 1)
        TrackTalkDebugLog.event(
            "ROUTE_RESOLUTION_RECHECK",
            "stage" to "FINAL",
            "mediaId" to event.mediaId,
            "resolution" to retryResolution.state,
            "reason" to retryResolution.reason,
            "retryAttempt" to 1,
            "stillCurrent" to true,
        )
        return retryResolution
    }

    /**
     * Resolve app enablement synchronously for the current media event. A new
     * app can emit its first MediaSession callback before ensureApp() finishes;
     * using a category-based fallback here prevents that race from bypassing
     * the default-off policy at runtime.
     */
    private fun appSettingsFor(event: PlaybackEvent): AppSettings =
        appSettings.value[event.sourcePackageName]
            ?: AppSettings(
                packageName = event.sourcePackageName,
                appName = event.sourceAppName,
                enabled = AppGuideEnablementPolicy.defaultEnabled(
                    event.sourcePackageName,
                    event.sourceAppName,
                ),
            )

    private fun logInitialAnnouncementDecision(
        event: PlaybackEvent,
        collection: PlaybackCollection,
        settings: UserSettings,
        app: AppSettings,
        decision: com.trackvoice.announcement.AnnouncementDecision,
        routeResolution: AudioRouteResolution,
        routeRetryAttempt: Int,
    ) {
        val configuration = AnnouncementPolicy.resolveConfiguration(settings, collection)
        TrackTalkDebugLog.event(
            "CONTENT_TYPE",
            "mediaId" to event.mediaId,
            "resolved" to collection,
            "source" to configuration.source,
        )
        TrackTalkDebugLog.event(
            "ANNOUNCEMENT_CONFIG",
            "source" to configuration.source,
            "selected" to decision.formatOptions.orderedFields?.joinToString(","),
            "legacyOrder" to decision.formatOptions.announcementOrder,
            "mode" to decision.mode,
        )
        TrackTalkDebugLog.event(
            "announcement_policy",
            "mediaId" to event.mediaId,
            "title" to event.title,
            "collection" to decision.collection,
            "mode" to decision.mode,
            "appEnabled" to app.enabled,
            "appOverride" to app.enabledOverride,
            "appDefault" to AppGuideEnablementPolicy.defaultEnabled(
                event.sourcePackageName,
                event.sourceAppName,
            ),
            "shouldAnnounce" to decision.shouldAnnounce,
            "skipReason" to decision.skipReason,
            "delayMs" to decision.delayMs,
            "routeResolution" to routeResolution.state,
            "routeReason" to routeResolution.reason,
            "routeRetryAttempt" to routeRetryAttempt,
        )
        TrackTalkDebugLog.event(
            "ANNOUNCEMENT_DECISION",
            "stage" to "INITIAL",
            "mediaId" to event.mediaId,
            "shouldAnnounce" to decision.shouldAnnounce,
            "skipReason" to decision.skipReason,
            "textAvailable" to (decision.text != null),
            "routeResolution" to routeResolution.state,
        )
    }

    private fun logDuplicateStateRead(
        event: PlaybackEvent,
        sessionKey: String?,
        eventSequenceNumber: Long,
        logicalSessionGeneration: Long,
    ) {
        TrackTalkDebugLog.event(
            "DUPLICATE_STATE_READ",
            "stage" to "DECISION",
            "historyPresent" to (lastAnnouncedTrack != null),
            "logicalTrack" to event.logicalIdentity(),
            "sameLogicalTrack" to (lastAnnouncedTrack?.let {
                AnnouncementTrackMatcher.matchesForDuplicateSuppression(it, event, requireSameSource = true)
            } == true),
            "sameLogicalSession" to (lastAnnouncedSessionKey != null && lastAnnouncedSessionKey == sessionKey),
            "samePackage" to (lastAnnouncedTrack?.sourcePackageName == event.sourcePackageName),
            "eventSequenceNumber" to eventSequenceNumber,
            "logicalSessionGeneration" to logicalSessionGeneration,
        )
    }

    private fun needsMetadataSettlement(
        event: PlaybackEvent,
        decision: com.trackvoice.announcement.AnnouncementDecision,
    ): Boolean = missingAnnouncementComponents(event, decision).isNotEmpty()

    private fun logAnnouncementComponents(
        event: PlaybackEvent,
        decision: com.trackvoice.announcement.AnnouncementDecision,
        action: String,
    ) {
        val required = requiredAnnouncementComponents(event, decision)
        val available = required.filter { componentAvailable(event, decision, it) }
        val missing = required.filterNot(available::contains)
        TrackTalkDebugLog.event(
            "METADATA_AVAILABILITY",
            "trackIdentity" to TrackFingerprint.announcementBase(event),
            "mediaId" to event.mediaId,
            "collection" to decision.collection,
            "mode" to decision.mode,
            "selected" to decision.formatOptions.orderedFields?.joinToString(","),
            "required" to required.joinToString(",", prefix = "[", postfix = "]"),
            "available" to available.joinToString(",", prefix = "[", postfix = "]"),
            "missing" to missing.joinToString(",", prefix = "[", postfix = "]"),
            "trackNumber" to event.trackNumber,
            "trackNumberSource" to event.trackNumberSource,
            "action" to action,
        )
    }

    private fun missingAnnouncementComponents(
        event: PlaybackEvent,
        decision: com.trackvoice.announcement.AnnouncementDecision,
    ): List<String> {
        val required = requiredAnnouncementComponents(event, decision)
        return required.filterNot { componentAvailable(event, decision, it) }
    }

    private fun newlyAvailableComponents(
        previous: PlaybackEvent?,
        current: PlaybackEvent,
        decision: com.trackvoice.announcement.AnnouncementDecision,
    ): List<String> {
        if (previous == null) return emptyList()
        return requiredAnnouncementComponents(current, decision).filter { component ->
            !componentAvailable(previous, decision, component) && componentAvailable(current, decision, component)
        }
    }

    private fun requiredAnnouncementComponents(
        event: PlaybackEvent,
        decision: com.trackvoice.announcement.AnnouncementDecision,
    ): List<String> {
        val mode = when (decision.mode) {
            AnnouncementMode.SMART -> when (decision.collection) {
                PlaybackCollection.ALBUM -> AnnouncementMode.ALBUM
                PlaybackCollection.PLAYLIST -> AnnouncementMode.PLAYLIST
                PlaybackCollection.ALGORITHMIC,
                PlaybackCollection.UNKNOWN,
                -> AnnouncementMode.TITLE_AND_ARTIST
            }
            else -> decision.mode
        }
        val options = decision.formatOptions
        options.orderedFields?.let { orderedFields ->
            return orderedFields
                .filter { field ->
                    field != AnnouncementReadField.ALBUM || options.shouldReadAlbum(event, decision.collection)
                }
                .map(AnnouncementReadField::name)
        }
        return when (mode) {
            AnnouncementMode.TITLE_ONLY -> listOf("TITLE")
            AnnouncementMode.TITLE_AND_ARTIST -> buildList {
                if (options.readTitle) add("TITLE")
                if (options.readArtist) add("ARTIST")
            }
            AnnouncementMode.ALBUM -> buildList {
                if (options.shouldReadAlbum(event, decision.collection)) add("ALBUM")
                if (options.readTrackNumber) add("TRACK_NUMBER")
                if (options.readTitle) add("TITLE")
                if (options.readArtist) add("ARTIST")
            }
            AnnouncementMode.PLAYLIST -> buildList {
                if (options.readCollection) add("COLLECTION")
                if (options.shouldReadAlbum(event, decision.collection)) add("ALBUM")
                if (options.readTrackNumber) add("TRACK_NUMBER")
                if (options.readTitle) add("TITLE")
                if (options.readArtist) add("ARTIST")
            }
            AnnouncementMode.SMART -> error("resolved above")
        }
    }

    private fun componentAvailable(
        event: PlaybackEvent,
        decision: com.trackvoice.announcement.AnnouncementDecision,
        component: String,
    ): Boolean = when (component) {
        "TITLE" -> event.hasTitle
        "ARTIST" -> !event.artist.isNullOrBlank()
        "ALBUM" -> !event.album.isNullOrBlank()
        "COLLECTION" -> !event.queueTitle.isNullOrBlank() &&
            !PlaybackCollectionResolver.isGenericQueueTitle(event.queueTitle)
        "TRACK_NUMBER" -> AlbumTrackNumberResolver.resolve(event) != null
        else -> false
    }

    /**
     * Applies music ducking/pausing before the delayed announcement job runs.
     * MediaSession callbacks arrive after playback has started, so doing this
     * here removes the additional gap before TTS takes over.
     */
    private fun prepareAnnouncementAudio(
        settings: UserSettings,
        event: PlaybackEvent? = pendingAnnouncementEvent,
        sessionKey: String? = selectedSessionKey,
        transitionAtElapsedNanos: Long? = lastActualTrackChangeAtElapsedNanos,
    ): Boolean {
        val preparationStartedAt = System.currentTimeMillis()
        val plan = AnnouncementPlaybackPlanner.plan(settings)
        if (!plan.pauseBeforeAnnouncement) {
            // If a previous "announce then play" batch was interrupted, do not
            // carry its pause token into a new "play immediately" announcement.
            finishAnnouncementAudio(trigger = PlaybackRestoreTrigger.NON_PAUSE_MODE)
        }
        audioFocusManager.abandon()
        val cycleId = if (plan.pauseBeforeAnnouncement) {
            armPlaybackRestore(event, sessionKey, transitionAtElapsedNanos)
        } else {
            null
        }
        TrackTalkDebugLog.event(
            "audio_protection",
            "mediaId" to event?.mediaId,
            "title" to event?.title,
            "pauseBeforeAnnouncement" to plan.pauseBeforeAnnouncement,
            "pauseToken" to (cycleId != null),
            "announcementCycleId" to cycleId,
            "duck" to plan.shouldDuckMusic,
            "musicAttenuationStrategy" to plan.musicAttenuationStrategy,
            "elapsedSinceObservedMs" to event?.let { preparationStartedAt - it.observedAt },
        )

        // In pause-until-finished mode, a focus request can pause a media app
        // even when its session was too transient to return a resume token.
        val shouldRequestAudioFocus = plan.requestAudioFocus &&
            (!plan.pauseBeforeAnnouncement || cycleId != null)
        if (shouldRequestAudioFocus && !audioFocusManager.request(plan.shouldDuckMusic)) {
            finishAnnouncementAudio(
                trigger = PlaybackRestoreTrigger.AUDIO_FOCUS_FAILED,
                cycleId = cycleId,
            )
            _diagnostics.value = _diagnostics.value.copy(
                lastAnnouncementAt = System.currentTimeMillis(),
                lastAnnouncementSucceeded = false,
                lastAnnouncementMessage = DiagnosticMessage.AUDIO_FOCUS_UNAVAILABLE,
            )
            return false
        }
        TrackTalkDebugLog.event(
            "audio_protection_ready",
            "mediaId" to event?.mediaId,
            "elapsedSinceObservedMs" to event?.let { System.currentTimeMillis() - it.observedAt },
        )
        TrackTalkDebugLog.event(
            "AUDIO_PROTECTION_ACTIVE",
            "mediaId" to event?.mediaId,
            "title" to event?.title,
            "transitionToProtectionMs" to lastActualTrackChangeAtMs?.let { System.currentTimeMillis() - it },
        )
        return true
    }

    private fun refreshPreparedNextTrack(event: PlaybackEvent, sessionKey: String?) {
        val candidate = NextTrackPrefetch.prepare(
            event = event,
            sessionKey = sessionKey,
            preparedAt = System.currentTimeMillis(),
        )
        val previous = preparedNextTrack
        if (candidate == null) {
            if (previous != null) invalidatePreparedNextTrack("NO_USABLE_NEXT_ITEM")
            return
        }
        if (previous != null && NextTrackPrefetch.samePrediction(previous, candidate)) {
            preparedNextAnnouncement = prepareNextAnnouncement(candidate, effectiveSettings())
            return
        }

        if (previous != null) invalidatePreparedNextTrack("QUEUE_OR_NEXT_ITEM_CHANGED")
        preparedNextTrack = candidate
        preparedNextAnnouncement = prepareNextAnnouncement(candidate, effectiveSettings())
        TrackTalkDebugLog.event(
            "NEXT_TRACK_PREPARED",
            "source" to candidate.sourcePackageName,
            "sessionKey" to candidate.sessionKey,
            "queueTitle" to candidate.queueTitle,
            "queueGenerationHash" to candidate.queueGeneration.hashCode(),
            "queueItemCount" to candidate.queueGeneration.count { it == '|' } + 1,
            "queueItemId" to candidate.predicted.queueItemId,
            "title" to candidate.title,
            "artist" to candidate.artist,
            "album" to candidate.album,
            "trackNumber" to candidate.trackNumber,
            "quality" to candidate.quality,
            "available" to candidate.availableFields.joinToString(","),
            "announcementTextPrepared" to (preparedNextAnnouncement != null),
            "voiceResolutionPrepared" to (preparedNextAnnouncement?.voicePlan != null),
            "preparedAt" to candidate.preparedAt,
        )
        if (effectiveSettings().defaultReadFields.contains(AnnouncementReadField.TRACK_NUMBER) &&
            candidate.trackNumber == null &&
            !candidate.title.isNullOrBlank()
        ) {
            requestExternalMetadata(
                query = ExternalTrackMetadataQuery(
                    title = candidate.title,
                    artist = candidate.artist,
                    album = candidate.album,
                    durationMs = null,
                ),
                predicted = candidate,
            )
        }
        // YouTube Music exposes no reliable hard-boundary signal in this
        // session. Keep audio intervention reactive so the current song is
        // never cut short merely to hide a few milliseconds of leakage.
        TrackTalkDebugLog.event(
            "PREARM_SKIPPED",
            "reason" to "NO_SAFE_BOUNDARY_SIGNAL",
            "quality" to candidate.quality,
            "durationMs" to event.duration,
            "positionMs" to event.playbackPosition,
        )
    }

    private fun requestExternalMetadata(event: PlaybackEvent) {
        val settings = effectiveSettings()
        if (!settings.defaultReadFields.contains(AnnouncementReadField.TRACK_NUMBER)) return
        if (AlbumTrackNumberResolver.resolve(event) != null || event.title.isNullOrBlank()) return
        requestExternalMetadata(
            query = ExternalTrackMetadataQuery(
                title = event.title,
                artist = event.artist,
                album = event.album,
                durationMs = event.duration,
            ),
        )
    }

    /**
     * Runs only metadata work. This job never pauses/ducks music and never
     * speaks; a result that arrives after the current utterance is cache-only.
     */
    private fun requestExternalMetadata(
        query: ExternalTrackMetadataQuery,
        predicted: PreparedNextTrack? = null,
    ) {
        if (query.title.isBlank()) return
        val cacheKey = query.cacheKey()
        val now = System.currentTimeMillis()
        val memoryEntry = externalMetadataCache[cacheKey]
        if (memoryEntry != null &&
            ExternalMetadataCachePolicy.isFresh(memoryEntry, now) &&
            memoryEntry.isDurationCompatible(query.durationMs)
        ) {
            applyExternalMetadata(cacheKey, query, memoryEntry, predicted, fromCache = true)
            return
        }
        if (externalMetadataLookupJobs.containsKey(cacheKey)) return

        val job = scope.launch {
            TrackTalkDebugLog.event(
                "EXTERNAL_METADATA_LOOKUP_STARTED",
                "provider" to "ITUNES_SEARCH",
                "cacheKey" to cacheKey,
                "title" to query.title,
                "artist" to query.artist,
                "album" to query.album,
                "durationMs" to query.durationMs,
                "predicted" to (predicted != null),
            )
            val persisted = runCatching {
                withContext(Dispatchers.IO) { repository.readExternalMetadataCache(cacheKey) }
            }.getOrNull()
            val persistedNow = System.currentTimeMillis()
            if (persisted != null &&
                ExternalMetadataCachePolicy.isFresh(persisted, persistedNow) &&
                persisted.isDurationCompatible(query.durationMs)
            ) {
                externalMetadataCache[cacheKey] = persisted
                applyExternalMetadata(cacheKey, query, persisted, predicted, fromCache = true)
                return@launch
            }

            val result = withTimeoutOrNull(EXTERNAL_METADATA_TIMEOUT_MS) {
                runCatching {
                    externalMetadataResolver.resolve(
                        title = query.title,
                        artist = query.artist,
                        album = query.album,
                        durationMs = query.durationMs,
                    )
                }.getOrElse {
                    com.trackvoice.metadata.ExternalTrackMetadataResult(
                        status = ExternalMetadataStatus.FAILED,
                        provider = "ITUNES_SEARCH",
                    )
                }
            } ?: com.trackvoice.metadata.ExternalTrackMetadataResult(
                status = ExternalMetadataStatus.FAILED,
                provider = "ITUNES_SEARCH",
            )
            val entry = result.toCacheEntry(System.currentTimeMillis())
            externalMetadataCache[cacheKey] = entry
            runCatching {
                withContext(Dispatchers.IO) { repository.writeExternalMetadataCache(cacheKey, entry) }
            }
            applyExternalMetadata(cacheKey, query, entry, predicted, fromCache = false)
        }
        externalMetadataLookupJobs[cacheKey] = job
        job.invokeOnCompletion {
            scope.launch {
                if (externalMetadataLookupJobs[cacheKey] === job) {
                    externalMetadataLookupJobs.remove(cacheKey)
                }
            }
        }
    }

    private fun applyExternalMetadata(
        cacheKey: String,
        query: ExternalTrackMetadataQuery,
        entry: ExternalMetadataCacheEntry,
        predicted: PreparedNextTrack?,
        fromCache: Boolean,
    ) {
        val result = entry.toResult()
        if (!entry.isDurationCompatible(query.durationMs)) {
            TrackTalkDebugLog.event(
                "EXTERNAL_METADATA_AMBIGUOUS",
                "provider" to result.provider,
                "reason" to "DURATION_MISMATCH_CACHE",
                "queryDurationMs" to query.durationMs,
                "cachedDurationMs" to entry.durationMs,
                "cacheKey" to cacheKey,
            )
            return
        }
        TrackTalkDebugLog.event(
            if (fromCache) "EXTERNAL_METADATA_CACHE_HIT" else "EXTERNAL_METADATA_LOOKUP_RESULT",
            "provider" to result.provider,
            "status" to result.status,
            "confidence" to result.confidence,
            "trackNumber" to result.metadata?.trackNumber,
            "trackCount" to result.metadata?.trackCount,
            "discNumber" to result.metadata?.discNumber,
            "cacheKey" to cacheKey,
            "predicted" to (predicted != null),
        )
        val metadata = result.metadata
        if (metadata == null || result.status != ExternalMetadataStatus.MATCHED) {
            if (result.status == ExternalMetadataStatus.AMBIGUOUS) {
                TrackTalkDebugLog.event(
                    "EXTERNAL_METADATA_AMBIGUOUS",
                    "provider" to result.provider,
                    "confidence" to result.confidence,
                    "cacheKey" to cacheKey,
                )
            }
            return
        }

        preparedNextTrack = preparedNextTrack?.let { prepared ->
            val preparedKey = ExternalTrackMetadataQuery(
                title = prepared.title.orEmpty(),
                artist = prepared.artist,
                album = prepared.album,
                durationMs = null,
            ).cacheKey()
            if (preparedKey == cacheKey && prepared.trackNumber == null) {
                prepared.copy(
                    trackNumber = metadata.trackNumber,
                    quality = if (
                        !prepared.title.isNullOrBlank() &&
                        !prepared.artist.isNullOrBlank() &&
                        !prepared.album.isNullOrBlank()
                    ) {
                        com.trackvoice.media.NextTrackPrefetchQuality.FULL
                    } else {
                        com.trackvoice.media.NextTrackPrefetchQuality.PARTIAL
                    },
                    availableFields = prepared.availableFields + com.trackvoice.media.NextTrackMetadataField.TRACK_NUMBER,
                )
            } else {
                prepared
            }
        }
        preparedNextTrack?.let { prepared ->
            preparedNextAnnouncement = prepareNextAnnouncement(prepared, effectiveSettings())
        }

        val current = _mediaState.value.currentEvent
        if (current != null && currentMetadataQuery(current).cacheKey() == cacheKey) {
            val enriched = current.withExternalMetadata(metadata)
            _mediaState.value = _mediaState.value.copy(currentEvent = enriched)
            pendingAnnouncementEvent = pendingAnnouncementEvent?.let { pending ->
                if (currentMetadataQuery(pending).cacheKey() == cacheKey) pending.withExternalMetadata(metadata) else pending
            }
            TrackTalkDebugLog.event(
                "TRACK_NUMBER_RESOLUTION",
                "value" to metadata.trackNumber,
                "source" to "EXTERNAL_CATALOG",
                "provider" to metadata.provider,
                "confidence" to metadata.confidence,
                "cacheKey" to cacheKey,
            )
        }
    }

    private fun applyExternalMetadataOverride(event: PlaybackEvent): PlaybackEvent {
        val entry = externalMetadataCache[currentMetadataQuery(event).cacheKey()] ?: return event
        if (!ExternalMetadataCachePolicy.isFresh(entry, System.currentTimeMillis())) return event
        if (!entry.isDurationCompatible(event.duration)) return event
        return entry.toResult().metadata?.let { metadata -> event.withExternalMetadata(metadata) } ?: event
    }

    private fun currentMetadataQuery(event: PlaybackEvent): ExternalTrackMetadataQuery = ExternalTrackMetadataQuery(
        title = event.title.orEmpty(),
        artist = event.artist,
        album = event.album,
        durationMs = event.duration,
    )

    private fun PlaybackEvent.withExternalMetadata(metadata: ExternalTrackMetadata): PlaybackEvent {
        val keepReliablePlayerNumber = trackNumber != null && trackNumberReliable
        return copy(
            title = title ?: metadata.canonicalTitle,
            artist = artist ?: metadata.canonicalArtist,
            album = album ?: metadata.canonicalAlbum,
            trackNumber = if (keepReliablePlayerNumber) trackNumber else metadata.trackNumber,
            totalTracks = totalTracks ?: metadata.trackCount,
            discNumber = discNumber ?: metadata.discNumber,
            trackNumberReliable = true,
            trackNumberSource = if (keepReliablePlayerNumber) trackNumberSource else TrackNumberSource.EXTERNAL_CATALOG,
        )
    }

    private fun prepareNextAnnouncement(
        candidate: PreparedNextTrack,
        settings: UserSettings,
    ): PreparedNextAnnouncement? {
        val prepared = NextTrackAnnouncementPreparation.prepare(candidate, settings) ?: return null
        return prepared.copy(voicePlan = ttsEngine.prepareVoicePlan(prepared.text, settings))
    }

    private fun invalidatePreparedNextTrack(reason: String) {
        val previous = preparedNextTrack ?: return
        preparedNextTrack = null
        preparedNextAnnouncement = null
        TrackTalkDebugLog.event(
            "NEXT_TRACK_INVALIDATED",
            "reason" to reason,
            "source" to previous.sourcePackageName,
            "queueItemId" to previous.predicted.queueItemId,
            "title" to previous.title,
            "preparedAt" to previous.preparedAt,
        )
    }

    private fun cancelPendingAnnouncement() {
        pendingAnnouncementToken += 1
        pendingJob?.cancel()
        pendingJob = null
        pendingAnnouncementEvent = null
        pendingFingerprints.clear()
        releasePreparedAnnouncement()
    }

    private fun isPendingAnnouncement(token: Long, fingerprint: String): Boolean =
        pendingAnnouncementToken == token && fingerprint in pendingFingerprints

    private fun isSameAnnouncementTrack(
        expected: PlaybackEvent,
        current: PlaybackEvent,
        requireSameSource: Boolean = true,
    ): Boolean = AnnouncementTrackMatcher.matches(expected, current, requireSameSource)

    private fun releasePreparedAnnouncement() {
        if (preparedAnnouncement == null) return
        preparedAnnouncement = null
        finishAnnouncementAudio()
    }

    private fun finishAnnouncementAudio(
        transitionAtElapsedNanos: Long? = activeSpeechTransitionAtElapsedNanos,
        trigger: PlaybackRestoreTrigger = PlaybackRestoreTrigger.ANNOUNCEMENT_CANCELLED,
        cycleId: Long? = playbackRestoreObligation.activeCycle()?.id,
        speechGeneration: Long? = null,
    ) {
        val restoreRequestedAtNanos = SystemClock.elapsedRealtimeNanos()
        TrackTalkDebugLog.event(
            "TRANSITION_TIMING",
            "stage" to "T8_PLAYBACK_RESTORE_REQUESTED",
            "announcementCycleId" to cycleId,
            "elapsedRealtimeNanos" to restoreRequestedAtNanos,
            "transitionElapsedMs" to transitionAtElapsedNanos.elapsedMillisUntil(restoreRequestedAtNanos),
            "reason" to trigger,
        )
        TrackTalkDebugLog.event(
            "playback_restore",
            "announcementCycleId" to cycleId,
            "reason" to trigger,
        )
        audioFocusManager.abandon()
        resumePausedPlayback(
            trigger = trigger,
            cycleId = cycleId,
            speechGeneration = speechGeneration,
        )
    }

    private data class PreparedAnnouncement(
        val fingerprint: String,
        val token: Long,
    )

    private data class TransitionObservation(
        val transitionAtMs: Long,
        val transitionAtElapsedNanos: Long,
        val identityResolvedAtElapsedNanos: Long,
        val eventSequenceNumber: Long,
        val eventType: MediaEventType,
        val sessionKey: String?,
        val previousEvent: PlaybackEvent,
        val currentEvent: PlaybackEvent,
        val matchedPrefetch: PreparedNextTrack?,
    )

    private companion object {
        const val METADATA_SETTLE_DELAY_MS = 250L
        const val EXTERNAL_METADATA_SETTLE_DELAY_MS = 450L
        const val EXTERNAL_METADATA_TIMEOUT_MS = 600L
        // One bounded retry is enough to distinguish Samsung's transient
        // stale speaker route from a deliberate phone-speaker selection.
        const val ROUTE_CONFLICT_RECHECK_DELAY_MS = 180L
    }

    private fun effectiveSettings(): UserSettings =
        userSettings.value.forPremiumEntitlement(premiumState.value.isPremium)
}
