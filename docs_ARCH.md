# Phase 3 architecture

## Timing
Unit = integer tick, 960 PPQ (`Sequencer.h`, `Model.kt`). ticksPerBeat = 3840/denominator; ticksPerBar = numerator x ticksPerBeat;
sixteenth = 240 ticks; frames/tick = 60*sr/(bpm*960). Seconds are never stored.

## Audio path
Kotlin (`ArrangementProject`, pure/immutable) --flatten()--> parallel arrays --JNI seqPublish--> preallocated snapshot (3 buffers,
lock-free handshake) --> `Sequencer::process()` in the Oboe callback (one shared clock for all tracks) --> `ScheduledHit[]`
--> `DrumSampler::renderWithHits()` which renders the block in segments so each voice starts on its exact frame.
Position = anchorTick + framesSinceAnchor/fpt (re-anchored on seek, loop wrap, tempo/sample-rate change): no float drift, and
hit frames are identical for any buffer size. Mute/solo/volume/pan are atomics read per hit. No allocation/locks/I/O in the callback
(verified by an allocation-counting test).

## Transport semantics
Play/Pause/Stop/Seek are atomics applied by the audio thread (or immediately when no callback is running). Pause keeps position;
Stop resets to tick 0; reaching the arrangement end (no loop) auto-stops; a loop region wraps to its start. The UI only displays state
read back from the engine (`seqState`, `seqPositionTicks`). Backgrounding/focus loss pauses the transport (no auto-resume).

## Editing model
Patterns are shared by id. Editing a pattern changes every clip using it ("SHARED BY n CLIPS"); "Make unique" copies it for one clip.
A track's instrument is its default/focus lane; events carry their own instrument and play on that lane. Clips on a track never overlap.
Undo/redo = stack of immutable project snapshots (structural edits); volume/pan/mute/solo are live parameters, not undoable.
Pattern preview publishes just that pattern, looped, on reserved native track 31; the arrangement is republished when preview stops.
