package com.trackvoice.ui

import com.trackvoice.data.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionBannerTest {
    @Test
    fun statusNotificationNeedsPermissionOnlyWhenRuntimePermissionIsMissing() {
        assertTrue(
            statusNotificationNeedsPermission(
                requiresRuntimePermission = true,
                permissionGranted = false,
            ),
        )
        assertFalse(
            statusNotificationNeedsPermission(
                requiresRuntimePermission = true,
                permissionGranted = true,
            ),
        )
        assertFalse(
            statusNotificationNeedsPermission(
                requiresRuntimePermission = false,
                permissionGranted = false,
            ),
        )
    }

    @Test
    fun statusNotificationCardCopyIsConciseAndLocalized() {
        val korean = TrackTalkStrings.forLanguage(AppLanguage.KOREAN, "en")
        assertEquals("상단바 상태 알림", korean.statusShortcut)
        assertEquals("현재 안내 상태를 상단바에서 확인합니다.", korean.statusShortcutSummary)
        assertEquals("알림을 표시하려면 권한이 필요합니다.", korean.statusShortcutPermissionSummary)
        assertEquals("음악 감지 권한 필요", korean.musicDetectionPermissionTitle)
        assertEquals("필수", korean.requiredPermissionBadge)
        assertEquals("권한 설정", korean.permissionSettings)

        val english = TrackTalkStrings.forLanguage(AppLanguage.ENGLISH, "ko")
        assertEquals("Status notification", english.statusShortcut)
        assertEquals("See TrackTalk's current status in the status bar.", english.statusShortcutSummary)
        assertEquals("Notification permission is required to show it.", english.statusShortcutPermissionSummary)
        assertEquals("Music detection", english.musicDetectionPermissionTitle)
        assertEquals("Required", english.requiredPermissionBadge)
        assertEquals("Open settings", english.permissionSettings)
    }

    @Test
    fun homePermissionPresentationKeepsCorePermissionAndPlaybackSemantics() {
        val requiredMissing = resolveHomePermissionPresentation(
            requiredPermissionGranted = false,
            isPremium = false,
        )
        assertTrue(requiredMissing.showRequiredPermission)
        assertFalse(requiredMissing.showPremiumPromotion)
        assertFalse(requiredMissing.showCurrentPlayback)

        val requiredGranted = resolveHomePermissionPresentation(
            requiredPermissionGranted = true,
            isPremium = false,
        )
        assertFalse(requiredGranted.showRequiredPermission)
        assertTrue(requiredGranted.showPremiumPromotion)
        assertTrue(requiredGranted.showCurrentPlayback)

        val premium = resolveHomePermissionPresentation(
            requiredPermissionGranted = true,
            isPremium = true,
        )
        assertFalse(premium.showRequiredPermission)
        assertFalse(premium.showPremiumPromotion)
        assertTrue(premium.showCurrentPlayback)
    }

    @Test
    fun revokingRequiredPermissionHidesStalePlayback() {
        val state = resolveHomePermissionPresentation(
            requiredPermissionGranted = false,
            isPremium = true,
        )
        assertTrue(state.showRequiredPermission)
        assertFalse(state.showCurrentPlayback)
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
