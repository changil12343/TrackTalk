package com.trackvoice.announcement

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.trackvoice.data.GenderFilter
import com.trackvoice.data.UserSettings
import com.trackvoice.data.VoiceLanguage
import com.trackvoice.diagnostics.DiagnosticMessage
import com.trackvoice.diagnostics.TrackTalkDebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private fun Long?.elapsedMillisUntil(endElapsedNanos: Long): Double? = this?.let { start ->
    (endElapsedNanos - start).coerceAtLeast(0L) / 1_000_000.0
}

enum class TtsStatus {
    INITIALIZING,
    READY,
    ERROR,
    CLOSED,
}

data class TtsState(
    val status: TtsStatus = TtsStatus.INITIALIZING,
    val message: DiagnosticMessage = DiagnosticMessage.TTS_INITIALIZING,
    val fallbackUsed: Boolean = false,
)

internal enum class TtsWarmPathStage {
    READY_TO_SPEAK,
    VOICE_RESOLUTION_STARTED,
    VOICE_RESOLUTION_COMPLETED,
    CONFIGURATION_STARTED,
    SPEAK_CALLED,
    TTS_STARTED,
    TTS_COMPLETED,
}

internal data class TtsWarmPathEvent(
    val requestId: String,
    val utteranceId: String? = null,
    val segmentIndex: Int? = null,
    val stage: TtsWarmPathStage,
    val elapsedRealtimeNanos: Long,
)

internal fun interface TtsWarmPathObserver {
    fun onEvent(event: TtsWarmPathEvent)
}

object TtsLocaleResolver {
    fun choose(requested: Locale, supported: Set<Locale>, systemDefault: Locale): Pair<Locale, Boolean> {
        val exact = supported.firstOrNull { it == requested }
        if (exact != null) return exact to false
        val sameLanguage = supported.firstOrNull { it.language == requested.language }
        if (sameLanguage != null) return sameLanguage to false
        return systemDefault to true
    }
}

data class LanguageSegment(val text: String, val locale: Locale)

object MixedLanguageSegmenter {
    fun segment(text: String, fallbackLocale: Locale = Locale.getDefault()): List<LanguageSegment> {
        if (text.isBlank()) return emptyList()
        val japaneseContext = text.any { it.code in 0x3040..0x30FF }
        val result = mutableListOf<LanguageSegment>()
        val buffer = StringBuilder()
        val leadingNeutral = StringBuilder()
        var currentLocale: Locale? = null

        fun flush() {
            val locale = currentLocale ?: return
            if (buffer.isNotEmpty()) result += LanguageSegment(buffer.toString(), locale)
            buffer.clear()
        }

        text.forEach { character ->
            val locale = localeFor(character, japaneseContext, fallbackLocale)
            if (locale == null) {
                if (currentLocale == null) leadingNeutral.append(character) else buffer.append(character)
            } else if (currentLocale == null) {
                currentLocale = locale
                buffer.append(leadingNeutral).append(character)
                leadingNeutral.clear()
            } else if (currentLocale?.language == locale.language) {
                buffer.append(character)
            } else {
                flush()
                currentLocale = locale
                buffer.append(character)
            }
        }
        if (currentLocale == null) return listOf(LanguageSegment(text, fallbackLocale))
        flush()
        return result
    }

    private fun localeFor(character: Char, japaneseContext: Boolean, fallbackLocale: Locale): Locale? = when {
        character.code in 0xAC00..0xD7A3 || character.code in 0x3131..0x318E -> Locale.KOREAN
        character.code in 0x3040..0x30FF -> Locale.JAPANESE
        character.code in 0x4E00..0x9FFF -> if (japaneseContext) Locale.JAPANESE else Locale.CHINESE
        Character.UnicodeScript.of(character.code) == Character.UnicodeScript.LATIN -> Locale.ENGLISH
        character.isLetter() -> fallbackLocale
        else -> null
    }
}

class TtsEngine internal constructor(
    context: Context,
    private val ttsProvider: TtsProvider,
    private val warmPathObserver: TtsWarmPathObserver?,
) {
    constructor(context: Context) : this(
        context = context,
        ttsProvider = AndroidSystemTtsProvider(context.applicationContext),
        warmPathObserver = null,
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lifecycleStartedAtElapsedNanos = SystemClock.elapsedRealtimeNanos()
    private val lifecycleId = "tts-${System.identityHashCode(this)}-$lifecycleStartedAtElapsedNanos"
    private var providerReadyAtElapsedNanos: Long? = null
    private val _state = MutableStateFlow(TtsState())
    private val _voices = MutableStateFlow<List<InstalledVoice>>(emptyList())
    private val voiceCatalog = TtsVoiceCatalog(ttsProvider)
    private val voiceResolver = TtsVoiceResolver()
    private var configuredVoiceName: String? = null
    private var configuredLanguageTag: String? = null
    private var configuredSpeechRate: Float? = null
    private var configuredPitch: Float? = null

    val state: StateFlow<TtsState> = _state.asStateFlow()
    val voices: StateFlow<List<InstalledVoice>> = _voices.asStateFlow()

    init {
        TrackTalkDebugLog.event(
            "TTS_LIFECYCLE",
            "lifecycleId" to lifecycleId,
            "stage" to "CONSTRUCTED",
            "providerId" to ttsProvider.providerId,
            "elapsedRealtimeNanos" to lifecycleStartedAtElapsedNanos,
        )
        mainHandler.post { ttsProvider.initialize(::onProviderInitialized) }
    }

    private fun onProviderInitialized(success: Boolean) {
        val initializedAtNanos = SystemClock.elapsedRealtimeNanos()
        if (success) providerReadyAtElapsedNanos = initializedAtNanos
        TrackTalkDebugLog.event(
            "tts_init",
            "status" to if (success) TextToSpeech.SUCCESS else TextToSpeech.ERROR,
            "lifecycleId" to lifecycleId,
            "providerId" to ttsProvider.providerId,
            "runtimeEngineId" to ttsProvider.runtimeEngineId,
            "elapsedRealtimeNanos" to initializedAtNanos,
            "initializationMs" to lifecycleStartedAtElapsedNanos.elapsedMillisUntil(initializedAtNanos),
        )
        if (!success) {
            _state.value = TtsState(TtsStatus.ERROR, DiagnosticMessage.TTS_INITIALIZATION_FAILED)
            return
        }
        runCatching { ttsProvider.setProgressListener(progressListener) }
        val audioAttributes = TrackTalkAudioAttributes.speech()
        val audioAttributesApplied = runCatching { ttsProvider.setAudioAttributes(audioAttributes) }
            .getOrDefault(false)
        TrackTalkDebugLog.event(
            "TTS_AUDIO_ATTRIBUTES",
            "applied" to audioAttributesApplied,
            "ttsUsage" to TrackTalkAudioAttributes.USAGE_LABEL,
            "ttsContentType" to TrackTalkAudioAttributes.CONTENT_TYPE_LABEL,
            "volumeControlStream" to audioAttributes.volumeControlStream,
        )
        runCatching { refreshVoices() }
        TrackTalkDebugLog.event(
            "TTS_LIFECYCLE",
            "lifecycleId" to lifecycleId,
            "stage" to "READY",
            "providerId" to ttsProvider.providerId,
            "runtimeEngineId" to ttsProvider.runtimeEngineId,
            "voiceCatalogGeneration" to voiceCatalog.snapshot.generation,
            "voiceCount" to voiceCatalog.snapshot.voices.size,
            "elapsedRealtimeNanos" to SystemClock.elapsedRealtimeNanos(),
        )
        _state.value = TtsState(TtsStatus.READY, DiagnosticMessage.TTS_READY)
    }

    fun speak(
        text: String,
        settings: UserSettings,
        transitionAtMs: Long? = null,
        transitionAtElapsedNanos: Long? = null,
        voiceNameOverride: String? = null,
        announcementCycleId: Long? = null,
        latencyCycleId: String? = null,
        onStarted: (() -> Unit)? = null,
        onFinished: (success: Boolean, message: DiagnosticMessage) -> Unit,
    ) = speakWithVoicePlan(
        text = text,
        settings = settings,
        transitionAtMs = transitionAtMs,
        transitionAtElapsedNanos = transitionAtElapsedNanos,
        voiceNameOverride = voiceNameOverride,
        preparedVoicePlan = null,
        announcementCycleId = announcementCycleId,
        latencyCycleId = latencyCycleId,
        onStarted = onStarted,
        onFinished = onFinished,
    )

    internal fun speakWithVoicePlan(
        text: String,
        settings: UserSettings,
        transitionAtMs: Long? = null,
        transitionAtElapsedNanos: Long? = null,
        voiceNameOverride: String? = null,
        preparedVoicePlan: PreparedTtsVoicePlan?,
        announcementCycleId: Long? = null,
        latencyCycleId: String? = null,
        onStarted: (() -> Unit)? = null,
        onFinished: (success: Boolean, message: DiagnosticMessage) -> Unit,
    ) {
        val requestId = warmPathObserver?.let { "trackvoice-request-${System.nanoTime()}" }
        reportWarmPath(requestId, TtsWarmPathStage.READY_TO_SPEAK)
        val dispatchRequestedAtNanos = SystemClock.elapsedRealtimeNanos()
        TrackTalkDebugLog.event(
            "ANNOUNCEMENT_WAIT",
            "latencyCycleId" to latencyCycleId,
            "waitClass" to "TTS_MAIN_HANDLER_DISPATCH",
            "deliberate" to false,
            "plannedMs" to 0,
            "stage" to "STARTED",
            "elapsedRealtimeNanos" to dispatchRequestedAtNanos,
            "transitionElapsedMs" to transitionAtElapsedNanos.elapsedMillisUntil(dispatchRequestedAtNanos),
        )
        mainHandler.postAtFrontOfQueue speakTask@{
            val dispatchStartedAtNanos = SystemClock.elapsedRealtimeNanos()
            TrackTalkDebugLog.event(
                "ANNOUNCEMENT_WAIT",
                "latencyCycleId" to latencyCycleId,
                "waitClass" to "TTS_MAIN_HANDLER_DISPATCH",
                "deliberate" to false,
                "plannedMs" to 0,
                "actualMs" to dispatchRequestedAtNanos.elapsedMillisUntil(dispatchStartedAtNanos),
                "stage" to "COMPLETED",
                "elapsedRealtimeNanos" to dispatchStartedAtNanos,
                "transitionElapsedMs" to transitionAtElapsedNanos.elapsedMillisUntil(dispatchStartedAtNanos),
            )
            TrackTalkDebugLog.event(
                "TTS_REQUEST_CONTEXT",
                "latencyCycleId" to latencyCycleId,
                "announcementCycleId" to announcementCycleId,
                "lifecycleId" to lifecycleId,
                "providerId" to ttsProvider.providerId,
                "runtimeEngineId" to ttsProvider.runtimeEngineId,
                "providerStatus" to _state.value.status,
                "providerReadyAgeMs" to providerReadyAtElapsedNanos.elapsedMillisUntil(dispatchStartedAtNanos),
                "voiceCatalogGeneration" to voiceCatalog.snapshot.generation,
                "voiceCount" to voiceCatalog.snapshot.voices.size,
                "preparedVoicePlan" to (preparedVoicePlan != null),
            )
            if (text.isBlank()) {
                logLatencyTerminal(
                    latencyCycleId = latencyCycleId,
                    announcementCycleId = announcementCycleId,
                    transitionAtElapsedNanos = transitionAtElapsedNanos,
                    result = DiagnosticMessage.TTS_NOTHING_TO_READ,
                )
                onFinished(false, DiagnosticMessage.TTS_NOTHING_TO_READ)
                return@speakTask
            }
            if (_state.value.status != TtsStatus.READY) {
                logLatencyTerminal(
                    latencyCycleId = latencyCycleId,
                    announcementCycleId = announcementCycleId,
                    transitionAtElapsedNanos = transitionAtElapsedNanos,
                    result = DiagnosticMessage.TTS_NOT_READY,
                )
                onFinished(false, DiagnosticMessage.TTS_NOT_READY)
                return@speakTask
            }

            // QUEUE_FLUSH stops the old audio, but old progress callbacks can still
            // arrive. Complete interrupted batches first so the controller can
            // abandon focus and resume a track that it paused for the old batch.
            val interruptedBatches = pendingResults.values.distinct().filterNot(PendingBatch::completed)
            interruptedBatches.forEach { batch ->
                if (!batch.completed) {
                    batch.completed = true
                    logLatencyTerminal(
                        batch = batch,
                        result = DiagnosticMessage.TTS_INTERRUPTED,
                    )
                    TrackTalkDebugLog.event(
                        "TTS_INTERRUPTED",
                        "announcementCycleId" to batch.announcementCycleId,
                        "elapsedRealtimeNanos" to SystemClock.elapsedRealtimeNanos(),
                    )
                    batch.callback(false, DiagnosticMessage.TTS_INTERRUPTED)
                }
            }
            pendingResults.clear()
            utteranceTransitionAtMs.clear()
            utteranceTransitionAtElapsedNanos.clear()
            utteranceRequestIds.clear()
            utteranceSegmentIndexes.clear()
            // A fresh first segment uses QUEUE_FLUSH, so an extra binder stop
            // is only needed when this request actually interrupted speech.
            if (interruptedBatches.isNotEmpty()) runCatching { ttsProvider.stop() }
            reportWarmPath(requestId, TtsWarmPathStage.CONFIGURATION_STARTED)
            val catalogSnapshot = voiceCatalog.snapshot
            val supportedLocales = catalogSnapshot.supportedLocales
            configureSpeechRate(settings.speechRate.coerceIn(0.5f, 2f))
            configurePitch(settings.pitch.coerceIn(0.5f, 2f))
            val ttsParamVolume = TtsVolumeMapping.parameterFor(settings)
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, ttsParamVolume)
            }
            val fallbackLocale = settings.voiceLanguage.toLocale(text)
            val segments = MixedLanguageSegmenter.segment(text, fallbackLocale).map { segment ->
                val (resolvedLocale, localeFallback) = TtsLocaleResolver.choose(
                    requested = segment.locale,
                    supported = supportedLocales,
                    systemDefault = fallbackLocale,
                )
                PreparedSpeechSegment(
                    text = segment.text,
                    requestedLocale = segment.locale,
                    resolvedLocale = resolvedLocale,
                    localeFallback = localeFallback,
                )
            }
            val batch = PendingBatch(
                remaining = segments.size,
                segments = segments,
                settings = settings,
                voiceNameOverride = voiceNameOverride,
                preparedVoicePlan = preparedVoicePlan,
                params = params,
                requestId = requestId,
                transitionAtMs = transitionAtMs,
                onStarted = onStarted,
                callback = onFinished,
                announcementCycleId = announcementCycleId,
                latencyCycleId = latencyCycleId,
                transitionAtElapsedNanos = transitionAtElapsedNanos,
            )
            TrackTalkDebugLog.event(
                "tts_enqueue",
                "announcementCycleId" to announcementCycleId,
                "latencyCycleId" to latencyCycleId,
                "segments" to segments.size,
                "textLength" to text.length,
                "volume" to ttsParamVolume,
                "voiceLanguage" to settings.voiceLanguage,
                "gender" to settings.genderFilter,
            )
            // Android TextToSpeech voice selection is process-global. Enqueue
            // only the first segment now; configure each later segment after
            // onDone so it cannot reconfigure or stall the first utterance.
            if (!enqueueNextSegment(batch)) return@speakTask
            // Diagnostics are useful but contain binder/device queries. Keep
            // them behind the latency-critical first speak() call.
            logVoiceGainDiagnostic(settings, ttsParamVolume)
        }
    }

    private fun enqueueNextSegment(batch: PendingBatch): Boolean {
        if (batch.completed) return false
        val index = batch.nextSegmentIndex
        val segment = batch.segments.getOrNull(index) ?: return false
        val voiceResolutionStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        val explicitVoiceName = batch.voiceNameOverride
            ?: batch.settings.voiceName.takeIf { batch.settings.voiceLanguage != VoiceLanguage.AUTO }
        TrackTalkDebugLog.event(
            "ANNOUNCEMENT_LATENCY",
            "latencyCycleId" to batch.latencyCycleId,
            "stage" to "T6_VOICE_RESOLUTION_BEGIN",
            "announcementCycleId" to batch.announcementCycleId,
            "elapsedRealtimeNanos" to voiceResolutionStartedAtNanos,
            "transitionElapsedMs" to batch.transitionAtElapsedNanos.elapsedMillisUntil(
                voiceResolutionStartedAtNanos,
            ),
            "segmentIndex" to index,
            "requestedLocale" to segment.requestedLocale.toLanguageTag(),
            "resolvedLocale" to segment.resolvedLocale.toLanguageTag(),
            "explicitVoiceName" to explicitVoiceName,
            "runtimeEngineId" to ttsProvider.runtimeEngineId,
        )
        reportWarmPath(
            requestId = batch.requestId,
            stage = TtsWarmPathStage.VOICE_RESOLUTION_STARTED,
            segmentIndex = index,
        )
        val voiceConfiguration = runCatching {
            configureVoice(
                locale = segment.resolvedLocale,
                settings = batch.settings,
                voiceNameOverride = batch.voiceNameOverride,
                preparedVoicePlan = batch.preparedVoicePlan,
            )
        }.getOrElse {
            VoiceConfiguration(
                usedGenderFallback = true,
                languageResult = configureLanguage(segment.resolvedLocale),
            )
        }
        val segmentFallback = segment.localeFallback ||
            voiceConfiguration.languageResult == TextToSpeech.LANG_MISSING_DATA ||
            voiceConfiguration.languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        batch.localeFallbackUsed = batch.localeFallbackUsed || segmentFallback
        batch.genderFallbackUsed = batch.genderFallbackUsed || voiceConfiguration.usedGenderFallback
        val selectedVoice = voiceConfiguration.selectedVoiceName?.let { selectedName ->
            voiceCatalog.snapshot.voices.firstOrNull { it.name == selectedName }
        }
        val voiceResolutionCompletedAtNanos = SystemClock.elapsedRealtimeNanos()
        TrackTalkDebugLog.event(
            "ANNOUNCEMENT_LATENCY",
            "latencyCycleId" to batch.latencyCycleId,
            "stage" to "T7_VOICE_RESOLUTION_COMPLETE",
            "announcementCycleId" to batch.announcementCycleId,
            "elapsedRealtimeNanos" to voiceResolutionCompletedAtNanos,
            "transitionElapsedMs" to batch.transitionAtElapsedNanos.elapsedMillisUntil(
                voiceResolutionCompletedAtNanos,
            ),
            "resolutionMs" to voiceResolutionStartedAtNanos.elapsedMillisUntil(
                voiceResolutionCompletedAtNanos,
            ),
            "segmentIndex" to index,
            "selectedVoiceName" to voiceConfiguration.selectedVoiceName,
            "selectedVoiceLocale" to selectedVoice?.localeTag,
            "requiresNetwork" to selectedVoice?.requiresNetwork,
            "voiceLatency" to selectedVoice?.latency,
            "voiceQuality" to selectedVoice?.quality,
            "explicitVoiceName" to explicitVoiceName,
            "explicitVoiceMatched" to (explicitVoiceName != null &&
                explicitVoiceName == voiceConfiguration.selectedVoiceName),
            "configurationReused" to voiceConfiguration.configurationReused,
            "localeFallback" to segmentFallback,
            "genderFallback" to voiceConfiguration.usedGenderFallback,
            "runtimeEngineId" to ttsProvider.runtimeEngineId,
        )
        reportWarmPath(
            requestId = batch.requestId,
            stage = TtsWarmPathStage.VOICE_RESOLUTION_COMPLETED,
            segmentIndex = index,
        )
        val utteranceId = "trackvoice-${System.nanoTime()}-$index"
        pendingResults[utteranceId] = batch
        utteranceTransitionAtMs[utteranceId] = batch.transitionAtMs
        utteranceTransitionAtElapsedNanos[utteranceId] = batch.transitionAtElapsedNanos
        batch.requestId?.let { utteranceRequestIds[utteranceId] = it }
        utteranceSegmentIndexes[utteranceId] = index
        batch.nextSegmentIndex += 1
        reportWarmPath(
            requestId = batch.requestId,
            stage = TtsWarmPathStage.SPEAK_CALLED,
            utteranceId = utteranceId,
            segmentIndex = index,
        )
        val speakCalledAtNanos = SystemClock.elapsedRealtimeNanos()
        val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        TrackTalkDebugLog.event(
            "ANNOUNCEMENT_LATENCY",
            "latencyCycleId" to batch.latencyCycleId,
            "stage" to "T8_SPEAK_CALLED",
            "announcementCycleId" to batch.announcementCycleId,
            "elapsedRealtimeNanos" to speakCalledAtNanos,
            "transitionElapsedMs" to batch.transitionAtElapsedNanos.elapsedMillisUntil(speakCalledAtNanos),
            "segmentIndex" to index,
            "utteranceId" to utteranceId,
            "queueMode" to if (queueMode == TextToSpeech.QUEUE_FLUSH) "FLUSH" else "ADD",
            "textLength" to segment.text.length,
            "runtimeEngineId" to ttsProvider.runtimeEngineId,
        )
        val result = runCatching {
            ttsProvider.speak(
                segment.text,
                queueMode,
                batch.params,
                utteranceId,
            )
        }.getOrDefault(TextToSpeech.ERROR)
        if (result == TextToSpeech.ERROR) {
            failBatch(batch, DiagnosticMessage.TTS_SYNTHESIS_FAILED)
            return false
        }
        updateReadyState(batch)
        return true
    }

    private fun updateReadyState(batch: PendingBatch) {
        _state.value = TtsState(
            status = TtsStatus.READY,
            message = when {
                batch.localeFallbackUsed && batch.genderFallbackUsed ->
                    DiagnosticMessage.TTS_FALLBACK_LANGUAGE_AND_GENDER
                batch.localeFallbackUsed -> DiagnosticMessage.TTS_FALLBACK_LANGUAGE
                batch.genderFallbackUsed -> DiagnosticMessage.TTS_FALLBACK_GENDER
                else -> DiagnosticMessage.TTS_READY
            },
            fallbackUsed = batch.localeFallbackUsed || batch.genderFallbackUsed,
        )
    }

    private fun logVoiceGainDiagnostic(settings: UserSettings, ttsParamVolume: Float) {
        val audioManager = appContext.getSystemService(AudioManager::class.java)
        val ttsAttributes = TrackTalkAudioAttributes.speech()
        val ttsVolumeControlStream = ttsAttributes.volumeControlStream
        val plan = AnnouncementPlaybackPlanner.plan(settings)
        val focusMode = when {
            !plan.requestAudioFocus -> "NONE"
            plan.shouldDuckMusic -> "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK"
            else -> "AUDIOFOCUS_GAIN_TRANSIENT"
        }
        val musicAttenuationMethod = when (plan.musicAttenuationStrategy) {
            MusicAttenuationStrategy.NONE -> "NONE"
            MusicAttenuationStrategy.SYSTEM_DUCK -> "AUDIO_FOCUS_AUTO_DUCK"
            MusicAttenuationStrategy.MEDIA_PAUSE -> "MEDIA_PAUSE"
        }
        TrackTalkDebugLog.event(
            "AUDIO_GAIN_STATE",
            "announcementVolumeMode" to settings.announcementVolumeMode,
            "customVoicePercent" to (settings.volume * 100f).toInt(),
            "ttsParamVolume" to ttsParamVolume,
            "ttsUsage" to TrackTalkAudioAttributes.USAGE_LABEL,
            "ttsContentType" to TrackTalkAudioAttributes.CONTENT_TYPE_LABEL,
            "musicUiSetting" to plan.musicTreatment,
            "musicAttenuationMethod" to musicAttenuationMethod,
            "mediaStreamVolumeBefore" to runCatching {
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            }.getOrNull(),
            "mediaStreamMax" to runCatching {
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            }.getOrNull(),
            "ttsVolumeControlStream" to ttsVolumeControlStream,
            "ttsStreamVolume" to runCatching {
                audioManager.getStreamVolume(ttsVolumeControlStream)
            }.getOrNull(),
            "ttsStreamMax" to runCatching {
                audioManager.getStreamMaxVolume(ttsVolumeControlStream)
            }.getOrNull(),
            "audioFocusGain" to focusMode,
            "outputDeviceTypes" to runCatching {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .map { it.type }
                    .distinct()
                    .sorted()
            }.getOrNull(),
        )
        TrackTalkDebugLog.event(
            "VOICE_GAIN_DIAGNOSTIC",
            "announcementVolumeMode" to settings.announcementVolumeMode,
            "customVoicePercent" to (settings.volume * 100f).toInt(),
            "effectiveVoicePercent" to (ttsParamVolume * 100f).toInt(),
            "ttsParamVolume" to ttsParamVolume,
            "musicTreatment" to plan.musicTreatment,
            "musicAttenuationMethod" to musicAttenuationMethod,
            "volumeControlStream" to ttsVolumeControlStream,
            "streamVolume" to runCatching {
                audioManager.getStreamVolume(ttsVolumeControlStream)
            }.getOrNull(),
            "streamMaxVolume" to runCatching {
                audioManager.getStreamMaxVolume(ttsVolumeControlStream)
            }.getOrNull(),
            "musicStreamVolume" to runCatching {
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            }.getOrNull(),
            "musicStreamMaxVolume" to runCatching {
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            }.getOrNull(),
            "ttsUsage" to TrackTalkAudioAttributes.USAGE_LABEL,
            "ttsContentType" to TrackTalkAudioAttributes.CONTENT_TYPE_LABEL,
            "audioFocusMode" to focusMode,
        )
    }

    /** Stops only the active speech batch and reports it as interrupted. */
    fun stopCurrentSpeech() {
        mainHandler.postAtFrontOfQueue {
            val interruptedBatches = pendingResults.values.distinct().filterNot(PendingBatch::completed)
            interruptedBatches.forEach { batch ->
                batch.completed = true
                logLatencyTerminal(batch = batch, result = DiagnosticMessage.TTS_INTERRUPTED)
                TrackTalkDebugLog.event(
                    "TTS_INTERRUPTED",
                    "announcementCycleId" to batch.announcementCycleId,
                    "elapsedRealtimeNanos" to SystemClock.elapsedRealtimeNanos(),
                    "reason" to "EXPLICIT_CONTROLLER_STOP",
                )
            }
            pendingResults.clear()
            utteranceTransitionAtMs.clear()
            utteranceTransitionAtElapsedNanos.clear()
            utteranceRequestIds.clear()
            utteranceSegmentIndexes.clear()
            runCatching { ttsProvider.stop() }
            interruptedBatches.forEach { batch ->
                batch.callback(false, DiagnosticMessage.TTS_INTERRUPTED)
            }
        }
    }

    fun shutdown() {
        mainHandler.post {
            TrackTalkDebugLog.event(
                "TTS_LIFECYCLE",
                "lifecycleId" to lifecycleId,
                "stage" to "SHUTDOWN",
                "providerId" to ttsProvider.providerId,
                "runtimeEngineId" to ttsProvider.runtimeEngineId,
                "elapsedRealtimeNanos" to SystemClock.elapsedRealtimeNanos(),
            )
            runCatching { ttsProvider.stop() }
            runCatching { ttsProvider.shutdown() }
            voiceCatalog.clear()
            voiceResolver.clear()
            configuredVoiceName = null
            configuredLanguageTag = null
            configuredSpeechRate = null
            configuredPitch = null
            providerReadyAtElapsedNanos = null
            _voices.value = emptyList()
            pendingResults.clear()
            utteranceTransitionAtMs.clear()
            utteranceTransitionAtElapsedNanos.clear()
            utteranceRequestIds.clear()
            utteranceSegmentIndexes.clear()
            _state.value = TtsState(TtsStatus.CLOSED, DiagnosticMessage.TTS_CLOSED)
        }
    }

    private data class PreparedSpeechSegment(
        val text: String,
        val requestedLocale: Locale,
        val resolvedLocale: Locale,
        val localeFallback: Boolean,
    )

    private data class PendingBatch(
        var remaining: Int,
        val segments: List<PreparedSpeechSegment>,
        val settings: UserSettings,
        val voiceNameOverride: String?,
        val preparedVoicePlan: PreparedTtsVoicePlan?,
        val params: Bundle,
        val requestId: String?,
        val transitionAtMs: Long?,
        val onStarted: (() -> Unit)?,
        val callback: (Boolean, DiagnosticMessage) -> Unit,
        val announcementCycleId: Long? = null,
        val latencyCycleId: String? = null,
        val transitionAtElapsedNanos: Long? = null,
        var nextSegmentIndex: Int = 0,
        var localeFallbackUsed: Boolean = false,
        var genderFallbackUsed: Boolean = false,
        var completed: Boolean = false,
        var latencyTerminalLogged: Boolean = false,
        val started: AtomicBoolean = AtomicBoolean(false),
    )

    private val pendingResults = mutableMapOf<String, PendingBatch>()
    private val utteranceTransitionAtMs = mutableMapOf<String, Long?>()
    private val utteranceTransitionAtElapsedNanos = mutableMapOf<String, Long?>()
    private val utteranceRequestIds = mutableMapOf<String, String>()
    private val utteranceSegmentIndexes = mutableMapOf<String, Int>()

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val startedAtNanos = SystemClock.elapsedRealtimeNanos()
            val batch = utteranceId?.let(pendingResults::get)
            if (batch != null && batch.started.compareAndSet(false, true)) {
                runCatching { batch.onStarted?.invoke() }
            }
            utteranceId?.let { id ->
                utteranceRequestIds[id]?.let { requestId ->
                    reportWarmPath(
                        requestId = requestId,
                        stage = TtsWarmPathStage.TTS_STARTED,
                        utteranceId = id,
                        segmentIndex = utteranceSegmentIndexes[id],
                        elapsedRealtimeNanos = startedAtNanos,
                    )
                }
            }
            TrackTalkDebugLog.event("tts_start", "utteranceId" to utteranceId)
            TrackTalkDebugLog.event(
                "ANNOUNCEMENT_LATENCY",
                "latencyCycleId" to batch?.latencyCycleId,
                "stage" to "T9_TTS_ON_START",
                "announcementCycleId" to batch?.announcementCycleId,
                "elapsedRealtimeNanos" to startedAtNanos,
                "transitionElapsedMs" to batch?.transitionAtElapsedNanos.elapsedMillisUntil(startedAtNanos),
                "utteranceId" to utteranceId,
                "segmentIndex" to utteranceId?.let(utteranceSegmentIndexes::get),
                "runtimeEngineId" to ttsProvider.runtimeEngineId,
            )
            TrackTalkDebugLog.event(
                "TTS_STARTED",
                "utteranceId" to utteranceId,
                "announcementCycleId" to utteranceId?.let(pendingResults::get)?.announcementCycleId,
                "elapsedRealtimeNanos" to startedAtNanos,
                "transitionToTtsStartMonotonicMs" to utteranceId?.let { id ->
                    utteranceTransitionAtElapsedNanos[id]?.let { startAt ->
                        (startedAtNanos - startAt).coerceAtLeast(0L) / 1_000_000.0
                    }
                },
                "transitionToTtsStartMs" to utteranceId?.let { id ->
                    utteranceTransitionAtMs[id]?.let { startAt -> System.currentTimeMillis() - startAt }
                },
            )
        }

        override fun onDone(utteranceId: String?) {
            if (utteranceId == null) return
            val completedAtNanos = SystemClock.elapsedRealtimeNanos()
            utteranceRequestIds[utteranceId]?.let { requestId ->
                reportWarmPath(
                    requestId = requestId,
                    stage = TtsWarmPathStage.TTS_COMPLETED,
                    utteranceId = utteranceId,
                    segmentIndex = utteranceSegmentIndexes[utteranceId],
                    elapsedRealtimeNanos = completedAtNanos,
                )
            }
            TrackTalkDebugLog.event("tts_segment_done", "utteranceId" to utteranceId)
            mainHandler.post progressTask@{
                val batch = pendingResults.remove(utteranceId) ?: return@progressTask
                utteranceTransitionAtMs.remove(utteranceId)
                utteranceTransitionAtElapsedNanos.remove(utteranceId)
                utteranceRequestIds.remove(utteranceId)
                utteranceSegmentIndexes.remove(utteranceId)
                batch.remaining -= 1
                if (batch.remaining == 0 && !batch.completed) {
                    batch.completed = true
                    logLatencyTerminal(
                        batch = batch,
                        result = DiagnosticMessage.TTS_COMPLETED,
                        elapsedRealtimeNanos = completedAtNanos,
                        utteranceId = utteranceId,
                    )
                    TrackTalkDebugLog.event(
                        "TTS_COMPLETED",
                        "utteranceId" to utteranceId,
                        "announcementCycleId" to batch.announcementCycleId,
                        "elapsedRealtimeNanos" to completedAtNanos,
                    )
                    batch.callback(true, DiagnosticMessage.TTS_COMPLETED)
                } else if (!batch.completed) {
                    enqueueNextSegment(batch)
                }
            }
        }

        @Deprecated("Deprecated in Android API; kept for TTS compatibility")
        override fun onError(utteranceId: String?) {
            if (utteranceId == null) return
            val erroredAtNanos = SystemClock.elapsedRealtimeNanos()
            TrackTalkDebugLog.event(
                "tts_error",
                "utteranceId" to utteranceId,
                "announcementCycleId" to pendingResults[utteranceId]?.announcementCycleId,
                "elapsedRealtimeNanos" to erroredAtNanos,
            )
            mainHandler.post {
                utteranceTransitionAtMs.remove(utteranceId)
                utteranceTransitionAtElapsedNanos.remove(utteranceId)
                utteranceRequestIds.remove(utteranceId)
                utteranceSegmentIndexes.remove(utteranceId)
                pendingResults[utteranceId]?.let {
                    failBatch(it, DiagnosticMessage.TTS_PLAYBACK_ERROR, erroredAtNanos, utteranceId)
                }
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            if (utteranceId == null) return
            val erroredAtNanos = SystemClock.elapsedRealtimeNanos()
            TrackTalkDebugLog.event(
                "tts_error",
                "utteranceId" to utteranceId,
                "announcementCycleId" to pendingResults[utteranceId]?.announcementCycleId,
                "errorCode" to errorCode,
                "elapsedRealtimeNanos" to erroredAtNanos,
            )
            mainHandler.post {
                utteranceTransitionAtMs.remove(utteranceId)
                utteranceTransitionAtElapsedNanos.remove(utteranceId)
                utteranceRequestIds.remove(utteranceId)
                utteranceSegmentIndexes.remove(utteranceId)
                pendingResults[utteranceId]?.let {
                    failBatch(it, DiagnosticMessage.TTS_PLAYBACK_ERROR, erroredAtNanos, utteranceId)
                }
            }
        }
    }

    private fun failBatch(
        batch: PendingBatch,
        message: DiagnosticMessage,
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
        utteranceId: String? = null,
    ) {
        if (batch.completed) return
        batch.completed = true
        logLatencyTerminal(batch, message, elapsedRealtimeNanos, utteranceId)
        TrackTalkDebugLog.event(
            "TTS_FAILED",
            "announcementCycleId" to batch.announcementCycleId,
            "message" to message,
            "elapsedRealtimeNanos" to elapsedRealtimeNanos,
        )
        pendingResults.filterValues { it === batch }.keys.forEach(utteranceTransitionAtMs::remove)
        pendingResults.filterValues { it === batch }.keys.forEach(utteranceTransitionAtElapsedNanos::remove)
        pendingResults.filterValues { it === batch }.keys.forEach(utteranceRequestIds::remove)
        pendingResults.filterValues { it === batch }.keys.forEach(utteranceSegmentIndexes::remove)
        pendingResults.entries.removeAll { it.value === batch }
        runCatching { ttsProvider.stop() }
        runCatching { batch.callback(false, message) }
    }

    private fun logLatencyTerminal(
        batch: PendingBatch,
        result: DiagnosticMessage,
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
        utteranceId: String? = null,
    ) {
        if (batch.latencyTerminalLogged) return
        batch.latencyTerminalLogged = true
        logLatencyTerminal(
            latencyCycleId = batch.latencyCycleId,
            announcementCycleId = batch.announcementCycleId,
            transitionAtElapsedNanos = batch.transitionAtElapsedNanos,
            result = result,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            utteranceId = utteranceId,
        )
    }

    private fun logLatencyTerminal(
        latencyCycleId: String?,
        announcementCycleId: Long?,
        transitionAtElapsedNanos: Long?,
        result: DiagnosticMessage,
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
        utteranceId: String? = null,
    ) {
        TrackTalkDebugLog.event(
            "ANNOUNCEMENT_LATENCY",
            "latencyCycleId" to latencyCycleId,
            "stage" to "T10_TTS_TERMINAL_CALLBACK",
            "announcementCycleId" to announcementCycleId,
            "elapsedRealtimeNanos" to elapsedRealtimeNanos,
            "transitionElapsedMs" to transitionAtElapsedNanos.elapsedMillisUntil(elapsedRealtimeNanos),
            "utteranceId" to utteranceId,
            "result" to result,
            "runtimeEngineId" to ttsProvider.runtimeEngineId,
        )
    }

    private data class VoiceConfiguration(
        val usedGenderFallback: Boolean,
        val languageResult: Int,
        val selectedVoiceName: String? = null,
        val configurationReused: Boolean = false,
    )

    private fun configureVoice(
        locale: Locale,
        settings: UserSettings,
        voiceNameOverride: String? = null,
        preparedVoicePlan: PreparedTtsVoicePlan? = null,
    ): VoiceConfiguration {
        val explicitVoiceName = voiceNameOverride
            ?: settings.voiceName.takeIf { settings.voiceLanguage != VoiceLanguage.AUTO }
        val requestedGender = if (voiceNameOverride != null) GenderFilter.ANY else settings.genderFilter
        val initialSnapshot = voiceCatalog.snapshot
        val resolutionKey = voiceResolver.keyFor(
            snapshot = initialSnapshot,
            locale = locale,
            requestedGender = requestedGender,
            explicitVoiceName = explicitVoiceName,
        )
        var decision = preparedVoicePlan?.decisionFor(resolutionKey)
            ?: voiceResolver.resolve(
                snapshot = initialSnapshot,
                locale = locale,
                requestedGender = requestedGender,
                explicitVoiceName = explicitVoiceName,
            )
        decision.voiceName?.let { desiredVoice ->
            if (configuredVoiceName == desiredVoice) {
                return VoiceConfiguration(
                    usedGenderFallback = decision.usedGenderFallback,
                    languageResult = TextToSpeech.LANG_AVAILABLE,
                    selectedVoiceName = desiredVoice,
                    configurationReused = true,
                )
            }
            val initialResult = runCatching { ttsProvider.setVoice(desiredVoice) }
                .getOrDefault(TextToSpeech.ERROR)
            if (initialResult != TextToSpeech.ERROR) {
                configuredVoiceName = desiredVoice
                configuredLanguageTag = locale.toLanguageTag()
                return VoiceConfiguration(
                    usedGenderFallback = decision.usedGenderFallback,
                    languageResult = TextToSpeech.LANG_AVAILABLE,
                    selectedVoiceName = desiredVoice,
                )
            }

            // A cached platform Voice can disappear after an engine/package
            // update. Refresh once, evict the stale decision and retry a
            // compatible voice before falling back to setLanguage().
            voiceResolver.invalidateVoice(desiredVoice)
            refreshVoices()
            decision = voiceResolver.resolve(
                snapshot = voiceCatalog.snapshot,
                locale = locale,
                requestedGender = requestedGender,
                explicitVoiceName = explicitVoiceName,
            )
            decision.voiceName?.let { refreshedVoice ->
                val retryResult = runCatching { ttsProvider.setVoice(refreshedVoice) }
                    .getOrDefault(TextToSpeech.ERROR)
                if (retryResult != TextToSpeech.ERROR) {
                    configuredVoiceName = refreshedVoice
                    configuredLanguageTag = locale.toLanguageTag()
                    return VoiceConfiguration(
                        usedGenderFallback = decision.usedGenderFallback,
                        languageResult = TextToSpeech.LANG_AVAILABLE,
                        selectedVoiceName = refreshedVoice,
                    )
                }
                voiceResolver.invalidateVoice(refreshedVoice)
            }
        }

        return VoiceConfiguration(
            usedGenderFallback = decision.usedGenderFallback,
            languageResult = configureLanguage(locale),
        )
    }

    private fun configureLanguage(locale: Locale): Int {
        val languageTag = locale.toLanguageTag()
        if (configuredVoiceName == null && configuredLanguageTag == languageTag) {
            return TextToSpeech.LANG_AVAILABLE
        }
        return runCatching { ttsProvider.setLanguage(locale) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            .also { result ->
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    configuredVoiceName = null
                    configuredLanguageTag = languageTag
                }
            }
    }

    private fun configureSpeechRate(rate: Float) {
        if (configuredSpeechRate == rate) return
        val result = runCatching { ttsProvider.setSpeechRate(rate) }.getOrDefault(TextToSpeech.ERROR)
        if (result != TextToSpeech.ERROR) configuredSpeechRate = rate
    }

    private fun configurePitch(pitch: Float) {
        if (configuredPitch == pitch) return
        val result = runCatching { ttsProvider.setPitch(pitch) }.getOrDefault(TextToSpeech.ERROR)
        if (result != TextToSpeech.ERROR) configuredPitch = pitch
    }

    private fun refreshVoices() {
        val snapshot = voiceCatalog.refresh()
        voiceResolver.clear()
        configuredVoiceName = null
        configuredLanguageTag = null
        _voices.value = snapshot.voices
    }

    /**
     * Resolves desired voice identities while the preceding track is playing.
     * This only primes/reads the resolution cache; it never calls setVoice(),
     * setLanguage(), speak(), or any playback/audio API.
     */
    internal fun prepareVoicePlan(text: String, settings: UserSettings): PreparedTtsVoicePlan? {
        if (_state.value.status != TtsStatus.READY || text.isBlank()) return null
        val snapshot = voiceCatalog.snapshot
        if (snapshot.voices.isEmpty()) return null
        val fallbackLocale = settings.voiceLanguage.toLocale(text)
        val explicitVoiceName = settings.voiceName.takeIf { settings.voiceLanguage != VoiceLanguage.AUTO }
        val decisions = MixedLanguageSegmenter.segment(text, fallbackLocale)
            .map { segment ->
                val (locale, _) = TtsLocaleResolver.choose(
                    requested = segment.locale,
                    supported = snapshot.supportedLocales,
                    systemDefault = fallbackLocale,
                )
                val key = voiceResolver.keyFor(
                    snapshot = snapshot,
                    locale = locale,
                    requestedGender = settings.genderFilter,
                    explicitVoiceName = explicitVoiceName,
                )
                PreparedTtsVoiceDecision(
                    key = key,
                    decision = voiceResolver.resolve(
                        snapshot = snapshot,
                        locale = locale,
                        requestedGender = settings.genderFilter,
                        explicitVoiceName = explicitVoiceName,
                    ),
                )
            }
            .distinctBy { it.key }
        return if (decisions.isEmpty()) null else PreparedTtsVoicePlan(decisions)
    }

    private fun reportWarmPath(
        requestId: String?,
        stage: TtsWarmPathStage,
        utteranceId: String? = null,
        segmentIndex: Int? = null,
        elapsedRealtimeNanos: Long? = null,
    ) {
        val observer = warmPathObserver ?: return
        val activeRequestId = requestId ?: return
        runCatching {
            observer.onEvent(
                TtsWarmPathEvent(
                    requestId = activeRequestId,
                    utteranceId = utteranceId,
                    segmentIndex = segmentIndex,
                    stage = stage,
                    elapsedRealtimeNanos = elapsedRealtimeNanos ?: SystemClock.elapsedRealtimeNanos(),
                ),
            )
        }
    }

    private fun VoiceLanguage.toLocale(text: String): Locale = when (this) {
        VoiceLanguage.AUTO -> detectLocale(text)
        VoiceLanguage.SYSTEM -> Locale.getDefault()
        VoiceLanguage.KOREAN -> Locale.KOREAN
        VoiceLanguage.ENGLISH -> Locale.ENGLISH
    }

    private fun detectLocale(text: String): Locale {
        val letters = text.filter(Char::isLetter)
        if (letters.isEmpty()) return Locale.getDefault()
        val hangul = letters.count { it.code in 0xAC00..0xD7A3 || it.code in 0x3131..0x318E }
        return if (hangul * 2 >= letters.length) Locale.KOREAN else Locale.ENGLISH
    }
}
