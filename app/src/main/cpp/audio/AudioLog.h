#pragma once

#include <android/log.h>

// Never call from the audio callback.
#define WA_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "WeAudio", __VA_ARGS__)
#define WA_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "WeAudio", __VA_ARGS__)
#define WA_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "WeAudio", __VA_ARGS__)
