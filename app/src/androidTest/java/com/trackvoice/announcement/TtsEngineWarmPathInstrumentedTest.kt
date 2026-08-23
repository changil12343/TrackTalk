package com.trackvoice.announcement

import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackvoice.data.GenderFilter
import com.trackvoice.data.UserSettings
import com.trackvoice.data.VoiceLanguage
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TtsEngineWarmPathInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var engine: TtsEngine? = null

    @After
    fun tearDown() {
        engine?.shutdown()
        instrumentation.waitForIdleSync()
        engine = null
    }

    @Test
    fun repeatedVoiceAndSettingsReuseCatalogAndEngineConfiguration() {
        val provider = FakeTtsProvider(listOf(listOf(voice("english-a", "en-US"))))
        val tts = createEngine(provider)
        val settings = englishSettings()

        speakAndAwait(tts, "First.", settings)
        speakAndAwait(tts, "Second.", settings)

        assertEquals(1, provider.availableVoiceCalls)
        assertEquals(listOf("english-a"), provider.setVoiceCalls)
        assertEquals(1, provider.setSpeechRateCalls)
        assertEquals(1, provider.setPitchCalls)
        assertEquals(listOf("english-a", "english-a"), provider.spokenVoiceNames)
    }

    @Test
    fun previewVoiceDoesNotPoisonCachedAutomaticVoice() {
        val provider = FakeTtsProvider(
            listOf(
                listOf(
                    voice("automatic-a", "en-US", quality = VoiceMetadataPolicy.QUALITY_VERY_HIGH),
                    voice("preview-b", "en-US", quality = VoiceMetadataPolicy.QUALITY_NORMAL),
                ),
            ),
        )
        val tts = createEngine(provider)
        val settings = englishSettings()

        speakAndAwait(tts, "Automatic.", settings)
        speakAndAwait(tts, "Preview.", settings, voiceNameOverride = "preview-b")
        speakAndAwait(tts, "Automatic again.", settings)

        assertEquals(listOf("automatic-a", "preview-b", "automatic-a"), provider.setVoiceCalls)
        assertEquals(listOf("automatic-a", "preview-b", "automatic-a"), provider.spokenVoiceNames)
    }

    @Test
    fun mixedLanguageSegmentsResolveAndApplyIndependentCachedVoices() {
        val provider = FakeTtsProvider(
            listOf(
                listOf(
                    voice("english", "en-US"),
                    voice("korean", "ko-KR"),
                ),
            ),
        )
        val tts = createEngine(provider)
        val settings = UserSettings(
            voiceLanguage = VoiceLanguage.AUTO,
            genderFilter = GenderFilter.ANY,
        )

        speakAndAwait(tts, "Hello. 안녕하세요.", settings)
        speakAndAwait(tts, "Again. 다시 만나요.", settings)

        assertEquals(listOf("english", "korean", "english", "korean"), provider.spokenVoiceNames)
        assertEquals(listOf("english", "korean", "english", "korean"), provider.setVoiceCalls)
        assertEquals(1, provider.availableVoiceCalls)
    }

    @Test
    fun mixedLanguageDoesNotReconfigureVoiceBeforePriorSegmentCompletes() {
        val provider = FakeTtsProvider(
            catalogs = listOf(
                listOf(
                    voice("english", "en-US"),
                    voice("korean", "ko-KR"),
                ),
            ),
            autoComplete = false,
        )
        val tts = createEngine(provider)
        val finished = CountDownLatch(1)
        val settings = UserSettings(
            voiceLanguage = VoiceLanguage.AUTO,
            genderFilter = GenderFilter.ANY,
        )

        instrumentation.runOnMainSync {
            tts.speak("Hello. 안녕하세요.", settings) { _, _ -> finished.countDown() }
        }
        instrumentation.waitForIdleSync()

        assertEquals(listOf("english"), provider.setVoiceCalls)
        assertEquals(listOf("english"), provider.spokenVoiceNames)

        provider.completeNext()
        instrumentation.waitForIdleSync()

        assertEquals(listOf("english", "korean"), provider.setVoiceCalls)
        assertEquals(listOf("english", "korean"), provider.spokenVoiceNames)

        provider.completeNext()
        assertTrue(finished.await(3L, TimeUnit.SECONDS))
    }

    @Test
    fun unavailableCachedVoiceRefreshesOnceAndFallsBackWithoutSilence() {
        val provider = FakeTtsProvider(
            catalogs = listOf(
                listOf(voice("removed", "en-US")),
                listOf(voice("replacement", "en-US")),
            ),
            failingVoiceNames = setOf("removed"),
        )
        val tts = createEngine(provider)

        speakAndAwait(tts, "Fallback.", englishSettings())

        assertEquals(2, provider.availableVoiceCalls)
        assertEquals(listOf("removed", "replacement"), provider.setVoiceCalls)
        assertEquals(listOf("replacement"), provider.spokenVoiceNames)
    }

    @Test
    fun preparedVoicePlanDoesNotMutateEngineAndRejectsChangedPreferences() {
        val provider = FakeTtsProvider(
            listOf(
                listOf(
                    voice("female", "en-US", gender = GenderFilter.FEMALE),
                    voice("male", "en-US", gender = GenderFilter.MALE),
                ),
            ),
        )
        val tts = createEngine(provider)
        val femaleSettings = englishSettings().copy(genderFilter = GenderFilter.FEMALE)
        val plan = tts.prepareVoicePlan("Prepared next.", femaleSettings)

        assertTrue(plan != null)
        assertTrue(provider.setVoiceCalls.isEmpty())
        assertTrue(provider.spokenVoiceNames.isEmpty())

        speakAndAwait(
            tts = tts,
            text = "Changed preference.",
            settings = femaleSettings.copy(genderFilter = GenderFilter.MALE),
            preparedVoicePlan = plan,
        )

        assertEquals(listOf("male"), provider.setVoiceCalls)
        assertEquals(listOf("male"), provider.spokenVoiceNames)
    }

    @Test
    fun mainThreadCallKeepsAsynchronousSerializationBoundary() {
        val provider = FakeTtsProvider(listOf(listOf(voice("english", "en-US"))))
        val tts = createEngine(provider)
        val finished = CountDownLatch(1)

        instrumentation.runOnMainSync {
            tts.speak("Queued.", englishSettings()) { _, _ -> finished.countDown() }
            assertTrue(Looper.myLooper() == Looper.getMainLooper())
            assertTrue(provider.spokenVoiceNames.isEmpty())
        }

        assertTrue(finished.await(3L, TimeUnit.SECONDS))
        assertEquals(1, provider.spokenVoiceNames.size)
    }

    @Test
    fun backgroundCallDispatchesSpeakToMainExactlyOnce() {
        val provider = FakeTtsProvider(listOf(listOf(voice("english", "en-US"))))
        val tts = createEngine(provider)

        speakAndAwait(tts, "Background.", englishSettings(), invokeOnMain = false)

        assertEquals(1, provider.spokenVoiceNames.size)
        assertEquals(listOf(true), provider.speakWasOnMain)
    }

    private fun createEngine(provider: FakeTtsProvider): TtsEngine = TtsEngine(
        context = context,
        ttsProvider = provider,
        warmPathObserver = null,
    ).also { created ->
        engine = created
        waitUntil(3_000L) { created.state.value.status != TtsStatus.INITIALIZING }
        assertEquals(TtsStatus.READY, created.state.value.status)
    }

    private fun speakAndAwait(
        tts: TtsEngine,
        text: String,
        settings: UserSettings,
        voiceNameOverride: String? = null,
        preparedVoicePlan: PreparedTtsVoicePlan? = null,
        invokeOnMain: Boolean = true,
    ) {
        val finished = CountDownLatch(1)
        val callbackResult = AtomicReference<Pair<Boolean, com.trackvoice.diagnostics.DiagnosticMessage>>()
        val invoke = {
            val callback: (Boolean, com.trackvoice.diagnostics.DiagnosticMessage) -> Unit = { success, message ->
                callbackResult.set(success to message)
                finished.countDown()
            }
            if (preparedVoicePlan == null) {
                tts.speak(
                    text,
                    settings,
                    voiceNameOverride = voiceNameOverride,
                    onFinished = callback,
                )
            } else {
                tts.speakWithVoicePlan(
                    text,
                    settings,
                    voiceNameOverride = voiceNameOverride,
                    preparedVoicePlan = preparedVoicePlan,
                    onFinished = callback,
                )
            }
        }
        if (invokeOnMain) instrumentation.runOnMainSync(invoke) else invoke()
        instrumentation.waitForIdleSync()
        assertTrue("TTS fake callback timed out for '$text'", finished.await(3L, TimeUnit.SECONDS))
        val (success, message) = requireNotNull(callbackResult.get())
        assertTrue("TTS fake callback failed: $message", success)
    }

    private fun englishSettings() = UserSettings(
        voiceLanguage = VoiceLanguage.ENGLISH,
        genderFilter = GenderFilter.ANY,
        speechRate = 1.1f,
        pitch = 0.9f,
    )

    private fun voice(
        name: String,
        localeTag: String,
        quality: Int = VoiceMetadataPolicy.QUALITY_HIGH,
        gender: GenderFilter = GenderFilter.UNSPECIFIED,
    ) = VoiceDescriptor(
        providerId = "fake",
        name = name,
        label = localeTag,
        localeTag = localeTag,
        quality = quality,
        requiresNetwork = false,
        gender = gender,
        latency = VoiceMetadataPolicy.LATENCY_LOW,
    )

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(10L)
        }
        assertTrue("Condition not met within ${timeoutMs}ms", condition())
    }

    private class FakeTtsProvider(
        catalogs: List<List<VoiceDescriptor>>,
        private val failingVoiceNames: Set<String> = emptySet(),
        private val autoComplete: Boolean = true,
    ) : TtsProvider {
        private val catalogs = ArrayDeque(catalogs)
        private var listener: UtteranceProgressListener? = null
        private var activeVoiceName: String? = null
        private val pendingUtteranceIds = ArrayDeque<String>()

        var availableVoiceCalls: Int = 0
            private set
        var setSpeechRateCalls: Int = 0
            private set
        var setPitchCalls: Int = 0
            private set
        val setVoiceCalls = mutableListOf<String>()
        val spokenVoiceNames = mutableListOf<String?>()
        val speakWasOnMain = mutableListOf<Boolean>()

        override val providerId: String = "fake"

        override fun initialize(onReady: (Boolean) -> Unit) {
            // Android TextToSpeech reports readiness asynchronously. Keep the
            // fake from invoking the callback reentrantly during construction.
            Handler(Looper.getMainLooper()).post { onReady(true) }
        }
        override fun setProgressListener(listener: UtteranceProgressListener) {
            this.listener = listener
        }
        override fun setAudioAttributes(attributes: AudioAttributes): Boolean = true
        override fun availableVoices(): List<VoiceDescriptor> {
            availableVoiceCalls += 1
            return if (catalogs.size > 1) catalogs.removeFirst() else catalogs.firstOrNull().orEmpty()
        }
        override fun setLanguage(locale: Locale): Int {
            activeVoiceName = null
            return TextToSpeech.LANG_AVAILABLE
        }
        override fun setVoice(voiceId: String): Int {
            setVoiceCalls += voiceId
            if (voiceId in failingVoiceNames) return TextToSpeech.ERROR
            activeVoiceName = voiceId
            return TextToSpeech.SUCCESS
        }
        override fun setSpeechRate(rate: Float): Int {
            setSpeechRateCalls += 1
            return TextToSpeech.SUCCESS
        }
        override fun setPitch(pitch: Float): Int {
            setPitchCalls += 1
            return TextToSpeech.SUCCESS
        }
        override fun speak(text: String, queueMode: Int, params: Bundle, utteranceId: String): Int {
            spokenVoiceNames += activeVoiceName
            speakWasOnMain += Looper.myLooper() == Looper.getMainLooper()
            if (autoComplete) {
                listener?.onStart(utteranceId)
                listener?.onDone(utteranceId)
            } else {
                pendingUtteranceIds += utteranceId
            }
            return TextToSpeech.SUCCESS
        }

        fun completeNext() {
            val utteranceId = pendingUtteranceIds.removeFirst()
            listener?.onStart(utteranceId)
            listener?.onDone(utteranceId)
        }
        override fun stop() = Unit
        override fun shutdown() = Unit
    }
}
