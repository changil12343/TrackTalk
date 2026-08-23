package com.trackvoice.service

import android.app.PendingIntent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackvoice.TrackVoiceApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackVoiceStatusNotificationActionReceiverInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val application: TrackVoiceApplication
        get() = context.applicationContext as TrackVoiceApplication

    @Test
    fun explicitTargetActionsToggleExistingSettingAndRemainIdempotent() = runBlocking {
        val original = application.repository.currentUserSettings()
        try {
            application.repository.setEnabled(true)
            awaitEnabled(true)

            sendNotificationAction(targetEnabled = false)
            assertFalse(awaitEnabled(false))

            sendNotificationAction(targetEnabled = false)
            assertFalse(awaitEnabled(false))

            sendNotificationAction(targetEnabled = true)
            assertTrue(awaitEnabled(true))
        } finally {
            application.repository.updateUserSettings { original }
            awaitEnabled(original.enabled)
        }
    }

    private suspend fun awaitEnabled(expected: Boolean): Boolean = withTimeout(5_000L) {
        application.repository.userSettings.first { settings -> settings.enabled == expected }.enabled
    }

    private suspend fun sendNotificationAction(targetEnabled: Boolean) {
        val action = StatusNotificationRenderer(context).build(
            StatusNotification(
                visible = true,
                appLanguage = application.repository.currentUserSettings().appLanguage,
                title = StatusNotificationTitle.ON,
                detail = StatusNotificationDetail.WaitingForMusic,
                actionTargetEnabled = targetEnabled,
            ),
        ).actions?.single()
            ?: error("Status notification must provide exactly one action")
        try {
            action.actionIntent.send()
        } catch (error: PendingIntent.CanceledException) {
            throw AssertionError("Status notification action was canceled", error)
        }
    }
}
