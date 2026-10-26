package cn.eririlily.launcher.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import cn.eririlily.launcher.game.Ui.dp
import cn.eririlily.launcher.game.Ui.dpi

/** The in-game Control Centre: everything adjustable while playing. */
@SuppressLint("ViewConstructor")
class ControlCenter(ctx: Context, private val a: Actions) : FrameLayout(ctx) {
    interface Actions {
        val settings: GameSettings
        val title: String
        val subtitle: String
        fun setRenderScale(pct: Int, commit: Boolean)
        fun setFpsLimit(fps: Int)
        fun applyPerf()
        fun hudChanged()
        fun controlsChanged()
        fun keyboard()
        fun editControls()
        fun key(code: Int)
        fun superOptimize(): String
        fun exitGame()
    }

    private val panel = LinearLayout(ctx)
    private lateinit var scaleSlider: IosSlider
    private lateinit var fpsSlider: IosSlider
    private val fpsSteps = intArrayOf(30, 45, 60, 90, 120, 144, 0)
    private val live = Ui.text(ctx, "", 13f, Ui.SECONDARY)
    private val tiles = mutableListOf<() -> Unit>() // refreshers

    var open = false
        private set

    init {
        visibility = GONE
        setBackgroundColor(0x66000000)
        setOnClickListener { hide() }
        panel.orientation = LinearLayout.HORIZONTAL
        panel.background = Ui.rounded(Ui.PANEL, ctx.dp(28f))
        panel.setPadding(ctx.dpi(18f), ctx.dpi(16f), ctx.dpi(18f), ctx.dpi(16f))
        panel.isClickable = true // keep taps inside the panel
        panel.elevation = ctx.dp(12f)

        val left = Ui.col(ctx)
        val right = Ui.col(ctx)
        panel.addView(left, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f))
        panel.addView(View(ctx), Ui.lp(ctx.dpi(16f), 1))
        panel.addView(right, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // header
        left.addView(Ui.text(ctx, a.title, 19f, bold = true))
        left.addView(Ui.text(ctx, a.subtitle, 12.5f, Ui.SECONDARY).apply { setPadding(0, ctx.dpi(4f), 0, 0) })
        left.addView(live.apply { setPadding(0, ctx.dpi(4f), 0, ctx.dpi(12f)) })

        val s = a.settings
        scaleSlider = IosSlider(ctx, 25, 100, s.renderScale, 5, { "$it%" }) { v, commit -> a.setRenderScale(v, commit) }.apply { title = "渲染倍率" }
        left.addView(scaleSlider, gap())
        val fpsIdx = fpsSteps.indexOf(s.fpsLimit).let { if (it < 0) 6 else it }
        fpsSlider = IosSlider(ctx, 0, 6, fpsIdx, 1, { if (fpsSteps[it] == 0) "不限" else "${fpsSteps[it]}" }) { v, commit ->
            if (commit) a.setFpsLimit(fpsSteps[v])
        }.apply { title = "帧率上限" }
        left.addView(fpsSlider, gap())
        left.addView(IosSlider(ctx, 25, 300, s.mouseSpeed, 5, { "$it%" }) { v, commit ->
            s.mouseSpeed = v; if (commit) s.save()
        }.apply { title = "视角灵敏度" }, gap())
        left.addView(IosSlider(ctx, 10, 100, s.controlsOpacity, 5, { "$it%" }) { v, commit ->
            s.controlsOpacity = v; a.controlsChanged(); if (commit) s.save()
        }.apply { title = "按键不透明度" }, gap())
        if (s.gyroAim) left.addView(IosSlider(ctx, 25, 300, s.gyroSens, 5, { "$it%" }) { v, commit ->
            s.gyroSens = v; if (commit) s.save()
        }.apply { title = "陀螺仪灵敏度" }, gap())

        // tiles: 3 per row
        val grid = Ui.col(ctx)
        right.addView(grid)
        val items = listOf(
            tile("帧率显示", { mapOf("off" to "关", "fps" to "简洁", "full" to "完整")[s.perfHud] ?: "简洁" }, { s.perfHud != "off" }) {
                s.perfHud = when (s.perfHud) { "off" -> "fps"; "fps" -> "full"; else -> "off" }; s.save(); a.hudChanged()
            },
            tile("垂直同步", { if (s.vsync) "开" else "关" }, { s.vsync }) { s.vsync = !s.vsync; s.save(); a.applyPerf() },
            tile("动态分辨率", { if (s.dynamicRes) "开" else "关" }, { s.dynamicRes }) { s.dynamicRes = !s.dynamicRes; s.save(); a.applyPerf() },
            tile("陀螺仪瞄准", { if (s.gyroAim) "开" else "关" }, { s.gyroAim }) { s.gyroAim = !s.gyroAim; s.save(); a.applyPerf() },
            tile("大核调度", { if (s.bigCores) "开" else "关" }, { s.bigCores }) { s.bigCores = !s.bigCores; s.save(); a.applyPerf() },
            tile("按键震动", { if (s.haptics) "开" else "关" }, { s.haptics }) { s.haptics = !s.haptics; s.save() },
            tile("键盘", { "输入文字" }, { false }) { hide(); a.keyboard() },
            tile("编辑键位", { s.controlsLayout }, { false }) { hide(); a.editControls() },
            tile("截图", { "F2" }, { false }) { hide(); postDelayed({ a.key(Keys.F1 + 1) }, 250) },
            tile("调试信息", { "F3" }, { false }) { a.key(Keys.F1 + 2) },
            tile("一键超级优化", { "按机型调校" }, { false }) {
                val msg = a.superOptimize()
                scaleSlider.set(s.renderScale)
                fpsSlider.set(fpsSteps.indexOf(s.fpsLimit).let { if (it < 0) 6 else it })
                live.text = msg
                refreshTiles()
            },
            tile("退出游戏", { "保存并退出" }, { false }, danger = true) { hide(); a.exitGame() },
        )
        items.chunked(3).forEach { rowItems ->
            val r = Ui.row(ctx)
            rowItems.forEachIndexed { i, v ->
                r.addView(v, Ui.lp(0, ctx.dpi(66f), 1f).apply { if (i > 0) leftMargin = ctx.dpi(8f) })
            }
            repeat(3 - rowItems.size) { r.addView(View(ctx), Ui.lp(0, 1, 1f)) }
            grid.addView(r, Ui.lp().apply { bottomMargin = ctx.dpi(8f) })
        }

        val scroll = ScrollView(ctx)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(panel)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            val m = ctx.dpi(14f)
            setMargins(ctx.dpi(48f), m, ctx.dpi(48f), m)
        })
    }

    private fun gap() = Ui.lp().apply { bottomMargin = context.dpi(10f) }

    private fun tile(name: String, sub: () -> String, on: () -> Boolean, danger: Boolean = false, click: () -> Unit): View {
        val ctx = context
        val box = Ui.col(ctx)
        box.gravity = Gravity.CENTER_VERTICAL
        box.setPadding(ctx.dpi(12f), ctx.dpi(8f), ctx.dpi(8f), ctx.dpi(8f))
        val t = Ui.text(ctx, name, 13.5f, bold = true)
        val st: TextView = Ui.text(ctx, "", 11.5f, Ui.SECONDARY).apply { setPadding(0, ctx.dpi(3f), 0, 0) }
        box.addView(t)
        box.addView(st)
        val refresh = {
            val active = on()
            box.background = Ui.rounded(if (danger) 0x40FF453A else if (active) Color.WHITE else Ui.TILE, ctx.dp(18f))
            t.setTextColor(if (danger) Ui.RED else if (active) 0xFF1C1C1E.toInt() else Color.WHITE)
            st.setTextColor(if (active && !danger) 0x991C1C1E.toInt() else Ui.SECONDARY)
            st.text = sub()
        }
        refresh()
        tiles += refresh
        Ui.pressable(box) { click(); refresh() }
        return box
    }

    private fun refreshTiles() = tiles.forEach { it() }

    fun updateLive(fps: Float, w: Int, h: Int) {
        if (open) live.text = "实时 ${fps.toInt()} FPS · 渲染 ${w}×${h}"
    }

    fun show() {
        if (open) return
        open = true
        refreshTiles()
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(160).start()
        panel.translationY = -context.dp(40f)
        panel.scaleX = .96f; panel.scaleY = .96f
        panel.animate().translationY(0f).scaleX(1f).scaleY(1f).setInterpolator(Ui.spring).setDuration(380).start()
    }

    fun hide() {
        if (!open) return
        open = false
        animate().alpha(0f).setDuration(160).withEndAction { visibility = GONE }.start()
        panel.animate().translationY(-context.dp(30f)).setDuration(160).start()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        super.onTouchEvent(event)
        return true // nothing reaches the game while the panel is open
    }
}
