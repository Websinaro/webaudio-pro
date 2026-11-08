package com.weaudio.pro.audio.keyboard

import android.view.KeyEvent
import com.weaudio.pro.audio.sampler.DrumInstrument

/**
 * Instrument -> Android keyCode. One key per instrument; a key drives at most one instrument.
 * Bindings use [KeyEvent.getKeyCode], i.e. the character the key produces in the user's layout
 * (an AZERTY user pressing the key labelled "A" gets Kick). Immutable.
 */
data class KeyMap(val bindings: Map<DrumInstrument, Int>) {
    fun instrumentFor(keyCode: Int): DrumInstrument? = bindings.entries.firstOrNull { it.value == keyCode }?.key
    fun keyFor(instrument: DrumInstrument): Int? = bindings[instrument]

    /** Binds [keyCode] to [instrument]; any other instrument that used the key becomes unbound. */
    fun bind(instrument: DrumInstrument, keyCode: Int): KeyMap =
        KeyMap(bindings.filterValues { it != keyCode }.minus(instrument) + (instrument to keyCode))

    fun unbind(instrument: DrumInstrument) = KeyMap(bindings - instrument)

    fun serialize(): String = bindings.entries.joinToString(",") { "${it.key.name}=${it.value}" }

    companion object {
        fun defaults() = KeyMap(DrumInstrument.entries.associateWith { it.defaultKeyCode })

        /** Tolerant parser: unknown names / garbage entries are skipped; null/blank gives defaults. */
        fun parse(text: String?): KeyMap {
            if (text.isNullOrBlank()) return defaults()
            var map = KeyMap(emptyMap())
            for (part in text.split(',')) {
                val (name, code) = part.split('=').takeIf { it.size == 2 } ?: continue
                val inst = DrumInstrument.entries.firstOrNull { it.name == name } ?: continue
                val key = code.toIntOrNull()?.takeIf { it > 0 } ?: continue
                map = map.bind(inst, key)
            }
            return map
        }

        /** Short human label, e.g. "A", "F5", "Space". */
        fun label(keyCode: Int): String = try {
            KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace('_', ' ')
        } catch (e: RuntimeException) {   // android.jar stubs in plain JVM unit tests
            if (keyCode in 29..54) ('A' + (keyCode - 29)).toString() else "#$keyCode"
        }
    }
}
