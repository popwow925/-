package cn.eririlily.launcher.game

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import cn.eririlily.launcher.game.Ui.dp
import cn.eririlily.launcher.game.Ui.dpi
import java.io.File

/** Toolbar + property sheet over a ControlsView in edit mode. */
@SuppressLint("ViewConstructor")
class LayoutEditor(
    ctx: Context, private val controls: ControlsView, private val dir: File, private var name: String,
    private val onDone: (saved: String) -> Unit,
) : FrameLayout(ctx) {
    private val sheet = ScrollView(ctx)
    private val sheetBody = Ui.col(ctx)
    private var current: ControlButton? = null

    init {
        // toolbar
        val bar = Ui.row(ctx).apply {
            background = Ui.rounded(Ui.PANEL, ctx.dp(22f))
            setPadding(ctx.dpi(6f), ctx.dpi(6f), ctx.dpi(6f), ctx.dpi(6f))
            elevation = ctx.dp(8f)
        }
        fun barBtn(label: String, color: Int = Color.WHITE, click: () -> Unit) {
            val t = Ui.text(ctx, label, 14f, color, bold = true).apply {
                setPadding(ctx.dpi(14f), ctx.dpi(9f), ctx.dpi(14f), ctx.dpi(9f))
                background = Ui.rounded(Ui.TILE, ctx.dp(16f))
            }
            Ui.pressable(t, click)
            bar.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.dpi(6f) })
        }
        barBtn("＋ 按键") {
            val b = ControlButton("新按键", mutableListOf(Keys.A), .5f, .5f, 52f)
            controls.layout.buttons += b
            controls.select(b); select(b); controls.invalidate()
        }
        barBtn("摇杆") { controls.layout.stick = !controls.layout.stick; controls.invalidate() }
        barBtn("预览全部") { controls.previewAll = !controls.previewAll; controls.invalidate() }
        barBtn("恢复默认", Ui.RED) {
            AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("恢复默认键位？").setMessage("当前布局「$name」会被默认布局替换。")
                .setPositiveButton("恢复") { _, _ ->
                    val def = ControlLayout.default()
                    controls.layout.buttons.clear(); controls.layout.buttons.addAll(def.buttons)
                    controls.layout.stick = true; controls.layout.stickX = def.stickX; controls.layout.stickY = def.stickY
                    select(null); controls.invalidate()
                }.setNegativeButton("取消", null).show()
        }
        barBtn("另存为") {
            val input = EditText(ctx).apply { setText(if (name == "default") "我的布局" else name); setSingleLine() }
            AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("布局名称").setView(input)
                .setPositiveButton("保存") { _, _ ->
                    val n = input.text.toString().trim().replace(Regex("[/\\\\.]"), "_").ifEmpty { "我的布局" }
                    name = n; save()
                }.setNegativeButton("取消", null).show()
        }
        barBtn("完成", Ui.BLUE) { save() }
        addView(bar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ctx.dpi(10f) })

        sheet.background = Ui.rounded(Ui.PANEL, ctx.dp(22f))
        sheet.elevation = ctx.dp(8f)
        sheet.visibility = GONE
        sheet.isVerticalScrollBarEnabled = false
        sheetBody.setPadding(ctx.dpi(16f), ctx.dpi(14f), ctx.dpi(16f), ctx.dpi(14f))
        sheet.addView(sheetBody)
        addView(sheet, LayoutParams(ctx.dpi(300f), LayoutParams.MATCH_PARENT, Gravity.END).apply {
            setMargins(0, ctx.dpi(64f), ctx.dpi(10f), ctx.dpi(10f))
        })
    }

    private fun save() {
        controls.layout.save(dir, name)
        onDone(name)
    }

    fun select(b: ControlButton?) {
        current = b
        sheetBody.removeAllViews()
        if (b == null) { sheet.visibility = GONE; return }
        sheet.visibility = VISIBLE
        val ctx = context
        fun label(s: String) = Ui.text(ctx, s, 12.5f, Ui.SECONDARY).apply { setPadding(0, ctx.dpi(10f), 0, ctx.dpi(6f)) }

        sheetBody.addView(Ui.text(ctx, "按键属性", 17f, bold = true))
        sheetBody.addView(label("显示名称"))
        sheetBody.addView(EditText(ctx).apply {
            setText(b.name); setSingleLine(); setTextColor(Color.WHITE); textSize = 15f
            background = Ui.rounded(Ui.TILE, ctx.dp(12f)); setPadding(ctx.dpi(12f), ctx.dpi(8f), ctx.dpi(12f), ctx.dpi(8f))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
                override fun afterTextChanged(s: Editable?) { b.name = s.toString(); controls.invalidate() }
            })
        })

        sheetBody.addView(label("映射（最多 3 个，同时按下）"))
        val keysRow = Ui.row(ctx)
        fun renderKeys() {
            keysRow.removeAllViews()
            b.keys.forEachIndexed { i, k ->
                val chip = Ui.text(ctx, Keys.label(k), 13f, bold = true).apply {
                    background = Ui.rounded(Ui.TILE, ctx.dp(10f)); setPadding(ctx.dpi(10f), ctx.dpi(7f), ctx.dpi(10f), ctx.dpi(7f))
                }
                Ui.pressable(chip) { pickKey { nk -> if (nk == null) b.keys.removeAt(i) else b.keys[i] = nk; if (b.keys.isEmpty()) b.keys += Keys.A; renderKeys(); controls.invalidate() } }
                keysRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.dpi(6f) })
            }
            if (b.keys.size < 3) {
                val add = Ui.text(ctx, "＋", 15f, Ui.BLUE, bold = true).apply { setPadding(ctx.dpi(12f), ctx.dpi(6f), ctx.dpi(12f), ctx.dpi(6f)) }
                Ui.pressable(add) { pickKey { nk -> if (nk != null) { b.keys += nk; renderKeys(); controls.invalidate() } } }
                keysRow.addView(add)
            }
        }
        renderKeys()
        sheetBody.addView(keysRow)

        sheetBody.addView(label("大小"))
        sheetBody.addView(IosSlider(ctx, 22, 120, b.size.toInt(), 2, { "${it}dp" }) { v, _ -> b.size = v.toFloat(); controls.invalidate() }.apply { title = "高度" })
        sheetBody.addView(View(ctx), Ui.lp(1, ctx.dpi(8f)))
        sheetBody.addView(IosSlider(ctx, 10, 50, (b.wide * 10).toInt(), 1, { "×${it / 10f}" }) { v, _ -> b.wide = v / 10f; controls.invalidate() }.apply { title = "宽高比" })

        fun switchRow(text: String, on: Boolean, f: (Boolean) -> Unit) {
            val r = Ui.row(ctx).apply { setPadding(0, ctx.dpi(10f), 0, 0) }
            r.addView(Ui.text(ctx, text, 15f), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(IosSwitch(ctx, on) { f(it); controls.invalidate() })
            sheetBody.addView(r)
        }
        switchRow("圆形", b.round) { b.round = it }
        switchRow("开关模式（按一下保持）", b.toggle) { b.toggle = it }
        switchRow("滑入即触发", b.swipe) { b.swipe = it }

        sheetBody.addView(label("显示时机"))
        val modes = listOf(ControlButton.SHOW_ALWAYS to "总是", ControlButton.SHOW_INGAME to "游戏中", ControlButton.SHOW_MENU to "菜单中")
        val seg = Ui.row(ctx).apply { background = Ui.rounded(Ui.TILE, ctx.dp(10f)); setPadding(ctx.dpi(2f), ctx.dpi(2f), ctx.dpi(2f), ctx.dpi(2f)) }
        fun renderSeg() {
            seg.removeAllViews()
            for ((v, t) in modes) {
                val on = b.show == v
                val item = Ui.text(ctx, t, 13f, if (on) 0xFF1C1C1E.toInt() else Color.WHITE, bold = on).apply {
                    gravity = Gravity.CENTER; setPadding(0, ctx.dpi(7f), 0, ctx.dpi(7f))
                    background = if (on) Ui.rounded(Color.WHITE, ctx.dp(8f)) else null
                }
                Ui.pressable(item) { b.show = v; renderSeg() }
                seg.addView(item, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        renderSeg()
        sheetBody.addView(seg)

        val actions = Ui.row(ctx).apply { setPadding(0, ctx.dpi(16f), 0, 0) }
        val dup = Ui.text(ctx, "复制", 15f, Ui.BLUE, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(0, ctx.dpi(10f), 0, ctx.dpi(10f)); background = Ui.rounded(Ui.TILE, ctx.dp(12f))
        }
        Ui.pressable(dup) { val c = b.copy().apply { x = (x + .06f).coerceAtMost(.97f) }; controls.layout.buttons += c; controls.select(c); select(c) }
        val del = Ui.text(ctx, "删除", 15f, Ui.RED, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(0, ctx.dpi(10f), 0, ctx.dpi(10f)); background = Ui.rounded(Ui.TILE, ctx.dp(12f))
        }
        Ui.pressable(del) { controls.layout.buttons.remove(b); controls.select(null); select(null) }
        actions.addView(dup, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ctx.dpi(8f) })
        actions.addView(del, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sheetBody.addView(actions)
    }

    private fun pickKey(done: (Int?) -> Unit) {
        val items = (Keys.choices.map { it.second } + "（移除）").toTypedArray()
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("选择按键")
            .setItems(items) { _, i -> done(if (i < Keys.choices.size) Keys.choices[i].first else null) }
            .show()
    }
}
