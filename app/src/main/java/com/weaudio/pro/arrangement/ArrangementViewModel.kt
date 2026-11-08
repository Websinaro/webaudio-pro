package com.weaudio.pro.arrangement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.weaudio.pro.audio.sampler.DrumInstrument
import com.weaudio.pro.project.TimeSignature
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class StudioTab { ARRANGE, PATTERN, PADS }
enum class TransportMode { STOPPED, PLAYING, PAUSED, AUDIO_PAUSED, PREVIEW }

/** What the transport UI shows. Built only from values READ from the native engine (never assumed). */
data class TransportUi(val mode: TransportMode = TransportMode.STOPPED, val positionTicks: Long = 0, val label: String = "001.1.1") {
    val playing get() = mode == TransportMode.PLAYING
}

fun presentTransport(seq: SeqState, engineRunning: Boolean, ticks: Long, sig: TimeSignature, previewing: Boolean): TransportUi {
    val mode = when {
        previewing && seq == SeqState.PLAYING -> TransportMode.PREVIEW
        seq == SeqState.PLAYING && !engineRunning -> TransportMode.AUDIO_PAUSED   // transport armed, but the stream is paused/interrupted
        seq == SeqState.PLAYING -> TransportMode.PLAYING
        seq == SeqState.PAUSED -> TransportMode.PAUSED
        else -> TransportMode.STOPPED
    }
    return TransportUi(mode, ticks, formatBarBeat(ticks, sig))
}

data class ArrangementUiState(
    val project: ArrangementProject,
    val selectedTrackId: String? = null,
    val selectedClipId: String? = null,
    val selectedPatternId: String? = null,
    val previewPatternId: String? = null,
    val viewport: TimelineViewport = TimelineViewport(),
    val trackHeightDp: Float = 56f,
    val tab: StudioTab = StudioTab.ARRANGE,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val message: String? = null,
) {
    val selectedClip get() = project.clip(selectedClipId)
    val selectedTrack get() = project.track(selectedTrackId)
    val selectedPattern get() = project.pattern(selectedPatternId)
}

/** Ticks per step cell in the pattern editor for a quantize setting (bar/off fall back to 1/16). */
fun stepTicksFor(q: Quantize): Int = when (q) { Quantize.BAR, Quantize.OFF -> TICKS_PER_SIXTEENTH; else -> q.ticks }

/**
 * UI state + editing for the multitrack arrangement. Owns the single source of truth for the project; the native
 * Sequencer only ever receives flattened snapshots (never timers or per-track callbacks). Survives rotation
 * because it is an Activity-scoped ViewModel.
 */
class ArrangementViewModel(
    private val backend: SequencerBackend,
    private val editor: ArrangementEditor = ArrangementEditor(),
    initial: ArrangementProject? = null,
    private val maxUndo: Int = 100,
) : ViewModel() {
    /** Set by the Activity: starts the audio stream / reports whether it is running. */
    var ensureAudio: () -> Unit = {}
    var engineRunning: () -> Boolean = { true }
    var viewWidthDp: Float = 0f

    private val start = initial ?: defaultProject(editor)
    private val _state = MutableStateFlow(ArrangementUiState(start, selectedTrackId = start.tracks.firstOrNull()?.id,
        selectedClipId = null, selectedPatternId = start.patterns.firstOrNull()?.id))
    val state: StateFlow<ArrangementUiState> = _state.asStateFlow()
    private val _transport = MutableStateFlow(TransportUi())
    val transport: StateFlow<TransportUi> = _transport.asStateFlow()
    private val undo = ArrayDeque<ArrangementProject>()
    private val redo = ArrayDeque<ArrangementProject>()
    private val project get() = _state.value.project

    init { backend.setTempo(start.tempoBpm); backend.setTimeSignature(start.timeSignature.numerator, start.timeSignature.denominator); pushAll() }

    // ---- publishing -----------------------------------------------------------------------
    private fun pushAll() { syncTracks(); publish(); }
    private fun syncTracks() {
        val p = project
        p.tracks.forEachIndexed { i, t -> backend.setTrack(i, t.volume, t.pan, t.mute, t.solo) }
        for (i in p.tracks.size until MAX_TRACKS) backend.setTrack(i, 1f, 0f, false, false)   // free indices must not keep stale solo/mute
    }
    private fun publish() {
        val s = _state.value
        if (s.previewPatternId != null) { s.project.pattern(s.previewPatternId)?.let { backend.publish(flattenPreview(it)) }; return }
        val data = flatten(project)
        val accepted = backend.publish(data)
        if (!accepted || data.truncated) note("Arrangement too dense for the sequencer: some events were not scheduled")
    }
    private fun note(m: String?) = _state.update { it.copy(message = m) }
    fun dismissMessage() = note(null)

    /** Applies an edit: undo-able, republishes the arrangement to the native engine, keeps selection valid. */
    private fun commit(r: EditResult, republish: Boolean = true, structural: Boolean = false): Boolean {
        if (!r.ok) { note(r.error); return false }
        if (r.project == project) return true
        undo.addLast(project); while (undo.size > maxUndo) undo.removeFirst(); redo.clear()
        setProject(r.project, structural)
        return true
    }
    private fun setProject(p: ArrangementProject, structural: Boolean = true) {
        _state.update { s ->
            val trackId = p.track(s.selectedTrackId)?.id ?: p.tracks.firstOrNull()?.id
            val clipId = p.clip(s.selectedClipId)?.id
            val patId = p.pattern(s.selectedPatternId)?.id ?: p.patterns.firstOrNull()?.id
            s.copy(project = p, selectedTrackId = trackId, selectedClipId = clipId, selectedPatternId = patId,
                previewPatternId = s.previewPatternId?.takeIf { p.pattern(it) != null }, canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty(), message = null)
        }
        if (structural) syncTracks()
        publish()
    }
    fun undo() { val prev = undo.removeLastOrNull() ?: return; redo.addLast(project); setProject(prev); }
    fun redo() { val next = redo.removeLastOrNull() ?: return; undo.addLast(project); setProject(next) }

    // ---- selection / view -----------------------------------------------------------------
    fun selectTrack(id: String) = _state.update { it.copy(selectedTrackId = id) }
    fun selectClip(id: String?) = _state.update { s ->
        val c = s.project.clip(id)
        s.copy(selectedClipId = c?.id, selectedTrackId = c?.trackId ?: s.selectedTrackId, selectedPatternId = c?.patternId ?: s.selectedPatternId)
    }
    fun selectPattern(id: String) = _state.update { it.copy(selectedPatternId = id) }
    fun setTab(tab: StudioTab) = _state.update { it.copy(tab = tab) }
    fun zoomBy(factor: Float, anchorDp: Float = 0f) = _state.update { it.copy(viewport = it.viewport.zoomBy(factor, anchorDp)) }
    fun scrollTo(dp: Float) = _state.update { if (it.viewport.scrollDp == dp) it else it.copy(viewport = it.viewport.copy(scrollDp = dp.coerceAtLeast(0f))) }
    fun scrollBy(dp: Float) = _state.update {
        it.copy(viewport = it.viewport.scrollBy(dp, it.viewport.contentWidthDp(it.project.lengthTicks), viewWidthDp))
    }
    fun setTrackHeight(dp: Float) = _state.update { it.copy(trackHeightDp = dp.coerceIn(40f, 120f)) }

    // ---- tracks -----------------------------------------------------------------------------
    fun addTrack(slot: Int): Boolean {
        val inst = DrumInstrument.fromSlot(slot) ?: return false
        val r = editor.addTrack(project, slot, inst.label)
        val ok = commit(r, structural = true)
        if (ok) r.createdId?.let { id -> _state.update { it.copy(selectedTrackId = id) } }
        return ok
    }
    fun removeTrack(id: String) = commit(editor.removeTrack(project, id), structural = true).also { _state.update { s -> s.copy(selectedClipId = s.project.clip(s.selectedClipId)?.id) } }
    fun duplicateTrack(id: String) = commit(editor.duplicateTrack(project, id), structural = true)
    fun renameTrack(id: String, name: String) = commit(editor.renameTrack(project, id, name))
    fun setTrackInstrument(id: String, slot: Int) = commit(editor.setTrackInstrument(project, id, slot))
    // Mixer-style parameters: applied straight to the native track (no republish, not part of undo).
    private fun param(r: EditResult) { _state.update { it.copy(project = r.project) }; syncTracks() }
    fun setTrackVolume(id: String, v: Float) = param(editor.setTrackVolume(project, id, v))
    fun setTrackPan(id: String, v: Float) = param(editor.setTrackPan(project, id, v))
    fun setTrackMute(id: String, m: Boolean) = param(editor.setTrackMute(project, id, m))
    fun setTrackSolo(id: String, s: Boolean) = param(editor.setTrackSolo(project, id, s))

    // ---- patterns -----------------------------------------------------------------------------
    fun createPattern(): String? {
        val r = editor.createPattern(project, "Pattern ${project.patterns.size + 1}")
        if (!commit(r)) return null
        _state.update { it.copy(selectedPatternId = r.createdId) }
        return r.createdId
    }
    fun duplicatePattern(id: String): Boolean {
        val r = editor.duplicatePattern(project, id)
        val ok = commit(r)
        if (ok) _state.update { it.copy(selectedPatternId = r.createdId) }
        return ok
    }
    fun clearPattern(id: String) = commit(editor.clearPattern(project, id))
    fun setPatternLength(id: String, ticks: Int) = commit(editor.setPatternLength(project, id, ticks))
    fun deletePattern(id: String) = commit(editor.deletePattern(project, id))
    /** Step toggle on the SHARED pattern: every clip using it changes (see [makeSelectedClipUnique]). */
    fun toggleStep(patternId: String, slot: Int, tick: Int, velocity: Float = 0.8f) = commit(editor.toggleStep(project, patternId, slot, tick, velocity))
    fun setStepVelocity(patternId: String, slot: Int, tick: Int, velocity: Float) = commit(editor.setStepVelocity(project, patternId, slot, tick, velocity))

    // ---- clips ----------------------------------------------------------------------------------
    private val q get() = project.playback.quantize
    private val sig get() = project.timeSignature
    fun playheadTick() = if (_state.value.previewPatternId == null) backend.positionTicks() else 0L

    fun addClip(trackId: String, patternId: String, startTick: Long): Boolean {
        val r = editor.addClip(project, trackId, patternId, q.snap(startTick, sig))
        val ok = commit(r)
        if (ok) selectClip(r.createdId)
        return ok
    }
    /** Adds a clip of the selected pattern (or a fresh empty one) to the selected track at the playhead. */
    fun addClipAtPlayhead(): Boolean {
        val track = _state.value.selectedTrackId ?: return false.also { note("Select a track first") }
        val pat = _state.value.selectedPatternId ?: createPattern() ?: return false
        return addClip(track, pat, playheadTick())
    }
    /** New empty pattern + a clip of it on the track: the quickest way to start writing a beat. */
    fun createPatternClip(trackId: String, startTick: Long): Boolean {
        val pr = editor.createPattern(project, "Pattern ${project.patterns.size + 1}")
        val withClip = editor.addClip(pr.project, trackId, pr.createdId!!, q.snap(startTick, sig))
        if (!withClip.ok) { note(withClip.error); return false }
        commit(withClip)
        _state.update { it.copy(selectedPatternId = pr.createdId) }
        selectClip(withClip.createdId)
        return true
    }
    fun moveClip(clipId: String, newStart: Long, newTrackId: String? = null) = commit(editor.moveClip(project, clipId, q.snap(newStart, sig), newTrackId))
    fun duplicateSelectedClip(): Boolean {
        val id = _state.value.selectedClipId ?: return false
        val r = editor.duplicateClip(project, id); val ok = commit(r); if (ok) selectClip(r.createdId); return ok
    }
    fun deleteSelectedClip(): Boolean { val id = _state.value.selectedClipId ?: return false; return commit(editor.deleteClip(project, id)) }
    fun splitSelectedAtPlayhead(): Boolean {
        val id = _state.value.selectedClipId ?: return false.also { note("Select a clip to split") }
        val r = editor.splitClip(project, id, q.snap(backend.positionTicks(), sig).let { if (project.clip(id)?.let { c -> it > c.startTick && it < c.endTick } == true) it else backend.positionTicks() })
        val ok = commit(r); if (ok) selectClip(r.createdId); return ok
    }
    fun setClipRange(id: String, start: Long, end: Long) = commit(editor.setClipRange(project, id, q.snap(start, sig), q.snap(end, sig).coerceAtLeast(q.snap(start, sig) + maxOf(1, q.gridTicks(sig)))))
    fun toggleSelectedClipLoop(): Boolean { val c = _state.value.selectedClip ?: return false; return commit(editor.setClipLoop(project, c.id, !c.loop)) }
    /** Gives the selected clip its own pattern copy, so edits no longer change other clips sharing the old pattern. */
    fun makeSelectedClipUnique(): Boolean {
        val id = _state.value.selectedClipId ?: return false
        val r = editor.makeClipUnique(project, id); val ok = commit(r)
        if (ok) _state.update { it.copy(selectedPatternId = r.createdId) }
        return ok
    }

    // ---- project settings -------------------------------------------------------------------------
    fun setTempo(bpm: Double) { val r = editor.setTempo(project, bpm); _state.update { it.copy(project = r.project) }; backend.setTempo(r.project.tempoBpm) }
    fun setTimeSignature(sig: TimeSignature) {
        _state.update { it.copy(project = editor.setTimeSignature(it.project, sig).project) }
        backend.setTimeSignature(sig.numerator, sig.denominator)
    }
    fun setQuantize(qz: Quantize) = _state.update { it.copy(project = editor.setQuantize(it.project, qz).project) }
    fun setLoopRegion(loop: LoopRegion) { commit(editor.setLoop(project, loop)) }
    /** Loop region over the selected clip, or the first 4 bars when no clip is selected. */
    fun toggleLoop() {
        val cur = project.playback.loop
        if (cur.enabled) { setLoopRegion(cur.copy(enabled = false)); return }
        val c = _state.value.selectedClip
        val bar = ticksPerBar(sig).toLong()
        setLoopRegion(if (c != null) LoopRegion(c.startTick, c.endTick, true) else LoopRegion(0, 4 * bar, true))
    }

    // ---- transport (all state comes from the native engine) ----------------------------------------
    fun play() { if (_state.value.previewPatternId != null) stopPreview(); ensureAudio(); backend.play(); refresh() }
    fun pause() { if (_state.value.previewPatternId != null) { stopPreview(); return }; backend.pause(); refresh() }
    fun stop() { if (_state.value.previewPatternId != null) stopPreview(); backend.stop(); refresh() }
    fun returnToStart() { if (_state.value.previewPatternId != null) stopPreview(); backend.seek(0); refresh() }
    fun seekTo(tick: Long) { if (_state.value.previewPatternId == null) { backend.seek(maxOf(0, q.snap(tick, sig))); refresh() } }
    fun togglePlayPause() = if (_transport.value.mode == TransportMode.PLAYING) pause() else play()

    fun startPreview(patternId: String) {
        val pat = project.pattern(patternId) ?: return
        backend.stop()
        _state.update { it.copy(previewPatternId = patternId) }
        backend.setTrack(PREVIEW_TRACK_INDEX, 1f, 0f, false, true)   // solo: audible even if arrangement tracks are soloed
        backend.publish(flattenPreview(pat))
        ensureAudio(); backend.play(); refresh()
    }
    fun stopPreview() {
        if (_state.value.previewPatternId == null) return
        backend.stop()
        backend.setTrack(PREVIEW_TRACK_INDEX, 1f, 0f, false, false)
        _state.update { it.copy(previewPatternId = null) }
        publish(); refresh()
    }
    /** App backgrounded / audio focus lost: the transport pauses (position kept) and does not auto-resume. */
    fun onAudioInterrupted() { if (_state.value.previewPatternId != null) stopPreview() else if (backend.state() == SeqState.PLAYING) backend.pause(); refresh() }

    fun refresh() {
        val s = _state.value
        val pos = backend.positionTicks()
        val t = presentTransport(backend.state(), engineRunning(), pos, s.project.timeSignature, s.previewPatternId != null)
        if (t != _transport.value) _transport.value = t
        if (t.mode == TransportMode.PLAYING && viewWidthDp > 0f) {
            val v = s.viewport.followPlayhead(pos, viewWidthDp, s.viewport.contentWidthDp(s.project.lengthTicks))
            if (v != s.viewport) _state.update { it.copy(viewport = v) }
        }
    }
    /** Runs while the UI is visible (called inside repeatOnLifecycle). ~30 Hz. */
    suspend fun pollLoop() { while (true) { refresh(); delay(33) } }

    companion object {
        fun defaultProject(editor: ArrangementEditor): ArrangementProject {
            var p = ArrangementProject()
            fun pat(name: String, slot: Int, steps: List<Int>, vel: (Int) -> Float = { 0.9f }): String {
                val r = editor.createPattern(p, name); p = r.project
                steps.forEach { st -> p = editor.toggleStep(p, r.createdId!!, slot, st * TICKS_PER_SIXTEENTH, vel(st)).project }
                return r.createdId!!
            }
            val kick = pat("Kick 4/4", DrumInstrument.KICK.slot, listOf(0, 4, 8, 12))
            val snare = pat("Snare backbeat", DrumInstrument.SNARE.slot, listOf(4, 12))
            val hat = pat("Hat 8ths", DrumInstrument.CLOSED_HAT.slot, listOf(0, 2, 4, 6, 8, 10, 12, 14)) { if (it % 4 == 0) 0.9f else 0.6f }
            for ((slot, patId) in listOf(DrumInstrument.KICK to kick, DrumInstrument.SNARE to snare, DrumInstrument.CLOSED_HAT to hat)) {
                val t = editor.addTrack(p, slot.slot, slot.label); p = t.project
                p = editor.addClip(p, t.createdId!!, patId, 0, 4L * 4 * PPQ).project
            }
            return p
        }
        fun factory() = viewModelFactory { initializer { ArrangementViewModel(NativeSequencerBackend()) } }
    }
}
