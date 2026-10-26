// EririLily native bridge: a GLFW 3.4 implementation on Android (EGL +
// ANativeWindow), the in-process JVM launcher and the performance helpers.
// One library (libelbridge.so) is loaded twice: by the app (JNI) and by
// LWJGL inside the game JVM (-Dorg.lwjgl.glfw.libname=...); both share the
// state below because the linker hands out the same instance.
#pragma once

#include <EGL/egl.h>
#include <android/log.h>
#include <android/native_window.h>
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>

#define TAG "EririLily"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define API __attribute__((visibility("default")))

// ---------------------------------------------------------------- GLFW constants
#define GLFW_TRUE 1
#define GLFW_FALSE 0
#define GLFW_RELEASE 0
#define GLFW_PRESS 1
#define GLFW_REPEAT 2
#define GLFW_KEY_LAST 348
#define GLFW_MOUSE_BUTTON_LAST 7

#define GLFW_NOT_INITIALIZED 0x00010001
#define GLFW_NO_CURRENT_CONTEXT 0x00010002
#define GLFW_INVALID_ENUM 0x00010003
#define GLFW_INVALID_VALUE 0x00010004
#define GLFW_API_UNAVAILABLE 0x00010006
#define GLFW_PLATFORM_ERROR 0x00010008
#define GLFW_FEATURE_UNAVAILABLE 0x0001000C

#define GLFW_FOCUSED 0x00020001
#define GLFW_ICONIFIED 0x00020002
#define GLFW_RESIZABLE 0x00020003
#define GLFW_VISIBLE 0x00020004
#define GLFW_DECORATED 0x00020005
#define GLFW_AUTO_ICONIFY 0x00020006
#define GLFW_FLOATING 0x00020007
#define GLFW_MAXIMIZED 0x00020008
#define GLFW_HOVERED 0x0002000B

#define GLFW_CLIENT_API 0x00022001
#define GLFW_CONTEXT_VERSION_MAJOR 0x00022002
#define GLFW_CONTEXT_VERSION_MINOR 0x00022003
#define GLFW_OPENGL_PROFILE 0x00022008
#define GLFW_OPENGL_API 0x00030001
#define GLFW_OPENGL_CORE_PROFILE 0x00032001

#define GLFW_CURSOR 0x00033001
#define GLFW_STICKY_KEYS 0x00033002
#define GLFW_STICKY_MOUSE_BUTTONS 0x00033003
#define GLFW_LOCK_KEY_MODS 0x00033004
#define GLFW_RAW_MOUSE_MOTION 0x00033005
#define GLFW_CURSOR_NORMAL 0x00034001
#define GLFW_CURSOR_HIDDEN 0x00034002
#define GLFW_CURSOR_DISABLED 0x00034003

#define GLFW_PLATFORM 0x00050003
#define GLFW_PLATFORM_X11 0x00060004
#define GLFW_PLATFORM_NULL 0x00060005

typedef struct GLFWwindow GLFWwindow;
typedef struct GLFWmonitor GLFWmonitor;
typedef struct GLFWcursor GLFWcursor;

typedef void (*GLFWerrorfun)(int, const char *);
typedef void (*GLFWwindowposfun)(GLFWwindow *, int, int);
typedef void (*GLFWwindowsizefun)(GLFWwindow *, int, int);
typedef void (*GLFWwindowclosefun)(GLFWwindow *);
typedef void (*GLFWwindowrefreshfun)(GLFWwindow *);
typedef void (*GLFWwindowfocusfun)(GLFWwindow *, int);
typedef void (*GLFWwindowiconifyfun)(GLFWwindow *, int);
typedef void (*GLFWwindowmaximizefun)(GLFWwindow *, int);
typedef void (*GLFWframebuffersizefun)(GLFWwindow *, int, int);
typedef void (*GLFWwindowcontentscalefun)(GLFWwindow *, float, float);
typedef void (*GLFWmousebuttonfun)(GLFWwindow *, int, int, int);
typedef void (*GLFWcursorposfun)(GLFWwindow *, double, double);
typedef void (*GLFWcursorenterfun)(GLFWwindow *, int);
typedef void (*GLFWscrollfun)(GLFWwindow *, double, double);
typedef void (*GLFWkeyfun)(GLFWwindow *, int, int, int, int);
typedef void (*GLFWcharfun)(GLFWwindow *, unsigned int);
typedef void (*GLFWcharmodsfun)(GLFWwindow *, unsigned int, int);
typedef void (*GLFWdropfun)(GLFWwindow *, int, const char **);
typedef void (*GLFWmonitorfun)(GLFWmonitor *, int);
typedef void (*GLFWjoystickfun)(int, int);

typedef struct GLFWvidmode {
    int width, height, redBits, greenBits, blueBits, refreshRate;
} GLFWvidmode;

typedef struct GLFWgammaramp {
    unsigned short *red, *green, *blue;
    unsigned int size;
} GLFWgammaramp;

typedef struct GLFWimage {
    int width, height;
    unsigned char *pixels;
} GLFWimage;

typedef struct GLFWgamepadstate {
    unsigned char buttons[15];
    float axes[6];
} GLFWgamepadstate;

// ---------------------------------------------------------------- events
enum { EV_KEY = 1, EV_CHAR, EV_MOUSE, EV_CURSOR, EV_SCROLL, EV_FOCUS, EV_RESIZE, EV_CLOSE };

typedef struct {
    int type;
    int i1, i2, i3, i4;
    double d1, d2;
} Event;

#define QUEUE_SIZE 1024

// ---------------------------------------------------------------- shared state
struct GLFWwindow {
    // callbacks
    GLFWwindowposfun pos;
    GLFWwindowsizefun size;
    GLFWwindowclosefun close;
    GLFWwindowrefreshfun refresh;
    GLFWwindowfocusfun focus;
    GLFWwindowiconifyfun iconify;
    GLFWwindowmaximizefun maximize;
    GLFWframebuffersizefun fbsize;
    GLFWwindowcontentscalefun scale;
    GLFWmousebuttonfun mouse;
    GLFWcursorposfun cursor;
    GLFWcursorenterfun enter;
    GLFWscrollfun scroll;
    GLFWkeyfun key;
    GLFWcharfun chr;
    GLFWcharmodsfun charmods;
    GLFWdropfun drop;
    void *user;
    int shouldClose;
    int focused;
    int cursorMode;
    int stickyKeys, stickyMouse, lockMods, rawMotion;
    int width, height; // last size reported to the game
    char keys[GLFW_KEY_LAST + 1];
    char buttons[GLFW_MOUSE_BUTTON_LAST + 1];
    double cx, cy;
    char title[256];
};

typedef struct {
    // surface (written by the UI thread under lock, consumed by the game thread)
    pthread_mutex_t lock;
    pthread_cond_t cond;
    ANativeWindow *pending;  // new surface waiting to be picked up
    bool surfaceLost;        // the surface was destroyed
    int surfW, surfH;        // buffer size (render resolution)
    int viewW, viewH;        // on-screen size

    // EGL (game thread)
    EGLDisplay dpy;
    EGLConfig config;
    EGLContext ctx;
    EGLSurface surf;
    EGLSurface pbuffer;      // keeps the context current while backgrounded
    ANativeWindow *window;   // the window surf draws to
    int gles;                // 2 or 3

    GLFWwindow win;
    bool created;
    bool initialized;

    // queue
    pthread_mutex_t qlock;
    Event queue[QUEUE_SIZE];
    int qhead, qtail;

    // display
    int screenW, screenH, refresh;

    // stats + perf (atomics: read by the UI thread)
    _Atomic int fpsX10;
    _Atomic int frameUs;
    _Atomic int fpsLimit;
    _Atomic int vsync;       // -1 = leave alone
    _Atomic int bigCores;
    _Atomic int perfHint;
    _Atomic int renderTid;
    _Atomic long frames;
    float frameTimes[240];   // ms, ring
    _Atomic int ftHead;
} State;

extern State g;
extern JavaVM *artVM;

void queue_push(Event e);
void perf_on_swap(void);
void perf_reset(void);
int64_t now_ns(void);
void perf_pin_big_cores(void);
void set_error(int code, const char *msg);
