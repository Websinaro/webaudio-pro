# We Audio Pro — Phase 1 foundation + Phase 2 drum sampler

Android DAW foundation: Kotlin + Jetpack Compose UI, a C++20 native audio engine on Google Oboe, JNI bridge.
Package/applicationId: `com.weaudio.pro`.

## What Phase 1 contains
- **Studio workspace**: toolbar (name, project, status, settings, project menu), transport (restart/play/pause/stop wired to the real engine; tempo, time signature, position, engine status, real output peak meter), track-header region, bar/beat ruler, empty horizontally-scrolling arrangement area, disabled "Add layer" (Phase 3). Sounds/Mixer are marked upcoming; Projects shows the in-memory project.
- **Native engine** (`app/src/main/cpp`): `audio/` (AudioEngine, AudioStreamManager, AudioCallback, AudioConfiguration, AudioDiagnostics), `engine/` (AudioClock, AudioProcessor = sine test tone, MasterOutput = smoothed volume + soft limiter), `jni/NativeAudioBridge.cpp`.
  - One shared engine and one Oboe stream (not recreated on Play). Float32, stereo, LowLatency, Exclusive → Shared → device-default fallback, device-native sample rate.
  - Callback: no allocation, locks, logging or I/O. Control calls are serialized by a mutex; parameters cross to the audio thread via atomics.
  - Disconnect handling reopens the stream and resumes if playing. Pause falls back to stop if the back-end can't pause. "Running" is reported only when the stream state is `Started`.
- **Diagnostics screen** (Settings): every value comes from the native stream or Android APIs; unknown values show "Unavailable". Test-tone enable, frequency, master volume, Start/Stop, Reinitialize.
- **Lifecycle**: backgrounding or losing audio focus pauses and resumes automatically only if it was playing; engine released on exit. No permissions needed (output only).

## Requirements
Android Studio (Ladybug or newer), JDK 17, Android SDK 35, NDK (any recent; Studio offers to install), CMake 3.22.1 (SDK Manager → SDK Tools). Gradle 8.13 via the wrapper.
Dependencies are pinned in `gradle/libs.versions.toml`: AGP 8.12.0, Kotlin 1.9.24, Compose compiler 1.5.14, Compose BOM 2024.04.01, Lifecycle 2.8.7, Navigation 2.7.7, Coroutines 1.8.1, **Oboe 1.9.0** (Maven AAR via prefab). minSdk 24 (Android 7.0+); Oboe chooses a compatible native backend on older Android releases.

## Build and run
```
./gradlew assembleDebug            # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug             # install on a connected device
```

## Tests
```
# C++ (host, no Android needed): tone frequency, amplitude, block continuity, clipping, state logic
cmake -S app/src/main/cpp -B build/native -DCMAKE_BUILD_TYPE=Release
cmake --build build/native && ctest --test-dir build/native --output-on-failure

./gradlew testDebugUnitTest         # Kotlin: engine state handling, errors, interruptions, parsing
./gradlew connectedDebugAndroidTest # device: UI startup/navigation + real engine init/start/tone/stop/release/reinit
```
Status of verification: the native DSP/state tests were compiled and passed on a Linux host. The Gradle build, Kotlin tests, Compose UI tests and the Oboe/JNI engine were **not** built or run on a device in the environment that produced this project — run the commands above and check on hardware (`adb logcat -s WeAudio`).

## Extending (later phases)
Add sources to `AudioProcessor::render` (e.g. a voice mixer for the Phase 2 sampler) ahead of `MasterOutput`; `AudioClock` is the transport time base for Phase 3; `MasterOutput` is where Phase 4 mixer/FX buses attach. `ProjectRepository` is the persistence seam.


---
# Phase 2 — drum sampler, physical keyboard, adaptive UI

## Architecture
```
Touch pad ─┐                                   ┌─ audio thread (Oboe callback) ──────────────────────────┐
           ├─> DrumViewModel.trigger ─ JNI ─> DrumSampler.trigger ─> MPSC-guarded SPSC command ring ─> drain ─> voices ─> mix
Keyboard ──┘   (DrumKeyRouter)                (atomics: volume/pitch/mute)                         │  AudioProcessor(tone) -> DrumSampler -> MasterOutput(limiter)
Kotlin/IO thread: loadSample ─ decode WAV ─ resample (windowed sinc) ─ immutable SampleBuffer ─ Swap command ─┘  old buffer returns via retire ring, freed on control thread
```
- **Native** (`app/src/main/cpp/sampler`): `SampleDecoder` (WAV PCM 8/16/24/32, float 32/64, EXTENSIBLE; validates size, rate, channels, bit depth, truncation, NaN/Inf, silence), `SampleRateConverter` (offline Blackman-Harris windowed sinc, 16 zero crossings/side, cutoff lowered when downsampling, unity DC gain), `SampleBuffer`, `DrumVoice`, `DrumSampler` (12 slots, voice allocation/stealing, per-voice gain/pitch ratio/position, stereo mix, hi-hat choke group), `SpscRing`.
- **Real-time rules**: `render()` does no file I/O, allocation, decoding, mutex waits or logging. Samples are decoded/resampled on the *calling* (IO) thread; the audio thread only swaps pointers. A try-lock guard (`consumer_`) means the audio thread skips one drum block rather than ever waiting.
- **Polyphony**: 32 live voices + 8 extra slots so stolen/choked voices can fade out (3 ms) instead of clicking. The oldest live voice is stolen first. Each voice has a 0.7 ms fade-in, a 1 ms tail fade and per-block gain ramping (mute/volume changes are click-free). Voices carry a 0.6 headroom factor and `MasterOutput`'s soft limiter guarantees |x| <= 1.
- **Sample rate**: stream rate comes from Oboe. Samples are converted to it at load time; if a reconnect gives a different rate, every loaded sample is re-converted from the retained original.
- **Lifecycle**: the sampler is owned by the JNI layer, so kits survive `Reinitialize` and disconnect recovery. Pause/stop/disconnect clear voices and reject triggers (no stale hits on resume). Samples can be loaded while the stream is stopped.
- **Diagnostics** (Settings): samples loaded, active voices, polyphony limit, hits triggered, voices stolen, dropped triggers, conversion rate, plus all Phase 1 values. xruns are read from the stream, not hardcoded.
- **Keyboard** (`audio/keyboard`): `MainActivity.dispatchKeyEvent` -> `DrumKeyRouter` (pure Kotlin). First key-down triggers; auto-repeat is swallowed; only real, non-virtual keyboards; Ctrl/Alt/Meta ignored; disabled when the Studio is not on top, the window lost focus, or a text field is active. Focus loss and `InputDeviceListener.onInputDeviceRemoved` release held keys. Mapping is editable on **Sounds** (Remap / Clear / Reset, saved in SharedPreferences). Bindings use the Android `keyCode` (the character your layout produces). There is no velocity from typing keys: a fixed, configurable velocity is used (touch uses it too). No native "KeyboardEventBridge" module exists: keys go Kotlin -> the same JNI `drumTrigger` as touch.
- **UI**: landscape = nav rail + compact transport + kit/instrument browser | 4x3 pads | inspector + status strip. Portrait = transport, 3x4 pads, instrument chips, collapsible instrument settings, status strip, bottom nav (Studio / Sounds / Mixer / Projects). Selection, kit, mappings and sampler state live in `DrumViewModel` (+`SavedStateHandle`); the Activity also handles configuration changes itself so rotation never restarts audio.
- **Samples**: `assets/kits/studio_synth` and `dry_punch` — synthesised from scratch by `tools/generate_kit.py` (CC0, see `assets/kits/LICENSE.txt`). 44.1 kHz mono 16-bit, so the sample-rate converter is exercised on 48 kHz devices. No third-party audio. Replace uses the Storage Access Framework (no storage permission). User replacements are session-only (project save is Phase 3).

## Build / test commands
```
./gradlew assembleDebug                       # APK
./gradlew testDebugUnitTest                   # Kotlin unit tests (keyboard, view model)
./gradlew connectedDebugAndroidTest           # on-device integration + UI tests (device required)
cmake -S app/src/main/cpp -B build/native -DCMAKE_BUILD_TYPE=Release && cmake --build build/native && ctest --test-dir build/native --output-on-failure
python3 tools/generate_kit.py                 # regenerate the bundled kits (needs numpy)
```

## Phase 2 test report (what was and was NOT run)
**Run in the authoring sandbox (Linux, g++ 13, no Android SDK/NDK/Gradle/Kotlin compiler, no device):**
- `native_tests` (Phase 1 DSP): ALL PASSED.
- `drum_tests` (new): 77+ checks, ALL PASSED, also clean under AddressSanitizer + UBSan and ThreadSanitizer. Covers: WAV 8/16/24/32-bit PCM and 32/64-bit float decode accuracy, EXTENSIBLE, 13 malformed-input cases + truncation fuzz, resampler (44.1k->48k, 48k->44.1k, 22.05k->48k, 96k->48k sine error < 0.002, DC gain, aliasing rejection), trigger/gain/velocity/mute, pitch (+/-12 st measured by frequency), mid-play mute, 11 simultaneous voices, layering, 32-voice limit and stealing (click-free step bound), hi-hat choke, clipping prevention through `MasterOutput`, sample replacement/failed replacement/unload, 300 consecutive replacements (retire ring), offline load while stopped, block-size independence, decode+play of all 24 bundled samples, and a 3-producer + loader + audio-thread concurrency stress test.
- Kotlin source was only checked for bracket balance. **It has not been compiled.**

**NOT run (must be done by you):** `./gradlew assembleDebug`, Kotlin unit tests, instrumented tests, CMake/NDK/Oboe build, and every item of the real-device acceptance list (install, tap all pads, USB/Bluetooth keyboard, overlapping hits, rotation, pause/resume, click/dropout/latency listening, xrun reading). No latency, xrun or device figures are claimed. The Phase 1 screenshots (OPPO CPH2823, AAudio, 48 kHz, 512-frame buffer, 0 xruns, test tone only) predate the sampler and say nothing about Phase 2 performance.

## Changed / new files
Changed: `README.md`, `CMakeLists.txt`, `audio/AudioCallback.{h,cpp}`, `audio/AudioEngine.{h,cpp}`, `audio/AudioDiagnostics.cpp`, `jni/NativeAudioBridge.cpp`, `NativeAudioBridge.kt`, `AudioViewModel.kt` (test tone now off by default), `MainActivity.kt`, `WeAudioApp.kt`, `Navigation.kt`, `StudioScreen.kt` (timeline placeholder replaced by the drum workspace), `DiagnosticsScreen.kt`, `StudioUiTest.kt`.
New: `cpp/sampler/*` (SampleBuffer.h, SampleDecoder, SampleRateConverter, SpscRing.h, DrumVoice.h, DrumSampler), `cpp/tests/drum_tests.cpp`, `audio/sampler/{DrumInstrument,DrumBackend}.kt`, `audio/keyboard/{KeyMap,DrumKeyRouter}.kt`, `settings/KeyMapStore.kt`, `samples/KitRepository.kt`, `studio/drum/{DrumViewModel,DrumComponents,DrumWorkspace,SoundsScreen}.kt`, `tools/generate_kit.py`, `assets/kits/**`, tests `DrumKeyboardTest.kt`, `DrumViewModelTest.kt`, `DrumIntegrationTest.kt`.

## Known limitations
- Pitch uses linear interpolation at playback (fine for drums, small HF loss when pitched far); pitch/volume changes apply to new hits (volume/mute also to ringing voices).
- Replacing a sample cuts any still-ringing voice of that instrument. User samples are not persisted across app restarts.
- Mono/stereo WAV only; no MP3/OGG/FLAC; no velocity layers or round-robin.
- Key bindings follow the layout's key code, not physical position. Touch/keyboard are fixed-velocity.
- The audio callback skips the drum mix for one block if a sample hand-over happens at exactly the same instant (never blocks).
- Phase 1 "Add layer"/timeline placeholder was removed from Studio (Phase 3 work). Mixer remains a Phase 4 placeholder.
- Not yet verified on any device: Oboe latency with drums, xruns under load, Bluetooth keyboard behaviour.
