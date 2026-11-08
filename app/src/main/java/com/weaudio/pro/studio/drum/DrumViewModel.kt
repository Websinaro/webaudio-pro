package com.weaudio.pro.studio.drum

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.weaudio.pro.audio.keyboard.DrumKeyRouter
import com.weaudio.pro.audio.keyboard.KeyInput
import com.weaudio.pro.audio.keyboard.KeyMap
import com.weaudio.pro.audio.keyboard.KeyResult
import com.weaudio.pro.audio.sampler.DrumBackend
import com.weaudio.pro.audio.sampler.DrumInstrument
import com.weaudio.pro.audio.sampler.NativeDrumBackend
import com.weaudio.pro.samples.AssetKitSource
import com.weaudio.pro.samples.KitInfo
import com.weaudio.pro.samples.KitSource
import com.weaudio.pro.settings.DrumSettingsStore
import com.weaudio.pro.settings.PrefsDrumSettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SampleStatus { EMPTY, LOADING, LOADED, ERROR }

data class InstrumentState(
    val instrument: DrumInstrument,
    val status: SampleStatus = SampleStatus.EMPTY,
    val sampleName: String = "",
    val isUserSample: Boolean = false,
    val error: String? = null,
    val volume: Float = 1f,
    val pitchSemitones: Float = 0f,
    val muted: Boolean = false,
)

data class DrumUiState(
    val instruments: List<InstrumentState> = DrumInstrument.entries.map { InstrumentState(it) },
    val kits: List<KitInfo> = emptyList(),
    val kitId: String? = null,
    val selected: DrumInstrument = DrumInstrument.KICK,
    val velocity: Float = 0.8f,
    val keyMap: KeyMap = KeyMap.defaults(),
    val captureTarget: DrumInstrument? = null,
    val keyboardConnected: Boolean = false,
    val kitLoading: Boolean = false,
    val message: String? = null,
) {
    operator fun get(i: DrumInstrument) = instruments.first { it.instrument == i }
    val loadedCount get() = instruments.count { it.status == SampleStatus.LOADED }
}

/**
 * Owns all drum-instrument UI state; survives rotation (and, for kit/selection, process death via
 * [SavedStateHandle]). Touch and keyboard both end in [trigger] -> DrumBackend.trigger, a direct,
 * non-blocking call into the native sampler (no coroutine hop, no delay).
 */
class DrumViewModel(
    private val backend: DrumBackend,
    private val kitSource: KitSource,
    private val settings: DrumSettingsStore,
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pressVisibleMs: Long = 80L,
) : ViewModel() {

    private val _state = MutableStateFlow(DrumUiState())
    val state: StateFlow<DrumUiState> = _state.asStateFlow()
    private val _pressed = MutableStateFlow<Set<DrumInstrument>>(emptySet())
    /** Pads currently showing a press. Visual only: audio is triggered before this is updated. */
    val pressed: StateFlow<Set<DrumInstrument>> = _pressed.asStateFlow()

    private val router = DrumKeyRouter()
    private val pressTokens = HashMap<DrumInstrument, Int>()
    private var studioVisible = false
    private var windowFocused = true
    private var textInputActive = false

    init {
        val map = settings.loadKeyMap()
        router.keyMap = map
        val selected = saved.get<String>(KEY_SELECTED)?.let { n -> DrumInstrument.entries.firstOrNull { it.name == n } }
        _state.update { it.copy(keyMap = map, velocity = settings.loadVelocity(), selected = selected ?: it.selected) }
        val kits = kitSource.kits()
        _state.update { it.copy(kits = kits) }
        val kit = saved.get<String>(KEY_KIT)?.takeIf { id -> kits.any { it.id == id } } ?: kits.firstOrNull()?.id
        if (kit != null) loadKit(kit)
        else _state.update { it.copy(message = "No drum kits found in the app assets") }
    }

    // ---- triggering -------------------------------------------------------------------
    /** Fires the native sampler immediately. Returns whether the hit was accepted. */
    fun trigger(instrument: DrumInstrument, velocity: Float = _state.value.velocity): Boolean {
        val ok = backend.trigger(instrument.slot, velocity)
        showPressed(instrument)
        return ok
    }

    fun release(instrument: DrumInstrument) {
        val token = pressTokens.getOrDefault(instrument, 0)
        viewModelScope.launch {
            delay(pressVisibleMs)   // keep very short taps visible; purely cosmetic
            if (pressTokens.getOrDefault(instrument, 0) == token) _pressed.update { it - instrument }
        }
    }

    private fun showPressed(i: DrumInstrument) {
        pressTokens[i] = pressTokens.getOrDefault(i, 0) + 1
        _pressed.update { it + i }
    }

    fun select(i: DrumInstrument) { saved[KEY_SELECTED] = i.name; _state.update { it.copy(selected = i) } }

    // ---- physical keyboard --------------------------------------------------------------
    /** Called for every KeyEvent from the Activity. Returns true if the event was consumed. */
    fun onKeyEvent(input: KeyInput): Boolean {
        val capture = _state.value.captureTarget
        if (capture != null && input.isPhysicalKeyboard && windowFocused && !input.hasShortcutModifier) {
            if (!input.isDown) return true
            if (input.repeatCount == 0) {
                if (input.keyCode == KEYCODE_ESCAPE) _state.update { it.copy(captureTarget = null) }
                else applyKeyMap(_state.value.keyMap.bind(capture, input.keyCode), captureTarget = null)
            }
            return true
        }
        return when (val r = router.handle(input, enabled = studioVisible && windowFocused && !textInputActive)) {
            is KeyResult.Hit -> { trigger(r.instrument); true }
            is KeyResult.Released -> { release(r.instrument); true }
            KeyResult.Swallowed -> true
            KeyResult.Ignored -> false
        }
    }

    fun setStudioVisible(visible: Boolean) { studioVisible = visible; if (!visible) releaseAllKeys() }
    fun setWindowFocused(focused: Boolean) { windowFocused = focused; if (!focused) releaseAllKeys() }
    fun setTextInputActive(active: Boolean) { textInputActive = active; if (active) releaseAllKeys() }
    fun onKeyboardDeviceRemoved(deviceId: Int) { router.releaseDevice(deviceId).forEach(::release) }
    fun setKeyboardConnected(connected: Boolean) = _state.update { it.copy(keyboardConnected = connected) }
    private fun releaseAllKeys() { router.reset().forEach(::release) }

    fun beginKeyCapture(i: DrumInstrument) = _state.update { it.copy(captureTarget = i) }
    fun cancelKeyCapture() = _state.update { it.copy(captureTarget = null) }
    fun clearKey(i: DrumInstrument) = applyKeyMap(_state.value.keyMap.unbind(i), _state.value.captureTarget)
    fun resetKeyMap() = applyKeyMap(KeyMap.defaults(), null)
    private fun applyKeyMap(map: KeyMap, captureTarget: DrumInstrument?) {
        router.keyMap = map
        releaseAllKeys()
        settings.saveKeyMap(map)
        _state.update { it.copy(keyMap = map, captureTarget = captureTarget) }
    }

    fun setVelocity(v: Float) {
        val c = v.coerceIn(0.1f, 1f)
        settings.saveVelocity(c); _state.update { it.copy(velocity = c) }
    }

    // ---- per-instrument parameters ----------------------------------------------------
    fun setVolume(i: DrumInstrument, v: Float) { backend.setVolume(i.slot, v); updateInst(i) { it.copy(volume = v) } }
    fun setPitch(i: DrumInstrument, st: Float) { backend.setPitch(i.slot, st); updateInst(i) { it.copy(pitchSemitones = st) } }
    fun setMuted(i: DrumInstrument, m: Boolean) { backend.setMuted(i.slot, m); updateInst(i) { it.copy(muted = m) } }
    private fun updateInst(i: DrumInstrument, f: (InstrumentState) -> InstrumentState) =
        _state.update { s -> s.copy(instruments = s.instruments.map { if (it.instrument == i) f(it) else it }) }

    // ---- kits and samples --------------------------------------------------------------
    fun loadKit(kitId: String) {
        saved[KEY_KIT] = kitId
        _state.update { s ->
            s.copy(kitId = kitId, kitLoading = true, message = null,
                instruments = s.instruments.map { it.copy(status = SampleStatus.LOADING, error = null, isUserSample = false) })
        }
        viewModelScope.launch {
            for (inst in DrumInstrument.entries) loadFromKit(inst, kitId)
            _state.update { it.copy(kitLoading = false) }
        }
    }

    private suspend fun loadFromKit(inst: DrumInstrument, kitId: String) {
        val result = withContext(io) {
            val bytes = kitSource.read(kitId, inst) ?: return@withContext "Missing file ${inst.fileName} in kit" to ""
            backend.loadSample(inst.slot, bytes) to inst.fileName
        }
        applyLoadResult(inst, result.first, result.second, user = false)
    }

    /** Replaces one instrument's sample with a user-provided WAV (bytes already read from SAF). */
    fun replaceSample(inst: DrumInstrument, name: String, bytes: ByteArray) {
        updateInst(inst) { it.copy(status = SampleStatus.LOADING, error = null) }
        viewModelScope.launch {
            val err = withContext(io) { backend.loadSample(inst.slot, bytes) }
            applyLoadResult(inst, err, name, user = true)
        }
    }

    fun reportImportError(inst: DrumInstrument, message: String) =
        updateInst(inst) { it.copy(error = message) }

    fun restoreOriginal(inst: DrumInstrument) {
        val kit = _state.value.kitId ?: return
        updateInst(inst) { it.copy(status = SampleStatus.LOADING, error = null) }
        viewModelScope.launch { loadFromKit(inst, kit) }
    }

    private fun applyLoadResult(inst: DrumInstrument, error: String, name: String, user: Boolean) {
        val cur = _state.value[inst]
        if (error.isEmpty()) {
            // Native parameters survive a sample swap; re-assert them so UI and engine always agree.
            backend.setVolume(inst.slot, cur.volume); backend.setPitch(inst.slot, cur.pitchSemitones); backend.setMuted(inst.slot, cur.muted)
            updateInst(inst) { it.copy(status = SampleStatus.LOADED, sampleName = name, isUserSample = user, error = null) }
        } else {
            // A failed user replacement leaves the previous sample playing (native keeps it).
            val stillLoaded = backend.isLoaded(inst.slot)
            updateInst(inst) { it.copy(status = if (stillLoaded) SampleStatus.LOADED else SampleStatus.ERROR, error = error) }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() { backend.stopAll() }

    companion object {
        private const val KEY_KIT = "kit"
        private const val KEY_SELECTED = "selected"
        const val KEYCODE_ESCAPE = 111

        fun factory(context: Context) = viewModelFactory {
            val app = context.applicationContext
            initializer {
                DrumViewModel(NativeDrumBackend(), AssetKitSource(app), PrefsDrumSettingsStore(app), createSavedStateHandle())
            }
        }
    }
}
