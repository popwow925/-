package cn.eririlily.launcher.game

import com.movtery.zalithlauncher.bridge.LoggerBridge
import cn.eririlily.runtime.RuntimeEnv
import cn.eririlily.runtime.LaunchSpec
import cn.eririlily.runtime.GameLauncher
import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import cn.eririlily.launcher.SystemBars
import cn.eririlily.launcher.DeviceInfo
import cn.eririlily.launcher.game.Ui.dpi
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Runs Minecraft: a SurfaceView sized by the render scale (the display
 * hardware upscales it for free), the touch controls, the performance HUD and
 * the Control Centre. Lives in its own process (":game") with the game JVM.
 */
class GameActivity : Activity(), SurfaceHolder.Callback, ControlsView.Host, ControlCenter.Actions, SensorEventListener {
    companion object {
        const val EXTRA_SPEC = "spec"
        @Volatile private var jvmStarted = false
        @Volatile private var lastSpec: String? = null // the activity can be recreated while the game keeps running
    }

    private lateinit var spec: GameSpec
    override lateinit var settings: GameSettings
    private lateinit var dataDir: File
    private lateinit var surface: SurfaceView
    private lateinit var controls: ControlsView
    private lateinit var hud: HudView
    private lateinit var cc: ControlCenter
    private lateinit var keyboard: KeyboardInput
    private lateinit var root: FrameLayout
    private var editor: LayoutEditor? = null

    private var viewW = 0
    private var viewH = 0
    private var surfW = 0
    private var surfH = 0
    private var refresh = 60
    private var dynScale = 100
    private var grabbing = false
    private var mouseSeen = 0L
    private var pendingStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Bridge.context = applicationContext
        val path = intent.getStringExtra(EXTRA_SPEC) ?: lastSpec
        if (path != null && path != lastSpec && jvmStarted) {
            // a new launch while an old game still owns this process: restart the process first
            Toast.makeText(this, "上一局游戏仍在运行", Toast.LENGTH_LONG).show()
        }
        lastSpec = lastSpec ?: path
        spec = runCatching { GameSpec.load(lastSpec!!) }.getOrElse {
            Toast.makeText(this, "启动信息无效", Toast.LENGTH_LONG).show(); finish(); return
        }
        dataDir = File(filesDir, "EririLily")
        settings = GameSettings(dataDir, spec.settings)
        dynScale = settings.renderScale

        setupWindow()
        buildViews()

        val (w, h, r) = DeviceInfo.display(this)
        refresh = r.roundToInt().coerceAtLeast(60)
        Bridge.setDisplay(maxOf(w, h), minOf(w, h), refresh)
        applyPerf()

        if (!jvmStarted) {
            jvmStarted = true
            startJvm()
        }
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun setupWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        SystemBars.edgeToEdge(window)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // the display's fastest mode at the current resolution
        @Suppress("DEPRECATION")
        val d = windowManager.defaultDisplay
        val cur = d.mode
        d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate }?.let { m -> window.attributes = window.attributes.apply { preferredDisplayModeId = m.modeId } }
        hideBars()
    }

    private fun hideBars() = SystemBars.hide(window)

    private fun buildViews() {
        root = FrameLayout(this)
        surface = SurfaceView(this)
        surface.holder.addCallback(this)
        surface.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            if (r - l != viewW || b - t != viewH) { viewW = r - l; viewH = b - t; applyScale() }
        }
        root.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val layout = ControlLayout.load(File(dataDir, "controls"), settings.controlsLayout)
        controls = ControlsView(this, layout, this)
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        hud = HudView(this, spec.rendererName)
        hud.mode = settings.perfHud
        hud.fpsLimit = settings.fpsLimit
        root.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            leftMargin = dpi(150f); topMargin = dpi(8f)
        })

        keyboard = KeyboardInput(this)
        root.addView(keyboard, FrameLayout.LayoutParams(1, 1))

        cc = ControlCenter(this, this)
        root.addView(cc, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    /** The JVM starts once the first surface exists (the runtime needs a window to bind to). */
    private fun startJvm() {
        pendingStart = true
    }

    private fun launchOnRuntime(width: Int, height: Int) {
        pendingStart = false
        RuntimeEnv.init(applicationContext)
        RuntimeEnv.activity = this
        RuntimeEnv.onExit = { code, _ -> runCatching { File(spec.exitFile).writeText(code.toString()) } }
        val launchSpec = LaunchSpec.parse(spec.file.readText())
        Thread({
            runCatching { File(spec.pidFile).writeText(Process.myPid().toString()) }
            // the native logger opens the file without creating it
            runCatching { File(spec.logFile).apply { parentFile?.mkdirs(); if (!exists()) createNewFile() } }
            LoggerBridge.start(spec.logFile)
            val code = runCatching {
                val launcher = GameLauncher(applicationContext, launchSpec)
                launcher.prepare { what -> runOnUiThread { Toast.makeText(this, "首次启动：正在准备 $what…", Toast.LENGTH_SHORT).show() } }
                launcher.launch(width, height)
            }.getOrElse { e ->
                LoggerBridge.append("[EririLily] 启动失败：" + e.stackTraceToString())
                -1
            }
            runCatching { File(spec.exitFile).writeText(code.toString()) }
            Process.killProcess(Process.myPid())
        }, "Minecraft").start()
    }

    // ---------------------------------------------------------------- surface & render scale

    private fun applyScale() {
        if (viewW <= 0 || viewH <= 0) return
        val s = (if (settings.dynamicRes) min(dynScale, settings.renderScale) else settings.renderScale).coerceIn(25, 100)
        val w = (viewW * s / 100f).roundToInt() and 1.inv()
        val h = (viewH * s / 100f).roundToInt() and 1.inv()
        surface.holder.setFixedSize(w.coerceAtLeast(2), h.coerceAtLeast(2))
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfW = width; surfH = height
        hud.renderW = width; hud.renderH = height
        Bridge.setSurface(holder.surface, width, height)
        if (pendingStart) launchOnRuntime(width, height)
        applyFrameRate()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Bridge.surfaceDestroyed()
    }

    /** Tell the compositor our pace so a 60 FPS cap on a 120 Hz screen doesn't judder. */
    private fun applyFrameRate() {
        if (Build.VERSION.SDK_INT < 30) return
        val s = surface.holder.surface ?: return
        if (!s.isValid) return
        val fps = settings.fpsLimit.toFloat()
        runCatching {
            if (fps > 0) s.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            else s.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
    }

    override fun gameScale(): Float = if (viewW > 0 && surfW > 0) surfW.toFloat() / viewW else settings.renderScale / 100f
    override fun guiScale(): Int = settings.guiScale

    // ---------------------------------------------------------------- frame loop

    private var lastHud = 0L
    private var lastDyn = 0L
    private var slow = 0
    private var fast = 0
    private var padX = 0f
    private var padY = 0f
    private var lastFrameNs = 0L

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            val dt = if (lastFrameNs == 0L) 0f else (now - lastFrameNs) / 1e9f
            lastFrameNs = now
            val g = Bridge.isGrabbing()
            if (g != grabbing) {
                grabbing = g
                controls.grabbing = g
                if (g) {
                    if (SystemClock.uptimeMillis() - mouseSeen < 60000) surface.requestPointerCapture()
                } else {
                    surface.releasePointerCapture()
                    // the game re-centres its cursor on release: follow it
                    val c = FloatArray(2)
                    Bridge.getCursor(c)
                    val k = gameScale()
                    if (k > 0) controls.moveCursor(c[0] / k, c[1] / k)
                }
            }
            // gamepad right stick
            if (abs(padX) > .12f || abs(padY) > .12f) {
                val speed = 900f * dt * settings.mouseSpeed / 100f
                if (grabbing) Bridge.sendCursorDelta(padX * speed, padY * speed)
                else controls.moveCursor((controls.cursorX + padX * speed).coerceIn(0f, viewW.toFloat()), (controls.cursorY + padY * speed).coerceIn(0f, viewH.toFloat()))
            }
            val t = SystemClock.uptimeMillis()
            if (t - lastHud > 250) {
                lastHud = t
                hud.refresh()
                cc.updateLive(Bridge.getFps(), surfW, surfH)
            }
            if (settings.dynamicRes && t - lastDyn > 2000 && Bridge.isWindowReady() && grabbing) {
                lastDyn = t
                dynamicResolution()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Smart resolution: trade sharpness for a steady frame rate, and take it back when there is headroom. */
    private fun dynamicResolution() {
        val target = (if (settings.fpsLimit > 0) settings.fpsLimit else refresh).toFloat()
        val fps = Bridge.getFps()
        if (fps < target * .85f) { slow++; fast = 0 } else if (fps >= target * .97f) { fast++; slow = 0 } else { slow = 0; fast = 0 }
        if (slow >= 2 && dynScale > 40) { dynScale -= 5; slow = 0; applyScale() }
        if (fast >= 4 && dynScale < settings.renderScale) { dynScale += 5; fast = 0; applyScale() }
    }

    // ---------------------------------------------------------------- Control Centre actions

    override val title: String get() = spec.name
    override val subtitle: String get() = "Minecraft ${spec.game} · ${spec.rendererName} · Java ${spec.javaMajor}"

    override fun setRenderScale(pct: Int, commit: Boolean) {
        settings.renderScale = pct
        dynScale = pct
        applyScale()
        if (commit) settings.save()
    }

    override fun setFpsLimit(fps: Int) {
        settings.fpsLimit = fps
        hud.fpsLimit = fps
        settings.save()
        applyPerf()
    }

    override fun applyPerf() {
        Bridge.setFpsLimit(settings.fpsLimit)
        Bridge.setVsync(if (settings.vsync) 1 else 0)
        Bridge.setPerf(settings.bigCores, settings.perfHint)
        val pm = getSystemService(android.os.PowerManager::class.java)!!
        if (pm.isSustainedPerformanceModeSupported) window.setSustainedPerformanceMode(settings.sustainedPerf)
        if (!settings.dynamicRes) { dynScale = settings.renderScale; applyScale() }
        applyFrameRate()
        updateGyro()
    }

    override fun hudChanged() {
        hud.mode = settings.perfHud
    }

    override fun controlsChanged() = controls.invalidate()

    override fun keyboard() = keyboard.toggle()

    override fun editControls() {
        if (editor != null) return
        controls.edit = true
        val ed = LayoutEditor(this, controls, File(dataDir, "controls"), settings.controlsLayout) { saved ->
            settings.controlsLayout = saved
            settings.save()
            closeEditor()
            Toast.makeText(this, "键位已保存", Toast.LENGTH_SHORT).show()
        }
        editor = ed
        root.addView(ed, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun closeEditor() {
        editor?.let { root.removeView(it) }
        editor = null
        controls.edit = false
        controls.previewAll = false
    }

    override fun onSelect(b: ControlButton?) {
        editor?.select(b)
    }

    override fun key(code: Int) = Bridge.tapKey(code)

    override fun superOptimize(): String {
        val dev = DeviceInfo.json(this)
        val ram = dev.optInt("ramMB")
        val mhz = dev.optInt("maxFreqMHz")
        val tier = when {
            ram >= 11000 && mhz >= 3000 -> 3
            ram >= 7000 && mhz >= 2400 -> 2
            else -> 1
        }
        val short = minOf(viewW, viewH).coerceAtLeast(1)
        val target = intArrayOf(0, 540, 720, 1080)[tier]
        settings.renderScale = ((target * 100f / short / 5).roundToInt() * 5).coerceIn(35, 100)
        settings.fpsLimit = when (tier) { 3 -> min(refresh, 120); 2 -> min(refresh, 90); else -> 60 }
        settings.vsync = false
        settings.sustainedPerf = true
        settings.bigCores = true
        settings.perfHint = true
        settings.dynamicRes = true
        if (settings.perfHud == "off") settings.perfHud = "fps"
        settings.save()
        dynScale = settings.renderScale
        hud.fpsLimit = settings.fpsLimit
        hudChanged()
        applyPerf()
        applyScale()
        val name = arrayOf("", "入门机", "中端机", "旗舰机")[tier]
        return "已按$name 优化：渲染 ${settings.renderScale}% · 上限 ${settings.fpsLimit} FPS · 动态分辨率 · 大核调度"
    }

    override fun exitGame() {
        Bridge.requestClose()
        Toast.makeText(this, "正在保存并退出…", Toast.LENGTH_SHORT).show()
        // the game saves and exits on its own; if it hangs, end the process
        root.postDelayed({ Process.killProcess(Process.myPid()) }, 12000)
    }

    override fun special(code: Int, down: Boolean) {
        if (!down) return
        when (code) {
            Keys.KEYBOARD -> keyboard.toggle()
            Keys.CONTROL_CENTER -> cc.show()
            Keys.TOGGLE_CONTROLS -> controls.hidden = !controls.hidden
            Keys.TOGGLE_HUD -> { settings.perfHud = if (settings.perfHud == "off") "fps" else "off"; hudChanged(); settings.save() }
        }
    }

    // ---------------------------------------------------------------- gyroscope

    private var gyroOn = false

    private fun updateGyro() {
        val sm = getSystemService(SensorManager::class.java)!!
        val want = settings.gyroAim
        if (want == gyroOn) return
        gyroOn = want
        if (want) sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        else sm.unregisterListener(this)
    }

    private var lastGyro = 0L

    override fun onSensorChanged(e: SensorEvent) {
        val dt = if (lastGyro == 0L) 0f else (e.timestamp - lastGyro) / 1e9f
        lastGyro = e.timestamp
        if (!grabbing || dt <= 0f || dt > .1f) return
        @Suppress("DEPRECATION")
        val rot = windowManager.defaultDisplay.rotation
        val sign = if (rot == Surface.ROTATION_270) -1f else 1f
        val k = 380f * settings.gyroSens / 100f * dt
        Bridge.sendCursorDelta(-e.values[0] * sign * k, -e.values[1] * sign * k)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------------------------------------------------------------- keys, mouse, gamepad

    private val padButtons = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to Keys.SPACE, KeyEvent.KEYCODE_BUTTON_B to Keys.LEFT_SHIFT,
        KeyEvent.KEYCODE_BUTTON_X to Keys.A + 4, KeyEvent.KEYCODE_BUTTON_Y to Keys.F1 + 4,
        KeyEvent.KEYCODE_BUTTON_START to Keys.ESCAPE, KeyEvent.KEYCODE_BUTTON_SELECT to Keys.TAB,
        KeyEvent.KEYCODE_BUTTON_THUMBL to Keys.LEFT_CONTROL, KeyEvent.KEYCODE_BUTTON_THUMBR to Keys.F1 + 4,
        KeyEvent.KEYCODE_DPAD_UP to Keys.F1 + 4, KeyEvent.KEYCODE_DPAD_DOWN to Keys.A + 16,
        KeyEvent.KEYCODE_DPAD_LEFT to Keys.A + 5, KeyEvent.KEYCODE_DPAD_RIGHT to Keys.A + 19,
    )

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        if (e.keyCode == KeyEvent.KEYCODE_VOLUME_UP || e.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || e.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE)
            return super.dispatchKeyEvent(e)
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.isFromSource(InputDevice.SOURCE_MOUSE)) return true // the right button: handled as a mouse button
            if (down && e.repeatCount == 0) when {
                editor != null -> closeEditor()
                cc.open -> cc.hide()
                else -> Bridge.tapKey(Keys.ESCAPE)
            }
            return true
        }
        if (KeyEvent.isGamepadButton(e.keyCode) || (e.isFromSource(InputDevice.SOURCE_GAMEPAD) && e.keyCode in padButtons)) {
            if (e.repeatCount > 0) return true
            when (e.keyCode) {
                KeyEvent.KEYCODE_BUTTON_L1 -> if (down) Bridge.sendScroll(0f, 1f)
                KeyEvent.KEYCODE_BUTTON_R1 -> if (down) Bridge.sendScroll(0f, -1f)
                KeyEvent.KEYCODE_BUTTON_L2 -> Bridge.mouse(1, down)
                KeyEvent.KEYCODE_BUTTON_R2 -> Bridge.mouse(0, down)
                KeyEvent.KEYCODE_BUTTON_MODE -> if (down) { if (cc.open) cc.hide() else cc.show() }
                else -> padButtons[e.keyCode]?.let { Bridge.key(it, down) }
            }
            return true
        }
        val k = Keys.fromAndroid(e.keyCode)
        if (k == 0 && e.unicodeChar == 0) return super.dispatchKeyEvent(e)
        val mods = Keys.mods(e)
        if (k != 0) Bridge.sendKey(k, e.scanCode, if (!down) Bridge.RELEASE else if (e.repeatCount > 0) 2 else Bridge.PRESS, mods)
        if (down && e.unicodeChar != 0 && !e.isCtrlPressed && k != Keys.ENTER && k != Keys.BACKSPACE && k != Keys.TAB && k != Keys.ESCAPE)
            Bridge.sendChar(e.unicodeChar, mods)
        return true
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_MOUSE) && editor == null && !cc.open) {
            mouseSeen = SystemClock.uptimeMillis()
            if (!grabbing) controls.moveCursor(e.x, e.y)
            return true // buttons arrive as ACTION_BUTTON_PRESS / RELEASE
        }
        return super.dispatchTouchEvent(e)
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) {
            mouseSeen = SystemClock.uptimeMillis()
            when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE -> if (!grabbing) controls.moveCursor(e.x, e.y)
                MotionEvent.ACTION_SCROLL -> Bridge.sendScroll(e.getAxisValue(MotionEvent.AXIS_HSCROLL), e.getAxisValue(MotionEvent.AXIS_VSCROLL))
                MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> mouseButton(e)
            }
            if (grabbing) surface.requestPointerCapture()
            return true
        }
        if (e.isFromSource(InputDevice.SOURCE_JOYSTICK) && e.actionMasked == MotionEvent.ACTION_MOVE) {
            controls.gamepadStick(e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y))
            padX = e.getAxisValue(MotionEvent.AXIS_Z)
            padY = e.getAxisValue(MotionEvent.AXIS_RZ)
            val lt = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
            val rt = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
            trigger(1, lt > .5f)
            trigger(0, rt > .5f)
            val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
            hat(hx, hy)
            return true
        }
        return super.dispatchGenericMotionEvent(e)
    }

    private val triggers = BooleanArray(2)
    private fun trigger(button: Int, on: Boolean) {
        if (triggers[button] != on) { triggers[button] = on; Bridge.mouse(button, on) }
    }

    private var hatX = 0f
    private var hatY = 0f
    private fun hat(x: Float, y: Float) {
        if (x != hatX) { if (x < 0) Bridge.tapKey(Keys.A + 5); if (x > 0) Bridge.tapKey(Keys.A + 19); hatX = x }
        if (y != hatY) { if (y < 0) Bridge.tapKey(Keys.F1 + 4); if (y > 0) Bridge.tapKey(Keys.A + 16); hatY = y }
    }

    private var mouseButtons = 0
    private fun mouseButton(e: MotionEvent) {
        val now = e.buttonState
        for ((mask, b) in listOf(MotionEvent.BUTTON_PRIMARY to 0, MotionEvent.BUTTON_SECONDARY to 1, MotionEvent.BUTTON_TERTIARY to 2,
            MotionEvent.BUTTON_BACK to 3, MotionEvent.BUTTON_FORWARD to 4)) {
            val was = mouseButtons and mask != 0
            val isDown = now and mask != 0
            if (was != isDown) Bridge.mouse(b, isDown)
        }
        mouseButtons = now
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
        if (Bridge.isWindowReady()) Bridge.sendFocus(hasFocus)
    }

    override fun onResume() {
        super.onResume()
        surface.setOnCapturedPointerListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_MOVE || e.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                Bridge.sendCursorDelta(e.x * settings.mouseSpeed / 100f, e.y * settings.mouseSpeed / 100f)
            } else if (e.actionMasked == MotionEvent.ACTION_SCROLL) {
                Bridge.sendScroll(0f, e.getAxisValue(MotionEvent.AXIS_VSCROLL))
            } else {
                mouseButton(e)
            }
            true
        }
        if (gyroOn) { gyroOn = false; updateGyro() }
    }

    override fun onPause() {
        super.onPause()
        controls.releaseAll()
        if (gyroOn) getSystemService(SensorManager::class.java)!!.unregisterListener(this)
    }
}
