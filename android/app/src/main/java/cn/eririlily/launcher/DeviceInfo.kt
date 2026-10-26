package cn.eririlily.launcher

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import org.json.JSONObject
import java.io.File

/** What the core and the optimiser need to know about the phone. */
object DeviceInfo {
    @Volatile private var cached: JSONObject? = null

    fun json(ctx: Context): JSONObject {
        cached?.let { return it }
        val o = JSONObject()
        val am = ctx.getSystemService(ActivityManager::class.java)!!
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        o.put("model", Build.MODEL)
        o.put("brand", Build.MANUFACTURER)
        o.put("soc", if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE)
        o.put("sdk", Build.VERSION.SDK_INT)
        o.put("release", Build.VERSION.RELEASE)
        val freqs = cpuMaxFreqs()
        o.put("cores", Runtime.getRuntime().availableProcessors())
        val min = freqs.filter { it > 0 }.minOrNull() ?: 0
        o.put("bigCores", freqs.count { it > min })
        o.put("maxFreqMHz", (freqs.maxOrNull() ?: 0) / 1000)
        o.put("ramMB", mi.totalMem / (1024 * 1024))
        val (w, h, refresh) = display(ctx)
        o.put("screenW", maxOf(w, h))
        o.put("screenH", minOf(w, h))
        o.put("refresh", refresh.toDouble())
        val (renderer, version) = glInfo(ctx)
        o.put("glRenderer", renderer)
        o.put("glesVersion", version)
        o.put("vulkan", ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL))
        o.put("perfHint", Build.VERSION.SDK_INT >= 33)
        val pm = ctx.getSystemService(PowerManager::class.java)!!
        o.put("sustained", pm.isSustainedPerformanceModeSupported)
        if (Build.VERSION.SDK_INT >= 29) o.put("thermal", pm.currentThermalStatus)
        cached = o
        return o
    }

    fun cpuMaxFreqs(): List<Long> {
        val n = Runtime.getRuntime().availableProcessors()
        return (0 until maxOf(n, 8)).mapNotNull { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
        }
    }

    /** Physical size in pixels and the highest refresh rate the display offers. */
    fun display(ctx: Context): Triple<Int, Int, Float> {
        val wm = ctx.getSystemService(WindowManager::class.java)!!
        @Suppress("DEPRECATION")
        val d = wm.defaultDisplay
        var w = 0
        var h = 0
        var r = d.refreshRate
        for (m in d.supportedModes) {
            if (m.physicalWidth * m.physicalHeight >= w * h) {
                w = m.physicalWidth; h = m.physicalHeight
            }
            if (m.refreshRate > r) r = m.refreshRate
        }
        return Triple(w, h, r)
    }

    /** GL_RENDERER / GL_VERSION from a throw-away 1x1 context (cached across runs). */
    private fun glInfo(ctx: Context): Pair<String, String> {
        val prefs = ctx.getSharedPreferences("device", Context.MODE_PRIVATE)
        val key = "gl-" + Build.FINGERPRINT
        prefs.getString(key, null)?.let { val p = it.split('\n'); if (p.size == 2) return p[0] to p[1] }
        var out = "" to ""
        runCatching {
            val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            EGL14.eglInitialize(dpy, IntArray(1), 0, IntArray(1), 0)
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            EGL14.eglChooseConfig(dpy, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, cfgs, 0, 1, n, 0)
            val c = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            val s = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(dpy, s, s, c)
            out = (GLES20.glGetString(GLES20.GL_RENDERER) ?: "") to (GLES20.glGetString(GLES20.GL_VERSION) ?: "")
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, s)
            EGL14.eglDestroyContext(dpy, c)
        }
        if (out.first.isNotEmpty()) prefs.edit().putString(key, out.first + "\n" + out.second).apply()
        return out
    }
}
