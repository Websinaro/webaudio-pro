# We Audio Pro — Phase 1 foundation

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
Dependencies are pinned in `gradle/libs.versions.toml`: AGP 8.12.0, Kotlin 1.9.24, Compose compiler 1.5.14, Compose BOM 2024.04.01, Lifecycle 2.8.7, Navigation 2.7.7, Coroutines 1.8.1, **Oboe 1.9.0** (Maven AAR via prefab). minSdk 26.

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
