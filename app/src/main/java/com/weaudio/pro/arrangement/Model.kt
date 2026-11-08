package com.weaudio.pro.arrangement

import com.weaudio.pro.project.TimeSignature

/**
 * Musical time. The ONLY position unit is the integer tick: 960 ticks per quarter note (PPQ),
 * identical to the native engine (cpp/engine/Sequencer.h).
 *
 *   ticksPerBeat(den) = 960 * 4 / den       4/4 -> 960      6/8 -> 480
 *   ticksPerBar       = numerator * ticksPerBeat
 *   sixteenth note    = 240 ticks           a 16-step pattern in 4/4 = 3840 ticks = 1 bar
 *   seconds per tick  = 60 / (bpm * 960)    (only the native callback converts ticks to frames)
 */
const val PPQ = 960
const val TICKS_PER_SIXTEENTH = PPQ / 4
const val MAX_TRACKS = 24                // native supports 32; the last slot is reserved for pattern preview
const val PREVIEW_TRACK_INDEX = 31
const val MIN_TEMPO = 20.0
const val MAX_TEMPO = 400.0
val SUPPORTED_SIGNATURES = listOf(TimeSignature(2, 4), TimeSignature(3, 4), TimeSignature(4, 4), TimeSignature(5, 4),
    TimeSignature(6, 8), TimeSignature(7, 8), TimeSignature(12, 8))

fun ticksPerBeat(sig: TimeSignature): Int = PPQ * 4 / sig.denominator
fun ticksPerBar(sig: TimeSignature): Int = sig.numerator * ticksPerBeat(sig)

data class BarBeat(val bar: Int, val beat: Int, val sixteenth: Int)   // zero-based
fun ticksToBarBeat(tick: Long, sig: TimeSignature): BarBeat {
    val t = tick.coerceAtLeast(0)
    val bar = ticksPerBar(sig).toLong()
    val inBar = t % bar
    val beat = ticksPerBeat(sig)
    return BarBeat((t / bar).toInt(), (inBar / beat).toInt(), ((inBar % beat) / TICKS_PER_SIXTEENTH).toInt())
}
fun formatBarBeat(tick: Long, sig: TimeSignature): String =
    ticksToBarBeat(tick, sig).let { "%03d.%d.%d".format(java.util.Locale.US, it.bar + 1, it.beat + 1, it.sixteenth + 1) }

/** Grid used for snapping / step entry. [ticks] == 0 means "one bar" (depends on the time signature). */
enum class Quantize(val label: String, val ticks: Int) {
    BAR("Bar", 0), BEAT("1/4", PPQ), EIGHTH("1/8", PPQ / 2), SIXTEENTH("1/16", PPQ / 4), THIRTY_SECOND("1/32", PPQ / 8), OFF("Off", 1);
    fun gridTicks(sig: TimeSignature) = if (this == BAR) ticksPerBar(sig) else ticks
    fun snap(tick: Long, sig: TimeSignature): Long {
        val g = gridTicks(sig).toLong()
        return if (g <= 1) tick else ((tick + g / 2) / g) * g
    }
}

/** One drum hit inside a pattern. [tick] is relative to the pattern start. */
data class DrumEvent(
    val id: String,
    val instrumentSlot: Int,          // DrumSampler slot / DrumInstrument.slot
    val tick: Int,
    val velocity: Float = 0.8f,       // 0..1
    val pitchSemitones: Float = 0f,   // optional per-event pitch offset
    val enabled: Boolean = true,
)

/** A reusable drum pattern. Clips reference it by [id]; editing it changes every clip that uses it. */
data class Pattern(
    val id: String,
    val name: String,
    val lengthTicks: Int = 4 * PPQ,
    val events: List<DrumEvent> = emptyList(),
)

/**
 * A placed pattern. Plays pattern content starting at [offsetTicks] into the pattern (non-zero after a split),
 * repeating when [loop] is true, for [durationTicks] timeline ticks.
 */
data class Clip(
    val id: String,
    val trackId: String,
    val startTick: Long,
    val durationTicks: Long,
    val patternId: String,
    val name: String,
    val loop: Boolean = true,
    val offsetTicks: Int = 0,
) { val endTick get() = startTick + durationTicks }

data class Track(
    val id: String,
    val name: String,
    val instrumentSlot: Int,          // the track's primary instrument (default row/focus for new patterns)
    val volume: Float = 1f,           // 0..2
    val pan: Float = 0f,              // -1..1
    val mute: Boolean = false,
    val solo: Boolean = false,
    val colorArgb: Long = 0xFF45C98A,
    val clips: List<Clip> = emptyList(),   // ordered by startTick
)

data class LoopRegion(val startTick: Long = 0, val endTick: Long = 0, val enabled: Boolean = false)
data class PlaybackSettings(val loop: LoopRegion = LoopRegion(), val quantize: Quantize = Quantize.SIXTEENTH)

/** Whole session. Pure immutable data (serialisable in Phase 4); identity is by stable string ids. */
data class ArrangementProject(
    val id: String = "project-1",
    val name: String = "Untitled Project",
    val tempoBpm: Double = 120.0,
    val timeSignature: TimeSignature = TimeSignature(4, 4),
    val tracks: List<Track> = emptyList(),
    val patterns: List<Pattern> = emptyList(),
    val minBars: Int = 8,
    val playback: PlaybackSettings = PlaybackSettings(),
) {
    fun pattern(id: String?) = patterns.firstOrNull { it.id == id }
    fun track(id: String?) = tracks.firstOrNull { it.id == id }
    fun clip(id: String?): Clip? = tracks.firstNotNullOfOrNull { t -> t.clips.firstOrNull { it.id == id } }
    fun trackIndex(id: String?) = tracks.indexOfFirst { it.id == id }
    fun patternUseCount(patternId: String) = tracks.sumOf { t -> t.clips.count { it.patternId == patternId } }
    /** Arrangement length: at least [minBars], grown to the last clip end rounded up to a bar. */
    val lengthTicks: Long get() {
        val bar = ticksPerBar(timeSignature).toLong()
        val lastEnd = tracks.maxOfOrNull { t -> t.clips.maxOfOrNull { it.endTick } ?: 0L } ?: 0L
        return maxOf(minBars * bar, ((lastEnd + bar - 1) / bar) * bar)
    }
}
