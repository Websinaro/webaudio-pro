package com.weaudio.pro.arrangement

/** Flat, tick-based playback data handed to the native Sequencer (parallel arrays: cheap to pass through JNI). */
class SeqData(
    val ticks: LongArray, val tracks: IntArray, val slots: IntArray, val velocities: FloatArray, val pitches: FloatArray,
    val lengthTicks: Long, val loopEnabled: Boolean, val loopStart: Long, val loopEnd: Long, val truncated: Boolean = false,
) { val count get() = ticks.size }

const val NATIVE_MAX_EVENTS = 32768

/**
 * Expands clips into absolute-tick events. A clip plays pattern content from [Clip.offsetTicks]; with
 * [Clip.loop] the pattern repeats until the clip ends, otherwise it plays once. Nothing at or after a
 * clip's end is emitted (clip boundary), disabled events are skipped. The track's native index is its list position.
 */
fun flatten(project: ArrangementProject, maxEvents: Int = NATIVE_MAX_EVENTS): SeqData {
    val t = ArrayList<Long>(); val tr = ArrayList<Int>(); val sl = ArrayList<Int>(); val v = ArrayList<Float>(); val p = ArrayList<Float>()
    var truncated = false
    outer@ for ((index, track) in project.tracks.withIndex()) {
        for (clip in track.clips) {
            val pattern = project.pattern(clip.patternId) ?: continue
            val len = pattern.lengthTicks
            if (len <= 0 || clip.durationTicks <= 0) continue
            val offset = ((clip.offsetTicks % len) + len) % len
            val cycles = if (clip.loop) (clip.durationTicks + offset + len - 1) / len else 1L
            for (k in 0 until cycles) for (e in pattern.events) {
                if (!e.enabled || e.tick < 0 || e.tick >= len) continue
                val rel = e.tick - offset + k * len
                if (rel < 0 || rel >= clip.durationTicks) continue
                if (!clip.loop && e.tick < offset) continue
                if (t.size >= maxEvents) { truncated = true; break@outer }
                t.add(clip.startTick + rel); tr.add(index); sl.add(e.instrumentSlot); v.add(e.velocity.coerceIn(0f, 1f)); p.add(e.pitchSemitones)
            }
        }
    }
    val loop = project.playback.loop
    return SeqData(t.toLongArray(), tr.toIntArray(), sl.toIntArray(), v.toFloatArray(), p.toFloatArray(), project.lengthTicks,
        loop.enabled && loop.endTick > loop.startTick, loop.startTick, loop.endTick, truncated)
}

/** Preview data: one pattern looped on the reserved preview track (all lanes), independent of the arrangement. */
fun flattenPreview(pattern: Pattern): SeqData {
    val ev = pattern.events.filter { it.enabled && it.tick in 0 until pattern.lengthTicks }
    return SeqData(LongArray(ev.size) { ev[it].tick.toLong() }, IntArray(ev.size) { PREVIEW_TRACK_INDEX }, IntArray(ev.size) { ev[it].instrumentSlot },
        FloatArray(ev.size) { ev[it].velocity }, FloatArray(ev.size) { ev[it].pitchSemitones },
        pattern.lengthTicks.toLong(), true, 0, pattern.lengthTicks.toLong())
}
