package com.weaudio.pro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.weaudio.pro.audio.AudioFocusController
import com.weaudio.pro.audio.AudioViewModel
import com.weaudio.pro.audio.EngineState
import com.weaudio.pro.audio.FocusEvent
import com.weaudio.pro.ui.theme.WeAudioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val audio: AudioViewModel by viewModels { AudioViewModel.factory(applicationContext) }
    private lateinit var focus: AudioFocusController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        focus = AudioFocusController(this) { event ->
            when (event) {
                FocusEvent.GAINED -> audio.endInterruption("focus")
                FocusEvent.LOST_TRANSIENT -> audio.beginInterruption("focus")
                FocusEvent.LOST -> { audio.pause(); focus.abandon() }
            }
        }

        // Backgrounding releases the output (pause); returning resumes only if it was playing.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = audio.beginInterruption("background")
            override fun onStart(owner: LifecycleOwner) = audio.endInterruption("background")
            override fun onDestroy(owner: LifecycleOwner) { if (isFinishing) audio.shutdown() }
        })

        // Hold audio focus while a transport session is active; drop it when fully stopped.
        lifecycleScope.launch {
            audio.state.map { it.engine in ACTIVE }.distinctUntilChanged().collect { active ->
                if (active) focus.acquire() else focus.abandon()
            }
        }
        // Poll real engine state/meters only while the UI is visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { audio.poll(); delay(POLL_MS) }
            }
        }

        audio.initialize()
        setContent { WeAudioTheme { WeAudioApp(audio) } }
    }

    private companion object {
        const val POLL_MS = 150L
        val ACTIVE = setOf(EngineState.STARTING, EngineState.RUNNING, EngineState.PAUSED, EngineState.DISCONNECTED)
    }
}
