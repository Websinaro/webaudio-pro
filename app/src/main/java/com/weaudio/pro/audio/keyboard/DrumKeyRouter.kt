package com.weaudio.pro.audio.keyboard

import com.weaudio.pro.audio.sampler.DrumInstrument

/** Framework-independent description of one KeyEvent (built in MainActivity). */
data class KeyInput(
    val keyCode: Int,
    val isDown: Boolean,
    val repeatCount: Int,
    val deviceId: Int,
    /** True only for a real, non-virtual keyboard source (not the soft keyboard, not a gamepad). */
    val isPhysicalKeyboard: Boolean,
    /** Ctrl/Alt/Meta held: leave the event to system/app shortcuts. */
    val hasShortcutModifier: Boolean = false,
)

sealed interface KeyResult {
    data object Ignored : KeyResult                          // not ours: let Android handle it
    data object Swallowed : KeyResult                        // ours but nothing to do (auto-repeat)
    data class Hit(val instrument: DrumInstrument) : KeyResult
    data class Released(val instrument: DrumInstrument) : KeyResult
}

/**
 * Turns key events into drum hits. Pure logic, no Android calls.
 *  - Triggers on the first key-down only: auto-repeat (repeatCount > 0, or a down for a key already
 *    held) is swallowed, so holding a key never machine-guns the sampler.
 *  - Tracks held keys per (device, key) so a key-up always pairs with its key-down, even if the
 *    mapping changed or the interface lost focus in between.
 *  - [enabled] is false while the drum surface is not focused / a text field is being edited.
 */
class DrumKeyRouter(var keyMap: KeyMap = KeyMap.defaults()) {
    private val held = HashMap<Long, DrumInstrument>()
    private fun id(device: Int, key: Int) = (device.toLong() shl 32) or (key.toLong() and 0xFFFFFFFFL)

    fun handle(input: KeyInput, enabled: Boolean): KeyResult {
        if (!input.isPhysicalKeyboard) return KeyResult.Ignored
        val id = id(input.deviceId, input.keyCode)
        if (!input.isDown) return held.remove(id)?.let { KeyResult.Released(it) } ?: KeyResult.Ignored
        if (input.hasShortcutModifier) return KeyResult.Ignored
        if (held.containsKey(id)) return KeyResult.Swallowed
        val instrument = keyMap.instrumentFor(input.keyCode) ?: return KeyResult.Ignored
        if (!enabled) return KeyResult.Ignored
        if (input.repeatCount > 0) return KeyResult.Swallowed   // repeat of a press we never saw (e.g. focus returned mid-hold)
        held[id] = instrument
        return KeyResult.Hit(instrument)
    }

    /** Focus lost / app paused: forget every held key. Returns instruments whose pads were showing pressed. */
    fun reset(): Set<DrumInstrument> = held.values.toSet().also { held.clear() }

    /** Keyboard unplugged. */
    fun releaseDevice(deviceId: Int): Set<DrumInstrument> {
        val gone = held.keys.filter { (it shr 32).toInt() == deviceId }
        return gone.mapNotNull { held.remove(it) }.toSet()
    }

    val heldCount: Int get() = held.size
}
