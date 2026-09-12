package com.trackvoice.ui

import com.trackvoice.data.AppLanguage
import com.trackvoice.data.APP_LANGUAGE_OPTIONS
import com.trackvoice.data.AnnouncementOutputPolicy
import com.trackvoice.data.AnnouncementTiming
import com.trackvoice.data.AnnouncementReadField
import com.trackvoice.data.AnnouncementVolumeMode
import com.trackvoice.data.resolve
import com.trackvoice.data.UserSettings
import com.trackvoice.data.VoiceLanguage
import com.trackvoice.announcement.AudioDeviceKind
import com.trackvoice.diagnostics.DiagnosticMessage
import com.trackvoice.announcement.AnnouncementConfigurationSource
import com.trackvoice.announcement.EffectiveAnnouncementConfiguration
import com.trackvoice.media.PlaybackCollection
import com.trackvoice.monetization.PremiumMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {
    @Test
    fun systemKoreanResolvesToKorean() {
        assertEquals(AppLanguage.KOREAN, AppLanguage.SYSTEM.resolve("ko"))
    }

    @Test
    fun systemNonKoreanResolvesToEnglish() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.SYSTEM.resolve("en-US"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.SYSTEM.resolve("ja-JP"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.SYSTEM.resolve("fr-FR"))
    }

    @Test
    fun explicitLanguageIgnoresSystemLanguage() {
        assertEquals(AppLanguage.KOREAN, AppLanguage.KOREAN.resolve("en"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.ENGLISH.resolve("ko"))
    }

    @Test
    fun stringsFollowSelectedLanguage() {
        assertEquals("홈", TrackTalkStrings.forLanguage(AppLanguage.KOREAN, "en").navLabel(AppSection.HOME))
        assertEquals("Home", TrackTalkStrings.forLanguage(AppLanguage.ENGLISH, "ko").navLabel(AppSection.HOME))
        assertEquals("안내·음성", TrackTalkStrings.forLanguage(AppLanguage.KOREAN, "en").sectionTitle(AppSection.GENERAL))
        assertEquals("안내·음성", TrackTalkStrings.forLanguage(AppLanguage.KOREAN, "en").navLabel(AppSection.GENERAL))
        assertEquals("기기", TrackTalkStrings.forLanguage(AppLanguage.KOREAN, "en").navLabel(AppSection.DEVICES))
        assertEquals("Speech", TrackTalkStrings.forLanguage(AppLanguage.ENGLISH, "ko").navLabel(AppSection.GENERAL))
        assertEquals("Device", TrackTalkStrings.forLanguage(AppLanguage.ENGLISH, "ko").navLabel(AppSection.DEVICES))
    }

    @Test
    fun premiumMessagesFollowSelectedLanguage() {
        assertEquals(
            "현재 설치 환경에서는 구매 정보를 확인할 수 없습니다. Google Play에서 설치한 앱에서 다시 확인해 주세요.",
            TrackTalkStrings.forLanguage(AppLanguage.KOREAN, "en")
                .premiumMessage(PremiumMessage.PRODUCT_UNAVAILABLE),
        )
        assertEquals(
            "Purchase details aren't available in this installation. Check the Google Play version of the app.",
            TrackTalkStrings.forLanguage(AppLanguage.ENGLISH, "ko")
                .premiumMessage(PremiumMessage.PRODUCT_UNAVAILABLE),
        )
    }

    @Test
    fun freeTierCopyDescribesBehaviorWithoutTierLabels() {
        val korean = TrackTalkStrings.forLanguage(AppLanguage.KOREAN, "en")
        val english = TrackTalkStrings.forLanguage(AppLanguage.ENGLISH, "ko")

        assertEquals("안내 시점과 음량을 세밀하게 조절합니다.", korean.freeGuideDetailsSummary)
        assertEquals("Fine-tune announcement timing and volume.", english.freeGuideDetailsSummary)
        assertFalse(korean.readingFieldsSummary.contains("무료"))
        assertFalse(english.readingFieldsSummary.contains("Free", ignoreCase = true))
        assertEquals("기기·진단", korean.sectionTitle(AppSection.DEVICES))
        assertEquals("Device & diagnostics", english.sectionTitle(AppSection.DEVICES))
        assertEquals("PLUS", korean.plusBadge)
        assertEquals("자동화", korean.automationPlusTitle)
        assertEquals("Automation", english.automationPlusTitle)
    }

    @Test
    fun defaultVoiceVolumeSummaryUsesFollowMediaInBothLanguages() {
        assertEquals(
            "안내 음성: 음악 볼륨 따르기",
            TrackTalkStrings.forLanguage(AppLanguage.KOREAN, "en").defaultFollowMediaVolume,
        )
        assertEquals(
            "Announcement voice: follows media volume",
            TrackTalkStrings.forLanguage(AppLanguage.ENGLISH, "ko").defaultFollowMediaVolume,
        )
    }

    @Test
    fun privacyCopyDisclosesTheSelectedTtsEngineAndProviderBoundary() {
        val korean = TrackTalkStrings.forLanguage(AppLanguage.KOREAN, "en")
        val english = TrackTalkStrings.forLanguage(AppLanguage.ENGLISH, "ko")

        assertTrue(korean.ttsPrivacySummary.contains("Android TTS 엔진"))
        assertTrue(korean.ttsPrivacySummary.contains("네트워크"))
        assertTrue(korean.ttsPrivacySummary.contains("제공업체"))
        assertTrue(english.ttsPrivacySummary.contains("Android TTS engine"))
        assertTrue(english.ttsPrivacySummary.contains("network processing"))
        assertTrue(english.ttsPrivacySummary.contains("provider's policies"))
        assertTrue(english.privacySummary.contains("Google Play"))
    }

    @Test
    fun announcementTimingExplainsTheActualWait() {
        val korean = TrackTalkStrings.forLanguage(AppLanguage.KOREAN, "en")
        val english = TrackTalkStrings.forLanguage(AppLanguage.ENGLISH, "ko")

        assertEquals(
            "새 곡을 감지한 뒤 2초 후 읽습니다.",
            korean.announcementTimingSummary(AnnouncementTiming.DELAYED, 2),
        )
        assertEquals(
            "Reads 1s after a new track is detected.",
            english.announcementTimingSummary(AnnouncementTiming.DELAYED, 0),
        )
        assertEquals(
            "자동 · 바로 안내",
            korean.guideSummary(com.trackvoice.data.AnnouncementMode.SMART, AnnouncementTiming.IMMEDIATE, 2),
        )
    }

    @Test
    fun nowPlayingCardSummarizesTheGlobalFieldOrder() {
        val korean = TrackTalkStrings.forLanguage(AppLanguage.KOREAN, "en")
        val english = TrackTalkStrings.forLanguage(AppLanguage.ENGLISH, "ko")
        val defaultConfiguration = EffectiveAnnouncementConfiguration(
            source = AnnouncementConfigurationSource.DEFAULT,
            collection = PlaybackCollection.UNKNOWN,
            fields = listOf(AnnouncementReadField.TITLE, AnnouncementReadField.ARTIST),
            typeSpecificSettingsEnabled = true,
        )
        assertEquals("기본 설정", korean.homeAnnouncementBasis(defaultConfiguration))
        assertEquals("트랙 번호 → 곡명", korean.announcementFieldsSummary(listOf(AnnouncementReadField.TRACK_NUMBER, AnnouncementReadField.TITLE)))
        assertEquals("Track number → Title", english.announcementFieldsSummary(listOf(AnnouncementReadField.TRACK_NUMBER, AnnouncementReadField.TITLE)))
    }

    @Test
    fun outputPolicyLabelsMatchSelectedLanguage() {
        val korean = TrackTalkStrings.forLanguage(AppLanguage.KOREAN, "en")
        val english = TrackTalkStrings.forLanguage(AppLanguage.ENGLISH, "ko")

        assertEquals("모든 오디오 출력", korean.announcementOutputOption(AnnouncementOutputPolicy.ALL_OUTPUTS))
        assertEquals("External audio only", english.announcementOutputOption(AnnouncementOutputPolicy.EXTERNAL_ONLY))
    }

    @Test
    fun freshSettingsUseSystemLanguageAndTitleOnlyReading() {
        val settings = UserSettings()

        assertEquals(AppLanguage.SYSTEM, settings.appLanguage)
        assertEquals(
            listOf(AppLanguage.SYSTEM, AppLanguage.ENGLISH, AppLanguage.KOREAN),
            APP_LANGUAGE_OPTIONS,
        )
        assertEquals(VoiceLanguage.AUTO, settings.voiceLanguage)
        assertEquals(AnnouncementVolumeMode.FOLLOW_MEDIA, settings.announcementVolumeMode)
        assertEquals(listOf(AnnouncementReadField.TITLE), settings.defaultReadFields)
        assertEquals("System default", TrackTalkStrings.forLanguage(AppLanguage.ENGLISH, "en").appLanguageOption(AppLanguage.SYSTEM))
        assertEquals("시스템 언어", TrackTalkStrings.forLanguage(AppLanguage.KOREAN, "ko").appLanguageOption(AppLanguage.SYSTEM))
        assertEquals("General", TrackTalkStrings.forLanguage(AppLanguage.ENGLISH, "en").generalSection)
        assertEquals("일반", TrackTalkStrings.forLanguage(AppLanguage.KOREAN, "ko").generalSection)
    }

    @Test
    fun changingInterfaceLanguageDoesNotChangeSpeechOrReadingSettings() {
        val original = UserSettings(
            appLanguage = AppLanguage.SYSTEM,
            voiceLanguage = VoiceLanguage.KOREAN,
            defaultReadFields = listOf(AnnouncementReadField.ARTIST, AnnouncementReadField.TITLE),
        )

        val changed = original.copy(appLanguage = AppLanguage.ENGLISH)

        assertEquals(VoiceLanguage.KOREAN, changed.voiceLanguage)
        assertEquals(original.defaultReadFields, changed.defaultReadFields)
    }

    @Test
    fun englishCoverageUsesNaturalCopyAcrossEveryMainSurface() {
        val english = TrackTalkStrings.forLanguage(AppLanguage.ENGLISH, "ko")

        assertEquals("Voice announcements", english.homeVoiceGuide)
        assertEquals("Basic announcements", english.trackGuide)
        assertEquals("Spoken information", english.readingFieldsSection)
        assertEquals("Speech language", english.defaultVoiceLanguage)
        assertEquals("Auto-detect from title", english.voiceLanguage(VoiceLanguage.AUTO))
        assertEquals("Choose which apps TrackTalk monitors.", english.appsIntro)
        assertEquals("Announcements & voice", english.sectionTitle(AppSection.GENERAL))
        assertEquals("Screen automation", english.screenAutomation)
        assertEquals("Device & diagnostics", english.sectionTitle(AppSection.DEVICES))
        assertEquals("Send feedback", english.feedbackDeveloper)
        assertEquals("Wired headphones", english.audioDeviceType(AudioDeviceKind.WIRED_HEADPHONES))
        assertEquals("Text-to-speech ready", english.diagnosticMessage(DiagnosticMessage.TTS_READY))
    }
}
