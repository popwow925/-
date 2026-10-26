package cn.eririlily.launcher.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The touch layer over the game: on-screen buttons, the movement stick, and
 * everything else as mouse/look input. Also the layout editor (edit = true).
 */
@SuppressLint("ViewConstructor")
class ControlsView(ctx: Context, var layout: ControlLayout, private val host: Host) : View(ctx) {
    interface Host {
        fun special(code: Int, down: Boolean)
        /** game pixels per view pixel (render scale) */
        fun gameScale(): Float
        fun guiScale(): Int
        val settings: GameSettings
        fun onSelect(b: ControlButton?) {}
        fun onEdited() {}
    }

    var edit = false
        set(v) { field = v; selected = null; releaseAll(); invalidate() }
    var hidden = false
        set(v) { field = v; if (v) releaseAll(); invalidate() }
    var selected: ControlButton? = null
        private set
    var grabbing = false
        set(v) {
            if (field != v) { field = v; releaseHidden(); invalidate() }
        }
    /** in edit mode outside the game: show buttons of both modes */
    var previewAll = false

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * resources.displayMetrics.density }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val rect = RectF()

    // ---------------------------------------------------------------- geometry

    private fun visible(b: ControlButton): Boolean = when {
        hidden && b.keys.firstOrNull() != Keys.CONTROL_CENTER && b.keys.firstOrNull() != Keys.TOGGLE_CONTROLS -> false
        edit && previewAll -> true
        b.show == ControlButton.SHOW_INGAME -> grabbing
        b.show == ControlButton.SHOW_MENU -> !grabbing
        else -> true
    }

    private fun bounds(b: ControlButton, out: RectF) {
        val h = b.size * d
        val w = h * b.wide
        val cx = b.x * width
        val cy = b.y * height
        out.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    private fun hit(x: Float, y: Float, swipeOnly: Boolean = false): ControlButton? {
        for (b in layout.buttons.asReversed()) {
            if (!visible(b) || (swipeOnly && !b.swipe)) continue
            bounds(b, rect)
            val slop = 6 * d
            if (x >= rect.left - slop && x <= rect.right + slop && y >= rect.top - slop && y <= rect.bottom + slop) return b
        }
        return null
    }

    private val stickR get() = layout.stickSize * d / 2
    private val stickCX get() = layout.stickX * width
    private val stickCY get() = layout.stickY * height
    private fun stickVisible() = layout.stick && !hidden && (grabbing || (edit && previewAll))
    private fun inStickZone(x: Float, y: Float) = stickVisible() && hypot(x - stickCX, y - stickCY) < stickR * 1.5f

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        val alpha = (host.settings.controlsOpacity.coerceIn(10, 100) * 2.55f).toInt()
        if (edit) drawGrid(c)
        if (stickVisible()) drawStick(c, alpha)
        for (b in layout.buttons) if (visible(b)) drawButton(c, b, alpha)
        if (!grabbing && !edit && cursorShown) drawCursor(c)
    }

    private fun drawGrid(c: Canvas) {
        fill.color = Color.argb(70, 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        stroke.color = Color.argb(40, 255, 255, 255)
        val step = 24 * d
        var x = 0f
        while (x < width) { c.drawLine(x, 0f, x, height.toFloat(), stroke); x += step }
        var y = 0f
        while (y < height) { c.drawLine(0f, y, width.toFloat(), y, stroke); y += step }
    }

    private fun drawButton(c: Canvas, b: ControlButton, alpha: Int) {
        bounds(b, rect)
        val special = b.keys.firstOrNull() == Keys.CONTROL_CENTER
        if (special) {
            // a Dynamic-Island style capsule: tap to open the control centre
            fill.color = Color.argb(max(alpha, 150), 0, 0, 0)
            c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill)
            fill.color = Color.argb(200, 255, 255, 255)
            val gw = rect.width() * .32f
            c.drawRoundRect(rect.centerX() - gw / 2, rect.centerY() - 1.5f * d, rect.centerX() + gw / 2, rect.centerY() + 1.5f * d, 2 * d, 2 * d, fill)
            return
        }
        val on = b.down || (edit && b === selected)
        fill.color = if (on) Color.argb(min(255, alpha + 60), 255, 255, 255) else Color.argb(alpha * 45 / 100, 28, 28, 30)
        stroke.color = Color.argb(if (edit && b === selected) 255 else alpha * 70 / 100, 255, 255, 255)
        val r = if (b.round && b.wide == 1f) rect.width() / 2 else min(rect.height(), rect.width()) * .32f
        c.drawRoundRect(rect, r, r, fill)
        c.drawRoundRect(rect, r, r, stroke)
        text.color = if (on) Color.argb(255, 20, 20, 22) else Color.argb(min(255, alpha + 80), 255, 255, 255)
        text.textSize = min(rect.height() * .34f, 15 * d)
        val label = b.name.ifEmpty { b.keys.joinToString("+") { Keys.label(it) } }
        c.drawText(label, rect.centerX(), rect.centerY() - (text.descent() + text.ascent()) / 2, text)
        if (b.toggle) {
            fill.color = if (b.down) Color.rgb(52, 199, 89) else Color.argb(alpha, 160, 160, 160)
            c.drawCircle(rect.right - 6 * d, rect.top + 6 * d, 3 * d, fill)
        }
    }

    private fun drawStick(c: Canvas, alpha: Int) {
        val r = stickR
        fill.color = Color.argb(alpha * 35 / 100, 28, 28, 30)
        c.drawCircle(stickCX, stickCY, r, fill)
        stroke.color = Color.argb(alpha * 60 / 100, 255, 255, 255)
        c.drawCircle(stickCX, stickCY, r, stroke)
        if (sprintLatched) {
            stroke.color = Color.argb(220, 255, 214, 10)
            c.drawCircle(stickCX, stickCY, r * 1.08f, stroke)
        }
        fill.color = Color.argb(min(255, alpha + 40), 255, 255, 255)
        c.drawCircle(stickCX + knobX * r, stickCY + knobY * r, r * .38f, fill)
    }

    private val cursorPath = Path()
    private fun drawCursor(c: Canvas) {
        val x = cursorX
        val y = cursorY
        cursorPath.reset()
        cursorPath.moveTo(x, y)
        cursorPath.lineTo(x, y + 18 * d)
        cursorPath.lineTo(x + 5 * d, y + 13.5f * d)
        cursorPath.lineTo(x + 12.5f * d, y + 13.5f * d)
        cursorPath.close()
        fill.color = Color.WHITE
        c.drawPath(cursorPath, fill)
        stroke.color = Color.BLACK
        c.drawPath(cursorPath, stroke)
    }

    // ---------------------------------------------------------------- input state

    private var stickPointer = -1
    private var knobX = 0f
    private var knobY = 0f
    private val held = BooleanArray(4) // W A S D
    private var sprintLatched = false

    private var lookPointer = -1
    private var lookStartX = 0f
    private var lookStartY = 0f
    private var lookLastX = 0f
    private var lookLastY = 0f
    private var lookDownAt = 0L
    private var lookMoved = false
    private var breaking = false
    private var hotbarSlot = -1
    private var menuSecondTap = false

    var cursorX = 0f
        private set
    var cursorY = 0f
        private set
    var cursorShown = false

    private val longPress = Runnable {
        if (lookPointer >= 0 && !lookMoved && grabbing) {
            breaking = true
            Bridge.mouse(0, true)
            haptic()
        }
    }

    private fun haptic() {
        if (host.settings.haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun releaseAll() {
        for (b in layout.buttons) if (b.down) press(b, false)
        setStick(0f, 0f)
        stickPointer = -1
        if (breaking) Bridge.mouse(0, false)
        if (lookPointer >= 0 && !grabbing) Bridge.mouse(0, false)
        breaking = false
        lookPointer = -1
        removeCallbacks(longPress)
    }

    private fun releaseHidden() {
        for (b in layout.buttons) if (b.down && !visible(b) && !b.toggle) press(b, false)
        if (!grabbing) { setStick(0f, 0f); stickPointer = -1 }
        if (breaking) { Bridge.mouse(0, false); breaking = false }
        lookPointer = -1
        removeCallbacks(longPress)
    }

    private fun press(b: ControlButton, down: Boolean) {
        if (b.down == down) return
        b.down = down
        val keys = if (down) b.keys else b.keys.asReversed()
        for (k in keys) send(k, down)
        if (down) haptic()
        invalidate()
    }

    private fun send(k: Int, down: Boolean) {
        when {
            k == Keys.MOUSE_LEFT -> Bridge.mouse(0, down)
            k == Keys.MOUSE_RIGHT -> Bridge.mouse(1, down)
            k == Keys.MOUSE_MIDDLE -> Bridge.mouse(2, down)
            k == Keys.SCROLL_UP -> if (down) Bridge.sendScroll(0f, 1f)
            k == Keys.SCROLL_DOWN -> if (down) Bridge.sendScroll(0f, -1f)
            k < 0 -> host.special(k, down)
            else -> Bridge.key(k, down)
        }
    }

    private fun setStick(x: Float, y: Float) {
        knobX = x
        knobY = y
        val dead = .28f
        val want = booleanArrayOf(y < -dead, x < -dead, y > dead, x > dead)
        val keys = intArrayOf(Keys.A + 22, Keys.A, Keys.A + 18, Keys.A + 3) // W A S D
        for (i in 0..3) if (want[i] != held[i]) { held[i] = want[i]; Bridge.key(keys[i], want[i]) }
        invalidate()
    }

    // ---------------------------------------------------------------- touch

    private var dragTarget: Any? = null
    private var dragDX = 0f
    private var dragDY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (edit) return editTouch(e)
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> down(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_MOVE -> for (p in 0 until e.pointerCount) move(e.getPointerId(p), e.getX(p), e.getY(p))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(i), e.getX(i), e.getY(i), false)
            MotionEvent.ACTION_CANCEL -> for (p in 0 until e.pointerCount) up(e.getPointerId(p), e.getX(p), e.getY(p), true)
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        hit(x, y)?.let { b ->
            if (b.toggle) press(b, !b.down) else { b.pointer = id; press(b, true) }
            if (b.toggle) b.pointer = -1
            return
        }
        if (stickPointer < 0 && inStickZone(x, y)) {
            stickPointer = id
            stickMove(x, y)
            return
        }
        if (lookPointer >= 0) {
            // a second finger in a menu: right click at the cursor (split stacks, etc.)
            if (!grabbing && !menuSecondTap) {
                menuSecondTap = true
                Bridge.mouse(0, false)
                Bridge.mouse(1, true); Bridge.mouse(1, false)
            }
            return
        }
        lookPointer = id
        lookStartX = x; lookStartY = y; lookLastX = x; lookLastY = y
        lookDownAt = SystemClock.uptimeMillis()
        lookMoved = false
        menuSecondTap = false
        hotbarSlot = if (grabbing) hotbarAt(x, y) else -1
        if (hotbarSlot >= 0) {
            Bridge.tapKey(Keys.N0 + 1 + hotbarSlot)
            haptic()
            return
        }
        if (grabbing) {
            postDelayed(longPress, 260)
        } else {
            moveCursor(x, y)
            Bridge.mouse(0, true)
        }
    }

    private fun move(id: Int, x: Float, y: Float) {
        // buttons that let a finger slide onto them
        for (b in layout.buttons) if (b.pointer == id && b.down) {
            bounds(b, rect)
            if (!rect.contains(x, y)) {
                val next = hit(x, y, swipeOnly = true)
                if (next != null && next !== b) { press(b, false); b.pointer = -1; next.pointer = id; press(next, true) }
            }
            return
        }
        if (id == stickPointer) { stickMove(x, y); return }
        if (id != lookPointer) return
        if (hotbarSlot >= 0) {
            val s = hotbarAt(x, y)
            if (s >= 0 && s != hotbarSlot) { hotbarSlot = s; Bridge.tapKey(Keys.N0 + 1 + s); haptic() }
            return
        }
        val dx = x - lookLastX
        val dy = y - lookLastY
        lookLastX = x; lookLastY = y
        if (!lookMoved && hypot(x - lookStartX, y - lookStartY) > 9 * d) {
            lookMoved = true
            if (!breaking) removeCallbacks(longPress)
        }
        if (grabbing) {
            val k = host.gameScale() * host.settings.mouseSpeed / 100f
            Bridge.sendCursorDelta(dx * k, dy * k)
        } else {
            moveCursor(x, y)
        }
    }

    private fun up(id: Int, x: Float, y: Float, cancel: Boolean) {
        for (b in layout.buttons) if (b.pointer == id) { b.pointer = -1; if (!b.toggle) press(b, false) }
        if (id == stickPointer) {
            stickPointer = -1
            setStick(0f, 0f)
            if (sprintLatched) { sprintLatched = false; Bridge.key(Keys.LEFT_CONTROL, false) }
            return
        }
        if (id != lookPointer) return
        lookPointer = -1
        removeCallbacks(longPress)
        if (hotbarSlot >= 0) { hotbarSlot = -1; return }
        if (grabbing) {
            if (breaking) {
                breaking = false
                Bridge.mouse(0, false)
            } else if (!cancel && !lookMoved && SystemClock.uptimeMillis() - lookDownAt < 260) {
                // a quick tap uses / places
                Bridge.mouse(1, true)
                postDelayed({ Bridge.mouse(1, false) }, 40)
            }
        } else {
            moveCursor(x, y)
            Bridge.mouse(0, false)
        }
    }

    private fun stickMove(x: Float, y: Float) {
        val r = stickR
        var dx = (x - stickCX) / r
        var dy = (y - stickCY) / r
        val len = hypot(dx, dy)
        // pushing forward past the ring sprints, like Bedrock
        val sprint = dy < -.6f && len > 1.25f
        if (sprint != sprintLatched) { sprintLatched = sprint; Bridge.key(Keys.LEFT_CONTROL, sprint); if (sprint) haptic() }
        if (len > 1f) { dx /= len; dy /= len }
        setStick(dx, dy)
    }

    fun moveCursor(x: Float, y: Float) {
        cursorX = x; cursorY = y
        cursorShown = true
        val k = host.gameScale()
        Bridge.sendCursor(x * k, y * k)
        invalidate()
    }

    /** Hotbar slot (0-8) under a point, from Minecraft's GUI scale. */
    private fun hotbarAt(x: Float, y: Float): Int {
        val k = host.gameScale()
        val gw = width * k
        val gh = height * k
        val gs = host.guiScale().let { want ->
            var auto = 1
            while (auto < 8 && gw / (auto + 1) >= 320 && gh / (auto + 1) >= 240) auto++
            if (want in 1..auto) want else auto
        }
        val hbW = 182 * gs / k
        val hbH = 22 * gs / k
        val left = (width - hbW) / 2
        if (y < height - hbH || x < left || x > left + hbW) return -1
        return ((x - left) / (20 * gs / k)).toInt().coerceIn(0, 8)
    }

    // ---------------------------------------------------------------- editor

    private fun editTouch(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val b = hit(x, y)
                dragTarget = when {
                    b != null -> b
                    hypot(x - stickCX, y - stickCY) < stickR -> layout
                    else -> null
                }
                selected = b
                host.onSelect(b)
                when (val t = dragTarget) {
                    is ControlButton -> { dragDX = t.x * width - x; dragDY = t.y * height - y }
                    is ControlLayout -> { dragDX = stickCX - x; dragDY = stickCY - y }
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val snap = 8 * d
                val nx = (((x + dragDX) / snap).toInt() * snap).coerceIn(0f, width.toFloat()) / width
                val ny = (((y + dragDY) / snap).toInt() * snap).coerceIn(0f, height.toFloat()) / height
                when (val t = dragTarget) {
                    is ControlButton -> { t.x = nx; t.y = ny }
                    is ControlLayout -> { t.stickX = nx; t.stickY = ny }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> if (dragTarget != null) host.onEdited()
        }
        return true
    }

    fun select(b: ControlButton?) {
        selected = b
        invalidate()
    }

    // ---------------------------------------------------------------- gamepad / mouse helpers

    /** a physical stick moves like the on-screen one */
    fun gamepadStick(x: Float, y: Float) {
        if (stickPointer < 0) setStick(x, y)
    }
}
