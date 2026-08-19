package com.trackvoice.announcement

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackvoice.data.UserSettings
import com.trackvoice.data.VoiceLanguage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TtsWarmPathBenchmarkInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val events = ConcurrentHashMap<String, CopyOnWriteArrayList<TtsWarmPathEvent>>()
    private lateinit var engine: TtsEngine

    @Before
    fun setUp() {
        engine = TtsEngine(
            context = context,
            ttsProvider = AndroidSystemTtsProvider(context),
            warmPathObserver = TtsWarmPathObserver { event ->
                events.getOrPut(event.requestId) { CopyOnWriteArrayList() }.add(event)
            },
        )
        waitUntil(timeoutMs = 10_000L) { engine.state.value.status != TtsStatus.INITIALIZING }
        assertEquals(TtsStatus.READY, engine.state.value.status)
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) {
            engine.shutdown()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    @Test
    fun capturesColdAndTwentyWarmAnnouncements() {
        val settings = UserSettings(
            voiceLanguage = VoiceLanguage.ENGLISH,
            speechRate = 2f,
            pitch = 1f,
            volume = 0.05f,
        )

        val samples = (0 until TOTAL_UTTERANCES).map { index ->
            speakAndCapture("TrackTalk ${index + 1}.", settings)
        }
        assertEquals(TOTAL_UTTERANCES, samples.size)
        val cold = samples.first()
        val warm = samples.drop(1)
        assertEquals(WARM_UTTERANCES, warm.size)

        Log.i(
            TAG,
            buildString {
                append("TTS_WARM_PATH ")
                append("cold=")
                append(cold.summary())
                append(" warmCount=")
                append(warm.size)
                append(" voiceResolutionMedianMs=")
                append(percentile(warm.map { it.voiceResolutionMs }, 0.50))
                append(" voiceResolutionP95Ms=")
                append(percentile(warm.map { it.voiceResolutionMs }, 0.95))
                append(" readyToSpeakMedianMs=")
                append(percentile(warm.map { it.readyToSpeakMs }, 0.50))
                append(" readyToSpeakP95Ms=")
                append(percentile(warm.map { it.readyToSpeakMs }, 0.95))
                append(" speakToStartMedianMs=")
                append(percentile(warm.map { it.speakToStartMs }, 0.50))
                append(" speakToStartP95Ms=")
                append(percentile(warm.map { it.speakToStartMs }, 0.95))
                append(" readyToStartMedianMs=")
                append(percentile(warm.map { it.readyToStartMs }, 0.50))
                append(" readyToStartP95Ms=")
                append(percentile(warm.map { it.readyToStartMs }, 0.95))
            },
        )
    }

    @Test
    fun capturesTwentyRepresentativeWarmAnnouncements() {
        val base = UserSettings(
            speechRate = 2f,
            pitch = 1f,
            volume = 0.05f,
        )
        val english = base.copy(voiceLanguage = VoiceLanguage.ENGLISH)
        val korean = base.copy(voiceLanguage = VoiceLanguage.KOREAN)
        val automatic = base.copy(voiceLanguage = VoiceLanguage.AUTO)
        val manualEnglishVoice = requireNotNull(engine.voices.value.firstOrNull {
            java.util.Locale.forLanguageTag(it.localeTag).language == java.util.Locale.ENGLISH.language
        }?.name) { "Pixel TTS engine has no English voice" }

        val scenarios = listOf(
            Scenario("fixedEnglish", "TrackTalk.", english, null),
            Scenario("fixedKorean", "트랙톡.", korean, null),
            Scenario("autoAlternating", "TrackTalk.", automatic, null),
            Scenario("manualEnglish", "TrackTalk.", english, manualEnglishVoice),
        )
        val results = linkedMapOf<String, List<TimingSample>>()
        scenarios.forEach { scenario ->
            // Keep the first language/voice mutation out of this scenario's
            // warm data. Auto alternation is warmed for both language keys.
            speakAndCapture(scenario.text, scenario.settings, scenario.voiceNameOverride)
            if (scenario.name == "autoAlternating") {
                speakAndCapture("안내.", scenario.settings)
            }
            results[scenario.name] = List(PER_SCENARIO_WARM_UTTERANCES) { index ->
                val text = if (scenario.name == "autoAlternating" && index % 2 == 1) {
                    "안내."
                } else {
                    scenario.text
                }
                speakAndCapture(text, scenario.settings, scenario.voiceNameOverride)
            }
        }

        assertEquals(REPRESENTATIVE_WARM_UTTERANCES, results.values.sumOf(List<TimingSample>::size))
        results.forEach { (name, samples) ->
            Log.i(TAG, "TTS_WARM_PATTERN name=$name ${summary(samples)}")
        }
        Log.i(TAG, "TTS_WARM_PATTERN name=combined ${summary(results.values.flatten())}")
    }

    private fun speakAndCapture(
        text: String,
        settings: UserSettings,
        voiceNameOverride: String? = null,
    ): TimingSample {
        val before = events.keys.toSet()
        val finished = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            engine.speak(
                text = text,
                settings = settings,
                voiceNameOverride = voiceNameOverride,
            ) { _, _ ->
                finished.countDown()
            }
        }
        assertTrue("TTS utterance timed out", finished.await(8L, TimeUnit.SECONDS))
        val requestId = (events.keys - before).single()
        return requireNotNull(sample(events.getValue(requestId)))
    }

    private fun summary(samples: List<TimingSample>): String = buildString {
        append("count=${samples.size}")
        append(" voiceResolutionMedianMs=${percentile(samples.map { it.voiceResolutionMs }, 0.50)}")
        append(" voiceResolutionP95Ms=${percentile(samples.map { it.voiceResolutionMs }, 0.95)}")
        append(" readyToSpeakMedianMs=${percentile(samples.map { it.readyToSpeakMs }, 0.50)}")
        append(" readyToSpeakP95Ms=${percentile(samples.map { it.readyToSpeakMs }, 0.95)}")
        append(" speakToStartMedianMs=${percentile(samples.map { it.speakToStartMs }, 0.50)}")
        append(" speakToStartP95Ms=${percentile(samples.map { it.speakToStartMs }, 0.95)}")
        append(" readyToStartMedianMs=${percentile(samples.map { it.readyToStartMs }, 0.50)}")
        append(" readyToStartP95Ms=${percentile(samples.map { it.readyToStartMs }, 0.95)}")
    }

    private fun sample(events: List<TtsWarmPathEvent>): TimingSample? {
        fun stage(stage: TtsWarmPathStage): Long? = events
            .filter { it.segmentIndex == null || it.segmentIndex == 0 }
            .firstOrNull { it.stage == stage }
            ?.elapsedRealtimeNanos

        val ready = stage(TtsWarmPathStage.READY_TO_SPEAK) ?: return null
        val resolutionStart = stage(TtsWarmPathStage.VOICE_RESOLUTION_STARTED) ?: return null
        val resolutionEnd = stage(TtsWarmPathStage.VOICE_RESOLUTION_COMPLETED) ?: return null
        val speak = stage(TtsWarmPathStage.SPEAK_CALLED) ?: return null
        val started = stage(TtsWarmPathStage.TTS_STARTED) ?: return null
        val completed = stage(TtsWarmPathStage.TTS_COMPLETED) ?: return null
        return TimingSample(
            readyAtNanos = ready,
            voiceResolutionMs = elapsedMs(resolutionStart, resolutionEnd),
            readyToSpeakMs = elapsedMs(ready, speak),
            speakToStartMs = elapsedMs(speak, started),
            readyToStartMs = elapsedMs(ready, started),
            readyToDoneMs = elapsedMs(ready, completed),
        )
    }

    private fun percentile(values: List<Double>, percentile: Double): Double {
        val sorted = values.sorted()
        val index = (ceil(percentile * sorted.size).toInt() - 1).coerceIn(sorted.indices)
        return rounded(sorted[index])
    }

    private fun elapsedMs(startNanos: Long, endNanos: Long): Double =
        rounded((endNanos - startNanos).coerceAtLeast(0L) / 1_000_000.0)

    private fun rounded(value: Double): Double = kotlin.math.round(value * 1_000.0) / 1_000.0

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(20L)
        }
        assertTrue("Condition not met within ${timeoutMs}ms", condition())
    }

    private data class TimingSample(
        val readyAtNanos: Long,
        val voiceResolutionMs: Double,
        val readyToSpeakMs: Double,
        val speakToStartMs: Double,
        val readyToStartMs: Double,
        val readyToDoneMs: Double,
    ) {
        fun summary(): String =
            "{voiceResolutionMs=$voiceResolutionMs,readyToSpeakMs=$readyToSpeakMs," +
                "speakToStartMs=$speakToStartMs,readyToStartMs=$readyToStartMs," +
                "readyToDoneMs=$readyToDoneMs}"
    }

    private data class Scenario(
        val name: String,
        val text: String,
        val settings: UserSettings,
        val voiceNameOverride: String?,
    )

    private companion object {
        const val TAG = "TrackTalk.TtsBenchmark"
        const val TOTAL_UTTERANCES = 21
        const val WARM_UTTERANCES = 20
        const val PER_SCENARIO_WARM_UTTERANCES = 5
        const val REPRESENTATIVE_WARM_UTTERANCES = 20
    }
}
