package com.weaudio.pro.arrangement

import com.weaudio.pro.project.TimeSignature

/** Result of an edit: the new project (or the unchanged one + [error]) and optionally the id of the created object. */
data class EditResult(val project: ArrangementProject, val createdId: String? = null, val error: String? = null) {
    val ok get() = error == null
}

fun interface IdSource { fun next(prefix: String): String }
class CounterIdSource(private var n: Long = 0) : IdSource { override fun next(prefix: String) = "$prefix-${++n}" }

private val TRACK_COLORS = longArrayOf(0xFF45C98A, 0xFF5AA9E6, 0xFFD9A441, 0xFFC77DDB, 0xFFE5534B, 0xFF7BD3D1, 0xFFB5C95A, 0xFFE08A5A)

/**
 * Every arrangement edit as a pure function: (project, args) -> new project. Nothing mutates; the ViewModel keeps
 * old projects on its undo stack. Invariants kept by every operation:
 *  - clips never reference a missing pattern or track (delete cascades),
 *  - a track's clips are ordered by start and never overlap,
 *  - ids are unique and stable (never array positions).
 */
class ArrangementEditor(private val ids: IdSource = CounterIdSource()) {

    private fun ok(p: ArrangementProject, id: String? = null) = EditResult(p, id)
    private fun fail(p: ArrangementProject, why: String) = EditResult(p, error = why)
    private fun ArrangementProject.mapTrack(id: String, f: (Track) -> Track) = copy(tracks = tracks.map { if (it.id == id) f(it) else it })
    private fun sorted(c: List<Clip>) = c.sortedBy { it.startTick }
    private fun overlaps(clips: List<Clip>, start: Long, end: Long, ignore: String? = null) =
        clips.any { it.id != ignore && it.startTick < end && start < it.endTick }

    // ---- tracks -------------------------------------------------------------------------
    fun addTrack(p: ArrangementProject, instrumentSlot: Int, name: String): EditResult {
        if (p.tracks.size >= MAX_TRACKS) return fail(p, "Track limit ($MAX_TRACKS) reached")
        val id = ids.next("track")
        val t = Track(id, name, instrumentSlot, colorArgb = TRACK_COLORS[p.tracks.size % TRACK_COLORS.size])
        return ok(p.copy(tracks = p.tracks + t), id)
    }
    fun removeTrack(p: ArrangementProject, trackId: String): EditResult =
        if (p.track(trackId) == null) fail(p, "No such track") else ok(p.copy(tracks = p.tracks.filter { it.id != trackId }))   // its clips go with it
    fun duplicateTrack(p: ArrangementProject, trackId: String): EditResult {
        val src = p.track(trackId) ?: return fail(p, "No such track")
        if (p.tracks.size >= MAX_TRACKS) return fail(p, "Track limit ($MAX_TRACKS) reached")
        val id = ids.next("track")
        val copy = src.copy(id = id, name = src.name + " copy", solo = false,
            clips = src.clips.map { it.copy(id = ids.next("clip"), trackId = id) })   // clips keep sharing the same patterns
        val at = p.tracks.indexOfFirst { it.id == trackId } + 1
        return ok(p.copy(tracks = p.tracks.take(at) + copy + p.tracks.drop(at)), id)
    }
    fun renameTrack(p: ArrangementProject, id: String, name: String) = ok(p.mapTrack(id) { it.copy(name = name.trim().ifEmpty { it.name }.take(24)) })
    fun setTrackInstrument(p: ArrangementProject, id: String, slot: Int) = ok(p.mapTrack(id) { it.copy(instrumentSlot = slot) })
    fun setTrackVolume(p: ArrangementProject, id: String, v: Float) = ok(p.mapTrack(id) { it.copy(volume = v.coerceIn(0f, 2f)) })
    fun setTrackPan(p: ArrangementProject, id: String, v: Float) = ok(p.mapTrack(id) { it.copy(pan = v.coerceIn(-1f, 1f)) })
    fun setTrackMute(p: ArrangementProject, id: String, m: Boolean) = ok(p.mapTrack(id) { it.copy(mute = m) })
    fun setTrackSolo(p: ArrangementProject, id: String, s: Boolean) = ok(p.mapTrack(id) { it.copy(solo = s) })

    // ---- patterns -----------------------------------------------------------------------
    fun createPattern(p: ArrangementProject, name: String, lengthTicks: Int = 4 * PPQ): EditResult {
        val id = ids.next("pattern")
        return ok(p.copy(patterns = p.patterns + Pattern(id, name, lengthTicks.coerceIn(TICKS_PER_SIXTEENTH, 16 * 4 * PPQ))), id)
    }
    fun duplicatePattern(p: ArrangementProject, patternId: String): EditResult {
        val src = p.pattern(patternId) ?: return fail(p, "No such pattern")
        val id = ids.next("pattern")
        return ok(p.copy(patterns = p.patterns + src.copy(id = id, name = src.name + " copy", events = src.events.map { it.copy(id = ids.next("ev")) })), id)
    }
    private fun ArrangementProject.mapPattern(id: String, f: (Pattern) -> Pattern) = copy(patterns = patterns.map { if (it.id == id) f(it) else it })
    fun clearPattern(p: ArrangementProject, id: String) = if (p.pattern(id) == null) fail(p, "No such pattern") else ok(p.mapPattern(id) { it.copy(events = emptyList()) })
    fun renamePattern(p: ArrangementProject, id: String, name: String) = ok(p.mapPattern(id) { it.copy(name = name.trim().ifEmpty { it.name }.take(24)) })
    /** Shortening drops events past the new end; clips keep referencing the pattern. */
    fun setPatternLength(p: ArrangementProject, id: String, lengthTicks: Int): EditResult {
        if (p.pattern(id) == null) return fail(p, "No such pattern")
        val len = lengthTicks.coerceIn(TICKS_PER_SIXTEENTH, 16 * 4 * PPQ)
        return ok(p.mapPattern(id) { pat -> pat.copy(lengthTicks = len, events = pat.events.filter { it.tick < len }) })
    }
    /** Toggles the hit at (instrument, tick); a new hit gets [velocity]. Edits the SHARED pattern (all clips using it). */
    fun toggleStep(p: ArrangementProject, patternId: String, slot: Int, tick: Int, velocity: Float = 0.8f): EditResult {
        val pat = p.pattern(patternId) ?: return fail(p, "No such pattern")
        if (tick < 0 || tick >= pat.lengthTicks) return fail(p, "Step outside the pattern")
        val existing = pat.events.firstOrNull { it.instrumentSlot == slot && it.tick == tick }
        val events = if (existing != null) pat.events - existing else pat.events + DrumEvent(ids.next("ev"), slot, tick, velocity.coerceIn(0.05f, 1f))
        return ok(p.mapPattern(patternId) { it.copy(events = events.sortedBy { e -> e.tick }) })
    }
    fun setStepVelocity(p: ArrangementProject, patternId: String, slot: Int, tick: Int, velocity: Float): EditResult {
        val pat = p.pattern(patternId) ?: return fail(p, "No such pattern")
        if (pat.events.none { it.instrumentSlot == slot && it.tick == tick }) return fail(p, "No hit at this step")
        return ok(p.mapPattern(patternId) { it.copy(events = it.events.map { e -> if (e.instrumentSlot == slot && e.tick == tick) e.copy(velocity = velocity.coerceIn(0.05f, 1f)) else e }) })
    }
    /** Deleting a pattern removes every clip that references it (no dangling references). */
    fun deletePattern(p: ArrangementProject, id: String): EditResult =
        if (p.pattern(id) == null) fail(p, "No such pattern")
        else ok(p.copy(patterns = p.patterns.filter { it.id != id }, tracks = p.tracks.map { t -> t.copy(clips = t.clips.filter { it.patternId != id }) }))

    // ---- clips --------------------------------------------------------------------------
    fun addClip(p: ArrangementProject, trackId: String, patternId: String, startTick: Long, durationTicks: Long? = null): EditResult {
        val t = p.track(trackId) ?: return fail(p, "No such track")
        val pat = p.pattern(patternId) ?: return fail(p, "No such pattern")
        val start = startTick.coerceAtLeast(0)
        val dur = durationTicks ?: pat.lengthTicks.toLong()
        if (dur <= 0) return fail(p, "Clip needs a positive length")
        if (overlaps(t.clips, start, start + dur)) return fail(p, "A clip already occupies this range")
        val id = ids.next("clip")
        return ok(p.mapTrack(trackId) { it.copy(clips = sorted(it.clips + Clip(id, trackId, start, dur, patternId, pat.name))) }, id)
    }
    fun deleteClip(p: ArrangementProject, clipId: String): EditResult {
        val c = p.clip(clipId) ?: return fail(p, "No such clip")
        return ok(p.mapTrack(c.trackId) { it.copy(clips = it.clips.filter { x -> x.id != clipId }) })
    }
    /** Moves to [newStart] and optionally onto another track. Refused if it would overlap or go below 0. */
    fun moveClip(p: ArrangementProject, clipId: String, newStart: Long, newTrackId: String? = null): EditResult {
        val c = p.clip(clipId) ?: return fail(p, "No such clip")
        val target = p.track(newTrackId ?: c.trackId) ?: return fail(p, "No such track")
        val start = newStart.coerceAtLeast(0)
        if (overlaps(target.clips, start, start + c.durationTicks, ignore = clipId)) return fail(p, "A clip already occupies this range")
        val moved = c.copy(startTick = start, trackId = target.id)
        val without = p.mapTrack(c.trackId) { it.copy(clips = it.clips.filter { x -> x.id != clipId }) }
        return ok(without.mapTrack(target.id) { it.copy(clips = sorted(it.clips + moved)) })
    }
    /** Copy placed right after the original (or in the next free gap on that track). Shares the pattern. */
    fun duplicateClip(p: ArrangementProject, clipId: String): EditResult {
        val c = p.clip(clipId) ?: return fail(p, "No such clip")
        val t = p.track(c.trackId)!!
        var start = c.endTick
        while (overlaps(t.clips, start, start + c.durationTicks)) start = t.clips.filter { it.startTick < start + c.durationTicks && start < it.endTick }.maxOf { it.endTick }
        val id = ids.next("clip")
        return ok(p.mapTrack(t.id) { it.copy(clips = sorted(it.clips + c.copy(id = id, startTick = start))) }, id)
    }
    /** Splits at [atTick] into two clips sharing the pattern; the right half continues the pattern phase seamlessly. */
    fun splitClip(p: ArrangementProject, clipId: String, atTick: Long): EditResult {
        val c = p.clip(clipId) ?: return fail(p, "No such clip")
        if (atTick <= c.startTick || atTick >= c.endTick) return fail(p, "Playhead is not inside the clip")
        val len = p.pattern(c.patternId)?.lengthTicks ?: return fail(p, "Missing pattern")
        val left = c.copy(durationTicks = atTick - c.startTick)
        val rightId = ids.next("clip")
        val phase = (((c.offsetTicks + (atTick - c.startTick)) % len) + len) % len
        val right = c.copy(id = rightId, startTick = atTick, durationTicks = c.endTick - atTick, offsetTicks = phase.toInt())
        return ok(p.mapTrack(c.trackId) { it.copy(clips = sorted(it.clips.filter { x -> x.id != clipId } + left + right)) }, rightId)
    }
    /** Changes the clip's range. Moving the left edge keeps the pattern content in place (offset follows). */
    fun setClipRange(p: ArrangementProject, clipId: String, newStart: Long, newEnd: Long): EditResult {
        val c = p.clip(clipId) ?: return fail(p, "No such clip")
        val len = p.pattern(c.patternId)?.lengthTicks ?: return fail(p, "Missing pattern")
        if (newStart < 0 || newEnd <= newStart) return fail(p, "Invalid clip range")
        val t = p.track(c.trackId)!!
        if (overlaps(t.clips, newStart, newEnd, ignore = clipId)) return fail(p, "A clip already occupies this range")
        val shift = newStart - c.startTick
        val offset = (((c.offsetTicks + shift) % len) + len) % len
        return ok(p.mapTrack(c.trackId) { it.copy(clips = sorted(it.clips.map { x -> if (x.id == clipId) x.copy(startTick = newStart, durationTicks = newEnd - newStart, offsetTicks = offset.toInt()) else x })) })
    }
    fun setClipLoop(p: ArrangementProject, clipId: String, loop: Boolean): EditResult {
        val c = p.clip(clipId) ?: return fail(p, "No such clip")
        return ok(p.mapTrack(c.trackId) { it.copy(clips = it.clips.map { x -> if (x.id == clipId) x.copy(loop = loop) else x }) })
    }
    /** "Make unique": gives this clip its own copy of the pattern so later edits no longer affect other clips. */
    fun makeClipUnique(p: ArrangementProject, clipId: String): EditResult {
        val c = p.clip(clipId) ?: return fail(p, "No such clip")
        val dup = duplicatePattern(p, c.patternId)
        if (!dup.ok) return dup
        val newId = dup.createdId!!
        return ok(dup.project.mapTrack(c.trackId) { it.copy(clips = it.clips.map { x -> if (x.id == clipId) x.copy(patternId = newId, name = dup.project.pattern(newId)!!.name) else x }) }, newId)
    }

    // ---- project-level -------------------------------------------------------------------
    fun setTempo(p: ArrangementProject, bpm: Double) = ok(p.copy(tempoBpm = bpm.coerceIn(MIN_TEMPO, MAX_TEMPO)))
    fun setTimeSignature(p: ArrangementProject, sig: TimeSignature) = ok(p.copy(timeSignature = sig))
    fun setQuantize(p: ArrangementProject, q: Quantize) = ok(p.copy(playback = p.playback.copy(quantize = q)))
    fun setLoop(p: ArrangementProject, loop: LoopRegion) = ok(p.copy(playback = p.playback.copy(loop = loop)))
}
