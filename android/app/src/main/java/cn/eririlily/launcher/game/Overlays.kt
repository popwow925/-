package cn.eririlily.launcher.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.movtery.zalithlauncher.game.input.KeystrokeTracker
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The look of the in-game overlays. They have no background at all: only thin outlines and text
 * with a soft shadow, so they stay readable over any scene. One palette per launcher theme.
 */
class OverlayTheme(val fg: Int, val outline: Int, val accent: Int, val onAccent: Int, val shadow: Int) {
    companion object {
        fun of(name: String) = when (name) {
            "white" -> OverlayTheme(Color.WHITE, 0xCCFFFFFF.toInt(), Color.WHITE, 0xFF20242C.toInt(), 0xCC000000.toInt())
            "black" -> OverlayTheme(0xFF14110F.toInt(), 0xCC14110F.toInt(), 0xFF14110F.toInt(), Color.WHITE, 0xB3FFFFFF.toInt())
            else -> OverlayTheme(0xF2FFFFFF.toInt(), 0x99FFFFFF.toInt(), 0xFFE9809B.toInt(), Color.WHITE, 0xCC000000.toInt())
        }
    }
}

/**
 * Base of a small overlay that can be dragged anywhere, remembers where it was left and folds
 * into a tiny chip when tapped.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
abstract class OverlayView(ctx: Context, private val id: String, val theme: OverlayTheme, private val defX: Float, private val defY: Float) : View(ctx) {
    protected val d = resources.displayMetrics.density
    private val prefs = ctx.getSharedPreferences("overlays", Context.MODE_PRIVATE)
    var collapsed = prefs.getBoolean("$id.collapsed", false)
        private set
    private var downX = 0f; private var downY = 0f; private var baseX = 0f; private var baseY = 0f
    private var moved = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    protected val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.fg; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(3.2f * d, 0f, 1.2f * d, theme.shadow)
    }
    protected val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d }
    protected val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val rect = RectF()

    init {
        post {
            translationX = prefs.getFloat("$id.x", defX * d).coerceIn(0f, ((parent as View).width - width).toFloat().coerceAtLeast(0f))
            translationY = prefs.getFloat("$id.y", defY * d).coerceIn(0f, ((parent as View).height - height).toFloat().coerceAtLeast(0f))
        }
        setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; baseX = translationX; baseY = translationY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && hypot(e.rawX - downX, e.rawY - downY) > slop) moved = true
                    if (moved) {
                        val p = parent as View
                        translationX = (baseX + e.rawX - downX).coerceIn(0f, (p.width - width).toFloat().coerceAtLeast(0f))
                        translationY = (baseY + e.rawY - downY).coerceIn(0f, (p.height - height).toFloat().coerceAtLeast(0f))
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) prefs.edit().putFloat("$id.x", translationX).putFloat("$id.y", translationY).apply()
                    else { collapsed = !collapsed; prefs.edit().putBoolean("$id.collapsed", collapsed).apply(); requestLayout(); invalidate() }
                }
            }
            true
        }
    }

    /** the folded chip: a small outlined pill with a few letters */
    protected fun drawChip(c: Canvas, label: String) {
        txt.textSize = 10f * d; txt.textAlign = Paint.Align.CENTER
        val w = txt.measureText(label) + 16 * d
        rect.set(0f, 0f, w, 20 * d)
        stroke.color = theme.shadow and 0x00FFFFFF or 0x55000000; stroke.strokeWidth = 2.4f * d; c.drawRoundRect(rect, 10 * d, 10 * d, stroke)
        stroke.color = theme.outline; stroke.strokeWidth = 1.2f * d; c.drawRoundRect(rect, 10 * d, 10 * d, stroke)
        c.drawText(label, w / 2, 10 * d - (txt.descent() + txt.ascent()) / 2, txt)
    }
    protected fun chipSize(label: String): Pair<Int, Int> {
        txt.textSize = 10f * d
        return ((txt.measureText(label) + 16 * d).toInt() + 2) to (20 * d).toInt() + 2
    }
}

/** W A S D, the two mouse buttons with clicks per second, shift and space. A pressed key fills with the theme's accent. */
@SuppressLint("ViewConstructor")
class KeysView(ctx: Context, theme: OverlayTheme) : OverlayView(ctx, "keys", theme, 150f, 70f) {
    private val u = 25 * d
    private val g = 3.5f * d
    private var lastState = -1
    private var lc = 0; private var rc = 0
    private var next = 0L

    init { visibility = GONE }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); postOnAnimation(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
    private val tick = object : Runnable {
        override fun run() {
            if (isShown && !collapsed) {
                val t = KeystrokeTracker
                var st = 0
                for ((i, b) in listOf(t.w, t.a, t.s, t.d, t.lmb, t.rmb, t.shift, t.space).withIndex()) if (b) st = st or (1 shl i)
                val now = System.currentTimeMillis()
                if (now > next) { next = now + 250; val l = t.cps(true); val r = t.cps(false); if (l != lc || r != rc) { lc = l; rc = r; st = st or (1 shl 20) } }
                if (st != lastState) { lastState = st and (1 shl 20).inv(); invalidate() }
            }
            postOnAnimation(this)
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        if (collapsed) { val (cw, ch) = chipSize("WASD"); setMeasuredDimension(cw, ch) }
        else setMeasuredDimension((3 * u + 2 * g + 2).toInt(), (u * 2 + u * 0.78f + u * 0.7f + g * 3 + 2).toInt())
    }

    private fun key(c: Canvas, label: String, x: Float, y: Float, w: Float, h: Float, on: Boolean, size: Float = 11f, sub: String? = null) {
        rect.set(x + 1, y + 1, x + w + 1, y + h + 1)
        val r = 7 * d
        if (on) { fill.color = theme.accent; c.drawRoundRect(rect, r, r, fill) }
        else { stroke.color = theme.shadow and 0x00FFFFFF or 0x4D000000; stroke.strokeWidth = 2.6f * d; c.drawRoundRect(rect, r, r, stroke) }
        stroke.color = if (on) theme.accent else theme.outline; stroke.strokeWidth = 1.2f * d
        c.drawRoundRect(rect, r, r, stroke)
        txt.color = if (on) theme.onAccent else theme.fg
        txt.setShadowLayer(if (on) 0f else 3.2f * d, 0f, 1.2f * d, theme.shadow)
        txt.textAlign = Paint.Align.CENTER; txt.textSize = size * d
        val cx = rect.centerX()
        if (sub == null) c.drawText(label, cx, rect.centerY() - (txt.descent() + txt.ascent()) / 2, txt)
        else {
            c.drawText(label, cx, rect.centerY() - 2 * d, txt)
            txt.textSize = 8.5f * d; txt.typeface = Typeface.DEFAULT
            c.drawText(sub, cx, rect.centerY() + 9 * d, txt); txt.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    override fun onDraw(c: Canvas) {
        if (collapsed) { drawChip(c, "WASD"); return }
        val t = KeystrokeTracker
        key(c, "W", u + g, 0f, u, u, t.w)
        key(c, "A", 0f, u + g, u, u, t.a); key(c, "S", u + g, u + g, u, u, t.s); key(c, "D", 2 * (u + g), u + g, u, u, t.d)
        val y2 = 2 * (u + g); val hw = (3 * u + 2 * g - g) / 2
        key(c, "LMB", 0f, y2, hw, u * 0.78f, t.lmb, 9.5f, "$lc CPS"); key(c, "RMB", hw + g, y2, hw, u * 0.78f, t.rmb, 9.5f, "$rc CPS")
        val y3 = y2 + u * 0.78f + g
        key(c, "⇧", 0f, y3, u, u * 0.7f, t.shift, 10.5f); key(c, "", u + g, y3, 2 * u + g, u * 0.7f, t.space)
        // the space bar's dash
        stroke.color = if (t.space) theme.onAccent else theme.fg; stroke.strokeWidth = 1.6f * d
        c.drawLine(u + g + 0.7f * u, y3 + u * 0.35f + 1, u + g + 1.8f * u, y3 + u * 0.35f + 1, stroke)
    }
}
