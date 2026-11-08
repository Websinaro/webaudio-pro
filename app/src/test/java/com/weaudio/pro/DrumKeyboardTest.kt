package com.weaudio.pro

import android.view.KeyEvent
import com.weaudio.pro.audio.keyboard.*
import com.weaudio.pro.audio.sampler.DrumInstrument
import org.junit.Assert.*
import org.junit.Test

class DrumKeyboardTest {
    private fun down(code: Int, repeat: Int = 0, dev: Int = 5, physical: Boolean = true, mod: Boolean = false) = KeyInput(code, true, repeat, dev, physical, mod)
    private fun up(code: Int, dev: Int = 5) = KeyInput(code, false, 0, dev, true)

    @Test fun defaultMappingMatchesSpecification() {
        val m = KeyMap.defaults()
        val expect = mapOf("A" to DrumInstrument.KICK, "S" to DrumInstrument.SNARE, "D" to DrumInstrument.CLOSED_HAT, "F" to DrumInstrument.OPEN_HAT,
            "G" to DrumInstrument.CRASH, "H" to DrumInstrument.RIDE, "J" to DrumInstrument.TOM_HIGH, "K" to DrumInstrument.TOM_MID,
            "L" to DrumInstrument.TOM_FLOOR, "Q" to DrumInstrument.CLAP, "W" to DrumInstrument.RIMSHOT, "E" to DrumInstrument.PERCUSSION)
        for ((letter, inst) in expect) assertEquals(inst, m.instrumentFor(KeyEvent.KEYCODE_A + (letter[0] - 'A')))
        assertEquals(12, m.bindings.size)
    }

    @Test fun keyDownTriggersOnceAndAutoRepeatIsSwallowed() {
        val r = DrumKeyRouter()
        assertEquals(KeyResult.Hit(DrumInstrument.KICK), r.handle(down(KeyEvent.KEYCODE_A), true))
        assertEquals(KeyResult.Swallowed, r.handle(down(KeyEvent.KEYCODE_A, repeat = 1), true))
        assertEquals(KeyResult.Swallowed, r.handle(down(KeyEvent.KEYCODE_A, repeat = 0), true))   // down while held
        assertEquals(KeyResult.Released(DrumInstrument.KICK), r.handle(up(KeyEvent.KEYCODE_A), true))
        assertEquals(KeyResult.Hit(DrumInstrument.KICK), r.handle(down(KeyEvent.KEYCODE_A), true))  // re-press works
    }

    @Test fun unmappedVirtualAndModifiedKeysAreIgnored() {
        val r = DrumKeyRouter()
        assertEquals(KeyResult.Ignored, r.handle(down(KeyEvent.KEYCODE_Z), true))
        assertEquals(KeyResult.Ignored, r.handle(down(KeyEvent.KEYCODE_A, physical = false), true))
        assertEquals(KeyResult.Ignored, r.handle(down(KeyEvent.KEYCODE_A, mod = true), true))
        assertEquals(0, r.heldCount)
    }

    @Test fun disabledWhenNotFocusedOrTyping() {
        val r = DrumKeyRouter()
        assertEquals(KeyResult.Ignored, r.handle(down(KeyEvent.KEYCODE_A), enabled = false))
        assertEquals(0, r.heldCount)
        // press while enabled, focus lost, release while disabled: still pairs up
        r.handle(down(KeyEvent.KEYCODE_S), true)
        assertEquals(KeyResult.Released(DrumInstrument.SNARE), r.handle(up(KeyEvent.KEYCODE_S), false))
    }

    @Test fun focusLossAndDisconnectionReleaseHeldKeys() {
        val r = DrumKeyRouter()
        r.handle(down(KeyEvent.KEYCODE_A, dev = 1), true); r.handle(down(KeyEvent.KEYCODE_S, dev = 2), true)
        assertEquals(setOf(DrumInstrument.KICK), r.releaseDevice(1))
        assertEquals(1, r.heldCount)
        assertEquals(setOf(DrumInstrument.SNARE), r.reset()); assertEquals(0, r.heldCount)
        assertEquals(KeyResult.Hit(DrumInstrument.SNARE), r.handle(down(KeyEvent.KEYCODE_S, dev = 2), true))
    }

    @Test fun remapMovesKeyAndSerializes() {
        val m = KeyMap.defaults().bind(DrumInstrument.CLAP, KeyEvent.KEYCODE_A)
        assertEquals(DrumInstrument.CLAP, m.instrumentFor(KeyEvent.KEYCODE_A)); assertNull(m.keyFor(DrumInstrument.KICK))
        assertNull(m.keyFor(DrumInstrument.KICK)); assertNull(m.instrumentFor(KeyEvent.KEYCODE_Q))
        assertEquals(m, KeyMap.parse(m.serialize()))
        assertEquals(KeyMap.defaults(), KeyMap.parse(null))
        assertTrue(KeyMap.parse("garbage,KICK=abc,NOPE=3").bindings.isEmpty())
        assertEquals(setOf(DrumInstrument.KICK), KeyMap.parse("KICK=29,x,=,SNARE=-1").bindings.keys)
    }
}
