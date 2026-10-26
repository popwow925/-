// JNI entry points for the app (cn.eririlily.launcher.game.Bridge).
#include "bridge.h"

#include <android/native_window_jni.h>
#include <string.h>

JavaVM *artVM;
jclass bridgeClass;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    artVM = vm;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
        jclass c = (*env)->FindClass(env, "cn/eririlily/launcher/game/Bridge");
        if (c) bridgeClass = (*env)->NewGlobalRef(env, c);
        (*env)->ExceptionClear(env);
    }
    return JNI_VERSION_1_6;
}

#define FN(ret, name) JNIEXPORT ret JNICALL Java_cn_eririlily_launcher_game_Bridge_##name

FN(void, setSurface)(JNIEnv *env, jclass c, jobject surface, jint w, jint h) {
    (void)c;
    ANativeWindow *win = ANativeWindow_fromSurface(env, surface);
    pthread_mutex_lock(&g.lock);
    g.surfW = w;
    g.surfH = h;
    if (win && (win == g.window && !g.surfaceLost)) {
        ANativeWindow_release(win); // same surface, new size: EGL follows on the next swap
    } else if (win) {
        if (g.pending) ANativeWindow_release(g.pending);
        g.pending = win;
    }
    pthread_cond_broadcast(&g.cond);
    pthread_mutex_unlock(&g.lock);
    LOGI("surface %dx%d", w, h);
}

FN(void, surfaceDestroyed)(JNIEnv *env, jclass c) {
    (void)env; (void)c;
    pthread_mutex_lock(&g.lock);
    g.surfaceLost = true;
    if (g.pending) {
        ANativeWindow_release(g.pending);
        g.pending = NULL;
    }
    pthread_cond_broadcast(&g.cond);
    pthread_mutex_unlock(&g.lock);
}

FN(void, setDisplay)(JNIEnv *env, jclass c, jint w, jint h, jint refresh) {
    (void)env; (void)c;
    g.screenW = w;
    g.screenH = h;
    g.refresh = refresh > 0 ? refresh : 60;
}

FN(void, sendKey)(JNIEnv *env, jclass c, jint key, jint scancode, jint action, jint mods) {
    (void)env; (void)c;
    Event e = {.type = EV_KEY, .i1 = key, .i2 = scancode, .i3 = action, .i4 = mods};
    queue_push(e);
}

FN(void, sendChar)(JNIEnv *env, jclass c, jint codepoint, jint mods) {
    (void)env; (void)c;
    Event e = {.type = EV_CHAR, .i1 = codepoint, .i2 = mods};
    queue_push(e);
}

FN(void, sendMouseButton)(JNIEnv *env, jclass c, jint button, jint action, jint mods) {
    (void)env; (void)c;
    Event e = {.type = EV_MOUSE, .i1 = button, .i2 = action, .i3 = mods};
    queue_push(e);
}

FN(void, sendCursor)(JNIEnv *env, jclass c, jfloat x, jfloat y) {
    (void)env; (void)c;
    Event e = {.type = EV_CURSOR, .i1 = 0, .d1 = x, .d2 = y};
    queue_push(e);
}

FN(void, sendCursorDelta)(JNIEnv *env, jclass c, jfloat dx, jfloat dy) {
    (void)env; (void)c;
    Event e = {.type = EV_CURSOR, .i1 = 1, .d1 = dx, .d2 = dy};
    queue_push(e);
}

FN(void, sendScroll)(JNIEnv *env, jclass c, jfloat dx, jfloat dy) {
    (void)env; (void)c;
    Event e = {.type = EV_SCROLL, .d1 = dx, .d2 = dy};
    queue_push(e);
}

FN(void, sendFocus)(JNIEnv *env, jclass c, jboolean focused) {
    (void)env; (void)c;
    Event e = {.type = EV_FOCUS, .i1 = focused ? 1 : 0};
    queue_push(e);
}

FN(void, requestClose)(JNIEnv *env, jclass c) {
    (void)env; (void)c;
    Event e = {.type = EV_CLOSE};
    queue_push(e);
}

// true while the game has captured the mouse (playing, not in a menu)
FN(jboolean, isGrabbing)(JNIEnv *env, jclass c) {
    (void)env; (void)c;
    return g.created && g.win.cursorMode == GLFW_CURSOR_DISABLED;
}

FN(jboolean, isWindowReady)(JNIEnv *env, jclass c) {
    (void)env; (void)c;
    return g.created;
}

FN(void, getCursor)(JNIEnv *env, jclass c, jfloatArray out) {
    (void)c;
    jfloat v[2] = {(jfloat)g.win.cx, (jfloat)g.win.cy};
    (*env)->SetFloatArrayRegion(env, out, 0, 2, v);
}

FN(jfloat, getFps)(JNIEnv *env, jclass c) {
    (void)env; (void)c;
    return atomic_load(&g.fpsX10) / 10.0f;
}

FN(jint, getFrameUs)(JNIEnv *env, jclass c) {
    (void)env; (void)c;
    return atomic_load(&g.frameUs);
}

FN(jlong, getFrames)(JNIEnv *env, jclass c) {
    (void)env; (void)c;
    return atomic_load(&g.frames);
}

// copies the frame-time ring (ms) in order, oldest first
FN(void, getFrameTimes)(JNIEnv *env, jclass c, jfloatArray out) {
    (void)c;
    float tmp[240];
    int h = atomic_load(&g.ftHead);
    for (int i = 0; i < 240; i++) tmp[i] = g.frameTimes[(h + i) % 240];
    jsize n = (*env)->GetArrayLength(env, out);
    (*env)->SetFloatArrayRegion(env, out, 0, n < 240 ? n : 240, tmp + (n < 240 ? 240 - n : 0));
}

FN(void, setFpsLimit)(JNIEnv *env, jclass c, jint fps) {
    (void)env; (void)c;
    atomic_store(&g.fpsLimit, fps < 0 ? 0 : fps);
}

// -1 lets the game decide, 0 off, 1 on
FN(void, setVsync)(JNIEnv *env, jclass c, jint v) {
    (void)env; (void)c;
    atomic_store(&g.vsync, v);
}

FN(void, setPerf)(JNIEnv *env, jclass c, jboolean bigCores, jboolean perfHint) {
    (void)env; (void)c;
    atomic_store(&g.bigCores, bigCores ? 1 : 0);
    atomic_store(&g.perfHint, perfHint ? 1 : 0);
}

FN(jint, getRenderTid)(JNIEnv *env, jclass c) {
    (void)env; (void)c;
    return atomic_load(&g.renderTid);
}
