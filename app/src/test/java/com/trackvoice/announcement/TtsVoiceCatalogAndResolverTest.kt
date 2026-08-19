package com.trackvoice.announcement

import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.UtteranceProgressListener
import com.trackvoice.data.GenderFilter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsVoiceCatalogAndResolverTest {
    @Test
    fun fiveHundredVoiceCatalogIsTraversedOnceForOneHundredWarmResolutions() {
        val provider = CountingCatalogProvider(
            (0 until 500).map { index ->
                voice(
                    name = "voice-$index",
                    language = if (index % 2 == 0) "en-US" else "ko-KR",
                    gender = if (index % 3 == 0) GenderFilter.FEMALE else GenderFilter.UNSPECIFIED,
                )
            },
        )
        val catalog = TtsVoiceCatalog(provider)
        val snapshot = catalog.refresh()
        var cacheMisses = 0
        val resolver = TtsVoiceResolver(onCacheMiss = { cacheMisses += 1 })

        repeat(100) {
            resolver.resolve(snapshot, Locale.ENGLISH, GenderFilter.FEMALE, explicitVoiceName = null)
        }

        assertEquals(1, provider.availableVoiceCalls)
        assertEquals(250, snapshot.voicesFor(Locale.ENGLISH).size)
        assertEquals(1, cacheMisses)
    }

    @Test
    fun languageGenderAndExplicitVoiceAreIndependentCacheKeys() {
        val snapshot = snapshot(
            generation = 1L,
            voices = listOf(
                voice("en-female", "en-US", GenderFilter.FEMALE),
                voice("en-male", "en-US", GenderFilter.MALE),
                voice("ko-female", "ko-KR", GenderFilter.FEMALE),
            ),
        )
        var misses = 0
        val resolver = TtsVoiceResolver(onCacheMiss = { misses += 1 })

        val englishFemale = resolver.resolve(snapshot, Locale.ENGLISH, GenderFilter.FEMALE, null)
        val englishFemaleAgain = resolver.resolve(snapshot, Locale.UK, GenderFilter.FEMALE, null)
        val koreanFemale = resolver.resolve(snapshot, Locale.KOREAN, GenderFilter.FEMALE, null)
        val englishMale = resolver.resolve(snapshot, Locale.ENGLISH, GenderFilter.MALE, null)
        val explicit = resolver.resolve(snapshot, Locale.ENGLISH, GenderFilter.ANY, "en-male")

        assertEquals("en-female", englishFemale.voiceName)
        assertEquals(englishFemale, englishFemaleAgain)
        assertEquals("ko-female", koreanFemale.voiceName)
        assertEquals("en-male", englishMale.voiceName)
        assertEquals("en-male", explicit.voiceName)
        assertEquals(4, misses)
    }

    @Test
    fun catalogGenerationChangeDoesNotReuseStaleResolution() {
        var misses = 0
        val resolver = TtsVoiceResolver(onCacheMiss = { misses += 1 })
        val first = snapshot(1L, listOf(voice("voice-a", "en-US", GenderFilter.FEMALE)))
        val second = snapshot(2L, listOf(voice("voice-b", "en-US", GenderFilter.FEMALE)))

        val firstResult = resolver.resolve(first, Locale.ENGLISH, GenderFilter.FEMALE, null)
        val secondResult = resolver.resolve(second, Locale.ENGLISH, GenderFilter.FEMALE, null)

        assertEquals("voice-a", firstResult.voiceName)
        assertEquals("voice-b", secondResult.voiceName)
        assertNotEquals(firstResult, secondResult)
        assertEquals(2, misses)
    }

    @Test
    fun removedVoiceFallsBackToCompatibleUnknownVoiceAfterRefresh() {
        val resolver = TtsVoiceResolver()
        val original = snapshot(1L, listOf(voice("female", "en-US", GenderFilter.FEMALE)))
        val refreshed = snapshot(
            2L,
            listOf(
                voice("male", "en-US", GenderFilter.MALE),
                voice("unknown", "en-US", GenderFilter.UNSPECIFIED),
            ),
        )

        assertEquals(
            "female",
            resolver.resolve(original, Locale.ENGLISH, GenderFilter.FEMALE, null).voiceName,
        )
        resolver.invalidateVoice("female")
        val fallback = resolver.resolve(refreshed, Locale.ENGLISH, GenderFilter.FEMALE, null)

        assertEquals("unknown", fallback.voiceName)
        assertTrue(fallback.usedGenderFallback)
    }

    @Test
    fun missingExplicitVoiceNeverSelectsAnIncompatibleLanguage() {
        val resolver = TtsVoiceResolver()
        val catalog = snapshot(
            1L,
            listOf(
                voice("english", "en-US", GenderFilter.UNSPECIFIED),
                voice("korean", "ko-KR", GenderFilter.UNSPECIFIED),
            ),
        )

        val result = resolver.resolve(
            snapshot = catalog,
            locale = Locale.KOREAN,
            requestedGender = GenderFilter.ANY,
            explicitVoiceName = "removed-english-voice",
        )

        assertEquals("korean", result.voiceName)
        assertFalse(result.usedGenderFallback)
    }

    private fun snapshot(generation: Long, voices: List<VoiceDescriptor>) =
        TtsVoiceCatalogSnapshot.create("fake", generation, voices)

    private fun voice(
        name: String,
        language: String,
        gender: GenderFilter,
    ) = VoiceDescriptor(
        providerId = "fake",
        name = name,
        label = language,
        localeTag = language,
        quality = VoiceMetadataPolicy.QUALITY_HIGH,
        requiresNetwork = false,
        gender = gender,
        latency = VoiceMetadataPolicy.LATENCY_LOW,
    )

    private class CountingCatalogProvider(
        private val catalog: List<VoiceDescriptor>,
    ) : TtsProvider {
        var availableVoiceCalls = 0
            private set

        override val providerId: String = "fake"
        override fun initialize(onReady: (Boolean) -> Unit) = onReady(true)
        override fun setProgressListener(listener: UtteranceProgressListener) = Unit
        override fun setAudioAttributes(attributes: AudioAttributes): Boolean = true
        override fun availableVoices(): List<VoiceDescriptor> {
            availableVoiceCalls += 1
            return catalog
        }
        override fun setLanguage(locale: Locale): Int = 0
        override fun setVoice(voiceId: String): Int = 0
        override fun setSpeechRate(rate: Float): Int = 0
        override fun setPitch(pitch: Float): Int = 0
        override fun speak(text: String, queueMode: Int, params: Bundle, utteranceId: String): Int = 0
        override fun stop() = Unit
        override fun shutdown() = Unit
    }
}
