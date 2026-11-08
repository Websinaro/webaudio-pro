package com.weaudio.pro

import android.content.Context
import android.hardware.input.InputManager
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import com.weaudio.pro.audio.keyboard.KeyInput
import com.weaudio.pro.studio.drum.DrumViewModel
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
    private val drums: DrumViewModel by viewModels { DrumViewModel.factory(applicationContext) }
    private lateinit var focus: AudioFocusController

    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshKeyboardState()
        override fun onInputDeviceChanged(deviceId: Int) = refreshKeyboardState()
        override fun onInputDeviceRemoved(deviceId: Int) {
            drums.onKeyboardDeviceRemoved(deviceId)   // release held keys of an unplugged keyboard
            refreshKeyboardState()
        }
    }

    private fun refreshKeyboardState() {
        val connected = InputDevice.getDeviceIds().any { id ->
            InputDevice.getDevice(id)?.let { !it.isVirtual && it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
                (it.sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD } == true
        }
        drums.setKeyboardConnected(connected)
    }

    // Physical keyboard -> drum sampler. Runs before Compose sees the event; consumed only when a
    // mapped drum key is pressed while the Studio is focused (otherwise normal key handling continues).
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val device = event.device
        val physical = device != null && !device.isVirtual && (event.source and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
        val input = KeyInput(
            keyCode = event.keyCode, isDown = event.action == KeyEvent.ACTION_DOWN, repeatCount = event.repeatCount,
            deviceId = event.deviceId, isPhysicalKeyboard = physical,
            hasShortcutModifier = event.isCtrlPressed || event.isAltPressed || event.isMetaPressed,
        )
        if (event.action != KeyEvent.ACTION_MULTIPLE && drums.onKeyEvent(input)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        drums.setWindowFocused(hasFocus)
    }

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
            override fun onStart(owner: LifecycleOwner) {
                audio.endInterruption("background")
                (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(inputListener, null)
                refreshKeyboardState()
            }
            override fun onStop(owner: LifecycleOwner) {
                audio.beginInterruption("background")
                drums.setWindowFocused(false)
                (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(inputListener)
            }
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
        setContent { WeAudioTheme { WeAudioApp(audio, drums) } }
    }

    private companion object {
        const val POLL_MS = 150L
        val ACTIVE = setOf(EngineState.STARTING, EngineState.RUNNING, EngineState.PAUSED, EngineState.DISCONNECTED)
    }
}
