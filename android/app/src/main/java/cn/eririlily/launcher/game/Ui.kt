package cn.eririlily.launcher.game

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Small iOS-style widgets drawn by hand for the in-game panels. */
object Ui {
    val spring = PathInterpolator(.2f, .9f, .25f, 1.05f)
    const val GREEN = 0xFF30D158.toInt()
    const val PINK = 0xFFFF6F91.toInt()
    const val RED = 0xFFFF453A.toInt()

    // the palette follows the launcher theme (sakura / white / black); see apply()
    @JvmField var BLUE = 0xFFE0527A.toInt()      // accent
    @JvmField var ON_ACCENT = Color.WHITE        // text on the accent
    @JvmField var LABEL = 0xFF3A2830.toInt()
    @JvmField var SECONDARY = 0x993A2830.toInt()
    @JvmField var FILL = 0x26E0527A
    @JvmField var PANEL = 0xF5FFF1F5.toInt()
    @JvmField var TILE = 0xFFFFFFFF.toInt()

    fun apply(theme: String) {
        when (theme) {
            "white" -> { BLUE = 0xFF3D5AFE.toInt(); ON_ACCENT = Color.WHITE; LABEL = 0xFF14171F.toInt(); SECONDARY = 0x9914171F.toInt()
                FILL = 0x1F14171F; PANEL = 0xF5FFFFFF.toInt(); TILE = 0xFFF1F2F5.toInt() }
            "black" -> { BLUE = 0xFFFF7AA2.toInt(); ON_ACCENT = 0xFF2A0D18.toInt(); LABEL = 0xFFF4F1F3.toInt(); SECONDARY = 0x99F4F1F3.toInt()
                FILL = 0x33FFFFFF; PANEL = 0xF5101012.toInt(); TILE = 0xFF1C1C1F.toInt() }
            else -> { BLUE = 0xFFE0527A.toInt(); ON_ACCENT = Color.WHITE; LABEL = 0xFF3A2830.toInt(); SECONDARY = 0x993A2830.toInt()
                FILL = 0x26E0527A; PANEL = 0xF5FFF1F5.toInt(); TILE = 0xFFFFFFFF.toInt() }
        }
    }

    fun Context.dp(v: Float) = v * resources.displayMetrics.density
    fun Context.dpi(v: Float) = (v * resources.displayMetrics.density).roundToInt()

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    fun text(ctx: Context, s: String, size: Float = 15f, color: Int = LABEL, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        includeFontPadding = false
    }

    fun row(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun col(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    /** Press feedback: a quick spring scale like iOS buttons. */
    @SuppressLint("ClickableViewAccessibility")
    fun pressable(v: View, onClick: () -> Unit) {
        v.isClickable = true
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(.94f).scaleY(.94f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setInterpolator(spring).setDuration(260).start()
            }
            false
        }
        v.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
    }
}

/** iOS switch. */
class IosSwitch(ctx: Context, initial: Boolean, private val onChange: (Boolean) -> Unit) : View(ctx) {
    var checked = initial
        private set
    private var pos = if (initial) 1f else 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        isClickable = true
        setOnClickListener { set(!checked, true) }
    }

    fun set(v: Boolean, notify: Boolean = false) {
        if (v == checked) return
        checked = v
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        ValueAnimator.ofFloat(pos, if (v) 1f else 0f).apply {
            duration = 260
            interpolator = Ui.spring
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
        }.start()
        if (notify) onChange(v)
    }

    override fun onMeasure(w: Int, h: Int) {
        val d = resources.displayMetrics.density
        setMeasuredDimension((51 * d).toInt(), (31 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        r.set(0f, 0f, width.toFloat(), h)
        p.color = blend(Ui.FILL, Ui.GREEN, pos)
        c.drawRoundRect(r, h / 2, h / 2, p)
        val k = h / 2 - 2 * resources.displayMetrics.density
        val cx = h / 2 + (width - h) * pos
        p.color = Color.WHITE
        p.setShadowLayer(4f, 0f, 2f, 0x40000000)
        c.drawCircle(cx, h / 2, k, p)
        p.clearShadowLayer()
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(), (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )
}

/** iOS Control Centre style slider: a thick rounded bar that fills. */
class IosSlider(
    ctx: Context, private val min: Int, private val max: Int, initial: Int, private val step: Int = 1,
    private val label: (Int) -> String, private val onChange: (Int, Boolean) -> Unit,
) : View(ctx) {
    var value = initial.coerceIn(min, max)
        private set
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val r = RectF()
    var title = ""

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (resources.displayMetrics.density * 46).toInt())
    }

    fun set(v: Int) {
        value = v.coerceIn(min, max)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val h = height.toFloat()
        r.set(0f, 0f, width.toFloat(), h)
        p.color = Ui.FILL
        c.drawRoundRect(r, 14 * d, 14 * d, p)
        val f = (value - min).toFloat() / (max - min)
        c.save()
        c.clipRect(0f, 0f, width * f, h)
        p.color = Color.WHITE
        c.drawRoundRect(r, 14 * d, 14 * d, p)
        c.restore()
        t.textSize = 14 * d
        t.textAlign = Paint.Align.LEFT
        t.color = if (f > .3f) ON_ACCENT else LABEL
        c.drawText(title, 14 * d, h / 2 - (t.descent() + t.ascent()) / 2, t)
        t.textAlign = Paint.Align.RIGHT
        t.color = if (f > .95f) ON_ACCENT else LABEL
        c.drawText(label(value), width - 14 * d, h / 2 - (t.descent() + t.ascent()) / 2, t)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val f = (e.x / width).coerceIn(0f, 1f)
        var v = (min + f * (max - min)).roundToInt()
        v = (v / step) * step
        v = v.coerceIn(min, max)
        if (v != value) {
            value = v
            if (v == min || v == max) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            invalidate()
            onChange(v, false)
        }
        if (e.actionMasked == MotionEvent.ACTION_UP) onChange(value, true)
        return true
    }
}
