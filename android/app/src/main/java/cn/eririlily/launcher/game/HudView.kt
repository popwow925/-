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
 * Performance overlay: an FPS capsule, or the full panel with frame time,
 * a frame-time graph, render resolution, memory, battery temperature and
 * the thermal state.
 */
@SuppressLint("ViewConstructor")
class HudView(ctx: Context, private val renderer: String) : View(ctx) {
    var mode = "fps" // off | fps | full
        set(v) { field = v; visibility = if (v == "off") GONE else VISIBLE; requestLayout(); invalidate() }
    var renderW = 0
    var renderH = 0
    var fpsLimit = 0

    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f * resources.displayMetrics.density }
    private val r = RectF()
    private val times = FloatArray(120)
    private val path = Path()

    private var fps = 0f
    private var frameMs = 0f
    private var worstMs = 0f
    private var memMB = 0
    private var batteryC = 0f
    private var thermal = 0
    private var tick = 0

    /** called ~4×/s by the activity */
    fun refresh() {
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
        if (Build.VERSION.SDK_INT >= 29) thermal = context.getSystemService(PowerManager::class.java)!!.currentThermalStatus
    }

    private fun fpsColor(): Int {
        val target = if (fpsLimit > 0) fpsLimit.toFloat() else 60f
        return when {
            fps >= target * .9f -> Ui.GREEN
            fps >= target * .55f -> 0xFFFFD60A.toInt()
            else -> Ui.RED
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        if (mode == "full") setMeasuredDimension((196 * d).toInt(), (136 * d).toInt())
        else setMeasuredDimension((86 * d).toInt(), (26 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        if (mode == "off") return
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        bg.color = 0x99000000.toInt()
        val rad = if (mode == "full") 14 * d else height / 2f
        c.drawRoundRect(r, rad, rad, bg)
        if (mode != "full") {
            bg.color = fpsColor()
            c.drawCircle(13 * d, height / 2f, 3.5f * d, bg)
            txt.textSize = 12.5f * d
            txt.textAlign = Paint.Align.LEFT
            c.drawText("${fps.toInt()} FPS", 22 * d, height / 2f - (txt.descent() + txt.ascent()) / 2, txt)
            return
        }
        txt.textAlign = Paint.Align.LEFT
        txt.textSize = 20 * d
        txt.color = fpsColor()
        c.drawText("${fps.toInt()}", 12 * d, 28 * d, txt)
        val w = txt.measureText("${fps.toInt()}")
        txt.color = Color.WHITE
        txt.textSize = 11 * d
        c.drawText("FPS  ${"%.1f".format(frameMs)} ms  峰 ${"%.0f".format(worstMs)}", 16 * d + w, 27 * d, txt)

        // frame-time graph (0..50 ms), with 16.7 / 33 ms guides
        val gx = 12 * d
        val gy = 38 * d
        val gw = width - 24 * d
        val gh = 40 * d
        line.color = 0x33FFFFFF
        for (ms in floatArrayOf(16.7f, 33.3f)) {
            val y = gy + gh - gh * ms / 50f
            c.drawLine(gx, y, gx + gw, y, line)
        }
        path.reset()
        for (i in times.indices) {
            val x = gx + gw * i / (times.size - 1)
            val y = gy + gh - gh * (times[i].coerceIn(0f, 50f) / 50f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        line.color = fpsColor()
        c.drawPath(path, line)

        txt.textSize = 10.5f * d
        txt.color = 0xCCFFFFFF.toInt()
        val th = arrayOf("正常", "轻微", "中等", "严重", "危急", "紧急", "关机")[thermal.coerceIn(0, 6)]
        c.drawText("渲染 ${renderW}×${renderH} · $renderer", 12 * d, 94 * d, txt)
        c.drawText("内存 ${memMB} MB · 电池 ${"%.1f".format(batteryC)}℃", 12 * d, 110 * d, txt)
        c.drawText("温控 $th${if (fpsLimit > 0) " · 上限 $fpsLimit" else ""}", 12 * d, 126 * d, txt)
    }
}
