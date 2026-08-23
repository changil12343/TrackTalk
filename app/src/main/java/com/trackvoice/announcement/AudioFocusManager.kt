package com.trackvoice.announcement

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.trackvoice.diagnostics.TrackTalkDebugLog

class AudioFocusManager(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null
    @Volatile
    private var focusOwner: FocusOwner? = null
    private var focusGeneration = 0L

    fun request(
        duck: Boolean,
        announcementCycleId: Long? = null,
        latencyCycleId: String? = null,
    ): Boolean {
        // Replace an existing request cleanly when a new TTS batch starts.
        abandon()
        return runCatching {
            val owner = FocusOwner(
                generation = ++focusGeneration,
                announcementCycleId = announcementCycleId,
                latencyCycleId = latencyCycleId,
                duck = duck,
            )
            val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
                val isCurrent = focusOwner === owner
                TrackTalkDebugLog.event(
                    "audio_focus",
                    "action" to "change",
                    "announcementCycleId" to owner.announcementCycleId,
                    "latencyCycleId" to owner.latencyCycleId,
                    "focusGeneration" to owner.generation,
                    "focusChange" to change,
                    "currentOwner" to isCurrent,
                    "origin" to if (owner.abandoning) "TRACKTALK_ABANDON" else "EXTERNAL_OR_SYSTEM",
                )
            }
            val request = AudioFocusRequest.Builder(
                if (duck) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
                .setAudioAttributes(TrackTalkAudioAttributes.speech())
                .setOnAudioFocusChangeListener(focusChangeListener)
                .setAcceptsDelayedFocusGain(false)
                .build()
            focusOwner = owner
            focusRequest = request
            val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            TrackTalkDebugLog.event(
                "audio_focus",
                "action" to "request",
                "announcementCycleId" to announcementCycleId,
                "latencyCycleId" to latencyCycleId,
                "focusGeneration" to owner.generation,
                "duck" to duck,
                "granted" to granted,
                "audioFocusMode" to if (duck) "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK" else "AUDIOFOCUS_GAIN_TRANSIENT",
                "usage" to TrackTalkAudioAttributes.USAGE_LABEL,
                "contentType" to TrackTalkAudioAttributes.CONTENT_TYPE_LABEL,
                "musicStreamVolumeBefore" to runCatching {
                    audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                }.getOrNull(),
                "musicStreamMaxVolume" to runCatching {
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                }.getOrNull(),
                "ttsVolumeControlStream" to TrackTalkAudioAttributes.speech().volumeControlStream,
                "ttsStreamVolumeBefore" to runCatching {
                    audioManager.getStreamVolume(TrackTalkAudioAttributes.speech().volumeControlStream)
                }.getOrNull(),
                "outputDeviceTypes" to runCatching {
                    audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .map { it.type }
                        .distinct()
                        .sorted()
                }.getOrNull(),
            )
            if (!granted) {
                focusRequest = null
                focusOwner = null
            }
            granted
        }.getOrDefault(false)
    }

    fun abandon() {
        val owner = focusOwner
        owner?.abandoning = true
        if (focusRequest != null) {
            TrackTalkDebugLog.event(
                "audio_focus",
                "action" to "abandon",
                "announcementCycleId" to owner?.announcementCycleId,
                "latencyCycleId" to owner?.latencyCycleId,
                "focusGeneration" to owner?.generation,
                "musicStreamVolumeAfter" to runCatching {
                    audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                }.getOrNull(),
                "ttsStreamVolumeAfter" to runCatching {
                    audioManager.getStreamVolume(TrackTalkAudioAttributes.speech().volumeControlStream)
                }.getOrNull(),
                "outputDeviceTypes" to runCatching {
                    audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .map { it.type }
                        .distinct()
                        .sorted()
                }.getOrNull(),
            )
        }
        runCatching { focusRequest?.let(audioManager::abandonAudioFocusRequest) }
        focusRequest = null
        if (focusOwner === owner) focusOwner = null
    }

    private data class FocusOwner(
        val generation: Long,
        val announcementCycleId: Long?,
        val latencyCycleId: String?,
        val duck: Boolean,
        @Volatile var abandoning: Boolean = false,
    )
}
