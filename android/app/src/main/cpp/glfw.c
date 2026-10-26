// GLFW 3.4 API on Android. Exactly one window; its "framebuffer" is the
// game SurfaceView, sized by the render scale. Input arrives from the app's
// UI thread through the event queue and is dispatched in glfwPollEvents on
// the game thread, like a desktop GLFW does.
#include "bridge.h"

#include <EGL/eglext.h>
#include <dlfcn.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

State g = {
    .lock = PTHREAD_MUTEX_INITIALIZER,
    .cond = PTHREAD_COND_INITIALIZER,
    .qlock = PTHREAD_MUTEX_INITIALIZER,
    .dpy = EGL_NO_DISPLAY,
    .ctx = EGL_NO_CONTEXT,
    .surf = EGL_NO_SURFACE,
    .pbuffer = EGL_NO_SURFACE,
    .gles = 3,
    .screenW = 1920,
    .screenH = 1080,
    .refresh = 60,
    .vsync = -1,
};

static GLFWerrorfun errorCb;
static GLFWmonitorfun monitorCb;
static GLFWjoystickfun joystickCb;
static int lastError;
static char lastErrorMsg[256];
static double timeBase;
static int hints[64][2];
static int nhints;
static void *monitorUser;
static int monitorDummy;
static GLFWvidmode vidmode;
static GLFWmonitor *monitors[1];
static int cursorDummy;
static char *clipboard;
static __thread GLFWwindow *current; // the window whose context is current on this thread

void set_error(int code, const char *msg) {
    lastError = code;
    snprintf(lastErrorMsg, sizeof lastErrorMsg, "%s", msg);
    LOGW("GLFW error 0x%x: %s", code, msg);
    if (errorCb) errorCb(code, msg);
}

int64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

void queue_push(Event e) {
    pthread_mutex_lock(&g.qlock);
    int next = (g.qtail + 1) % QUEUE_SIZE;
    if (next == g.qhead) {
        g.qhead = (g.qhead + 1) % QUEUE_SIZE; // full: drop the oldest
    }
    // coalesce cursor moves: only the newest position matters
    if (e.type == EV_CURSOR && g.qtail != g.qhead) {
        int last = (g.qtail + QUEUE_SIZE - 1) % QUEUE_SIZE;
        if (g.queue[last].type == EV_CURSOR && g.queue[last].i1 == e.i1) {
            if (e.i1) { // relative: add up
                g.queue[last].d1 += e.d1;
                g.queue[last].d2 += e.d2;
            } else {
                g.queue[last] = e;
            }
            pthread_mutex_unlock(&g.qlock);
            return;
        }
    }
    g.queue[g.qtail] = e;
    g.qtail = next;
    pthread_mutex_unlock(&g.qlock);
}

// ---------------------------------------------------------------- init

API int glfwInit(void) {
    if (!g.initialized) {
        timeBase = now_ns() / 1e9;
        g.initialized = true;
        monitors[0] = (GLFWmonitor *)&monitorDummy;
        g.win.focused = 1;
        g.win.cursorMode = GLFW_CURSOR_NORMAL;
    }
    return GLFW_TRUE;
}

API void glfwTerminate(void) {}
API void glfwInitHint(int hint, int value) { (void)hint; (void)value; }
API void glfwInitAllocator(const void *allocator) { (void)allocator; }
API void glfwInitVulkanLoader(void *loader) { (void)loader; }

API void glfwGetVersion(int *major, int *minor, int *rev) {
    if (major) *major = 3;
    if (minor) *minor = 4;
    if (rev) *rev = 0;
}

API const char *glfwGetVersionString(void) { return "3.4.0 EririLily Android EGL"; }

API int glfwGetError(const char **description) {
    int e = lastError;
    if (description) *description = e ? lastErrorMsg : NULL;
    lastError = 0;
    return e;
}

API GLFWerrorfun glfwSetErrorCallback(GLFWerrorfun cb) {
    GLFWerrorfun old = errorCb;
    errorCb = cb;
    return old;
}

// Report X11 so games and mods take their ordinary Linux path.
API int glfwGetPlatform(void) { return GLFW_PLATFORM_X11; }
API int glfwPlatformSupported(int p) { return p == GLFW_PLATFORM_X11 || p == GLFW_PLATFORM_NULL; }

// ---------------------------------------------------------------- monitors

static void fill_vidmode(void) {
    vidmode.width = g.screenW;
    vidmode.height = g.screenH;
    vidmode.redBits = vidmode.greenBits = vidmode.blueBits = 8;
    vidmode.refreshRate = g.refresh;
}

API GLFWmonitor **glfwGetMonitors(int *count) {
    if (count) *count = 1;
    monitors[0] = (GLFWmonitor *)&monitorDummy;
    return monitors;
}
API GLFWmonitor *glfwGetPrimaryMonitor(void) { return (GLFWmonitor *)&monitorDummy; }
API void glfwGetMonitorPos(GLFWmonitor *m, int *x, int *y) {
    (void)m;
    if (x) *x = 0;
    if (y) *y = 0;
}
API void glfwGetMonitorWorkarea(GLFWmonitor *m, int *x, int *y, int *w, int *h) {
    (void)m;
    if (x) *x = 0;
    if (y) *y = 0;
    if (w) *w = g.surfW > 0 ? g.surfW : g.screenW;
    if (h) *h = g.surfH > 0 ? g.surfH : g.screenH;
}
API void glfwGetMonitorPhysicalSize(GLFWmonitor *m, int *w, int *h) {
    (void)m;
    if (w) *w = 160;
    if (h) *h = 75;
}
API void glfwGetMonitorContentScale(GLFWmonitor *m, float *x, float *y) {
    (void)m;
    if (x) *x = 1;
    if (y) *y = 1;
}
API const char *glfwGetMonitorName(GLFWmonitor *m) {
    (void)m;
    return "Android Display";
}
API void glfwSetMonitorUserPointer(GLFWmonitor *m, void *p) {
    (void)m;
    monitorUser = p;
}
API void *glfwGetMonitorUserPointer(GLFWmonitor *m) {
    (void)m;
    return monitorUser;
}
API GLFWmonitorfun glfwSetMonitorCallback(GLFWmonitorfun cb) {
    GLFWmonitorfun old = monitorCb;
    monitorCb = cb;
    return old;
}
API const GLFWvidmode *glfwGetVideoModes(GLFWmonitor *m, int *count) {
    (void)m;
    fill_vidmode();
    if (count) *count = 1;
    return &vidmode;
}
API const GLFWvidmode *glfwGetVideoMode(GLFWmonitor *m) {
    (void)m;
    fill_vidmode();
    return &vidmode;
}
API void glfwSetGamma(GLFWmonitor *m, float gamma) { (void)m; (void)gamma; }
API const GLFWgammaramp *glfwGetGammaRamp(GLFWmonitor *m) {
    (void)m;
    set_error(GLFW_FEATURE_UNAVAILABLE, "gamma ramps are not available on Android");
    return NULL;
}
API void glfwSetGammaRamp(GLFWmonitor *m, const GLFWgammaramp *r) { (void)m; (void)r; }

// ---------------------------------------------------------------- window hints

API void glfwDefaultWindowHints(void) { nhints = 0; }
API void glfwWindowHint(int hint, int value) {
    for (int i = 0; i < nhints; i++)
        if (hints[i][0] == hint) {
            hints[i][1] = value;
            return;
        }
    if (nhints < 64) {
        hints[nhints][0] = hint;
        hints[nhints][1] = value;
        nhints++;
    }
}
API void glfwWindowHintString(int hint, const char *value) { (void)hint; (void)value; }

static int hint_value(int hint, int def) {
    for (int i = 0; i < nhints; i++)
        if (hints[i][0] == hint) return hints[i][1];
    return def;
}

// ---------------------------------------------------------------- EGL

static bool egl_setup(void) {
    if (g.dpy != EGL_NO_DISPLAY) return true;
    g.dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (g.dpy == EGL_NO_DISPLAY || !eglInitialize(g.dpy, NULL, NULL)) {
        set_error(GLFW_PLATFORM_ERROR, "eglInitialize failed");
        return false;
    }
    const char *es = getenv("EL_GLES");
    g.gles = es && es[0] == '2' ? 2 : 3;
    EGLint renderable = g.gles == 3 ? 0x40 /* EGL_OPENGL_ES3_BIT_KHR */ : EGL_OPENGL_ES2_BIT;
    // RGBA8 + depth 24 + stencil 8 first; then cheaper fallbacks
    const EGLint depths[][2] = {{24, 8}, {24, 0}, {16, 8}, {16, 0}};
    EGLint n = 0;
    for (int i = 0; i < 4 && n == 0; i++) {
        EGLint attrs[] = {EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                          EGL_DEPTH_SIZE, depths[i][0], EGL_STENCIL_SIZE, depths[i][1],
                          EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
                          EGL_RENDERABLE_TYPE, renderable, EGL_NONE};
        eglChooseConfig(g.dpy, attrs, &g.config, 1, &n);
        if (n == 0 && g.gles == 3 && i == 3) { // very old GPU: fall back to ES 2
            g.gles = 2;
            renderable = EGL_OPENGL_ES2_BIT;
            i = -1;
        }
    }
    if (n == 0) {
        set_error(GLFW_PLATFORM_ERROR, "no EGL config");
        return false;
    }
    eglBindAPI(EGL_OPENGL_ES_API);
    EGLint ctxAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, g.gles, EGL_NONE};
    if (g.gles == 3) {
        // ask for the newest ES 3.x the driver offers (MobileGlues wants 3.2)
        EGLint a32[] = {EGL_CONTEXT_MAJOR_VERSION_KHR, 3, EGL_CONTEXT_MINOR_VERSION_KHR, 2, EGL_NONE};
        g.ctx = eglCreateContext(g.dpy, g.config, EGL_NO_CONTEXT, a32);
    }
    if (g.ctx == EGL_NO_CONTEXT) g.ctx = eglCreateContext(g.dpy, g.config, EGL_NO_CONTEXT, ctxAttrs);
    if (g.ctx == EGL_NO_CONTEXT) {
        set_error(GLFW_PLATFORM_ERROR, "eglCreateContext failed");
        return false;
    }
    EGLint pb[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    g.pbuffer = eglCreatePbufferSurface(g.dpy, g.config, pb);
    LOGI("EGL ready: GLES %d", g.gles);
    return true;
}

static void report_size(int w, int h) {
    if (w <= 0 || h <= 0 || (w == g.win.width && h == g.win.height)) return;
    g.win.width = w;
    g.win.height = h;
    Event e = {.type = EV_RESIZE, .i1 = w, .i2 = h};
    queue_push(e);
}

// Pick up surface changes from the UI thread. Called on the game thread.
// Returns false while there is no surface to draw to.
static bool sync_surface(bool block) {
    pthread_mutex_lock(&g.lock);
    for (;;) {
        if (g.pending) {
            ANativeWindow *w = g.pending;
            g.pending = NULL;
            if (g.surf != EGL_NO_SURFACE) {
                eglMakeCurrent(g.dpy, g.pbuffer, g.pbuffer, g.ctx);
                eglDestroySurface(g.dpy, g.surf);
                g.surf = EGL_NO_SURFACE;
            }
            if (g.window) ANativeWindow_release(g.window);
            g.window = w;
            g.surfaceLost = false;
            g.surf = eglCreateWindowSurface(g.dpy, g.config, w, NULL);
            if (g.surf == EGL_NO_SURFACE) LOGE("eglCreateWindowSurface: 0x%x", eglGetError());
            if (g.ctx != EGL_NO_CONTEXT && g.created) eglMakeCurrent(g.dpy, g.surf, g.surf, g.ctx);
            int v = atomic_load(&g.vsync);
            if (v >= 0) eglSwapInterval(g.dpy, v);
            else eglSwapInterval(g.dpy, 0);
        }
        if (g.surfaceLost && g.surf != EGL_NO_SURFACE) {
            eglMakeCurrent(g.dpy, g.pbuffer, g.pbuffer, g.ctx);
            eglDestroySurface(g.dpy, g.surf);
            g.surf = EGL_NO_SURFACE;
            if (g.window) ANativeWindow_release(g.window);
            g.window = NULL;
        }
        if (g.surf != EGL_NO_SURFACE || !block) break;
        // backgrounded: wait for the surface to come back (the game is paused anyway)
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_sec += 1;
        pthread_cond_timedwait(&g.cond, &g.lock, &ts);
    }
    int w = g.surfW, h = g.surfH;
    bool ok = g.surf != EGL_NO_SURFACE;
    pthread_mutex_unlock(&g.lock);
    report_size(w, h);
    return ok;
}

// ---------------------------------------------------------------- windows

API GLFWwindow *glfwCreateWindow(int width, int height, const char *title, GLFWmonitor *monitor, GLFWwindow *share) {
    (void)width; (void)height; (void)monitor; (void)share;
    if (g.created) {
        set_error(GLFW_PLATFORM_ERROR, "only one window is supported");
        return NULL;
    }
    if (!egl_setup()) return NULL;
    // wait for the game view's surface
    int64_t deadline = now_ns() + 60LL * 1000000000LL;
    for (;;) {
        pthread_mutex_lock(&g.lock);
        bool have = g.pending != NULL || g.window != NULL;
        pthread_mutex_unlock(&g.lock);
        if (have) break;
        if (now_ns() > deadline) {
            set_error(GLFW_PLATFORM_ERROR, "no surface");
            return NULL;
        }
        usleep(10000);
    }
    g.created = true;
    g.win.width = g.win.height = 0;
    if (title) snprintf(g.win.title, sizeof g.win.title, "%s", title);
    sync_surface(true);
    return &g.win;
}

API void glfwDestroyWindow(GLFWwindow *w) {
    (void)w;
    if (g.dpy != EGL_NO_DISPLAY) eglMakeCurrent(g.dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
}

API int glfwWindowShouldClose(GLFWwindow *w) { return w ? w->shouldClose : 0; }
API void glfwSetWindowShouldClose(GLFWwindow *w, int v) {
    if (w) w->shouldClose = v;
}
API const char *glfwGetWindowTitle(GLFWwindow *w) { return w ? w->title : NULL; }
API void glfwSetWindowTitle(GLFWwindow *w, const char *t) {
    if (w && t) snprintf(w->title, sizeof w->title, "%s", t);
}
API void glfwSetWindowIcon(GLFWwindow *w, int n, const GLFWimage *im) { (void)w; (void)n; (void)im; }
API void glfwGetWindowPos(GLFWwindow *w, int *x, int *y) {
    (void)w;
    if (x) *x = 0;
    if (y) *y = 0;
}
API void glfwSetWindowPos(GLFWwindow *w, int x, int y) { (void)w; (void)x; (void)y; }
API void glfwGetWindowSize(GLFWwindow *w, int *width, int *height) {
    (void)w;
    pthread_mutex_lock(&g.lock);
    if (width) *width = g.surfW;
    if (height) *height = g.surfH;
    pthread_mutex_unlock(&g.lock);
}
API void glfwSetWindowSizeLimits(GLFWwindow *w, int a, int b, int c, int d) { (void)w; (void)a; (void)b; (void)c; (void)d; }
API void glfwSetWindowAspectRatio(GLFWwindow *w, int n, int d) { (void)w; (void)n; (void)d; }
API void glfwSetWindowSize(GLFWwindow *w, int x, int y) { (void)w; (void)x; (void)y; }
API void glfwGetFramebufferSize(GLFWwindow *w, int *width, int *height) { glfwGetWindowSize(w, width, height); }
API void glfwGetWindowFrameSize(GLFWwindow *w, int *l, int *t, int *r, int *b) {
    (void)w;
    if (l) *l = 0;
    if (t) *t = 0;
    if (r) *r = 0;
    if (b) *b = 0;
}
API void glfwGetWindowContentScale(GLFWwindow *w, float *x, float *y) {
    (void)w;
    if (x) *x = 1;
    if (y) *y = 1;
}
API float glfwGetWindowOpacity(GLFWwindow *w) { (void)w; return 1; }
API void glfwSetWindowOpacity(GLFWwindow *w, float o) { (void)w; (void)o; }
API void glfwIconifyWindow(GLFWwindow *w) { (void)w; }
API void glfwRestoreWindow(GLFWwindow *w) { (void)w; }
API void glfwMaximizeWindow(GLFWwindow *w) { (void)w; }
API void glfwShowWindow(GLFWwindow *w) { (void)w; }
API void glfwHideWindow(GLFWwindow *w) { (void)w; }
API void glfwFocusWindow(GLFWwindow *w) { (void)w; }
API void glfwRequestWindowAttention(GLFWwindow *w) { (void)w; }
API GLFWmonitor *glfwGetWindowMonitor(GLFWwindow *w) { (void)w; return NULL; }
API void glfwSetWindowMonitor(GLFWwindow *w, GLFWmonitor *m, int x, int y, int width, int height, int rate) {
    (void)w; (void)m; (void)x; (void)y; (void)width; (void)height; (void)rate;
}

API int glfwGetWindowAttrib(GLFWwindow *w, int attrib) {
    switch (attrib) {
    case GLFW_FOCUSED: return w ? w->focused : 1;
    case GLFW_VISIBLE: case GLFW_DECORATED: case GLFW_HOVERED: return 1;
    case GLFW_ICONIFIED: case GLFW_FLOATING: return 0;
    case GLFW_MAXIMIZED: case GLFW_RESIZABLE: case GLFW_AUTO_ICONIFY: return 1;
    case GLFW_CLIENT_API: return GLFW_OPENGL_API;
    case GLFW_CONTEXT_VERSION_MAJOR: return hint_value(attrib, 3);
    case GLFW_CONTEXT_VERSION_MINOR: return hint_value(attrib, 2);
    case GLFW_OPENGL_PROFILE: return hint_value(attrib, GLFW_OPENGL_CORE_PROFILE);
    }
    return hint_value(attrib, 0);
}
API void glfwSetWindowAttrib(GLFWwindow *w, int a, int v) { (void)w; (void)a; (void)v; }
API void glfwSetWindowUserPointer(GLFWwindow *w, void *p) {
    if (w) w->user = p;
}
API void *glfwGetWindowUserPointer(GLFWwindow *w) { return w ? w->user : NULL; }

#define SETCB(name, type, field)                 \
    API type name(GLFWwindow *w, type cb) {      \
        if (!w) return NULL;                     \
        type old = w->field;                     \
        w->field = cb;                           \
        return old;                              \
    }
SETCB(glfwSetWindowPosCallback, GLFWwindowposfun, pos)
SETCB(glfwSetWindowSizeCallback, GLFWwindowsizefun, size)
SETCB(glfwSetWindowCloseCallback, GLFWwindowclosefun, close)
SETCB(glfwSetWindowRefreshCallback, GLFWwindowrefreshfun, refresh)
SETCB(glfwSetWindowFocusCallback, GLFWwindowfocusfun, focus)
SETCB(glfwSetWindowIconifyCallback, GLFWwindowiconifyfun, iconify)
SETCB(glfwSetWindowMaximizeCallback, GLFWwindowmaximizefun, maximize)
SETCB(glfwSetFramebufferSizeCallback, GLFWframebuffersizefun, fbsize)
SETCB(glfwSetWindowContentScaleCallback, GLFWwindowcontentscalefun, scale)
SETCB(glfwSetKeyCallback, GLFWkeyfun, key)
SETCB(glfwSetCharCallback, GLFWcharfun, chr)
SETCB(glfwSetCharModsCallback, GLFWcharmodsfun, charmods)
SETCB(glfwSetMouseButtonCallback, GLFWmousebuttonfun, mouse)
SETCB(glfwSetCursorPosCallback, GLFWcursorposfun, cursor)
SETCB(glfwSetCursorEnterCallback, GLFWcursorenterfun, enter)
SETCB(glfwSetScrollCallback, GLFWscrollfun, scroll)
SETCB(glfwSetDropCallback, GLFWdropfun, drop)

// ---------------------------------------------------------------- events

static void dispatch(Event *e) {
    GLFWwindow *w = &g.win;
    switch (e->type) {
    case EV_KEY:
        if (e->i1 >= 0 && e->i1 <= GLFW_KEY_LAST) w->keys[e->i1] = e->i3 != GLFW_RELEASE;
        if (w->key) w->key(w, e->i1, e->i2, e->i3, e->i4);
        break;
    case EV_CHAR:
        if (w->chr) w->chr(w, (unsigned)e->i1);
        if (w->charmods) w->charmods(w, (unsigned)e->i1, e->i2);
        break;
    case EV_MOUSE:
        if (e->i1 >= 0 && e->i1 <= GLFW_MOUSE_BUTTON_LAST) w->buttons[e->i1] = e->i2 != GLFW_RELEASE;
        if (w->mouse) w->mouse(w, e->i1, e->i2, e->i3);
        break;
    case EV_CURSOR:
        if (e->i1) { // relative (touch look / captured mouse)
            w->cx += e->d1;
            w->cy += e->d2;
        } else {
            w->cx = e->d1;
            w->cy = e->d2;
        }
        if (w->cursorMode != GLFW_CURSOR_DISABLED) { // a visible cursor stays inside the window
            if (w->cx < 0) w->cx = 0;
            if (w->cy < 0) w->cy = 0;
            if (w->width > 0 && w->cx > w->width - 1) w->cx = w->width - 1;
            if (w->height > 0 && w->cy > w->height - 1) w->cy = w->height - 1;
        }
        if (w->cursor) w->cursor(w, w->cx, w->cy);
        break;
    case EV_SCROLL:
        if (w->scroll) w->scroll(w, e->d1, e->d2);
        break;
    case EV_FOCUS:
        w->focused = e->i1;
        if (w->focus) w->focus(w, e->i1);
        break;
    case EV_RESIZE:
        if (w->size) w->size(w, e->i1, e->i2);
        if (w->fbsize) w->fbsize(w, e->i1, e->i2);
        break;
    case EV_CLOSE:
        w->shouldClose = 1;
        if (w->close) w->close(w);
        break;
    }
}

API void glfwPollEvents(void) {
    if (g.created && current) sync_surface(false); // only the context's thread may switch surfaces
    for (;;) {
        pthread_mutex_lock(&g.qlock);
        if (g.qhead == g.qtail) {
            pthread_mutex_unlock(&g.qlock);
            break;
        }
        Event e = g.queue[g.qhead];
        g.qhead = (g.qhead + 1) % QUEUE_SIZE;
        pthread_mutex_unlock(&g.qlock);
        dispatch(&e);
    }
}

API void glfwWaitEvents(void) {
    usleep(5000);
    glfwPollEvents();
}
API void glfwWaitEventsTimeout(double t) {
    if (t > 0) usleep((useconds_t)(t > 0.005 ? 5000 : t * 1e6));
    glfwPollEvents();
}
API void glfwPostEmptyEvent(void) {}

// ---------------------------------------------------------------- input

API int glfwGetInputMode(GLFWwindow *w, int mode) {
    if (!w) return 0;
    switch (mode) {
    case GLFW_CURSOR: return w->cursorMode;
    case GLFW_STICKY_KEYS: return w->stickyKeys;
    case GLFW_STICKY_MOUSE_BUTTONS: return w->stickyMouse;
    case GLFW_LOCK_KEY_MODS: return w->lockMods;
    case GLFW_RAW_MOUSE_MOTION: return w->rawMotion;
    }
    return 0;
}

API void glfwSetInputMode(GLFWwindow *w, int mode, int value) {
    if (!w) return;
    switch (mode) {
    case GLFW_CURSOR:
        if (value != w->cursorMode && value == GLFW_CURSOR_DISABLED) {
            // grabbing re-centres the cursor like desktop GLFW
            w->cx = g.win.width / 2.0;
            w->cy = g.win.height / 2.0;
        }
        w->cursorMode = value;
        break;
    case GLFW_STICKY_KEYS: w->stickyKeys = value; break;
    case GLFW_STICKY_MOUSE_BUTTONS: w->stickyMouse = value; break;
    case GLFW_LOCK_KEY_MODS: w->lockMods = value; break;
    case GLFW_RAW_MOUSE_MOTION: w->rawMotion = value; break;
    }
}

API int glfwRawMouseMotionSupported(void) { return GLFW_TRUE; }

API const char *glfwGetKeyName(int key, int scancode) {
    (void)scancode;
    static char buf[2];
    static const char *kp[] = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "/", "*", "-", "+"};
    if (key >= 65 && key <= 90) { // A-Z
        buf[0] = (char)(key + 32);
        buf[1] = 0;
        return buf;
    }
    if ((key >= 48 && key <= 57) || key == 39 || (key >= 44 && key <= 47) || key == 59 || key == 61 ||
        (key >= 91 && key <= 93) || key == 96) {
        buf[0] = (char)key;
        buf[1] = 0;
        return buf;
    }
    if (key >= 320 && key <= 334) return kp[key - 320];
    if (key == 336) return "=";
    return NULL;
}

API int glfwGetKeyScancode(int key) { return key; }

API int glfwGetKey(GLFWwindow *w, int key) {
    if (!w || key < 0 || key > GLFW_KEY_LAST) return GLFW_RELEASE;
    return w->keys[key] ? GLFW_PRESS : GLFW_RELEASE;
}

API int glfwGetMouseButton(GLFWwindow *w, int b) {
    if (!w || b < 0 || b > GLFW_MOUSE_BUTTON_LAST) return GLFW_RELEASE;
    return w->buttons[b] ? GLFW_PRESS : GLFW_RELEASE;
}

API void glfwGetCursorPos(GLFWwindow *w, double *x, double *y) {
    if (x) *x = w ? w->cx : 0;
    if (y) *y = w ? w->cy : 0;
}

API void glfwSetCursorPos(GLFWwindow *w, double x, double y) {
    if (!w) return;
    w->cx = x;
    w->cy = y;
}

API GLFWcursor *glfwCreateCursor(const GLFWimage *im, int x, int y) {
    (void)im; (void)x; (void)y;
    return (GLFWcursor *)&cursorDummy;
}
API GLFWcursor *glfwCreateStandardCursor(int shape) {
    (void)shape;
    return (GLFWcursor *)&cursorDummy;
}
API void glfwDestroyCursor(GLFWcursor *c) { (void)c; }
API void glfwSetCursor(GLFWwindow *w, GLFWcursor *c) { (void)w; (void)c; }

// joysticks: physical gamepads are mapped to keys/mouse by the app instead
API int glfwJoystickPresent(int j) { (void)j; return 0; }
API const float *glfwGetJoystickAxes(int j, int *n) { (void)j; if (n) *n = 0; return NULL; }
API const unsigned char *glfwGetJoystickButtons(int j, int *n) { (void)j; if (n) *n = 0; return NULL; }
API const unsigned char *glfwGetJoystickHats(int j, int *n) { (void)j; if (n) *n = 0; return NULL; }
API const char *glfwGetJoystickName(int j) { (void)j; return NULL; }
API const char *glfwGetJoystickGUID(int j) { (void)j; return NULL; }
API void glfwSetJoystickUserPointer(int j, void *p) { (void)j; (void)p; }
API void *glfwGetJoystickUserPointer(int j) { (void)j; return NULL; }
API int glfwJoystickIsGamepad(int j) { (void)j; return 0; }
API GLFWjoystickfun glfwSetJoystickCallback(GLFWjoystickfun cb) {
    GLFWjoystickfun old = joystickCb;
    joystickCb = cb;
    return old;
}
API int glfwUpdateGamepadMappings(const char *s) { (void)s; return GLFW_TRUE; }
API const char *glfwGetGamepadName(int j) { (void)j; return NULL; }
API int glfwGetGamepadState(int j, GLFWgamepadstate *s) { (void)j; (void)s; return GLFW_FALSE; }

// ---------------------------------------------------------------- clipboard (via the app)

static JNIEnv *art_env(bool *attached) {
    *attached = false;
    if (!artVM) return NULL;
    JNIEnv *env = NULL;
    if ((*artVM)->GetEnv(artVM, (void **)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    if ((*artVM)->AttachCurrentThread(artVM, &env, NULL) != JNI_OK) return NULL;
    *attached = true;
    return env;
}

extern jclass bridgeClass;

API void glfwSetClipboardString(GLFWwindow *w, const char *s) {
    (void)w;
    bool att;
    JNIEnv *env = art_env(&att);
    if (!env || !bridgeClass || !s) return;
    jmethodID m = (*env)->GetStaticMethodID(env, bridgeClass, "setClipboard", "(Ljava/lang/String;)V");
    jstring js = (*env)->NewStringUTF(env, s);
    if (m) (*env)->CallStaticVoidMethod(env, bridgeClass, m, js);
    (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, js);
    if (att) (*artVM)->DetachCurrentThread(artVM);
}

API const char *glfwGetClipboardString(GLFWwindow *w) {
    (void)w;
    bool att;
    JNIEnv *env = art_env(&att);
    if (!env || !bridgeClass) return NULL;
    jmethodID m = (*env)->GetStaticMethodID(env, bridgeClass, "getClipboard", "()Ljava/lang/String;");
    jstring js = m ? (jstring)(*env)->CallStaticObjectMethod(env, bridgeClass, m) : NULL;
    (*env)->ExceptionClear(env);
    free(clipboard);
    clipboard = NULL;
    if (js) {
        const char *c = (*env)->GetStringUTFChars(env, js, NULL);
        clipboard = strdup(c ? c : "");
        (*env)->ReleaseStringUTFChars(env, js, c);
        (*env)->DeleteLocalRef(env, js);
    }
    if (att) (*artVM)->DetachCurrentThread(artVM);
    return clipboard;
}

// ---------------------------------------------------------------- time

API double glfwGetTime(void) { return now_ns() / 1e9 - timeBase; }
API void glfwSetTime(double t) { timeBase = now_ns() / 1e9 - t; }
API uint64_t glfwGetTimerValue(void) { return (uint64_t)now_ns(); }
API uint64_t glfwGetTimerFrequency(void) { return 1000000000ULL; }

// ---------------------------------------------------------------- context

API void glfwMakeContextCurrent(GLFWwindow *w) {
    current = w;
    if (!w) {
        if (g.dpy != EGL_NO_DISPLAY) eglMakeCurrent(g.dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        return;
    }
    sync_surface(true);
    EGLSurface s = g.surf != EGL_NO_SURFACE ? g.surf : g.pbuffer;
    if (!eglMakeCurrent(g.dpy, s, s, g.ctx)) LOGE("eglMakeCurrent: 0x%x", eglGetError());
    atomic_store(&g.renderTid, gettid());
    perf_reset();
    if (atomic_load(&g.bigCores)) perf_pin_big_cores();
}

API GLFWwindow *glfwGetCurrentContext(void) { return current; }

API void glfwSwapBuffers(GLFWwindow *w) {
    (void)w;
    if (!sync_surface(true)) return;
    int v = atomic_load(&g.vsync);
    static int lastV = -2;
    if (v != lastV) {
        eglSwapInterval(g.dpy, v > 0 ? 1 : 0);
        lastV = v;
    }
    if (!eglSwapBuffers(g.dpy, g.surf)) {
        EGLint err = eglGetError();
        if (err == EGL_BAD_SURFACE || err == EGL_BAD_NATIVE_WINDOW) {
            pthread_mutex_lock(&g.lock);
            g.surfaceLost = g.pending == NULL;
            pthread_mutex_unlock(&g.lock);
        }
    }
    perf_on_swap();
}

API void glfwSwapInterval(int interval) {
    // the in-game setting only applies when the app leaves vsync to the game
    if (atomic_load(&g.vsync) < 0 && g.dpy != EGL_NO_DISPLAY) eglSwapInterval(g.dpy, interval);
}

API int glfwExtensionSupported(const char *ext) { (void)ext; return GLFW_FALSE; }

static void *rendererLib;

API void *glfwGetProcAddress(const char *name) {
    if (!rendererLib) {
        const char *p = getenv("EL_RENDERER_LIB");
        if (p) rendererLib = dlopen(p, RTLD_NOW | RTLD_GLOBAL);
    }
    void *f = rendererLib ? dlsym(rendererLib, name) : NULL;
    return f ? f : (void *)eglGetProcAddress(name);
}

// ---------------------------------------------------------------- Vulkan: not offered

API int glfwVulkanSupported(void) { return GLFW_FALSE; }
API const char **glfwGetRequiredInstanceExtensions(uint32_t *count) {
    if (count) *count = 0;
    return NULL;
}
API void *glfwGetInstanceProcAddress(void *instance, const char *name) { (void)instance; (void)name; return NULL; }
API int glfwGetPhysicalDevicePresentationSupport(void *i, void *d, uint32_t q) { (void)i; (void)d; (void)q; return GLFW_FALSE; }
API int glfwCreateWindowSurface(void *i, GLFWwindow *w, const void *a, void *s) {
    (void)i; (void)w; (void)a; (void)s;
    return -7; // VK_ERROR_EXTENSION_NOT_PRESENT
}

// ---------------------------------------------------------------- native access

API EGLDisplay glfwGetEGLDisplay(void) { return g.dpy; }
API EGLContext glfwGetEGLContext(GLFWwindow *w) { (void)w; return g.ctx; }
API EGLSurface glfwGetEGLSurface(GLFWwindow *w) { (void)w; return g.surf; }
API void *glfwGetX11Display(void) { return NULL; }
API unsigned long glfwGetX11Window(GLFWwindow *w) { (void)w; return 0; }
API void *glfwGetGLXContext(GLFWwindow *w) { (void)w; return NULL; }
API unsigned long glfwGetGLXWindow(GLFWwindow *w) { (void)w; return 0; }
