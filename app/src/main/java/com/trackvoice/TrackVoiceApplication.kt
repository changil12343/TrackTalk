package com.trackvoice

import android.app.Application
import com.trackvoice.data.DataStoreRepository
import com.trackvoice.monetization.PlayBillingManager
import com.trackvoice.service.TrackVoiceStatusNotificationManager

class TrackVoiceApplication : Application() {
    lateinit var repository: DataStoreRepository
        private set
    lateinit var controller: TrackVoiceController
        private set
    lateinit var billingManager: PlayBillingManager
        private set
    lateinit var statusNotificationManager: TrackVoiceStatusNotificationManager
        private set

    override fun onCreate() {
        super.onCreate()
        repository = DataStoreRepository(this)
        billingManager = PlayBillingManager(this)
        controller = TrackVoiceController(this, repository, billingManager.state)
        statusNotificationManager = TrackVoiceStatusNotificationManager(
            context = this,
            repository = repository,
            controller = controller,
            premiumState = billingManager.state,
        ).also(TrackVoiceStatusNotificationManager::start)
        billingManager.connect()
    }
}
