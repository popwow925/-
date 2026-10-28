package cn.eririlily.launcher.game

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.view.View
import java.io.File

/**
 * Performance overlay: a small FPS readout, or the full panel with frame time, a frame-time graph,
 * render resolution, memory, battery temperature and the thermal state. No background: text with a
 * soft shadow in the launcher theme's colours. Drag to move, tap to fold.
 */
@SuppressLint("ViewConstructor")
class HudView(ctx: Context, private val renderer: String, theme: OverlayTheme) : OverlayView(ctx, "hud", theme, 140f, 8f) {
    var mode = "fps" // off | fps | full
        set(v) { field = v; visibility = if (v == "off") GONE else VISIBLE; requestLayout(); invalidate() }
    var renderW = 0
    var renderH = 0
    var fpsLimit = 0

    private val times = FloatArray(120)
    private val path = Path()
    private var fps = 0f
    private var frameMs = 0f
    private var worstMs = 0f
    private var memMB = 0
    private var batteryC = 0f
    private var batteryPct = 0
    private var thermal = 0
    private var tick = 0

    /** called ~4×/s by the activity */
    fun refresh() {
        if (mode == "off" || collapsed) return
        fps = Bridge.getFps()
        frameMs = Bridge.getFrameUs() / 1000f
        if (mode == "full") {
            Bridge.getFrameTimes(times)
            worstMs = times.maxOrNull() ?: 0f
            if (tick++ % 8 == 0) slowStats()
        }
        invalidate()
    }

    private fun slowStats() {
        memMB = runCatching {
            File("/proc/self/statm").readText().split(' ')[1].toLong() * 4096 / (1024 * 1024)
        }.getOrDefault(0L).toInt()
        val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryC = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val lv = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val sc = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        batteryPct = if (lv >= 0) lv * 100 / sc else 0
        if (Build.VERSION.SDK_INT >= 29) thermal = context.getSystemService(PowerManager::class.java)!!.currentThermalStatus
    }

    private fun fpsColor(): Int {
        val target = if (fpsLimit > 0) fpsLimit.toFloat() else 60f
        return when {
            fps >= target * .9f -> 0xFF4CD98A.toInt()
            fps >= target * .55f -> 0xFFFFD25A.toInt()
            else -> 0xFFFF6B70.toInt()
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        when {
            collapsed -> { val (cw, ch) = chipSize("FPS"); setMeasuredDimension(cw, ch) }
            mode == "full" -> setMeasuredDimension((176 * d).toInt(), (104 * d).toInt())
            else -> setMeasuredDimension((78 * d).toInt(), (22 * d).toInt())
        }
    }

    override fun onDraw(c: Canvas) {
        if (mode == "off") return
        if (collapsed) { drawChip(c, "FPS"); return }
        txt.textAlign = Paint.Align.LEFT
        fill.color = fpsColor()
        if (mode != "full") {
            c.drawCircle(5 * d, height / 2f, 3.2f * d, fill)
            txt.color = theme.fg; txt.textSize = 13f * d
            c.drawText("${fps.toInt()} FPS", 14 * d, height / 2f - (txt.descent() + txt.ascent()) / 2, txt)
            return
        }
        txt.textSize = 19f * d; txt.color = fpsColor()
        c.drawText("${fps.toInt()}", 2 * d, 20 * d, txt)
        val w = txt.measureText("${fps.toInt()}")
        txt.color = theme.fg; txt.textSize = 10.5f * d
        c.drawText("FPS  ${"%.1f".format(frameMs)} ms  峰 ${"%.0f".format(worstMs)}", 7 * d + w, 19 * d, txt)

        // frame-time graph (0..50 ms), outlined so it reads without a background
        val gx = 2 * d; val gy = 28 * d; val gw = width - 4 * d; val gh = 28 * d
        path.reset()
        for (i in times.indices) {
            val x = gx + gw * i / (times.size - 1)
            val y = gy + gh - gh * (times[i].coerceIn(0f, 50f) / 50f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        stroke.style = Paint.Style.STROKE
        stroke.color = theme.shadow and 0x00FFFFFF or 0x66000000; stroke.strokeWidth = 3.4f * d; c.drawPath(path, stroke)
        stroke.color = fpsColor(); stroke.strokeWidth = 1.5f * d; c.drawPath(path, stroke)

        txt.textSize = 9.5f * d; txt.color = theme.fg
        val th = arrayOf("正常", "轻微", "中等", "严重", "危急", "紧急", "关机")[thermal.coerceIn(0, 6)]
        c.drawText("${renderW}×${renderH} · $renderer", 2 * d, 70 * d, txt)
        c.drawText("内存 ${memMB} MB · 电池 ${batteryPct}% ${"%.1f".format(batteryC)}℃", 2 * d, 84 * d, txt)
        c.drawText("温控 $th${if (fpsLimit > 0) " · 上限 $fpsLimit" else ""}", 2 * d, 98 * d, txt)
    }
}
