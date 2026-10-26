package cn.eririlily.launcher.game

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** JNI side of libelbridge (see cpp/jni_bridge.c and cpp/jvm.c). */
object Bridge {
    init {
        System.loadLibrary("elbridge")
    }

    @JvmStatic external fun setSurface(surface: Surface, width: Int, height: Int)
    @JvmStatic external fun surfaceDestroyed()
    @JvmStatic external fun setDisplay(width: Int, height: Int, refresh: Int)
    @JvmStatic external fun sendKey(key: Int, scancode: Int, action: Int, mods: Int)
    @JvmStatic external fun sendChar(codepoint: Int, mods: Int)
    @JvmStatic external fun sendMouseButton(button: Int, action: Int, mods: Int)
    @JvmStatic external fun sendCursor(x: Float, y: Float)
    @JvmStatic external fun sendCursorDelta(dx: Float, dy: Float)
    @JvmStatic external fun sendScroll(dx: Float, dy: Float)
    @JvmStatic external fun sendFocus(focused: Boolean)
    @JvmStatic external fun requestClose()
    @JvmStatic external fun isGrabbing(): Boolean
    @JvmStatic external fun isWindowReady(): Boolean
    @JvmStatic external fun getCursor(out: FloatArray)
    @JvmStatic external fun getFps(): Float
    @JvmStatic external fun getFrameUs(): Int
    @JvmStatic external fun getFrames(): Long
    @JvmStatic external fun getFrameTimes(out: FloatArray)
    @JvmStatic external fun setFpsLimit(fps: Int)
    @JvmStatic external fun setVsync(mode: Int)
    @JvmStatic external fun setPerf(bigCores: Boolean, perfHint: Boolean)
    @JvmStatic external fun getRenderTid(): Int

    @JvmStatic external fun launchJvm(
        libjvm: String, jvmArgs: Array<String>, classpath: String, libraryPath: String, mainClass: String,
        gameArgs: Array<String>, env: Array<String>, cwd: String, logFile: String, exitFile: String, pidFile: String,
    ): Int

    const val PRESS = 1
    const val RELEASE = 0

    fun key(code: Int, down: Boolean, mods: Int = 0) = sendKey(code, 0, if (down) PRESS else RELEASE, mods)
    fun tapKey(code: Int) { key(code, true); key(code, false) }
    fun mouse(button: Int, down: Boolean) = sendMouseButton(button, if (down) PRESS else RELEASE, 0)

    // ---------------------------------------------------------------- called from native code

    lateinit var context: Context
    private val main = Handler(Looper.getMainLooper())

    @JvmStatic
    fun getClipboard(): String? {
        var out: String? = null
        val done = CountDownLatch(1)
        main.post {
            out = runCatching {
                context.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            }.getOrNull()
            done.countDown()
        }
        done.await(500, TimeUnit.MILLISECONDS)
        return out
    }

    @JvmStatic
    fun setClipboard(text: String) {
        main.post {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Minecraft", text))
        }
    }
}
