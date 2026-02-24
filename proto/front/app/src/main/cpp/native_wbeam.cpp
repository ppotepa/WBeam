#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>

#if WBEAM_USE_TURBOJPEG
#include <turbojpeg.h>
#else
typedef void* tjhandle;
#endif

#define LOG_TAG "WBeamNative"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct WBeamNative {
    ANativeWindow* win = nullptr;
    tjhandle tj = nullptr;

    int outMaxW = 960;
    int outMaxH = 540;

    int curW = 0;
    int curH = 0;

    int lastRc = 0;
    int lastLen = 0;
    int lastInW = 0;
    int lastInH = 0;
    int lastTargetW = 0;
    int lastTargetH = 0;
    int lastBufW = 0;
    int lastBufH = 0;
    int lastBufStride = 0;
    int lastBufFormat = 0;
    uint64_t frameCounter = 0;

    pthread_mutex_t mu;
};

static inline WBeamNative* H(jlong handle) {
    return reinterpret_cast<WBeamNative*>(handle);
}

static void set_window_locked(WBeamNative* s, ANativeWindow* w) {
    if (s->win) {
        ANativeWindow_release(s->win);
        s->win = nullptr;
        s->curW = 0;
        s->curH = 0;
    }
    s->win = w;
    if (s->win) {
        ANativeWindow_acquire(s->win);
    }
}

static int pick_scaled_size(int inW, int inH, int outMaxW, int outMaxH, int* outW, int* outH) {
#if WBEAM_USE_TURBOJPEG
    // OPT: use turbojpeg scaling factors to decode smaller (less CPU + less blit)
    int n = 0;
    tjscalingfactor* sfs = tjGetScalingFactors(&n);
    if (!sfs || n <= 0) {
        *outW = inW; *outH = inH;
        return 0;
    }

    // Choose the largest scaled size that fits within outMaxW/outMaxH
    int bestW = 0;
    int bestH = 0;
    int bestArea = 0;

    for (int i = 0; i < n; i++) {
        int w = TJSCALED(inW, sfs[i]);
        int h = TJSCALED(inH, sfs[i]);
        if (w <= 0 || h <= 0) continue;
        if (w <= outMaxW && h <= outMaxH) {
            int area = w * h;
            if (area > bestArea) {
                bestW = w;
                bestH = h;
                bestArea = area;
            }
        }
    }

    // If original fits, keep it (highest quality)
    if (inW <= outMaxW && inH <= outMaxH) {
        bestW = inW;
        bestH = inH;
    } else if (bestW <= 0 || bestH <= 0) {
        // No turbo scale factor fits exactly; clamp by aspect ratio.
        const float rw = (float)outMaxW / (float)inW;
        const float rh = (float)outMaxH / (float)inH;
        const float r = rw < rh ? rw : rh;
        bestW = (int)(inW * r);
        bestH = (int)(inH * r);
        if (bestW < 1) bestW = 1;
        if (bestH < 1) bestH = 1;
    }

    *outW = bestW;
    *outH = bestH;
    return 0;
#else
    (void)outMaxW;
    (void)outMaxH;
    *outW = inW;
    *outH = inH;
    return 0;
#endif
}

static inline void draw_diag_marker(unsigned char* dst, int pitch, int w, int h, uint64_t frameCounter) {
    if (!dst || pitch <= 0 || w <= 0 || h <= 0) return;
    const int markW = (w < 16) ? w : 16;
    const int markH = (h < 16) ? h : 16;
    const int phase = (int)((frameCounter / 20u) & 1u);
    const unsigned char c0 = phase ? 255 : 40;
    const unsigned char c1 = phase ? 60 : 255;
    const unsigned char c2 = 40;
    for (int y = 0; y < markH; y++) {
        unsigned char* row = dst + y * pitch;
        for (int x = 0; x < markW; x++) {
            unsigned char* p = row + x * 4;
            p[0] = c0;
            p[1] = c1;
            p[2] = c2;
            p[3] = 255;
        }
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_proto_demo_MainActivity_nativeCreate(JNIEnv* env, jclass, jint outMaxW, jint outMaxH) {
    (void)env;
    auto* s = new WBeamNative();
    s->outMaxW = (int)outMaxW;
    s->outMaxH = (int)outMaxH;
    pthread_mutex_init(&s->mu, nullptr);

#if WBEAM_USE_TURBOJPEG
    s->tj = tjInitDecompress();
    if (!s->tj) {
        ALOGE("tjInitDecompress failed: %s", tjGetErrorStr());
    } else {
        ALOGI("nativeCreate ok outMax=%dx%d", s->outMaxW, s->outMaxH);
    }
#else
    ALOGW("nativeCreate: turbojpeg unavailable, decode path is stubbed");
#endif
    return (jlong)s;
}

extern "C" JNIEXPORT void JNICALL
Java_com_proto_demo_MainActivity_nativeDestroy(JNIEnv* env, jclass, jlong handle) {
    (void)env;
    auto* s = H(handle);
    if (!s) return;

    pthread_mutex_lock(&s->mu);

#if WBEAM_USE_TURBOJPEG
    if (s->tj) {
        tjDestroy(s->tj);
        s->tj = nullptr;
    }
#endif
    set_window_locked(s, nullptr);

    pthread_mutex_unlock(&s->mu);
    pthread_mutex_destroy(&s->mu);

    delete s;
}

extern "C" JNIEXPORT void JNICALL
Java_com_proto_demo_MainActivity_nativeSetSurface(JNIEnv* env, jclass, jlong handle, jobject surface) {
    auto* s = H(handle);
    if (!s) return;

    ANativeWindow* w = nullptr;
    if (surface) {
        w = ANativeWindow_fromSurface(env, surface);
    }

    pthread_mutex_lock(&s->mu);
    set_window_locked(s, w);
    pthread_mutex_unlock(&s->mu);

    if (w) {
        ANativeWindow_release(w); // we acquired inside set_window_locked
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_proto_demo_MainActivity_nativeClearSurface(JNIEnv* env, jclass, jlong handle) {
    (void)env;
    auto* s = H(handle);
    if (!s) return;

    pthread_mutex_lock(&s->mu);
    set_window_locked(s, nullptr);
    pthread_mutex_unlock(&s->mu);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_proto_demo_MainActivity_nativeTurboAvailable(JNIEnv* env, jclass) {
    (void)env;
#if WBEAM_USE_TURBOJPEG
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_proto_demo_MainActivity_nativeGetDiag(JNIEnv* env, jclass, jlong handle) {
    auto* s = H(handle);
    if (!s) {
        return env->NewStringUTF("handle=null");
    }
    char line[256];
    pthread_mutex_lock(&s->mu);
    snprintf(line, sizeof(line),
             "rc=%d len=%d in=%dx%d tgt=%dx%d buf=%dx%d st=%d fmt=%d fr=%llu",
             s->lastRc, s->lastLen, s->lastInW, s->lastInH,
             s->lastTargetW, s->lastTargetH, s->lastBufW, s->lastBufH,
             s->lastBufStride, s->lastBufFormat,
             (unsigned long long)s->frameCounter);
    pthread_mutex_unlock(&s->mu);
    return env->NewStringUTF(line);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_proto_demo_MainActivity_nativeDecodeAndRender(JNIEnv* env, jclass, jlong handle,
                                                      jbyteArray jpegArr, jint len,
                                                      jint outMaxW, jint outMaxH) {
    auto* s = H(handle);
    if (!s) return -1;
#if !WBEAM_USE_TURBOJPEG
    (void)env;
    (void)jpegArr;
    (void)len;
    (void)outMaxW;
    (void)outMaxH;
    return -100;
#else
    if (!s->tj) {
        s->lastRc = -1;
        return -1;
    }

    pthread_mutex_lock(&s->mu);
    s->lastLen = (int)len;
    s->frameCounter++;
    ANativeWindow* win = s->win;
    if (!win) {
        s->lastRc = -2;
        pthread_mutex_unlock(&s->mu);
        return -2;
    }

    // Read JPEG header
    int inW = 0, inH = 0, subsamp = 0, colorspace = 0;

    // OPT: GetPrimitiveArrayCritical => minimal/no-copy access for the duration
    jboolean isCopy = JNI_FALSE;
    unsigned char* jpegPtr = (unsigned char*)env->GetPrimitiveArrayCritical(jpegArr, &isCopy);
    if (!jpegPtr) {
        s->lastRc = -3;
        pthread_mutex_unlock(&s->mu);
        return -3;
    }

    if (tjDecompressHeader3(s->tj, jpegPtr, (unsigned long)len, &inW, &inH, &subsamp, &colorspace) != 0) {
        env->ReleasePrimitiveArrayCritical(jpegArr, jpegPtr, 0);
        s->lastRc = -4;
        pthread_mutex_unlock(&s->mu);
        ALOGW("tjDecompressHeader3 failed: %s", tjGetErrorStr());
        return -4;
    }
    s->lastInW = inW;
    s->lastInH = inH;

    // Decide scaled output size
    int targetW = inW;
    int targetH = inH;
    pick_scaled_size(inW, inH, (int)outMaxW, (int)outMaxH, &targetW, &targetH);
    s->lastTargetW = targetW;
    s->lastTargetH = targetH;

    // Configure window geometry only if needed
    if (targetW != s->curW || targetH != s->curH) {
        // Use RGBX to avoid alpha-related black output on some devices/compositors.
        int rc = ANativeWindow_setBuffersGeometry(win, targetW, targetH, WINDOW_FORMAT_RGBX_8888);
        if (rc != 0) {
            env->ReleasePrimitiveArrayCritical(jpegArr, jpegPtr, 0);
            s->lastRc = -5;
            pthread_mutex_unlock(&s->mu);
            ALOGW("ANativeWindow_setBuffersGeometry failed rc=%d", rc);
            return -5;
        }
        ALOGI("buffer geometry set %dx%d (input=%dx%d outMax=%dx%d)", targetW, targetH, inW, inH, (int)outMaxW, (int)outMaxH);
        s->curW = targetW;
        s->curH = targetH;
    }

    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(win, &buf, nullptr) != 0) {
        env->ReleasePrimitiveArrayCritical(jpegArr, jpegPtr, 0);
        s->lastRc = -6;
        pthread_mutex_unlock(&s->mu);
        return -6;
    }
    s->lastBufW = buf.width;
    s->lastBufH = buf.height;
    s->lastBufStride = buf.stride;
    s->lastBufFormat = buf.format;

    const int pitch = buf.stride * 4;
    unsigned char* dst = (unsigned char*)buf.bits;

    // OPT flags: FASTDCT + FASTUPSAMPLE = faster decode, usually good enough for streaming
    const int flags = TJFLAG_FASTDCT | TJFLAG_FASTUPSAMPLE;

    int tjrc = tjDecompress2(s->tj,
                            jpegPtr, (unsigned long)len,
                            dst, targetW, pitch, targetH,
                            TJPF_RGBX, flags);

    env->ReleasePrimitiveArrayCritical(jpegArr, jpegPtr, 0);

    if (tjrc == 0) {
        draw_diag_marker(dst, pitch, targetW, targetH, s->frameCounter);
        s->lastRc = 0;
    } else {
        s->lastRc = -7;
    }
    ANativeWindow_unlockAndPost(win);
    pthread_mutex_unlock(&s->mu);

    if (tjrc != 0) {
        ALOGW("tjDecompress2 failed: %s", tjGetErrorStr());
        return -7;
    }

    return 0;
#endif
}
