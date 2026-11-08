cmake_minimum_required(VERSION 3.22.1)
project(weaudio_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Oboe-independent DSP, shared by the Android library and the host tests.
set(WA_ENGINE_SOURCES
    engine/AudioClock.cpp
    engine/AudioProcessor.cpp
    engine/MasterOutput.cpp)

if(ANDROID)
    # Oboe comes from the Maven AAR via Gradle prefab (buildFeatures.prefab = true).
    find_package(oboe REQUIRED CONFIG)

    add_library(weaudio SHARED
        ${WA_ENGINE_SOURCES}
        audio/AudioCallback.cpp
        audio/AudioStreamManager.cpp
        audio/AudioEngine.cpp
        audio/AudioDiagnostics.cpp
        jni/NativeAudioBridge.cpp)

    target_include_directories(weaudio PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_compile_options(weaudio PRIVATE -Wall -Wextra -Werror=return-type)
    # Android 15+ devices may use 16 KB pages.
    target_link_options(weaudio PRIVATE -Wl,-z,max-page-size=16384)
    target_link_libraries(weaudio PRIVATE oboe::oboe log)
else()
    # Host build for native unit tests:
    #   cmake -S app/src/main/cpp -B build/native && cmake --build build/native && ctest --test-dir build/native
    enable_testing()
    add_executable(native_tests tests/native_tests.cpp ${WA_ENGINE_SOURCES})
    target_include_directories(native_tests PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_compile_options(native_tests PRIVATE -Wall -Wextra)
    add_test(NAME native_tests COMMAND native_tests)
endif()
