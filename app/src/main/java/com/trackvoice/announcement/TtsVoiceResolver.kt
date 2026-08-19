package com.trackvoice.announcement

import com.trackvoice.data.GenderFilter
import java.util.Locale

internal data class VoiceResolutionKey(
    val providerId: String,
    val catalogGeneration: Long,
    val language: String,
    val requestedGender: GenderFilter,
    val explicitVoiceName: String?,
)

internal data class ResolvedVoiceDecision(
    val voiceName: String?,
    val usedGenderFallback: Boolean,
)

internal data class PreparedTtsVoiceDecision(
    val key: VoiceResolutionKey,
    val decision: ResolvedVoiceDecision,
)

internal data class PreparedTtsVoicePlan(
    val decisions: List<PreparedTtsVoiceDecision>,
) {
    fun decisionFor(key: VoiceResolutionKey): ResolvedVoiceDecision? =
        decisions.firstOrNull { it.key == key }?.decision
}

/**
 * Small cache for the deterministic result of automatic/manual voice choice.
 * It stores desired voice identity only and never mutates the live TTS engine.
 */
internal class TtsVoiceResolver(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val onCacheMiss: (() -> Unit)? = null,
) {
    private val cache = LinkedHashMap<VoiceResolutionKey, ResolvedVoiceDecision>()

    @Synchronized
    fun resolve(
        snapshot: TtsVoiceCatalogSnapshot,
        locale: Locale,
        requestedGender: GenderFilter,
        explicitVoiceName: String?,
    ): ResolvedVoiceDecision {
        val key = keyFor(snapshot, locale, requestedGender, explicitVoiceName)
        cache[key]?.let { return it }
        onCacheMiss?.invoke()

        val candidates = snapshot.voicesFor(locale).map { voice ->
            VoiceCandidate(
                name = voice.name,
                gender = voice.gender,
                quality = voice.quality,
                requiresNetwork = voice.requiresNetwork,
                latency = voice.latency,
            )
        }
        val selection = VoiceSelectionPolicy.choose(
            candidates = candidates,
            explicitName = explicitVoiceName,
            requestedGender = requestedGender,
        )
        val resolved = ResolvedVoiceDecision(
            voiceName = selection.name,
            usedGenderFallback = selection.usedGenderFallback,
        )
        if (cache.size >= maxEntries) {
            cache.keys.firstOrNull()?.let(cache::remove)
        }
        cache[key] = resolved
        return resolved
    }

    fun keyFor(
        snapshot: TtsVoiceCatalogSnapshot,
        locale: Locale,
        requestedGender: GenderFilter,
        explicitVoiceName: String?,
    ): VoiceResolutionKey = VoiceResolutionKey(
        providerId = snapshot.providerId,
        catalogGeneration = snapshot.generation,
        language = locale.language.lowercase(Locale.ROOT),
        requestedGender = requestedGender,
        explicitVoiceName = explicitVoiceName,
    )

    @Synchronized
    fun invalidateVoice(voiceName: String) {
        cache.entries.removeAll { (key, value) ->
            key.explicitVoiceName == voiceName || value.voiceName == voiceName
        }
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }

    companion object {
        private const val DEFAULT_MAX_ENTRIES = 32
    }
}
