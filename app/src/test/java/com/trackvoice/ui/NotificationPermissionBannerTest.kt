package com.trackvoice.ui

import com.trackvoice.data.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionBannerTest {
    @Test
    fun bannerIsShownOnlyWhenRuntimePermissionIsNeededAndMissing() {
        assertTrue(
            shouldShowNotificationPermissionBanner(
                requiresRuntimePermission = true,
                permissionGranted = false,
                statusNotificationEnabled = true,
            ),
        )
        assertFalse(
            shouldShowNotificationPermissionBanner(
                requiresRuntimePermission = true,
                permissionGranted = true,
                statusNotificationEnabled = true,
            ),
        )
        assertFalse(
            shouldShowNotificationPermissionBanner(
                requiresRuntimePermission = false,
                permissionGranted = false,
                statusNotificationEnabled = true,
            ),
        )
        assertFalse(
            shouldShowNotificationPermissionBanner(
                requiresRuntimePermission = true,
                permissionGranted = false,
                statusNotificationEnabled = false,
            ),
        )
        assertFalse(
            shouldShowNotificationPermissionBanner(
                requiresRuntimePermission = true,
                permissionGranted = false,
                statusNotificationEnabled = true,
                requiredPermissionGranted = false,
            ),
        )
    }

    @Test
    fun bannerCopyIsConciseAndLocalized() {
        val korean = TrackTalkStrings.forLanguage(AppLanguage.KOREAN, "en")
        assertEquals("상단바 상태 알림", korean.notificationPermissionTitle)
        assertEquals("현재 안내 상태를 확인하고 빠르게 켜거나 끕니다.", korean.notificationPermissionSummary)
        assertEquals("음악 감지 권한 필요", korean.musicDetectionPermissionTitle)
        assertEquals("필수", korean.requiredPermissionBadge)
        assertEquals("선택", korean.optionalPermissionBadge)
        assertEquals("허용", korean.allowNotifications)

        val english = TrackTalkStrings.forLanguage(AppLanguage.ENGLISH, "ko")
        assertEquals("Status notification", english.notificationPermissionTitle)
        assertEquals(
            "See TrackTalk's current status and quickly turn announcements on or off.",
            english.notificationPermissionSummary,
        )
        assertEquals("Music detection", english.musicDetectionPermissionTitle)
        assertEquals("Required", english.requiredPermissionBadge)
        assertEquals("Optional", english.optionalPermissionBadge)
        assertEquals("Allow", english.allowNotifications)
    }

    @Test
    fun homePermissionPresentationProgressivelyRevealsOptionalPermission() {
        val requiredMissing = resolveHomePermissionPresentation(
            requiredPermissionGranted = false,
            optionalPermissionGranted = false,
            requiresOptionalRuntimePermission = true,
            statusNotificationEnabled = true,
            isPremium = false,
        )
        assertTrue(requiredMissing.showRequiredPermission)
        assertFalse(requiredMissing.showOptionalPermission)
        assertFalse(requiredMissing.showPremiumPromotion)
        assertFalse(requiredMissing.showCurrentPlayback)

        val optionalMissing = resolveHomePermissionPresentation(
            requiredPermissionGranted = true,
            optionalPermissionGranted = false,
            requiresOptionalRuntimePermission = true,
            statusNotificationEnabled = true,
            isPremium = false,
        )
        assertFalse(optionalMissing.showRequiredPermission)
        assertTrue(optionalMissing.showOptionalPermission)
        assertTrue(optionalMissing.showPremiumPromotion)
        assertTrue(optionalMissing.showCurrentPlayback)

        val allGranted = resolveHomePermissionPresentation(
            requiredPermissionGranted = true,
            optionalPermissionGranted = true,
            requiresOptionalRuntimePermission = true,
            statusNotificationEnabled = true,
            isPremium = true,
        )
        assertFalse(allGranted.showRequiredPermission)
        assertFalse(allGranted.showOptionalPermission)
        assertFalse(allGranted.showPremiumPromotion)
        assertTrue(allGranted.showCurrentPlayback)
    }

    @Test
    fun revokingRequiredPermissionHidesStalePlaybackAndOptionalPrompt() {
        val state = resolveHomePermissionPresentation(
            requiredPermissionGranted = false,
            optionalPermissionGranted = true,
            requiresOptionalRuntimePermission = true,
            statusNotificationEnabled = true,
            isPremium = true,
        )
        assertTrue(state.showRequiredPermission)
        assertFalse(state.showOptionalPermission)
        assertFalse(state.showCurrentPlayback)
    }

    @Test
    fun revokingOptionalPermissionDoesNotAffectCorePlayback() {
        val state = resolveHomePermissionPresentation(
            requiredPermissionGranted = true,
            optionalPermissionGranted = false,
            requiresOptionalRuntimePermission = true,
            statusNotificationEnabled = true,
            isPremium = true,
        )
        assertFalse(state.showRequiredPermission)
        assertTrue(state.showOptionalPermission)
        assertTrue(state.showCurrentPlayback)
    }

    @Test
    fun notificationBannerIsNotNeededOnAndroidVersionsWithoutRuntimePermission() {
        val state = resolveHomePermissionPresentation(
            requiredPermissionGranted = true,
            optionalPermissionGranted = false,
            requiresOptionalRuntimePermission = false,
            statusNotificationEnabled = true,
            isPremium = false,
        )
        assertFalse(state.showOptionalPermission)
        assertTrue(state.showCurrentPlayback)
    }

    @Test
    fun homeStatusDoesNotUseWarningPermissionLabel() {
        val korean = TrackTalkStrings.forLanguage(AppLanguage.KOREAN, "en")
        assertEquals("ON · 설정 필요", homeStatusText(true, false, korean))
        assertEquals("ON", homeStatusText(true, true, korean))
        assertEquals("OFF", homeStatusText(false, false, korean))

        val english = TrackTalkStrings.forLanguage(AppLanguage.ENGLISH, "ko")
        assertEquals("ON · Setup needed", homeStatusText(true, false, english))
    }
}
