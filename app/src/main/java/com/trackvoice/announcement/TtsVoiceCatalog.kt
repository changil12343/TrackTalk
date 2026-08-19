package com.trackvoice.announcement

import java.util.Locale

/**
 * Immutable view of the voices exposed by one initialized TTS provider.
 *
 * Locale grouping is built once when the provider is initialized or explicitly
 * refreshed. Runtime announcements can then resolve a language without asking
 * Android for its complete voice set again.
 */
internal data class TtsVoiceCatalogSnapshot(
    val providerId: String,
    val generation: Long,
    val voices: List<VoiceDescriptor>,
    val supportedLocales: Set<Locale>,
    private val voicesByLanguage: Map<String, List<VoiceDescriptor>>,
) {
    fun voicesFor(locale: Locale): List<VoiceDescriptor> =
        voicesByLanguage[locale.language.lowercase(Locale.ROOT)].orEmpty()

    companion object {
        fun empty(providerId: String): TtsVoiceCatalogSnapshot = TtsVoiceCatalogSnapshot(
            providerId = providerId,
            generation = 0L,
            voices = emptyList(),
            supportedLocales = emptySet(),
            voicesByLanguage = emptyMap(),
        )

        fun create(
            providerId: String,
            generation: Long,
            voices: List<VoiceDescriptor>,
        ): TtsVoiceCatalogSnapshot {
            val stableVoices = VoiceMetadataPolicy.sort(
                voices.distinctBy { voice -> voice.providerId to voice.name },
            )
            val localesByTag = stableVoices.mapNotNull { voice ->
                Locale.forLanguageTag(voice.localeTag).takeUnless { it.language.isBlank() }
            }.associateBy(Locale::toLanguageTag)
            val groupedVoices = stableVoices.groupBy { voice ->
                Locale.forLanguageTag(voice.localeTag).language.lowercase(Locale.ROOT)
            }.filterKeys(String::isNotBlank)
                .mapValues { (_, languageVoices) -> languageVoices.toList() }
            return TtsVoiceCatalogSnapshot(
                providerId = providerId,
                generation = generation,
                voices = stableVoices,
                supportedLocales = localesByTag.values.toSet(),
                voicesByLanguage = groupedVoices,
            )
        }
    }
}

/** Lifecycle-scoped catalog owned by a single [TtsEngine]. */
internal class TtsVoiceCatalog(
    private val provider: TtsProvider,
) {
    @Volatile
    var snapshot: TtsVoiceCatalogSnapshot = TtsVoiceCatalogSnapshot.empty(provider.providerId)
        private set

    fun refresh(): TtsVoiceCatalogSnapshot {
        val nextGeneration = snapshot.generation + 1L
        return TtsVoiceCatalogSnapshot.create(
            providerId = provider.providerId,
            generation = nextGeneration,
            voices = provider.availableVoices(),
        ).also { snapshot = it }
    }

    fun clear() {
        snapshot = TtsVoiceCatalogSnapshot.empty(provider.providerId)
    }
}
