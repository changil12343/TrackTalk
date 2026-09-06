package com.trackvoice.service

import android.app.Notification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaNotificationReconciliationEligibilityTest {
    @Test
    fun onlyYoutubeMusicTransportNotificationWithMediaSessionEvidenceIsEligible() {
        assertTrue(
            MediaNotificationReconciliationEligibility.isEligible(
                packageName = "com.google.android.apps.youtube.music",
                category = Notification.CATEGORY_TRANSPORT,
                hasMediaSessionToken = true,
            ),
        )
    }

    @Test
    fun unrelatedOrNonMediaNotificationsAreIgnored() {
        assertFalse(
            MediaNotificationReconciliationEligibility.isEligible(
                packageName = "com.android.settings",
                category = Notification.CATEGORY_TRANSPORT,
                hasMediaSessionToken = true,
            ),
        )
        assertFalse(
            MediaNotificationReconciliationEligibility.isEligible(
                packageName = "com.google.android.apps.youtube.music",
                category = Notification.CATEGORY_MESSAGE,
                hasMediaSessionToken = true,
            ),
        )
        assertFalse(
            MediaNotificationReconciliationEligibility.isEligible(
                packageName = "com.google.android.apps.youtube.music",
                category = Notification.CATEGORY_TRANSPORT,
                hasMediaSessionToken = false,
            ),
        )
    }
}
