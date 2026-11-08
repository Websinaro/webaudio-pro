# Phase 3 — multitrack drum arrangement
See docs_ARCH.md. Host native tests: `g++ -std=c++20 -I app/src/main/cpp app/src/main/cpp/tests/sequencer_tests.cpp app/src/main/cpp/engine/*.cpp app/src/main/cpp/sampler/*.cpp -pthread && ./a.out` (or cmake/ctest).
Kotlin tests: `./gradlew testDebugUnitTest`; device: `./gradlew connectedDebugAndroidTest`.

## Status (honest)
- VERIFIED here: native sequencer + sampler hooks compile (g++ 13, C++20) and pass `sequencer_tests` (also under ThreadSanitizer); Phase 1/2 native tests still pass.
- NOT VERIFIED here: `./gradlew clean assembleDebug`, Kotlin compilation, Kotlin unit tests, instrumented tests, real-device runs (the build sandbox had no Android SDK/NDK, Gradle distribution, Kotlin compiler or network). The CI workflow `.github/workflows/android.yml` runs them; fix any compile errors it reports.
- `horizontalScroll` error: both files already import `androidx.compose.foundation.horizontalScroll` in this snapshot, so it could not be reproduced; an explicit `compose.foundation` dependency was added as a precaution.

## Known limitations
No save/load, WAV export, advanced mixer (Phase 4). No process-death restore. Tempo changes apply at block granularity. Max 24 tracks, 32768 flattened events (UI warns).
Not yet tested on Android 7 or any physical device; underrun/CPU/memory monitoring = existing xrun diagnostics only.
