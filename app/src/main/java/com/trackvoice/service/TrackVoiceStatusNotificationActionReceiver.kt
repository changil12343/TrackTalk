package com.trackvoice.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.trackvoice.TrackVoiceApplication

/** Executes one explicit, target-state notification action without opening an Activity. */
class TrackVoiceStatusNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != StatusNotificationIntents.ACTION_TOGGLE) return
        if (!intent.hasExtra(StatusNotificationIntents.EXTRA_TARGET_ENABLED)) return
        val targetEnabled = intent.getBooleanExtra(
            StatusNotificationIntents.EXTRA_TARGET_ENABLED,
            false,
        )
        val pendingResult = goAsync()
        val application = context.applicationContext as? TrackVoiceApplication
        if (application == null) {
            pendingResult.finish()
            return
        }
        application.statusNotificationManager.applyToggleTarget(targetEnabled) {
            pendingResult.finish()
        }
    }
}
