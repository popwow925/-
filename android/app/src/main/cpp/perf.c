// Frame statistics, the frame limiter, big-core pinning and Android's
// performance hints (ADPF) for the game's render thread.
#define _GNU_SOURCE
#include "bridge.h"

#include <dlfcn.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/resource.h>
#include <time.h>
#include <unistd.h>

static int64_t lastSwap, windowStart, workStart, nextDeadline;
static int windowFrames;

// ---------------------------------------------------------------- ADPF (API 33+, looked up at run time)
typedef struct APerformanceHintManager APerformanceHintManager;
typedef struct APerformanceHintSession APerformanceHintSession;
static APerformanceHintManager *(*pGetManager)(void);
static APerformanceHintSession *(*pCreateSession)(APerformanceHintManager *, const int32_t *, size_t, int64_t);
static int (*pReport)(APerformanceHintSession *, int64_t);
static int (*pUpdateTarget)(APerformanceHintSession *, int64_t);
static APerformanceHintSession *session;
static int64_t sessionTarget;
static bool hintTried;

static void hint_setup(int64_t target) {
    if (!hintTried) {
        hintTried = true;
        void *lib = dlopen("libandroid.so", RTLD_NOW);
        if (lib) {
            pGetManager = dlsym(lib, "APerformanceHint_getManager");
            pCreateSession = dlsym(lib, "APerformanceHint_createSession");
            pReport = dlsym(lib, "APerformanceHint_reportActualWorkDuration");
            pUpdateTarget = dlsym(lib, "APerformanceHint_updateTargetWorkDuration");
        }
        if (pGetManager && pCreateSession) {
            APerformanceHintManager *m = pGetManager();
            int32_t tid = gettid();
            if (m) session = pCreateSession(m, &tid, 1, target);
            sessionTarget = target;
            LOGI("ADPF session %s", session ? "on" : "unavailable");
        }
    }
    if (session && pUpdateTarget && target != sessionTarget) {
        pUpdateTarget(session, target);
        sessionTarget = target;
    }
}

// ---------------------------------------------------------------- big cores

void perf_pin_big_cores(void) {
    int n = (int)sysconf(_SC_NPROCESSORS_CONF);
    if (n <= 0 || n > 64) return;
    long freq[64] = {0}, maxf = 0, minf = 0;
    for (int i = 0; i < n; i++) {
        char p[96];
        snprintf(p, sizeof p, "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        FILE *f = fopen(p, "r");
        if (f) {
            if (fscanf(f, "%ld", &freq[i]) != 1) freq[i] = 0;
            fclose(f);
        }
        if (freq[i] > maxf) maxf = freq[i];
        if (freq[i] > 0 && (minf == 0 || freq[i] < minf)) minf = freq[i];
    }
    if (maxf == 0 || maxf == minf) return; // unknown or no big.LITTLE: leave it to the scheduler
    cpu_set_t set;
    CPU_ZERO(&set);
    int count = 0;
    for (int i = 0; i < n; i++)
        if (freq[i] > minf) { // everything above the little cluster
            CPU_SET(i, &set);
            count++;
        }
    if (count > 0 && sched_setaffinity(0, sizeof set, &set) == 0) LOGI("render thread pinned to %d big cores", count);
    // a little more scheduler priority for the render thread (apps may go down to -10)
    if (setpriority(PRIO_PROCESS, 0, -10) != 0) setpriority(PRIO_PROCESS, 0, -4);
}

// ---------------------------------------------------------------- per frame

void perf_reset(void) {
    lastSwap = windowStart = workStart = now_ns();
    nextDeadline = 0;
    windowFrames = 0;
}

void perf_on_swap(void) {
    int64_t t = now_ns();
    int64_t ft = lastSwap ? t - lastSwap : 0;
    lastSwap = t;
    atomic_fetch_add(&g.frames, 1);
    if (ft > 0) {
        atomic_store(&g.frameUs, (int)(ft / 1000));
        int h = atomic_load(&g.ftHead);
        g.frameTimes[h % 240] = ft / 1e6f;
        atomic_store(&g.ftHead, (h + 1) % 240);
    }
    windowFrames++;
    if (t - windowStart >= 500000000LL) {
        atomic_store(&g.fpsX10, (int)(windowFrames * 1e10 / (t - windowStart)));
        windowStart = t;
        windowFrames = 0;
    }

    int limit = atomic_load(&g.fpsLimit);
    int64_t target = limit > 0 ? 1000000000LL / limit : 1000000000LL / (g.refresh > 0 ? g.refresh : 60);
    if (atomic_load(&g.perfHint)) {
        hint_setup(target);
        if (session && pReport) pReport(session, t - workStart > 0 ? t - workStart : 1);
    }

    // frame limiter: sleep to an absolute schedule so the pace stays even
    if (limit > 0) {
        if (nextDeadline == 0 || t - nextDeadline > target) nextDeadline = t;
        nextDeadline += target;
        int64_t wait = nextDeadline - now_ns();
        if (wait > 200000) {
            struct timespec ts = {.tv_sec = nextDeadline / 1000000000LL, .tv_nsec = nextDeadline % 1000000000LL};
            clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, NULL);
        }
    } else {
        nextDeadline = 0;
    }
    workStart = now_ns();
}
