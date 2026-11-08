package com.weaudio.pro.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/** Audio focus compatibility for Android 7+; Oboe selects a supported backend per device. */
enum class FocusEvent { GAINED, LOST_TRANSIENT, LOST }

class AudioFocusController(context: Context, private val onEvent: (FocusEvent) -> Unit) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var request: AudioFocusRequest? = null
    private val legacyListener = AudioManager.OnAudioFocusChangeListener { change -> dispatch(change) }
    private var legacyHeld = false

    val isHeld: Boolean get() = request != null || legacyHeld

    fun acquire() {
        if (isHeld) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(legacyListener)
                .build()
            request = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                legacyListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            )
            legacyHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    fun abandon() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request?.let { audioManager.abandonAudioFocusRequest(it) }
            request = null
        } else if (legacyHeld) {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(legacyListener)
            legacyHeld = false
        }
    }

    private fun dispatch(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> onEvent(FocusEvent.GAINED)
            AudioManager.AUDIOFOCUS_LOSS -> onEvent(FocusEvent.LOST)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onEvent(FocusEvent.LOST_TRANSIENT)
        }
    }
}
