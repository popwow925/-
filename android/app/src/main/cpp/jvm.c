// Starts the game's JVM (an Android build of OpenJDK) inside the game
// process, the way the desktop `java` launcher does: options in, main class
// run on a fresh thread with a large stack, exit code written out for the
// launcher core to read.
#include "bridge.h"

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

typedef jint (*CreateJavaVM_t)(JavaVM **, void **, void *);

static char *exitFile;
static int logFd = -1;
static volatile int exitWritten;

static void write_exit(int code) {
    if (exitWritten || !exitFile) return;
    exitWritten = 1;
    fflush(stdout);
    fflush(stderr);
    int fd = open(exitFile, O_CREAT | O_WRONLY | O_TRUNC, 0644);
    if (fd >= 0) {
        char b[16];
        int n = snprintf(b, sizeof b, "%d\n", code);
        write(fd, b, (size_t)n);
        fsync(fd);
        close(fd);
    }
    usleep(150000); // let the log thread drain the last lines
    if (logFd >= 0) fsync(logFd);
}

static void JNICALL exit_hook(jint code) {
    LOGI("game exit %d", code);
    write_exit(code);
    _exit(code);
}

static void JNICALL abort_hook(void) {
    LOGE("game JVM aborted");
    write_exit(134);
}

static void at_exit(void) { write_exit(0); }

// ---------------------------------------------------------------- output

static void *log_thread(void *arg) {
    int fd = (int)(intptr_t)arg;
    char buf[8192], line[4096];
    size_t ll = 0;
    ssize_t n;
    while ((n = read(fd, buf, sizeof buf)) > 0) {
        if (logFd >= 0) write(logFd, buf, (size_t)n);
        for (ssize_t i = 0; i < n; i++) {
            if (buf[i] == '\n' || ll == sizeof line - 1) {
                line[ll] = 0;
                __android_log_write(ANDROID_LOG_INFO, "EririLily-Game", line);
                ll = 0;
            } else {
                line[ll++] = buf[i];
            }
        }
    }
    return NULL;
}

static void redirect_output(const char *path) {
    logFd = open(path, O_CREAT | O_WRONLY | O_APPEND, 0644);
    int p[2];
    if (pipe(p) != 0) return;
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    dup2(p[1], 1);
    dup2(p[1], 2);
    close(p[1]);
    pthread_t t;
    pthread_create(&t, NULL, log_thread, (void *)(intptr_t)p[0]);
    pthread_detach(t);
}

// ---------------------------------------------------------------- launch

typedef struct {
    char *libjvm;
    char **opts;
    int nopts;
    char *mainClass;
    char **args;
    int nargs;
} Launch;

static int is_two_token(const char *a) {
    static const char *two[] = {"--add-opens", "--add-exports", "--add-reads", "--add-modules", "--module-path",
                                "--patch-module", "--limit-modules", "--upgrade-module-path",
                                "--enable-native-access", "-p", NULL};
    for (int i = 0; two[i]; i++)
        if (strcmp(a, two[i]) == 0) return 1;
    return 0;
}

static void *jvm_main(void *arg) {
    Launch *L = arg;
    void *h = dlopen(L->libjvm, RTLD_NOW | RTLD_GLOBAL);
    if (!h) {
        fprintf(stderr, "[EririLily] 无法加载 Java 虚拟机：%s\n", dlerror());
        write_exit(-2);
        _exit(1);
    }
    CreateJavaVM_t create = (CreateJavaVM_t)dlsym(h, "JNI_CreateJavaVM");
    if (!create) {
        fprintf(stderr, "[EririLily] libjvm.so 缺少 JNI_CreateJavaVM\n");
        write_exit(-2);
        _exit(1);
    }
    JavaVMOption *o = calloc((size_t)L->nopts + 4, sizeof(JavaVMOption));
    int n = 0;
    for (int i = 0; i < L->nopts; i++) o[n++].optionString = L->opts[i];
    o[n].optionString = "exit";
    o[n++].extraInfo = (void *)exit_hook;
    o[n].optionString = "abort";
    o[n++].extraInfo = (void *)abort_hook;
    JavaVMInitArgs vmArgs = {.version = JNI_VERSION_1_8, .nOptions = n, .options = o, .ignoreUnrecognized = JNI_TRUE};
    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    jint r = create(&vm, (void **)&env, &vmArgs);
    if (r != JNI_OK) {
        fprintf(stderr, "[EririLily] Java 虚拟机启动失败（%d），可能是内存设置过大\n", r);
        write_exit(-3);
        _exit(1);
    }
    char *cls = strdup(L->mainClass);
    for (char *p = cls; *p; p++)
        if (*p == '.') *p = '/';
    int code = 0;
    jclass mainCls = (*env)->FindClass(env, cls);
    jmethodID mainM = mainCls ? (*env)->GetStaticMethodID(env, mainCls, "main", "([Ljava/lang/String;)V") : NULL;
    if (!mainM) {
        (*env)->ExceptionDescribe(env);
        fprintf(stderr, "[EririLily] 找不到主类 %s\n", L->mainClass);
        code = 1;
    } else {
        jclass strCls = (*env)->FindClass(env, "java/lang/String");
        jobjectArray jargs = (*env)->NewObjectArray(env, L->nargs, strCls, NULL);
        for (int i = 0; i < L->nargs; i++) {
            jstring s = (*env)->NewStringUTF(env, L->args[i]);
            (*env)->SetObjectArrayElement(env, jargs, i, s);
            (*env)->DeleteLocalRef(env, s);
        }
        (*env)->CallStaticVoidMethod(env, mainCls, mainM, jargs);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            code = 1;
        }
    }
    (*vm)->DetachCurrentThread(vm);
    (*vm)->DestroyJavaVM(vm); // waits for the game's other threads, like `java` does
    write_exit(code);
    _exit(code);
    return NULL;
}

static char *dup_jstring(JNIEnv *env, jstring s) {
    if (!s) return strdup("");
    const char *c = (*env)->GetStringUTFChars(env, s, NULL);
    char *r = strdup(c ? c : "");
    (*env)->ReleaseStringUTFChars(env, s, c);
    return r;
}

static char **dup_array(JNIEnv *env, jobjectArray a, int *n) {
    *n = a ? (*env)->GetArrayLength(env, a) : 0;
    char **out = calloc((size_t)*n + 1, sizeof(char *));
    for (int i = 0; i < *n; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, a, i);
        out[i] = dup_jstring(env, s);
        (*env)->DeleteLocalRef(env, s);
    }
    return out;
}

static char *concat(const char *a, const char *b) {
    size_t la = strlen(a), lb = strlen(b);
    char *r = malloc(la + lb + 1);
    memcpy(r, a, la);
    memcpy(r + la, b, lb + 1);
    return r;
}

JNIEXPORT jint JNICALL Java_cn_eririlily_launcher_game_Bridge_launchJvm(
    JNIEnv *env, jclass c, jstring jlibjvm, jobjectArray jopts, jstring jcp, jstring jlibpath, jstring jmain,
    jobjectArray jgameArgs, jobjectArray jenv, jstring jcwd, jstring jlog, jstring jexit, jstring jpid) {
    (void)c;
    int nenv;
    char **envs = dup_array(env, jenv, &nenv);
    for (int i = 0; i < nenv; i++) {
        char *eq = strchr(envs[i], '=');
        if (eq) {
            *eq = 0;
            setenv(envs[i], eq + 1, 1);
        }
    }
    char *cwd = dup_jstring(env, jcwd);
    mkdir(cwd, 0755);
    if (chdir(cwd) != 0) LOGW("chdir %s: %s", cwd, strerror(errno));

    char *logPath = dup_jstring(env, jlog);
    redirect_output(logPath);
    exitFile = dup_jstring(env, jexit);
    atexit(at_exit);

    char *pidPath = dup_jstring(env, jpid);
    FILE *pf = fopen(pidPath, "w");
    if (pf) {
        fprintf(pf, "%d\n", getpid());
        fclose(pf);
    }

    Launch *L = calloc(1, sizeof(Launch));
    L->libjvm = dup_jstring(env, jlibjvm);
    L->mainClass = dup_jstring(env, jmain);
    L->args = dup_array(env, jgameArgs, &L->nargs);
    int nraw;
    char **raw = dup_array(env, jopts, &nraw);
    L->opts = calloc((size_t)nraw + 8, sizeof(char *));
    for (int i = 0; i < nraw; i++) {
        char *a = raw[i];
        if (!strcmp(a, "-cp") || !strcmp(a, "-classpath") || !strcmp(a, "--class-path")) {
            i++; // the class path is passed separately
            continue;
        }
        if (!strncmp(a, "-Djava.class.path=", 18) || !strncmp(a, "-Djava.library.path=", 20) ||
            !strcmp(a, "-XstartOnFirstThread") || !strcmp(a, "-server") || !strcmp(a, "-client"))
            continue;
        if (is_two_token(a) && i + 1 < nraw) {
            // the java launcher rewrites "--opt value" to "--opt=value" for the VM
            char *name = !strcmp(a, "-p") ? "--module-path" : a;
            char *tmp = concat(name, "=");
            L->opts[L->nopts++] = concat(tmp, raw[++i]);
            free(tmp);
            continue;
        }
        L->opts[L->nopts++] = a;
    }
    char *cp = dup_jstring(env, jcp);
    char *lp = dup_jstring(env, jlibpath);
    L->opts[L->nopts++] = concat("-Djava.class.path=", cp);
    L->opts[L->nopts++] = concat("-Djava.library.path=", lp);
    L->opts[L->nopts++] = "-Dsun.java.launcher=SUN_STANDARD";
    printf("[EririLily] 启动 %s，%d 个 JVM 参数\n", L->mainClass, L->nopts);

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 16 * 1024 * 1024);
    pthread_t t;
    int r = pthread_create(&t, &attr, jvm_main, L);
    pthread_attr_destroy(&attr);
    if (r != 0) {
        write_exit(-4);
        return -1;
    }
    pthread_detach(t);
    return 0;
}
