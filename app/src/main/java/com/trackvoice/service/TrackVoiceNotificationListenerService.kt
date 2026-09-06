package com.trackvoice.service

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
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
        val eligibleMediaHint = MediaNotificationReconciliationEligibility.isEligible(
            packageName = sbn.packageName,
            category = sbn.notification.category,
            hasMediaSessionToken = extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true,
        )
        TrackTalkDebugLog.event(
            "NOTIFICATION_MEDIA_EVIDENCE",
            "package" to sbn.packageName,
            "category" to sbn.notification.category,
            "ongoing" to sbn.isOngoing,
            "extras" to extras?.keySet()?.sorted()?.joinToString(",", prefix = "[", postfix = "]"),
            "trackLikeCandidates" to extras.trackLikeNumericCandidates(),
            "eligibleMediaHint" to eligibleMediaHint,
        )
        if (eligibleMediaHint) {
            // Notification data is never metadata or a transition proof. It only asks the
            // existing serialized MediaSession path to take one fresh authoritative snapshot.
            application.controller.onMediaNotificationReconcileHint(sbn.packageName)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        TrackTalkDebugLog.event("LISTENER_CONNECTED")
        // The Application/controller is reconstructed before this callback when Android recreates
        // the process. Re-attaching here deliberately needs no Activity/UI launch.
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
        TrackTalkDebugLog.event("LISTENER_DISCONNECTED")
        application.controller.detachNotificationListener(preservePlaybackHistory = true)
        unregisterScreenReceiver()
        requestListenerRebind()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        TrackTalkDebugLog.event("LISTENER_DESTROYED")
        application.controller.detachNotificationListener(preservePlaybackHistory = true)
        unregisterScreenReceiver()
        super.onDestroy()
    }

    private fun unregisterScreenReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(screenReceiver) }
        receiverRegistered = false
    }

    /**
     * This is Android's documented recovery path after a listener disconnects. It is a single
     * request to the system, not a foreground service or an app-managed retry loop. If the user
     * revoked notification access Android will simply keep the listener disconnected.
     */
    private fun requestListenerRebind() {
        try {
            requestRebind(ComponentName(this, TrackVoiceNotificationListenerService::class.java))
            TrackTalkDebugLog.event("LISTENER_REBIND_REQUESTED")
        } catch (failure: SecurityException) {
            TrackTalkDebugLog.event(
                "LISTENER_REBIND_FAILED",
                "failure" to failure.javaClass.simpleName,
            )
        } catch (failure: IllegalStateException) {
            TrackTalkDebugLog.event(
                "LISTENER_REBIND_FAILED",
                "failure" to failure.javaClass.simpleName,
            )
        }
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

/** Narrows notification-triggered reconciliation to supported media evidence only. */
internal object MediaNotificationReconciliationEligibility {
    fun isEligible(
        packageName: String,
        category: String?,
        hasMediaSessionToken: Boolean,
    ): Boolean =
        packageName == "com.google.android.apps.youtube.music" &&
            category == Notification.CATEGORY_TRANSPORT &&
            hasMediaSessionToken
}
