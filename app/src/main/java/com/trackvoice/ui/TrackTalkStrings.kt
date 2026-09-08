package com.trackvoice.ui

import com.trackvoice.data.AnnouncementMode
import com.trackvoice.data.AnnouncementOutputPolicy
import com.trackvoice.data.AnnouncementReadField
import com.trackvoice.data.AnnouncementTiming
import com.trackvoice.data.AnnouncementTimingPolicy
import com.trackvoice.data.AnnouncementVolumeMode
import com.trackvoice.data.AppCategory
import com.trackvoice.data.AppLanguage
import com.trackvoice.data.GenderFilter
import com.trackvoice.data.MusicTreatment
import com.trackvoice.data.TrackStartBehavior
import com.trackvoice.data.VoiceLanguage
import com.trackvoice.data.resolve
import com.trackvoice.announcement.AudioDeviceKind
import com.trackvoice.announcement.EffectiveAnnouncementConfiguration
import com.trackvoice.diagnostics.DiagnosticMessage
import com.trackvoice.monetization.PremiumMessage
import androidx.compose.runtime.staticCompositionLocalOf

/** Small, local UI dictionary used by the Compose surface. */
class TrackTalkStrings private constructor(private val language: AppLanguage) {
    private val english = language == AppLanguage.ENGLISH

    val isEnglish: Boolean get() = english

    private fun t(korean: String, englishText: String): String = if (english) englishText else korean

    fun sectionTitle(section: AppSection): String = when (section) {
        AppSection.HOME -> "TrackTalk"
        AppSection.GENERAL -> t("안내·음성", "Announcements & voice")
        AppSection.APPS -> t("앱 설정", "App settings")
        AppSection.DEVICES -> t("기기·진단", "Device & diagnostics")
        AppSection.DIAGNOSTICS -> t("진단", "Diagnostics")
    }

    fun navLabel(section: AppSection): String = when (section) {
        AppSection.HOME -> t("홈", "Home")
        AppSection.GENERAL -> t("안내·음성", "Speech")
        AppSection.APPS -> t("앱", "Apps")
        AppSection.DEVICES -> t("기기", "Device")
        AppSection.DIAGNOSTICS -> t("진단", "Diagnostics")
    }

    val announcementPane: String get() = t("안내", "Announcements")
    val voicePane: String get() = t("음성", "Voice")

    val plus: String get() = "Plus"
    val appLanguageTitle: String get() = t("앱 언어", "App language")
    val generalSection: String get() = t("일반", "General")

    fun appLanguageOption(language: AppLanguage): String = when (language) {
        AppLanguage.SYSTEM -> t("시스템 언어", "System default")
        AppLanguage.KOREAN -> "한국어"
        AppLanguage.ENGLISH -> "English"
    }

    fun guideSummary(mode: AnnouncementMode, timing: AnnouncementTiming, delaySeconds: Int): String {
        return guideSummary(announcementMode(mode), timing, delaySeconds)
    }
    fun guideSummary(basis: String, timing: AnnouncementTiming, delaySeconds: Int): String {
        return "$basis · ${guideTimingSummary(timing, delaySeconds)}"
    }
    fun guideTimingSummary(timing: AnnouncementTiming, delaySeconds: Int): String {
        val timingText = when (timing) {
            AnnouncementTiming.IMMEDIATE -> t("바로 안내", "Immediately")
            AnnouncementTiming.DELAYED,
            AnnouncementTiming.BETWEEN_TRACKS,
            -> {
                val effectiveDelay = AnnouncementTimingPolicy.normalizeStoredDelaySeconds(timing, delaySeconds)
                t("${effectiveDelay}초 후 안내", "After ${effectiveDelay}s")
            }
        }
        return timingText
    }
    fun seconds(value: Int): String = if (english) "${value}s" else "${value}초"
    val currentAnnouncement: String get() = t("현재 안내", "Current announcement")
    val announcementLabel: String get() = t("안내", "Announcements")
    val readingOrderLabel: String get() = t("읽기", "Spoken info")
    val openGuideSettings: String get() = t("안내 설정 열기", "Open announcement settings")
    fun homeAnnouncementBasis(configuration: EffectiveAnnouncementConfiguration): String =
        t("기본 설정", "Default")
    fun homeAnnouncementBehavior(
        trackStartBehavior: TrackStartBehavior,
        timing: AnnouncementTiming,
        delaySeconds: Int,
    ): String {
        val start = when (trackStartBehavior) {
            TrackStartBehavior.PLAY_IMMEDIATELY -> t("기본", "Default")
            TrackStartBehavior.ANNOUNCE_THEN_PLAY -> t("안내 후 재생", "Announce then play")
        }
        return "$start · ${guideTimingSummary(timing, delaySeconds)}"
    }

    fun announcementFieldsSummary(fields: List<AnnouncementReadField>): String =
        fields.joinToString(" → ") { readField(it) }

    val homeVoiceGuide: String get() = t("음성 안내", "Voice announcements")
    fun statusSummary(effectiveEnabled: Boolean, enabled: Boolean): String = when {
        !effectiveEnabled -> t("OFF · 음성 안내가 꺼져 있습니다.", "OFF · Voice announcements are off.")
        !enabled -> t("자동 활성화 · 조건에 맞을 때 안내합니다.", "Auto-enabled · Announces when conditions match.")
        else -> t("ON · 새 곡을 안내합니다.", "ON · Announces new tracks.")
    }

    val musicDetectionPermissionTitle: String get() = t("음악 감지 권한 필요", "Music detection")
    val musicDetectionPermissionSummary: String
        get() = t("재생 중인 곡을 확인하려면 권한을 허용해 주세요.", "Allow access so TrackTalk can detect the song currently playing.")
    val requiredPermissionBadge: String get() = t("필수", "Required")
    val permissionSettings: String get() = t("권한 설정", "Open settings")
    val permissionPlaybackSummary: String
        get() = t("권한을 설정하면 현재 재생 정보가 표시됩니다.", "Grant access to show the current track.")
    val statusNeedsSetup: String get() = t("ON · 설정 필요", "ON · Setup needed")

    val currentTrack: String get() = t("현재 재생", "Now playing")
    val noMusicPlaying: String get() = t("재생 중인 음악이 없습니다.", "No music is playing.")
    val playMusicHint: String get() = t("음악 앱을 재생하면 여기에 표시됩니다.", "Play music in a media app to see it here.")
    val appField: String get() = t("앱", "App")
    val trackField: String get() = t("곡", "Track")
    val artistField: String get() = t("아티스트", "Artist")
    val albumField: String get() = t("앨범", "Album")
    val playlistField: String get() = t("재생목록", "Playlist")
    val trackNumberField: String get() = t("트랙", "Track #")
    val unknownTitle: String get() = t("곡명 없음", "Unknown track title")
    val unknownArtist: String get() = t("아티스트 없음", "Unknown artist")
    val unknownAlbum: String get() = t("앨범 없음", "Unknown album")
    fun trackNumber(number: Int, total: Int?): String = if (english) {
        "#$number${total?.let { " / $it" }.orEmpty()}"
    } else {
        "${number}번${total?.let { " / $it" }.orEmpty()}"
    }
    val playMusic: String get() = t("음악 재생", "Play music")
    val pauseMusic: String get() = t("음악 일시정지", "Pause music")
    fun lastDetected(time: String): String = t("마지막 감지 $time", "Last detected $time")

    val premiumTitle: String get() = "TrackTalk Plus"
    val plusBadge: String get() = "PLUS"
    val premiumEnabledSummary: String get() = t("Plus가 활성화되어 모든 고급 기능을 사용할 수 있습니다.", "Plus is active. All advanced features are available.")
    val premiumLockedSummary: String get() = t("광고 제거와 세밀한 음성·자동화 설정을 한 번의 결제로 이용할 수 있습니다.", "Remove ads and unlock finer voice and automation controls with a one-time purchase.")
    val view: String get() = t("보기", "View")
    val premiumVoiceBenefit: String get() = t("음성 속도·높이·음량 세부 조절", "Fine-tune voice speed, pitch, and volume")
    val premiumDeviceBenefit: String get() = t("기기별 안내와 자동 활성화", "Per-device announcements and auto-enable")
    val premiumAdsBenefit: String get() = t("광고 없이 사용", "Use TrackTalk without ads")
    val premiumFutureBenefit: String get() = t("추가 예정인 고급 음성·자동화 기능", "Future advanced voice and automation features")
    val premiumActive: String get() = t("Plus가 활성화되어 있습니다.", "Plus is active.")
    fun oneTimePrice(price: String): String = t("일회성 결제 · $price", "One-time purchase · $price")
    val purchaseUnavailable: String get() = t(
        "현재 설치 환경에서는 구매 정보를 확인할 수 없습니다. Google Play에서 설치한 앱에서 다시 확인해 주세요.",
        "Purchase details aren't available in this installation. Check the Google Play version of the app.",
    )
    fun premiumMessage(message: PremiumMessage): String = when (message) {
        PremiumMessage.BILLING_UNAVAILABLE -> t("Google Play 결제를 사용할 수 없습니다.", "Google Play billing is unavailable.")
        PremiumMessage.SERVICE_DISCONNECTED -> t("Google Play 연결이 끊겼습니다. 잠시 후 다시 시도해 주세요.", "The Google Play connection was lost. Please try again later.")
        PremiumMessage.PRODUCT_UNAVAILABLE -> purchaseUnavailable
        PremiumMessage.PRODUCT_LOAD_FAILED -> t("구매 상품을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.", "We couldn't load the purchase product. Please try again later.")
        PremiumMessage.PURCHASE_UNAVAILABLE -> t("구매 상품을 아직 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.", "The purchase product isn't available yet. Please try again later.")
        PremiumMessage.PURCHASE_FLOW_FAILED -> t("구매 화면을 열지 못했습니다. 잠시 후 다시 시도해 주세요.", "We couldn't open the purchase screen. Please try again later.")
        PremiumMessage.PURCHASE_CANCELED -> t("구매를 취소했습니다.", "Purchase canceled.")
        PremiumMessage.PURCHASE_FAILED -> t("구매를 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.", "The purchase couldn't be completed. Please try again later.")
        PremiumMessage.RESTORE_FAILED -> t("구매 내역을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.", "We couldn't check your purchases. Please try again later.")
        PremiumMessage.PENDING -> t("결제가 보류 중입니다. 결제가 완료되면 Plus가 활성화됩니다.", "Payment is pending. Plus will activate when payment is complete.")
        PremiumMessage.LOCAL_CODE_APPLIED -> t("지인용 Plus 코드가 적용되었습니다.", "Friend Plus code applied.")
        PremiumMessage.ACKNOWLEDGE_FAILED -> t("구매 확인을 완료하지 못했습니다. 앱을 다시 열어 확인해 주세요.", "We couldn't confirm the purchase. Reopen the app to check again.")
    }
    val promoDescription: String
        get() = t("지인용 코드는 이 기기에서 Plus를 바로 활성화합니다. Google Play 프로모션 코드는 아래 버튼을 눌러 사용하세요.", "A friend code activates Plus on this device. Use a Google Play promo code with the button below.")
    val promoCode: String get() = t("코드 입력", "Enter a code")
    val promoCodeSection: String get() = t("프로모션 코드", "Promo code")
    val closePromoCode: String get() = t("프로모션 코드 닫기", "Hide promo code")
    val promoCodeFormatError: String get() = t("영문, 숫자, 하이픈(-)만 입력할 수 있습니다.", "Use letters, numbers, and hyphens (-) only.")
    val applyFriendCode: String get() = t("지인용 코드 적용", "Apply friend code")
    val useGooglePlayCode: String get() = t("Google Play 프로모션 코드 사용", "Redeem on Google Play")
    val close: String get() = t("닫기", "Close")
    val checking: String get() = t("확인 중...", "Checking...")
    val buyPlus: String get() = t("Plus 구매", "Buy Plus")
    val restorePurchase: String get() = t("구매 내역 복원", "Restore purchase")

    val basicOperation: String get() = t("기본 동작", "Basic behavior")
    val voiceGuide: String get() = t("음성 안내", "Voice announcements")
    val voiceGuideSummary: String get() = t("상단바의 안내 타일과 동기화됩니다.", "Matches the announcement tile in Quick Settings.")
    val announcementOutput: String get() = t("안내 출력", "Announcement output")
    fun announcementOutputOption(policy: AnnouncementOutputPolicy): String = when (policy) {
        AnnouncementOutputPolicy.ALL_OUTPUTS -> t("모든 오디오 출력", "All audio outputs")
        AnnouncementOutputPolicy.EXTERNAL_ONLY -> t("외부 오디오에서만", "External audio only")
    }
    fun announcementOutputSummary(policy: AnnouncementOutputPolicy): String = when (policy) {
        AnnouncementOutputPolicy.ALL_OUTPUTS -> t(
            "휴대폰 스피커 · Bluetooth · 유선 · USB · HDMI",
            "Phone speaker · Bluetooth · wired · USB · HDMI",
        )
        AnnouncementOutputPolicy.EXTERNAL_ONLY -> t(
            "Bluetooth · 유선 · USB · HDMI",
            "Bluetooth · wired · USB · HDMI",
        )
    }
    val statusShortcut: String get() = t("상단바 상태 알림", "Status notification")
    val statusShortcutSummary: String
        get() = t(
            "현재 안내 상태를 상단바에서 확인합니다.",
            "See TrackTalk's current status in the status bar.",
        )
    val statusShortcutPermissionSummary: String
        get() = t(
            "알림을 표시하려면 권한이 필요합니다.",
            "Notification permission is required to show it.",
        )
    val connectedDevices: String get() = t("연결 기기", "Connected devices")
    fun audioDeviceType(kind: AudioDeviceKind): String = when (kind) {
        AudioDeviceKind.WIRED_HEADPHONES -> t("유선 이어폰", "Wired headphones")
        AudioDeviceKind.USB_AUDIO -> t("USB 오디오", "USB audio")
        AudioDeviceKind.BLUETOOTH -> "Bluetooth"
        AudioDeviceKind.BLUETOOTH_LE -> "Bluetooth LE"
        AudioDeviceKind.HEARING_AID -> t("보청기", "Hearing aid")
        AudioDeviceKind.HDMI_AUDIO -> t("HDMI 오디오", "HDMI audio")
        AudioDeviceKind.LINE_AUDIO -> t("라인 오디오", "Line audio")
        AudioDeviceKind.OTHER -> t("오디오 기기", "Audio device")
    }
    val automationPlusTitle: String get() = t("자동화", "Automation")
    val automationPlusSummary: String get() = t(
        "Bluetooth·USB·HDMI 기기별 동작과 화면 꺼짐 자동 활성화를 설정할 수 있습니다.",
        "Configure Bluetooth, USB, and HDMI behavior plus screen-off auto-enable.",
    )
    val deviceAutomationSummary: String get() = t("기기별 안내와 자동 켜짐을 설정합니다.", "Set announcements and automation per device.")
    val noConnectedDevices: String get() = t("연결된 이어폰이나 Bluetooth 기기가 없습니다.", "No headphones or Bluetooth devices are connected.")
    val diagnosticsSummary: String get() = t("알림 접근, 미디어 감지, 음성 엔진 상태를 확인합니다.", "Check notification access, media detection, and the voice engine.")
    val openDiagnostics: String get() = t("진단 열기", "Open diagnostics")
    val backToDevices: String get() = t("기기·진단으로 돌아가기", "Back to devices & diagnostics")
    val useOnThisDevice: String get() = t("이 기기에서 사용", "Use on this device")
    // The English title is self-explanatory. Keep Korean supporting copy, but
    // omit English text rather than reserving an empty supporting-text line.
    val useOnThisDeviceSummary: String? get() = if (english) null else "이 기기에서 안내합니다."
    val autoEnableOnConnect: String get() = t("연결 시 자동 켜기", "Auto-enable on connect")
    val autoEnableOnConnectSummary: String get() = t("연결되면 안내를 켭니다.", "Turn on when connected.")
    val trackGuide: String get() = t("기본 안내", "Basic announcements")
    val guideDefaultsSummary: String get() = t("현재 안내 설정", "Current settings")
    val trackStart: String get() = t("재생 시작", "When playback starts")
    fun trackStartSummary(behavior: TrackStartBehavior): String = when (behavior) {
        TrackStartBehavior.PLAY_IMMEDIATELY -> t(
            "새 곡을 감지하면 음악과 함께 안내합니다.",
            "Announce over music when a new track starts.",
        )
        TrackStartBehavior.ANNOUNCE_THEN_PLAY -> t(
            "새 곡을 먼저 안내한 뒤 음악을 재생합니다.",
            "Announce the new track, then play music.",
        )
    }
    val announceThenPlaySummary: String get() = t(
        "안내 중 음악은 멈추고, 끝나면 다시 재생합니다.",
        "Music pauses during the announcement and resumes afterward.",
    )
    val musicDuringGuide: String get() = t("안내 중 음악", "Music during announcements")
    val musicVolumeSummary: String get() = t(
        "안내 중 음악은 자동으로 줄어들며, 정도는 기기와 음악 앱에 따라 다를 수 있습니다.",
        "Music is lowered automatically; the amount can vary by device and music app.",
    )
    val freeMusicDuckSummary: String get() = t(
        "안내 중 음악: 자동으로 줄이기",
        "Music during announcements: reduce automatically",
    )
    val freeGuideWithMusic: String get() = t("새 곡: 음악과 함께 안내", "New tracks: announce over music")
    val defaultFollowMediaVolume: String get() = t(
        "안내 음성: 음악 볼륨 따르기",
        "Announcement voice: follows media volume",
    )
    val freeGuideDetailsSummary: String get() = t(
        "안내 시점과 음량을 세밀하게 조절합니다.",
        "Fine-tune announcement timing and volume.",
    )
    val announcementTiming: String get() = t("언제 읽을까요?", "When to read")
    val announcementDelay: String get() = t("읽기 전 대기", "Wait before reading")
    fun announcementTimingSummary(
        timing: AnnouncementTiming,
        delaySeconds: Int,
        trackStartBehavior: TrackStartBehavior? = null,
    ): String {
        return when (timing) {
        AnnouncementTiming.IMMEDIATE -> t(
            "새 곡을 감지하면 바로 읽습니다. 대기 시간은 적용되지 않습니다.",
            "Reads as soon as a new track is detected. Wait time is ignored.",
        )
        AnnouncementTiming.DELAYED,
        AnnouncementTiming.BETWEEN_TRACKS,
        -> {
            val effectiveDelay = AnnouncementTimingPolicy.normalizeStoredDelaySeconds(timing, delaySeconds)
            t(
                "새 곡을 감지한 뒤 ${effectiveDelay}초 후 읽습니다.",
                "Reads ${effectiveDelay}s after a new track is detected.",
            )
        }
        }
    }
    val minimumPlayback: String get() = t("최소 재생 시간", "Minimum playback time")
    val repeatTrack: String get() = t("반복 재생도 다시 안내", "Announce repeated plays")
    val repeatTrackSummary: String get() = t("같은 곡이 새로 반복 재생되면 다시 안내합니다.", "Announce again when the same song starts a new repeat cycle.")
    val readingFieldsSection: String get() = t("읽을 항목", "Spoken information")
    val readingFieldsTitle: String get() = t("읽기 순서", "Announcement order")
    val readingFieldsSummary: String get() = t(
        "선택한 항목만 이 순서로 읽습니다.",
        "Only selected fields are read in this order.",
    )
    val contentReadOrderHint: String get() = t(
        "탭하여 선택 · 길게 눌러 순서 변경",
        "Tap to select · long-press to reorder",
    )
    val detailedGuidePlusTitle: String get() = t("상세 안내 설정", "Detailed guide settings")
    val screenAutomation: String get() = t("화면 자동화", "Screen automation")
    val screenOffEnable: String get() = t("화면 끄면 자동 켜기", "Enable on screen off")
    val screenOffEnableSummary: String get() = t("화면이 꺼지면 안내를 켭니다.", "Turn on when the screen turns off.")
    val screenOnRestore: String get() = t("화면 켜면 원래대로", "Restore on wake")
    val screenOnRestoreSummary: String get() = t("자동으로 켜진 안내를 해제합니다.", "Restore the previous state.")
    val bluetoothOnly: String get() = t("화면 끄면 Bluetooth에서만 켜기", "Require Bluetooth")
    val bluetoothOnlySummary: String get() = t("Bluetooth가 연결된 경우에만 안내를 켭니다.", "Only auto-enable with Bluetooth audio.")

    val appsIntro: String get() = t(
        "앱별로 TrackTalk 사용 여부만 선택합니다.",
        "Choose which apps TrackTalk monitors.",
    )
    val refresh: String get() = t("새로 고침", "Refresh")
    val visibleCategories: String get() = t("표시할 카테고리", "Visible categories")
    val scrollMore: String get() = t("카테고리 더 보기", "Scroll for more categories")
    fun appCountSummary(appCount: Int, categoryCount: Int): String = if (english) {
        val apps = if (appCount == 1) "app" else "apps"
        val categories = if (categoryCount == 1) "category" else "categories"
        "$appCount $apps shown · $categoryCount $categories selected"
    } else {
        "${appCount}개 앱 표시 · ${categoryCount}개 카테고리 선택"
    }
    val noSupportedApps: String get() = t("지원되는 음악 앱을 찾지 못했습니다.", "No supported music apps found.")
    val appAutoAddSummary: String get() = t("앱이 미디어 세션을 만들면 자동으로 추가됩니다.", "Apps are added automatically when they create a media session.")
    val noCategorySelected: String get() = t("선택한 카테고리가 없습니다. 위에서 하나 이상 선택하세요.", "No categories selected. Choose at least one above.")
    fun categoryTitle(category: AppCategory): String = when (category) {
        AppCategory.MUSIC_STREAMING -> t("음악 스트리밍", "Music streaming")
        AppCategory.MUSIC_VIDEO -> t("음악·동영상", "Music · video")
        AppCategory.LEARNING -> t("학습·오디오북", "Learning · audiobook")
        AppCategory.PODCAST -> t("팟캐스트·라디오", "Podcast · radio")
        AppCategory.OTHER -> t("기타 미디어", "Other media")
    }
    fun categoryDescription(category: AppCategory): String = when (category) {
        AppCategory.MUSIC_STREAMING -> t("음악을 중심으로 재생하는 앱", "Apps focused on music playback")
        AppCategory.MUSIC_VIDEO -> t("영상과 뮤직비디오를 재생하는 앱", "Apps for videos and music videos")
        AppCategory.LEARNING -> t("강의, 학습, 오디오북을 듣는 앱", "Apps for classes, study, and audiobooks")
        AppCategory.PODCAST -> t("팟캐스트와 라디오를 듣는 앱", "Apps for podcasts and radio")
        AppCategory.OTHER -> t("자동 분류되지 않은 미디어 앱", "Uncategorized media apps")
    }
    fun appCategoryCount(count: Int): String = if (english) "$count" else "${count}개"
    val appGuideEnabled: String get() = t("안내 사용 중", "Announcements on")
    val appGuideDisabled: String get() = t("안내 꺼짐", "Announcements off")
    val readTitle: String get() = t("곡명", "Title")
    val readArtist: String get() = t("아티스트", "Artist")
    val readTrackNumber: String get() = t("트랙 번호", "Track number")
    val readAlbum: String get() = t("앨범", "Album")
    fun readField(field: AnnouncementReadField): String = when (field) {
        AnnouncementReadField.TITLE -> readTitle
        AnnouncementReadField.ARTIST -> readArtist
        AnnouncementReadField.TRACK_NUMBER -> readTrackNumber
        AnnouncementReadField.ALBUM -> readAlbum
        AnnouncementReadField.COLLECTION -> t("재생목록 이름", "Playlist name")
    }

    val voiceSelection: String get() = t("음성 선택", "Voice")
    val defaultVoiceLanguage: String get() = t("기본 언어", "Speech language")
    val voiceLanguageHint: String get() = t("한글·영문·일문·중문이 섞이면 구간별 음성으로 자동 전환합니다.", "Mixed Korean, English, Japanese, and Chinese text switches voice by segment.")
    val gender: String get() = t("성별", "Gender")
    val voice: String get() = t("목소리", "Voice")
    val autoSelect: String get() = t("자동 선택", "Automatic")
    val noMatchingVoices: String get() = t("선택한 언어와 성별의 설치 음성이 없습니다.", "No installed voice matches the selected language and gender.")
    val voicePreview: String get() = t("음성 미리 듣기", "Preview")
    fun availableVoices(gender: GenderFilter, count: Int): String = if (english) {
        val noun = if (count == 1) "voice" else "voices"
        when (gender) {
            GenderFilter.FEMALE, GenderFilter.MALE -> "${genderLabel(gender)} $noun available: $count"
            else -> if (count == 1) "Voice available: 1" else "Voices available: $count"
        }
    } else {
        "선택 가능한 ${genderLabel(gender)} ${count}개"
    }
    val speechControls: String get() = t("말하기 조절", "Speech controls")
    val speechRate: String get() = t("속도", "Speed")
    val pitch: String get() = t("높이", "Pitch")
    val voiceVolumeSeparate: String get() = t("안내 음성 음량", "Announcement volume")
    fun announcementVolumeMode(mode: AnnouncementVolumeMode): String = when (mode) {
        AnnouncementVolumeMode.FOLLOW_MEDIA -> t("음악 볼륨 따르기", "Follow media volume")
        AnnouncementVolumeMode.CUSTOM -> t("직접 설정", "Custom")
    }
    val followMediaVolumeHint: String get() = t(
        "휴대폰의 미디어 볼륨을 따릅니다.",
        "Uses the current media volume.",
    )
    val customAnnouncementVolume: String get() = t("직접 설정 음량", "Custom volume")
    val testPlayback: String get() = t("테스트 재생", "Test voice")
    val testExample: String get() = t("예: Glass Eyes · Radiohead", "Example: Glass Eyes · Radiohead")
    val voiceControlsPlusTitle: String get() = t("음성 세밀 조절은 Plus 기능입니다.", "Detailed voice controls are a Plus feature.")
    val voiceControlsFreeSummary: String get() = t("말하기 속도·높이와 직접 설정 음량을 조절할 수 있습니다.", "Adjust speech speed, pitch, and a custom announcement volume.")

    val connectionStatus: String get() = t("연결 상태", "Connection status")
    val notificationAccess: String get() = t("알림 접근", "Notification access")
    val connected: String get() = t("연결됨", "Connected")
    val notConnected: String get() = t("연결되지 않음", "Not connected")
    val activeMediaSessions: String get() = t("활성 음악 세션", "Active media sessions")
    val selectedApp: String get() = t("선택한 앱", "Selected app")
    val none: String get() = t("없음", "None")
    val voiceEngine: String get() = t("음성 엔진", "Voice engine")
    val recentLog: String get() = t("최근 기록", "Recent log")
    val metadataDetected: String get() = t("곡 정보 감지", "Track metadata detected")
    val playbackDetected: String get() = t("재생 상태 감지", "Playback state detected")
    val lastAnnouncement: String get() = t("마지막 안내", "Last announcement")
    val announcementTime: String get() = t("안내 시각", "Announcement time")
    val appInfoTitle: String get() = t("앱 정보", "App info")
    val versionLabel: String get() = t("버전", "Version")
    val feedbackDeveloper: String get() = t("피드백 보내기", "Send feedback")
    val feedbackDeveloperSummary: String get() = t("의견이나 문제를 이메일로 보내 주세요.", "Send feedback or report a problem by email.")
    val noEmailApp: String get() = t("사용할 수 있는 이메일 앱이 없습니다.", "No compatible email app is available.")
    val privacy: String get() = t("개인정보", "Privacy")
    val privacySummary: String get() = t("곡 정보는 안내에만 사용하며 서버에 저장하지 않습니다.", "Track information is used only for announcements and is not stored on a server.")
    val currentTrackInfo: String get() = t("현재 곡 정보", "Current track information")
    val titleMissing: String get() = t("곡명 없음", "No track title")
    val artistMissing: String get() = t("아티스트 없음", "No artist")
    val permissionNeeded: String get() = t("권한 필요", "Permission required")
    val on: String get() = "ON"
    val off: String get() = "OFF"
    val permissionShort: String get() = t("권한", "Access")

    fun diagnosticMessage(message: DiagnosticMessage): String = when (message) {
        DiagnosticMessage.NEVER_ANNOUNCED -> t("아직 음성 안내가 실행되지 않았습니다.", "No announcement has run yet.")
        DiagnosticMessage.AUDIO_FOCUS_UNAVAILABLE -> t("오디오 포커스를 얻지 못해 안내를 건너뛰었습니다.", "Announcement skipped because audio focus was unavailable.")
        DiagnosticMessage.TTS_INITIALIZING -> t("TTS 초기화 중", "Initializing text-to-speech")
        DiagnosticMessage.TTS_INITIALIZATION_FAILED -> t("기본 TTS 엔진을 초기화하지 못했습니다.", "Couldn't initialize the default text-to-speech engine.")
        DiagnosticMessage.TTS_READY -> t("TTS 준비 완료", "Text-to-speech ready")
        DiagnosticMessage.TTS_NOTHING_TO_READ -> t("읽을 내용이 없습니다.", "Nothing to announce.")
        DiagnosticMessage.TTS_NOT_READY -> t("TTS가 아직 준비되지 않았습니다.", "Text-to-speech isn't ready yet.")
        DiagnosticMessage.TTS_INTERRUPTED -> t("이전 음성 안내가 중단되었습니다.", "The previous announcement was interrupted.")
        DiagnosticMessage.TTS_SYNTHESIS_FAILED -> t("TTS 음성 합성에 실패했습니다.", "Speech synthesis failed.")
        DiagnosticMessage.TTS_FALLBACK_LANGUAGE_AND_GENDER -> t("일부 언어 또는 성별 음성이 없어 기본 음성으로 안내합니다.", "Some language or gender voices are unavailable, so a default voice will be used.")
        DiagnosticMessage.TTS_FALLBACK_LANGUAGE -> t("일부 언어 음성이 없어 기본 음성으로 안내합니다.", "Some language voices are unavailable, so a default voice will be used.")
        DiagnosticMessage.TTS_FALLBACK_GENDER -> t("일부 언어에 지정한 성별 음성이 없어 가능한 기본 음성으로 안내합니다.", "The selected gender isn't available for every language, so a default voice will be used where needed.")
        DiagnosticMessage.TTS_CLOSED -> t("TTS 종료", "Text-to-speech stopped")
        DiagnosticMessage.TTS_COMPLETED -> t("다국어 음성 안내 완료", "Announcement completed")
        DiagnosticMessage.TTS_PLAYBACK_ERROR -> t("TTS 재생 중 오류가 발생했습니다.", "A text-to-speech playback error occurred.")
    }

    fun announcementMode(mode: AnnouncementMode): String = when (mode) {
        AnnouncementMode.SMART -> t("자동", "Automatic")
        AnnouncementMode.ALBUM -> t("앨범", "Album")
        AnnouncementMode.PLAYLIST -> t("재생목록", "Playlist")
        AnnouncementMode.TITLE_AND_ARTIST -> t("제목·아티스트", "Title · artist")
        AnnouncementMode.TITLE_ONLY -> t("제목", "Title")
    }

    fun announcementTiming(timing: AnnouncementTiming): String = when (timing) {
        AnnouncementTiming.IMMEDIATE -> t("바로 읽기", "Read now")
        AnnouncementTiming.DELAYED,
        AnnouncementTiming.BETWEEN_TRACKS,
        -> t("몇 초 후 읽기", "Read after a delay")
    }

    fun voiceLanguage(language: VoiceLanguage): String = when (language) {
        VoiceLanguage.AUTO -> t("제목에 맞게 자동", "Auto-detect from title")
        VoiceLanguage.SYSTEM -> t("시스템 언어", "System default")
        VoiceLanguage.KOREAN -> "한국어"
        VoiceLanguage.ENGLISH -> "English"
    }

    fun musicTreatment(treatment: MusicTreatment): String = when (treatment) {
        MusicTreatment.KEEP -> t("그대로 재생", "Keep music playing")
        MusicTreatment.DUCK -> t("음량 줄이기", "Reduce music volume")
        MusicTreatment.PAUSE -> t("일시정지 후 안내", "Pause during announcement")
    }
    val musicKeepSummary: String get() = t(
        "안내 중 음악은 바뀌지 않습니다.",
        "Music volume and playback are left unchanged during announcements.",
    )

    fun trackStartBehavior(behavior: TrackStartBehavior): String = when (behavior) {
        TrackStartBehavior.PLAY_IMMEDIATELY -> t("음악과 함께 안내", "Announce over music")
        TrackStartBehavior.ANNOUNCE_THEN_PLAY -> t("곡명 안내 후 재생", "Announce, then play")
    }

    fun genderLabel(gender: GenderFilter): String = when (gender) {
        GenderFilter.ANY,
        GenderFilter.UNSPECIFIED,
        -> t("자동 선택", "Automatic")
        GenderFilter.FEMALE -> t("여성 음성", "Female")
        GenderFilter.MALE -> t("남성 음성", "Male")
    }

    val automaticVoiceSelection: String get() = t(
        "언어에 맞는 음성을 자동으로 선택합니다.",
        "Automatically match language",
    )

    companion object {
        fun forLanguage(appLanguage: AppLanguage, systemLanguage: String): TrackTalkStrings =
            TrackTalkStrings(appLanguage.resolve(systemLanguage))
    }
}

val LocalTrackTalkStrings = staticCompositionLocalOf<TrackTalkStrings> {
    error("TrackTalk strings were not provided")
}
