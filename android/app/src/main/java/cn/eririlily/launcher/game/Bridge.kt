package cn.eririlily.launcher.game

import android.content.Context
import android.view.Surface
import com.movtery.zalithlauncher.bridge.ZLBridge
import org.lwjgl.glfw.CallbackBridge

/**
 * The game's window and input, on the runtime module (Zalith Launcher 2's native layer:
 * CallbackBridge / ZLBridge). Keeps the API the controls, keyboard, HUD and control centre use.
 */
object Bridge {
    const val PRESS = 1
    const val RELEASE = 0
    private const val GLFW_FOCUSED = 0x20001

    lateinit var context: Context

    @Volatile private var attached: Surface? = null
    @Volatile private var ready = false
    @Volatile private var frames = 0L

    init {
        CallbackBridge.setGraphicOutputListener { ready = true; frames++ }
    }

    // ---------------------------------------------------------------- window

    fun setSurface(surface: Surface, width: Int, height: Int) {
        if (attached !== surface) {
            ZLBridge.setupBridgeWindow(surface)
            attached = surface
        }
        CallbackBridge.physicalWidth = width
        CallbackBridge.physicalHeight = height
        CallbackBridge.sendUpdateWindowSize(width, height)
    }

    fun surfaceDestroyed() {
        if (attached != null) ZLBridge.releaseBridgeWindow()
        attached = null
    }

    fun setDisplay(width: Int, height: Int, refresh: Int) {}

    fun isWindowReady(): Boolean = ready || CallbackBridge.getCurrentFps() > 0

    // ---------------------------------------------------------------- input

    fun sendKey(key: Int, scancode: Int, action: Int, mods: Int) =
        CallbackBridge.sendKeycode(key, 0.toChar(), scancode, mods, action != RELEASE)

    fun sendChar(codepoint: Int, mods: Int) {
        for (c in Character.toChars(codepoint)) CallbackBridge.sendChar(c, mods)
    }

    fun sendMouseButton(button: Int, action: Int, mods: Int) =
        CallbackBridge.sendMouseKeycode(button, mods, action == PRESS)

    fun sendCursor(x: Float, y: Float) = CallbackBridge.sendCursorPos(x, y)
    fun sendCursorDelta(dx: Float, dy: Float) = CallbackBridge.sendCursorDelta(dx, dy)
    fun sendScroll(dx: Float, dy: Float) = CallbackBridge.sendScroll(dx.toDouble(), dy.toDouble())
    fun sendFocus(focused: Boolean) = CallbackBridge.nativeSetWindowAttrib(GLFW_FOCUSED, if (focused) 1 else 0)
    fun isGrabbing(): Boolean = CallbackBridge.isGrabbing()

    fun getCursor(out: FloatArray) {
        out[0] = CallbackBridge.mouseX
        out[1] = CallbackBridge.mouseY
    }

    /** Ask the game to quit: the same as closing its window. */
    fun requestClose() {
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    fun key(code: Int, down: Boolean, mods: Int = 0) = sendKey(code, 0, if (down) PRESS else RELEASE, mods)
    fun tapKey(code: Int) { key(code, true); key(code, false) }
    fun mouse(button: Int, down: Boolean) = sendMouseButton(button, if (down) PRESS else RELEASE, 0)

    // ---------------------------------------------------------------- frame stats

    fun getFps(): Float = CallbackBridge.getCurrentFps().toFloat()
    fun getFrameUs(): Int = getFps().let { if (it > 0) (1_000_000 / it).toInt() else 0 }
    fun getFrames(): Long = frames
    fun getFrameTimes(out: FloatArray) {
        val ms = getFrameUs() / 1000f
        out.fill(ms)
    }

    // Zalith's runtime paces frames itself; these stay for the control centre and do nothing yet.
    fun setFpsLimit(fps: Int) {}
    fun setVsync(mode: Int) {}
    fun setPerf(bigCores: Boolean, perfHint: Boolean) {}
    fun getRenderTid(): Int = -1
}
