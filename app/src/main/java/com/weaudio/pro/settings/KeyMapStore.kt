package com.weaudio.pro.settings

import android.content.Context
import com.weaudio.pro.audio.keyboard.KeyMap

interface DrumSettingsStore {
    fun loadKeyMap(): KeyMap
    fun saveKeyMap(map: KeyMap)
    fun loadVelocity(): Float
    fun saveVelocity(v: Float)
}

class PrefsDrumSettingsStore(context: Context) : DrumSettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences("drum_settings", Context.MODE_PRIVATE)
    override fun loadKeyMap() = KeyMap.parse(prefs.getString("keymap", null))
    override fun saveKeyMap(map: KeyMap) { prefs.edit().putString("keymap", map.serialize()).apply() }
    override fun loadVelocity() = prefs.getFloat("velocity", DEFAULT_VELOCITY).coerceIn(0.1f, 1f)
    override fun saveVelocity(v: Float) { prefs.edit().putFloat("velocity", v).apply() }
    companion object { const val DEFAULT_VELOCITY = 0.8f }
}
