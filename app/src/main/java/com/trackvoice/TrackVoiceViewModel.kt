package com.trackvoice

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import com.trackvoice.service.TrackVoiceNotificationListenerService
import com.trackvoice.monetization.PremiumState
import kotlinx.coroutines.flow.StateFlow

class TrackVoiceViewModel(application: Application) : AndroidViewModel(application) {
    private val trackVoiceApplication = application as TrackVoiceApplication
    val controller = trackVoiceApplication.controller
    val repository = trackVoiceApplication.repository
    private val billingManager = trackVoiceApplication.billingManager
    val userSettings = controller.userSettings
    val appSettings = controller.appSettings
    val mediaState = controller.mediaState
    val diagnostics = controller.diagnostics
    val ttsState = controller.ttsState
    val installedVoices = controller.installedVoices
    val connectedAudioDevices = controller.connectedAudioDevices
    val audioDeviceSettings = controller.audioDeviceSettings
    val premiumState: StateFlow<PremiumState> = billingManager.state

    fun refreshNotificationAccess(context: Context) {
        val component = ComponentName(context, TrackVoiceNotificationListenerService::class.java)
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        val enabled = enabledListeners.split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == component }
        controller.setNotificationAccessGranted(enabled)
        trackVoiceApplication.statusNotificationManager.refresh()
    }

    fun refreshStatusNotification() = trackVoiceApplication.statusNotificationManager.refresh()

    fun refreshBilling() = billingManager.refresh()

    fun purchasePremium(activity: android.app.Activity) = billingManager.launchPurchase(activity)

    fun restorePremium() = billingManager.restorePurchases()

    fun redeemLocalPromoCode(rawCode: String): Boolean = billingManager.redeemLocalPlusCode(rawCode)
}
