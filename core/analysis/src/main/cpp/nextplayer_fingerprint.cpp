/*
 * JNI bridge for the vendored Chromaprint library (LGPL-2.1-or-later).
 * Converts a finite window of interleaved 16-bit PCM into a raw
 * Chromaprint fingerprint, plus the per-item duration needed to map
 * fingerprint positions back to media timestamps.
 */
#include <jni.h>
#include <cstdint>
#include <vector>

#include "chromaprint.h"

namespace {

constexpr jint kMinChannels = 1;
constexpr jint kMaxChannels = 2;
constexpr jint kFeedFrames = 8192;

struct ContextGuard {
    ChromaprintContext *ctx;

    explicit ContextGuard(ChromaprintContext *context) : ctx(context) {}
    ~ContextGuard() {
        if (ctx != nullptr) {
            chromaprint_free(ctx);
        }
    }
};

} // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_anilbeesetti_nextplayer_core_analysis_fingerprint_FingerprintNative_computeFingerprint(
    JNIEnv *env,
    jclass /* clazz */,
    jshortArray jPcm,
    jint sampleRate,
    jint channels,
    jint algorithm,
    jintArray jItemDurationMs
) {
    if (jPcm == nullptr || sampleRate <= 0 || channels < kMinChannels || channels > kMaxChannels) {
        return nullptr;
    }
    const jsize totalFrames = env->GetArrayLength(jPcm) / channels;
    if (totalFrames <= 0) {
        return nullptr;
    }

    ContextGuard guard(chromaprint_new(algorithm));
    ChromaprintContext *ctx = guard.ctx;
    if (ctx == nullptr) {
        return nullptr;
    }
    if (!chromaprint_start(ctx, sampleRate, channels)) {
        return nullptr;
    }

    jshort *pcm = env->GetShortArrayElements(jPcm, nullptr);
    if (pcm == nullptr) {
        return nullptr;
    }

    bool ok = true;
    jint offset = 0;
    std::vector<jshort> chunk;
    chunk.reserve(static_cast<size_t>(kFeedFrames) * channels);
    while (ok && offset < totalFrames) {
        const jint frames = (totalFrames - offset) < kFeedFrames ? (totalFrames - offset) : kFeedFrames;
        chunk.assign(pcm + static_cast<size_t>(offset) * channels,
                     pcm + static_cast<size_t>(offset + frames) * channels);
        ok = chromaprint_feed(ctx, chunk.data(), frames * channels) == 1;
        offset += frames;
    }
    env->ReleaseShortArrayElements(jPcm, pcm, JNI_ABORT);

    if (!ok || chromaprint_finish(ctx) != 1) {
        return nullptr;
    }

    uint32_t *raw = nullptr;
    int size = 0;
    if (chromaprint_get_raw_fingerprint(ctx, &raw, &size) != 1 || raw == nullptr || size <= 0) {
        if (raw != nullptr) {
            chromaprint_dealloc(raw);
        }
        return nullptr;
    }

    jintArray result = env->NewIntArray(size);
    if (result != nullptr) {
        env->SetIntArrayRegion(result, 0, size, reinterpret_cast<const jint *>(raw));
    }
    chromaprint_dealloc(raw);

    if (result != nullptr && jItemDurationMs != nullptr && env->GetArrayLength(jItemDurationMs) > 0) {
        const jint itemDurationMs = chromaprint_get_item_duration_ms(ctx);
        env->SetIntArrayRegion(jItemDurationMs, 0, 1, &itemDurationMs);
    }
    return result;
}
