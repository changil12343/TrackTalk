package com.trackvoice.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import com.trackvoice.TrackVoiceApplication
import com.trackvoice.diagnostics.TrackTalkDebugLog

class TrackVoiceNotificationListenerService : NotificationListenerService() {
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val controller = application.controller
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> controller.onScreenOff()
                Intent.ACTION_SCREEN_ON -> controller.onScreenOn()
            }
        }
    }
    private var receiverRegistered = false

    private val application: TrackVoiceApplication
        get() = getApplication() as TrackVoiceApplication

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != "com.google.android.apps.youtube.music") return
        val extras = sbn.notification.extras
        TrackTalkDebugLog.event(
            "NOTIFICATION_MEDIA_EVIDENCE",
            "package" to sbn.packageName,
            "category" to sbn.notification.category,
            "ongoing" to sbn.isOngoing,
            "extras" to extras?.keySet()?.sorted()?.joinToString(",", prefix = "[", postfix = "]"),
            "trackLikeCandidates" to extras.trackLikeNumericCandidates(),
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        application.controller.attachNotificationListener()
        application.controller.attachMediaSessionMonitor(this)
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    override fun onListenerDisconnected() {
        application.controller.detachNotificationListener(preservePlaybackHistory = true)
        unregisterScreenReceiver()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        application.controller.detachNotificationListener(preservePlaybackHistory = true)
        unregisterScreenReceiver()
        super.onDestroy()
    }

    private fun unregisterScreenReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(screenReceiver) }
        receiverRegistered = false
    }

    private fun Bundle?.trackLikeNumericCandidates(): String = this
        ?.keySet()
        ?.asSequence()
        ?.filter { key ->
            val normalized = key.lowercase(java.util.Locale.ROOT)
            normalized.contains("track") ||
                normalized.contains("disc") ||
                normalized.contains("number") ||
                normalized.contains("position") ||
                normalized.contains("index")
        }
        ?.sorted()
        ?.mapNotNull { key ->
            runCatching {
                when (val value = get(key)) {
                    is Byte, is Short, is Int, is Long, is Float, is Double -> "$key=$value"
                    is String -> value.trim().toLongOrNull()?.let { "$key=$it" }
                    else -> null
                }
            }.getOrNull()
        }
        ?.joinToString(",")
        .orEmpty()
}
