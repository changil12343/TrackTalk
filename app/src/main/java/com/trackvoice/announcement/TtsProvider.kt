package com.trackvoice.announcement

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.trackvoice.data.GenderFilter
import java.util.Locale

/** Provider-neutral description of a voice available to TrackTalk. */
data class VoiceDescriptor(
    val providerId: String,
    val name: String,
    val label: String,
    val localeTag: String,
    val quality: Int,
    val requiresNetwork: Boolean,
    val gender: GenderFilter,
    val latency: Int = VoiceMetadataPolicy.LATENCY_NORMAL,
    val features: Set<String> = emptySet(),
) {
    /** Stable provider-specific identifier used when selecting the voice. */
    val id: String get() = name
}

/** Compatibility name retained for the existing controller and UI surface. */
typealias InstalledVoice = VoiceDescriptor

/**
 * Speech-provider boundary. The production implementation is Android system
 * TTS; a future on-device provider can implement the same boundary without
 * leaking its model/runtime into playback-session code.
 */
interface TtsProvider {
    val providerId: String

    fun initialize(onReady: (Boolean) -> Unit)
    fun setProgressListener(listener: UtteranceProgressListener)
    fun setAudioAttributes(attributes: AudioAttributes): Boolean
    fun availableVoices(): List<VoiceDescriptor>
    fun setLanguage(locale: Locale): Int
    fun setVoice(voiceId: String): Int
    fun setSpeechRate(rate: Float): Int
    fun setPitch(pitch: Float): Int
    fun speak(text: String, queueMode: Int, params: Bundle, utteranceId: String): Int
    fun stop()
    fun shutdown()

    fun supportedLocales(): Set<Locale> = availableVoices()
        .map { Locale.forLanguageTag(it.localeTag) }
        .toSet()
}

/** Adapter for the Android-installed TTS engine. */
class AndroidSystemTtsProvider(context: Context) : TtsProvider {
    private val appContext = context.applicationContext
    private var engine: TextToSpeech? = null
    private var platformVoicesByName: Map<String, android.speech.tts.Voice> = emptyMap()

    override val providerId: String = PROVIDER_ID

    override fun initialize(onReady: (Boolean) -> Unit) {
        if (engine != null) return
        engine = TextToSpeech(appContext) { status ->
            onReady(status == TextToSpeech.SUCCESS)
        }
    }

    override fun setProgressListener(listener: UtteranceProgressListener) {
        engine?.setOnUtteranceProgressListener(listener)
    }

    override fun setAudioAttributes(attributes: AudioAttributes): Boolean {
        val current = engine ?: return false
        return runCatching {
            current.setAudioAttributes(attributes)
            true
        }.getOrDefault(false)
    }

    override fun availableVoices(): List<VoiceDescriptor> {
        val installedVoices: List<android.speech.tts.Voice> = engine?.voices?.toList().orEmpty()
        platformVoicesByName = installedVoices.associateBy { it.name }
        return installedVoices
            .map { voice ->
                val localeTag = voice.locale.toLanguageTag()
                VoiceDescriptor(
                    providerId = providerId,
                    name = voice.name,
                    // Labels are built by the localized UI from structured
                    // metadata; this legacy field is not a user-facing label.
                    label = localeTag,
                    localeTag = localeTag,
                    quality = voice.quality,
                    requiresNetwork = voice.isNetworkConnectionRequired,
                    gender = VoiceGenderClassifier.infer(voice.name, voice.features),
                    latency = voice.latency,
                    features = voice.features,
                )
            }
    }

    override fun setLanguage(locale: Locale): Int =
        engine?.setLanguage(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED

    override fun setVoice(voiceId: String): Int =
        platformVoicesByName[voiceId]?.let { engine?.setVoice(it) }
            ?: TextToSpeech.ERROR

    override fun setSpeechRate(rate: Float): Int =
        engine?.setSpeechRate(rate) ?: TextToSpeech.ERROR

    override fun setPitch(pitch: Float): Int =
        engine?.setPitch(pitch) ?: TextToSpeech.ERROR

    override fun speak(text: String, queueMode: Int, params: Bundle, utteranceId: String): Int =
        engine?.speak(text, queueMode, params, utteranceId) ?: TextToSpeech.ERROR

    override fun stop() {
        runCatching { engine?.stop() }
    }

    override fun shutdown() {
        runCatching { engine?.shutdown() }
        engine = null
        platformVoicesByName = emptyMap()
    }

    companion object {
        const val PROVIDER_ID = "android-system"
    }
}

internal object VoiceGenderClassifier {
    fun infer(name: String, features: Set<String> = emptySet()): GenderFilter {
        val normalized = (name + " " + features.joinToString(" ")).lowercase(Locale.ROOT)
        return when {
            listOf("female", "woman", "fem", "-f-", "_f_").any(normalized::contains) -> GenderFilter.FEMALE
            listOf("male", "man", "masc", "-m-", "_m_").any(normalized::contains) -> GenderFilter.MALE
            googleFemaleVoiceCodes.any(normalized::contains) -> GenderFilter.FEMALE
            googleMaleVoiceCodes.any(normalized::contains) -> GenderFilter.MALE
            else -> GenderFilter.UNSPECIFIED
        }
    }

    private val googleFemaleVoiceCodes = listOf(
        "en-us-language", "en-us-x-iob", "en-us-x-iog", "en-us-x-sfg", "en-us-x-tpc", "en-us-x-tpf",
        "ko-kr-language", "ko-kr-x-ism", "ko-kr-x-kob",
        "en-au-language", "en-au-x-aua", "en-au-x-auc", "en-au-x-afh",
        "en-gb-language", "en-gb-x-gba", "en-gb-x-gbc", "en-gb-x-gbg", "en-gb-x-fis",
        "en-in-language", "en-in-x-ahp", "en-in-x-cxx", "en-in-x-ena", "en-in-x-enc",
    )

    private val googleMaleVoiceCodes = listOf(
        "en-us-x-iol", "en-us-x-iom", "en-us-x-tpd",
        "ko-kr-x-koc", "ko-kr-x-kod",
        "en-au-x-aub", "en-au-x-aud",
        "en-gb-x-gbb", "en-gb-x-gbd", "en-gb-x-rjs",
        "en-in-x-end", "en-in-x-ene",
    )
}
