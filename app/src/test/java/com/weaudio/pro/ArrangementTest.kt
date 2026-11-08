package com.weaudio.pro

import com.weaudio.pro.arrangement.*
import com.weaudio.pro.audio.sampler.DrumInstrument
import com.weaudio.pro.project.TimeSignature
import org.junit.Assert.*
import org.junit.Test

/** Records every call so tests can assert what the ViewModel actually sent to the (native) sequencer. */
class FakeSequencer : SequencerBackend {
    val published = mutableListOf<SeqData>()
    val trackParams = mutableMapOf<Int, List<Any>>()
    val calls = mutableListOf<String>()
    var state = SeqState.STOPPED
    var position = 0L
    var tempo = 0.0
    var sig = 0 to 0
    override fun publish(data: SeqData): Boolean { published += data; return true }
    override fun play() { calls += "play"; state = SeqState.PLAYING }
    override fun pause() { calls += "pause"; if (state == SeqState.PLAYING) state = SeqState.PAUSED }
    override fun stop() { calls += "stop"; state = SeqState.STOPPED; position = 0 }
    override fun seek(tick: Long) { calls += "seek$tick"; position = tick }
    override fun setTempo(bpm: Double) { tempo = bpm }
    override fun setTimeSignature(numerator: Int, denominator: Int) { sig = numerator to denominator }
    override fun setTrack(index: Int, volume: Float, pan: Float, mute: Boolean, solo: Boolean) { trackParams[index] = listOf(volume, pan, mute, solo) }
    override fun positionTicks() = position
    override fun state() = state
    override fun droppedHits() = 0L
}

private const val BAR = 3840L

class ArrangementEditorTest {
    private val ed = ArrangementEditor(CounterIdSource())
    private fun base(): ArrangementProject {
        var p = ArrangementProject()
        p = ed.addTrack(p, DrumInstrument.KICK.slot, "Kick").project
        p = ed.addTrack(p, DrumInstrument.SNARE.slot, "Snare").project
        return p
    }

    @Test fun trackCreationAssignsStableUniqueIds() {
        val p = base()
        assertEquals(2, p.tracks.size)
        assertEquals(2, p.tracks.map { it.id }.toSet().size)
        assertEquals(DrumInstrument.SNARE.slot, p.tracks[1].instrumentSlot)
    }

    @Test fun trackLimitIsEnforced() {
        var p = ArrangementProject()
        repeat(MAX_TRACKS) { p = ed.addTrack(p, 0, "t$it").project }
        val r = ed.addTrack(p, 0, "extra")
        assertFalse(r.ok); assertEquals(MAX_TRACKS, r.project.tracks.size)
    }

    @Test fun deletingTrackRemovesItsClipsAndLeavesNoDanglingReferences() {
        var p = base()
        val pat = ed.createPattern(p, "A"); p = pat.project
        p = ed.addClip(p, p.tracks[0].id, pat.createdId!!, 0).project
        p = ed.addClip(p, p.tracks[1].id, pat.createdId!!, 0).project
        val doomed = p.tracks[0].id
        p = ed.removeTrack(p, doomed).project
        assertEquals(1, p.tracks.size)
        assertNull(p.track(doomed))
        assertTrue(p.tracks.flatMap { it.clips }.all { it.trackId == p.tracks[0].id })
        assertEquals(1, p.patternUseCount(pat.createdId!!))
    }

    @Test fun duplicatingTrackCopiesClipsWithNewIdsAndSharesPatterns() {
        var p = base()
        val pat = ed.createPattern(p, "A"); p = pat.project
        p = ed.addClip(p, p.tracks[0].id, pat.createdId!!, 0).project
        val r = ed.duplicateTrack(p, p.tracks[0].id); p = r.project
        assertEquals(3, p.tracks.size)
        val copy = p.track(r.createdId)!!
        assertEquals(1, copy.clips.size)
        assertNotEquals(p.tracks[0].clips[0].id, copy.clips[0].id)
        assertEquals(copy.id, copy.clips[0].trackId)
        assertEquals(2, p.patternUseCount(pat.createdId!!))
        assertEquals(1, p.tracks.indexOfFirst { it.id == copy.id })   // placed right after the source
    }

    @Test fun clipCreationRefusesOverlapAndKeepsClipsOrdered() {
        var p = base(); val t = p.tracks[0].id
        val pat = ed.createPattern(p, "A"); p = pat.project; val pid = pat.createdId!!
        p = ed.addClip(p, t, pid, 2 * BAR).project
        p = ed.addClip(p, t, pid, 0).project
        assertEquals(listOf(0L, 2 * BAR), p.track(t)!!.clips.map { it.startTick })
        val bad = ed.addClip(p, t, pid, BAR / 2)
        assertFalse(bad.ok); assertEquals(p, bad.project)
    }

    @Test fun movingClipChangesStartAndCanChangeTrack() {
        var p = base(); val (a, b) = p.tracks.map { it.id }
        val pat = ed.createPattern(p, "A"); p = pat.project
        val c = ed.addClip(p, a, pat.createdId!!, 0); p = c.project
        p = ed.moveClip(p, c.createdId!!, 3 * BAR, b).project
        assertTrue(p.track(a)!!.clips.isEmpty())
        val moved = p.track(b)!!.clips.single()
        assertEquals(3 * BAR, moved.startTick); assertEquals(b, moved.trackId)
        assertFalse(ed.moveClip(p, moved.id, -5).project.clip(moved.id)!!.startTick < 0)
    }

    @Test fun splitCreatesTwoClipsThatCoverTheOriginalAndContinuePatternPhase() {
        var p = base(); val t = p.tracks[0].id
        val pat = ed.createPattern(p, "A", 2 * PPQ); p = pat.project            // 2-beat pattern
        val c = ed.addClip(p, t, pat.createdId!!, 0, 4 * BAR / 4 * 2); p = c.project   // 2 bars
        val r = ed.splitClip(p, c.createdId!!, 3 * PPQ.toLong()); p = r.project
        val clips = p.track(t)!!.clips
        assertEquals(2, clips.size)
        assertEquals(0L, clips[0].startTick); assertEquals(3L * PPQ, clips[0].durationTicks)
        assertEquals(3L * PPQ, clips[1].startTick); assertEquals(2 * BAR - 3L * PPQ, clips[1].durationTicks)
        assertEquals(PPQ, clips[1].offsetTicks)          // 3 beats into a 2-beat pattern -> phase 1 beat
        assertEquals(clips[0].patternId, clips[1].patternId)
        assertFalse(ed.splitClip(p, clips[0].id, 0).ok)  // not strictly inside
    }

    @Test fun splitPreservesTheExactEventStream() {
        var p = base(); val t = p.tracks[0].id
        val pat = ed.createPattern(p, "A"); p = pat.project; val pid = pat.createdId!!
        for (s in listOf(0, 4, 8, 12)) p = ed.toggleStep(p, pid, 0, s * TICKS_PER_SIXTEENTH).project
        val c = ed.addClip(p, t, pid, 0, 3 * BAR); p = c.project
        val before = flatten(p).ticks.toList()
        p = ed.splitClip(p, c.createdId!!, BAR + 960).project
        assertEquals(before, flatten(p).ticks.toList().sorted())
    }

    @Test fun duplicateClipPlacesCopyAfterOriginalSharingPattern() {
        var p = base(); val t = p.tracks[0].id
        val pat = ed.createPattern(p, "A"); p = pat.project
        val c = ed.addClip(p, t, pat.createdId!!, 0); p = c.project
        val d = ed.duplicateClip(p, c.createdId!!); p = d.project
        val copy = p.clip(d.createdId)!!
        assertEquals(BAR, copy.startTick); assertEquals(c.createdId.let { p.clip(it)!!.patternId }, copy.patternId)
        val third = ed.duplicateClip(p, c.createdId!!).project   // original's end slot is taken -> next free gap
        assertEquals(3, third.track(t)!!.clips.size)
        assertEquals(2 * BAR, third.track(t)!!.clips.maxOf { it.startTick })
    }

    @Test fun deleteClipAndPatternCascade() {
        var p = base(); val t = p.tracks[0].id
        val pat = ed.createPattern(p, "A"); p = pat.project; val pid = pat.createdId!!
        p = ed.addClip(p, t, pid, 0).project
        p = ed.addClip(p, t, pid, 2 * BAR).project
        p = ed.deleteClip(p, p.track(t)!!.clips[0].id).project
        assertEquals(1, p.track(t)!!.clips.size)
        p = ed.deletePattern(p, pid).project
        assertTrue(p.tracks.all { it.clips.isEmpty() }); assertNull(p.pattern(pid))
    }

    @Test fun sharedPatternEditsAffectAllClipsUntilMadeUnique() {
        var p = base(); val t = p.tracks[0].id
        val pat = ed.createPattern(p, "A"); p = pat.project; val pid = pat.createdId!!
        val c1 = ed.addClip(p, t, pid, 0); p = c1.project
        val c2 = ed.addClip(p, t, pid, 2 * BAR); p = c2.project
        p = ed.toggleStep(p, pid, 0, 0).project
        assertEquals(2, flatten(p).count)                              // both clips play the new hit
        val u = ed.makeClipUnique(p, c2.createdId!!); p = u.project
        assertNotEquals(pid, p.clip(c2.createdId)!!.patternId)
        p = ed.toggleStep(p, u.createdId!!, 0, 240).project            // edit only the unique copy
        assertEquals(1, p.pattern(pid)!!.events.size)
        assertEquals(2, p.pattern(u.createdId)!!.events.size)
        assertEquals(3, flatten(p).count)
    }

    @Test fun patternEditingToggleVelocityClearAndLength() {
        var p = ArrangementProject()
        val pid = ed.createPattern(p, "A").let { p = it.project; it.createdId!! }
        p = ed.toggleStep(p, pid, 1, 240, 0.5f).project
        assertEquals(1, p.pattern(pid)!!.events.size)
        p = ed.setStepVelocity(p, pid, 1, 240, 1.7f).project
        assertEquals(1f, p.pattern(pid)!!.events[0].velocity, 0f)
        assertFalse(ed.setStepVelocity(p, pid, 2, 240, 0.5f).ok)       // no hit there
        assertFalse(ed.toggleStep(p, pid, 1, 99999).ok)                // outside pattern
        p = ed.toggleStep(p, pid, 1, 240).project
        assertTrue(p.pattern(pid)!!.events.isEmpty())                  // toggling again removes it
        p = ed.toggleStep(p, pid, 1, 3000).project
        p = ed.setPatternLength(p, pid, 960).project
        assertTrue(p.pattern(pid)!!.events.isEmpty())                  // shortened: event past the end dropped
        p = ed.toggleStep(p, pid, 0, 0).project
        assertTrue(ed.clearPattern(p, pid).project.pattern(pid)!!.events.isEmpty())
    }

    @Test fun duplicatePatternIsIndependent() {
        var p = ArrangementProject()
        val pid = ed.createPattern(p, "A").let { p = it.project; it.createdId!! }
        p = ed.toggleStep(p, pid, 0, 0).project
        val d = ed.duplicatePattern(p, pid); p = d.project
        p = ed.toggleStep(p, d.createdId!!, 0, 240).project
        assertEquals(1, p.pattern(pid)!!.events.size); assertEquals(2, p.pattern(d.createdId)!!.events.size)
        assertNotEquals(p.pattern(pid)!!.events[0].id, p.pattern(d.createdId)!!.events[0].id)
    }

    @Test fun quantizeSnapsToGrid() {
        val sig = TimeSignature(4, 4)
        assertEquals(240L, Quantize.SIXTEENTH.snap(130, sig)); assertEquals(0L, Quantize.SIXTEENTH.snap(119, sig))
        assertEquals(960L, Quantize.BEAT.snap(700, sig)); assertEquals(3840L, Quantize.BAR.snap(2000, sig))
        assertEquals(1234L, Quantize.OFF.snap(1234, sig))
        assertEquals(2880L, Quantize.BAR.gridTicks(TimeSignature(6, 8)))
        assertEquals(480L, Quantize.EIGHTH.snap(500, sig))
    }

    @Test fun timeMathMatchesNativeEngine() {
        assertEquals(960, ticksPerBeat(TimeSignature(4, 4))); assertEquals(480, ticksPerBeat(TimeSignature(6, 8)))
        assertEquals(3840, ticksPerBar(TimeSignature(4, 4))); assertEquals(3360, ticksPerBar(TimeSignature(7, 8)))
        assertEquals(BarBeat(2, 3, 0), ticksToBarBeat(2 * 3840L + 3 * 960, TimeSignature(4, 4)))
        assertEquals("003.4.1", formatBarBeat(2 * 3840L + 3 * 960, TimeSignature(4, 4)))
    }

    @Test fun flattenLoopsClipsAndStopsAtTheClipBoundary() {
        var p = base(); val t = p.tracks[0].id
        val pat = ed.createPattern(p, "A", BAR.toInt()); p = pat.project; val pid = pat.createdId!!
        p = ed.toggleStep(p, pid, 0, 0).project; p = ed.toggleStep(p, pid, 0, 2 * PPQ).project
        p = ed.addClip(p, t, pid, BAR, 2 * BAR + BAR / 2).project             // 2.5 bars, starts at bar 2
        val d = flatten(p)
        assertEquals(listOf(BAR, BAR + 1920, 2 * BAR, 2 * BAR + 1920, 3 * BAR), d.ticks.sortedArray().toList())
        assertTrue(d.ticks.all { it < BAR + 2 * BAR + BAR / 2 })              // never past the clip end
        val once = ed.setClipLoop(p, p.track(t)!!.clips[0].id, false).project
        assertEquals(listOf(BAR, BAR + 1920), flatten(once).ticks.sortedArray().toList())
    }

    @Test fun flattenSkipsDisabledEventsAndMapsTrackIndexAndInstrument() {
        var p = base()
        val pat = ed.createPattern(p, "A"); p = pat.project; val pid = pat.createdId!!
        p = ed.toggleStep(p, pid, DrumInstrument.CRASH.slot, 0).project
        p = p.copy(patterns = p.patterns.map { it.copy(events = it.events + DrumEvent("x", 0, 480, enabled = false)) })
        p = ed.addClip(p, p.tracks[1].id, pid, 0).project
        val d = flatten(p)
        assertEquals(1, d.count); assertEquals(1, d.tracks[0]); assertEquals(DrumInstrument.CRASH.slot, d.slots[0])
    }

    @Test fun flattenIsTruncatedNotCrashingWhenTooDense() {
        var p = base(); val t = p.tracks[0].id
        val pat = ed.createPattern(p, "A"); p = pat.project; val pid = pat.createdId!!
        p = ed.toggleStep(p, pid, 0, 0).project
        p = ed.addClip(p, t, pid, 0, 100 * BAR).project
        assertTrue(flatten(p, maxEvents = 10).truncated); assertEquals(10, flatten(p, 10).count)
    }

    @Test fun arrangementLengthGrowsWithClips() {
        var p = base(); val t = p.tracks[0].id
        assertEquals(8 * BAR, p.lengthTicks)
        val pat = ed.createPattern(p, "A"); p = pat.project
        p = ed.addClip(p, t, pat.createdId!!, 10 * BAR).project
        assertEquals(11 * BAR, p.lengthTicks)
    }
}

class TimelineViewportTest {
    @Test fun zoomIsClampedAndKeepsTheAnchorTickFixed() {
        var v = TimelineViewport(dpPerBeat = 50f, scrollDp = 100f)
        val tickAtAnchor = v.dpToTick(100f + 40f)
        v = v.zoomBy(2f, anchorDp = 40f)
        assertEquals(100f, v.dpPerBeat, 0.001f)
        assertEquals(tickAtAnchor.toFloat(), v.dpToTick(v.scrollDp + 40f).toFloat(), 2f)
        assertEquals(TimelineViewport.MAX_ZOOM, TimelineViewport().zoomBy(1000f).dpPerBeat, 0f)
        assertEquals(TimelineViewport.MIN_ZOOM, TimelineViewport().zoomBy(0.0001f).dpPerBeat, 0f)
    }
    @Test fun scrollIsClampedToContent() {
        val v = TimelineViewport(scrollDp = 0f)
        assertEquals(0f, v.scrollBy(-50f, 1000f, 300f).scrollDp, 0f)
        assertEquals(700f, v.scrollBy(5000f, 1000f, 300f).scrollDp, 0f)
        assertEquals(0f, v.scrollBy(10f, 100f, 300f).scrollDp, 0f)   // content narrower than the view
    }
    @Test fun tickAndDpConversionsRoundTrip() {
        val v = TimelineViewport(dpPerBeat = 64f)
        assertEquals(64f, v.tickToDp(PPQ.toLong()), 0.001f)
        assertEquals(PPQ.toLong(), v.dpToTick(64f))
    }
    @Test fun followPlayheadScrollsOnlyWhenPlayheadLeavesTheView() {
        val v = TimelineViewport(dpPerBeat = 100f, scrollDp = 0f)
        assertEquals(v, v.followPlayhead(2L * PPQ, 300f, 2000f))
        assertTrue(v.followPlayhead(5L * PPQ, 300f, 2000f).scrollDp > 0f)
    }
}

class ArrangementViewModelTest {
    private fun vm(seq: FakeSequencer = FakeSequencer(), p: ArrangementProject? = null) = ArrangementViewModel(seq, ArrangementEditor(CounterIdSource(1000)), p)

    @Test fun defaultProjectPublishesRealEventsAtStartup() {
        val seq = FakeSequencer(); val v = vm(seq)
        assertEquals(3, v.state.value.project.tracks.size)
        assertTrue(seq.published.last().count > 0)
        assertEquals(120.0, seq.tempo, 0.0); assertEquals(4 to 4, seq.sig)
    }

    @Test fun editsRepublishToTheNativeEngineAndUndoRestores() {
        val seq = FakeSequencer(); val v = vm(seq)
        val before = seq.published.size; val events = seq.published.last().count
        val clip = v.state.value.project.tracks[0].clips[0]
        v.selectClip(clip.id); assertTrue(v.deleteSelectedClip())
        assertTrue(seq.published.size > before); assertTrue(seq.published.last().count < events)
        assertTrue(v.state.value.canUndo)
        v.undo()
        assertEquals(events, seq.published.last().count)
        v.redo(); assertTrue(seq.published.last().count < events)
    }

    @Test fun trackCreationDeletionAndDuplicationThroughTheViewModel() {
        val v = vm(); val n = v.state.value.project.tracks.size
        assertTrue(v.addTrack(DrumInstrument.CRASH.slot)); assertEquals(n + 1, v.state.value.project.tracks.size)
        assertEquals(DrumInstrument.CRASH.slot, v.state.value.selectedTrack!!.instrumentSlot)
        val id = v.state.value.selectedTrackId!!
        v.duplicateTrack(id); assertEquals(n + 2, v.state.value.project.tracks.size)
        v.removeTrack(id); assertEquals(n + 1, v.state.value.project.tracks.size)
        assertNotNull(v.state.value.selectedTrack)                      // selection moved to a valid track
    }

    @Test fun deletingTheSelectedClipsTrackClearsSelectionAndPublishesNoGhostEvents() {
        val seq = FakeSequencer(); val v = vm(seq)
        val t = v.state.value.project.tracks[0]; v.selectClip(t.clips[0].id)
        val total = seq.published.last().count
        v.removeTrack(t.id)
        assertNull(v.state.value.selectedClip)
        assertTrue(seq.published.last().count < total)
        assertTrue(seq.published.last().tracks.all { it < v.state.value.project.tracks.size })
    }

    @Test fun muteSoloVolumePanAreAppliedPerNativeTrackIndex() {
        val seq = FakeSequencer(); val v = vm(seq); val ids = v.state.value.project.tracks.map { it.id }
        v.setTrackMute(ids[0], true); v.setTrackSolo(ids[2], true); v.setTrackVolume(ids[1], 0.5f); v.setTrackPan(ids[1], -1f)
        assertEquals(listOf(1f, 0f, true, false), seq.trackParams[0])
        assertEquals(listOf(0.5f, -1f, false, false), seq.trackParams[1])
        assertEquals(listOf(1f, 0f, false, true), seq.trackParams[2])
    }

    @Test fun removingASoloedTrackClearsItsNativeSoloSoNothingStaysSilenced() {
        val seq = FakeSequencer(); val v = vm(seq); val ids = v.state.value.project.tracks.map { it.id }
        v.setTrackSolo(ids[2], true)
        assertEquals(true, seq.trackParams[2]!![3])
        v.removeTrack(ids[2])
        assertEquals(false, seq.trackParams[2]!![3])                    // freed native index no longer solos
    }

    @Test fun transportCallsReachTheEngineAndStateIsReadBackNotAssumed() {
        val seq = FakeSequencer(); val v = vm(seq); var audioStarted = 0
        v.ensureAudio = { audioStarted++ }
        v.play(); assertEquals(1, audioStarted)
        assertEquals(TransportMode.PLAYING, v.transport.value.mode)
        seq.position = 3840L + 960 * 2; v.refresh()
        assertEquals(seq.position, v.transport.value.positionTicks); assertEquals("002.3.1", v.transport.value.label)
        v.pause(); assertEquals(TransportMode.PAUSED, v.transport.value.mode); assertEquals(seq.position, v.transport.value.positionTicks)
        v.play(); assertEquals(TransportMode.PLAYING, v.transport.value.mode)
        v.stop(); assertEquals(TransportMode.STOPPED, v.transport.value.mode); assertEquals(0L, v.transport.value.positionTicks)
        // the UI never claims PLAYING unless the native state says so
        seq.state = SeqState.STOPPED; v.refresh(); assertEquals(TransportMode.STOPPED, v.transport.value.mode)
    }

    @Test fun transportShowsAudioPausedWhenTheStreamIsNotRunning() {
        val seq = FakeSequencer(); val v = vm(seq); var running = true
        v.engineRunning = { running }
        v.play(); assertEquals(TransportMode.PLAYING, v.transport.value.mode)
        running = false; v.refresh(); assertEquals(TransportMode.AUDIO_PAUSED, v.transport.value.mode)
    }

    @Test fun backgroundingPausesTheTransportAndKeepsPosition() {
        val seq = FakeSequencer(); val v = vm(seq)
        v.play(); seq.position = 5000; v.onAudioInterrupted()
        assertEquals(SeqState.PAUSED, seq.state); assertEquals(5000L, seq.position)
        v.refresh(); assertEquals(TransportMode.PAUSED, v.transport.value.mode)
    }

    @Test fun returnToStartSeeksToZeroWithoutStopping() {
        val seq = FakeSequencer(); val v = vm(seq); v.play(); seq.position = 9000
        v.returnToStart(); assertEquals(0L, seq.position); assertEquals(SeqState.PLAYING, seq.state)
    }

    @Test fun tempoAndTimeSignatureReachTheEngineAndAreClamped() {
        val seq = FakeSequencer(); val v = vm(seq)
        v.setTempo(140.5); assertEquals(140.5, seq.tempo, 0.0)
        v.setTempo(1000.0); assertEquals(MAX_TEMPO, seq.tempo, 0.0)
        v.setTempo(1.0); assertEquals(MIN_TEMPO, seq.tempo, 0.0)
        v.setTimeSignature(TimeSignature(6, 8)); assertEquals(6 to 8, seq.sig)
        assertEquals(2880, ticksPerBar(v.state.value.project.timeSignature))
    }

    @Test fun splitAtThePlayheadUsesTheNativePositionAndQuantize() {
        val seq = FakeSequencer(); val v = vm(seq)
        val c = v.state.value.project.tracks[0].clips[0]; v.selectClip(c.id)
        seq.position = BAR + 130                                         // 130 ticks snaps to the nearest 1/16 (240)
        assertTrue(v.splitSelectedAtPlayhead())
        val clips = v.state.value.project.tracks[0].clips
        assertEquals(2, clips.size); assertEquals(BAR + 240, clips[1].startTick)
    }

    @Test fun splitOutsideTheClipIsRefusedWithAMessage() {
        val seq = FakeSequencer(); val v = vm(seq)
        v.selectClip(v.state.value.project.tracks[0].clips[0].id); seq.position = 50 * BAR
        assertFalse(v.splitSelectedAtPlayhead()); assertNotNull(v.state.value.message)
    }

    @Test fun clipMoveSnapsToTheQuantizeGridAndDuplicateCreatesAnotherClip() {
        val seq = FakeSequencer(); val v = vm(seq)
        val c = v.state.value.project.tracks[0].clips[0]
        v.setQuantize(Quantize.BEAT)
        assertTrue(v.moveClip(c.id, 8 * BAR + 700))
        assertEquals(8 * BAR + 960, v.state.value.project.clip(c.id)!!.startTick)
        v.selectClip(c.id); assertTrue(v.duplicateSelectedClip())
        assertEquals(2, v.state.value.project.tracks[0].clips.size)
    }

    @Test fun overlappingMoveIsRefusedAndReportedNotApplied() {
        val v = vm(); val c = v.state.value.project.tracks[0].clips[0]
        val other = v.state.value.project.tracks[1]
        assertTrue(v.addClip(other.id, v.state.value.project.patterns[0].id, 8 * BAR))
        val second = v.state.value.project.track(other.id)!!.clips.last()
        assertFalse(v.moveClip(second.id, 0, other.id)); assertNotNull(v.state.value.message)
    }

    @Test fun patternPreviewPublishesOnlyThePatternThenRestoresTheArrangement() {
        val seq = FakeSequencer(); val v = vm(seq)
        val pid = v.state.value.project.patterns[0].id; val total = seq.published.last().count
        v.startPreview(pid)
        assertTrue(seq.published.last().tracks.all { it == PREVIEW_TRACK_INDEX }); assertTrue(seq.published.last().loopEnabled)
        assertEquals(TransportMode.PREVIEW, v.transport.value.mode)
        v.toggleStep(pid, DrumInstrument.RIDE.slot, 0)                  // live edit while previewing is heard
        assertTrue(seq.published.last().tracks.all { it == PREVIEW_TRACK_INDEX })
        v.stopPreview()
        assertEquals(total + 4, seq.published.last().count)   // 1 new hit x 4 looped cycles of the shared pattern; assertEquals(SeqState.STOPPED, seq.state)
    }

    @Test fun zoomScrollAndTabStateLiveInTheViewModelAndSurviveRecomposition() {
        val v = vm(); v.viewWidthDp = 300f
        v.zoomBy(2f); val zoom = v.state.value.viewport.dpPerBeat
        v.scrollBy(120f); v.setTab(StudioTab.PATTERN); v.selectTrack(v.state.value.project.tracks[1].id)
        // An orientation change recreates the composition, not the ViewModel: the same state object keeps serving collectors.
        val s = v.state.value
        assertEquals(zoom, s.viewport.dpPerBeat, 0f); assertTrue(s.viewport.scrollDp > 0f)
        assertEquals(StudioTab.PATTERN, s.tab); assertEquals(v.state.value.project.tracks[1].id, s.selectedTrackId)
    }

    @Test fun presentTransportMapsEveryNativeStateHonestly() {
        val sig = TimeSignature(4, 4)
        assertEquals(TransportMode.STOPPED, presentTransport(SeqState.STOPPED, true, 0, sig, false).mode)
        assertEquals(TransportMode.PLAYING, presentTransport(SeqState.PLAYING, true, 0, sig, false).mode)
        assertEquals(TransportMode.PAUSED, presentTransport(SeqState.PAUSED, true, 0, sig, false).mode)
        assertEquals(TransportMode.AUDIO_PAUSED, presentTransport(SeqState.PLAYING, false, 0, sig, false).mode)
        assertEquals(TransportMode.PREVIEW, presentTransport(SeqState.PLAYING, true, 0, sig, true).mode)
        assertEquals(TransportMode.STOPPED, presentTransport(SeqState.STOPPED, true, 0, sig, true).mode)
    }

    @Test fun sixTrackProjectFlattensToSynchronizedEventsOnSixTracks() {
        val ed = ArrangementEditor(CounterIdSource()); var p = ArrangementProject()
        val slots = listOf(DrumInstrument.KICK, DrumInstrument.SNARE, DrumInstrument.CLOSED_HAT, DrumInstrument.OPEN_HAT, DrumInstrument.CRASH, DrumInstrument.PERCUSSION)
        for (s in slots) {
            p = ed.addTrack(p, s.slot, s.label).project
            val pat = ed.createPattern(p, s.label); p = ed.toggleStep(pat.project, pat.createdId!!, s.slot, 0).project
            p = ed.addClip(p, p.tracks.last().id, pat.createdId!!, 0, 2 * BAR).project
        }
        val d = flatten(p)
        assertEquals(6 * 2, d.count)
        assertEquals((0..5).toSet(), d.tracks.toSet())
        assertEquals(setOf(0L, BAR), d.ticks.toSet())                   // every track hits on the same ticks
    }
}
