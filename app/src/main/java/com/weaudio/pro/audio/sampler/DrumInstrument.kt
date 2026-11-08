package com.weaudio.pro.audio.sampler

import android.view.KeyEvent

/** The 12 drum instruments. [slot] is the native sampler slot; [fileName] is the sample file in a kit folder. */
enum class DrumInstrument(val slot: Int, val label: String, val fileName: String, val defaultKeyCode: Int) {
    KICK(0, "Kick", "kick.wav", KeyEvent.KEYCODE_A),
    SNARE(1, "Snare", "snare.wav", KeyEvent.KEYCODE_S),
    CLOSED_HAT(2, "Closed Hi-Hat", "closed_hat.wav", KeyEvent.KEYCODE_D),
    OPEN_HAT(3, "Open Hi-Hat", "open_hat.wav", KeyEvent.KEYCODE_F),
    CRASH(4, "Crash", "crash.wav", KeyEvent.KEYCODE_G),
    RIDE(5, "Ride", "ride.wav", KeyEvent.KEYCODE_H),
    TOM_HIGH(6, "High Tom", "tom_high.wav", KeyEvent.KEYCODE_J),
    TOM_MID(7, "Mid Tom", "tom_mid.wav", KeyEvent.KEYCODE_K),
    TOM_FLOOR(8, "Floor Tom", "tom_floor.wav", KeyEvent.KEYCODE_L),
    CLAP(9, "Clap", "clap.wav", KeyEvent.KEYCODE_Q),
    RIMSHOT(10, "Rimshot", "rimshot.wav", KeyEvent.KEYCODE_W),
    PERCUSSION(11, "Percussion", "percussion.wav", KeyEvent.KEYCODE_E);

    companion object {
        /** Pad layout, top row first (a conventional kit: cymbals, toms, then kick/snare/hand percussion). */
        val PAD_ORDER = listOf(CRASH, RIDE, OPEN_HAT, CLOSED_HAT, TOM_HIGH, TOM_MID, TOM_FLOOR, PERCUSSION, KICK, SNARE, CLAP, RIMSHOT)
        fun fromSlot(slot: Int) = entries.firstOrNull { it.slot == slot }
    }
}
