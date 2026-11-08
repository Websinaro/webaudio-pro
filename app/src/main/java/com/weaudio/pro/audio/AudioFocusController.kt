package com.weaudio.pro.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

enum class FocusEvent { GAINED, LOST_TRANSIENT, LOST }

/** Wraps Android audio focus so calls/other media can interrupt the engine politely. */
class AudioFocusController(context: Context, private val onEvent: (FocusEvent) -> Unit) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var request: AudioFocusRequest? = null

    val isHeld: Boolean get() = request != null

    fun acquire() {
        if (request != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> onEvent(FocusEvent.GAINED)
                    AudioManager.AUDIOFOCUS_LOSS -> onEvent(FocusEvent.LOST)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onEvent(FocusEvent.LOST_TRANSIENT)
                }
            }
            .build()
        request = req
        audioManager.requestAudioFocus(req)   // a DAW keeps playing even if focus is refused
    }

    fun abandon() {
        request?.let { audioManager.abandonAudioFocusRequest(it) }
        request = null
    }
}
