package cn.eririlily.launcher.game

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import cn.eririlily.launcher.SystemBars
import org.json.JSONObject
import java.io.File

/** Edit a control layout from the launcher, over a stand-in game screen. */
class ControlsEditorActivity : Activity(), ControlsView.Host {
    override lateinit var settings: GameSettings
    private lateinit var controls: ControlsView
    private lateinit var editor: LayoutEditor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = File(filesDir, "EririLily")
        val saved = runCatching { JSONObject(File(data, "game_settings.json").readText()) }.getOrDefault(JSONObject())
        settings = GameSettings(data, saved)
        val name = intent.getStringExtra("layout")?.ifEmpty { null } ?: settings.controlsLayout
        SystemBars.hide(window)
        val root = FrameLayout(this)
        root.addView(Backdrop(this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        controls = ControlsView(this, ControlLayout.load(File(data, "controls"), name), this)
        controls.grabbing = true
        controls.edit = true
        controls.previewAll = true
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        editor = LayoutEditor(this, controls, File(data, "controls"), name) { savedName ->
            settings.controlsLayout = savedName
            settings.save()
            Toast.makeText(this, "已保存「$savedName」", Toast.LENGTH_SHORT).show()
            finish()
        }
        root.addView(editor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    override fun special(code: Int, down: Boolean) {}
    override fun gameScale() = 1f
    override fun guiScale() = settings.guiScale
    override fun onSelect(b: ControlButton?) = editor.select(b)

    /** sky, grass and a hotbar, so positions can be judged */
    private class Backdrop(ctx: android.content.Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            val w = width.toFloat()
            p.shader = LinearGradient(0f, 0f, 0f, h * .62f, 0xFF7FB2FF.toInt(), 0xFFBFD9FF.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h * .62f, p)
            p.shader = null
            p.color = 0xFF5D9B3A.toInt()
            c.drawRect(0f, h * .62f, w, h, p)
            p.color = 0xFF7A5230.toInt()
            c.drawRect(0f, h * .8f, w, h, p)
            val d = resources.displayMetrics.density
            val slot = 20 * d
            val left = (w - slot * 9) / 2
            p.color = Color.argb(150, 0, 0, 0)
            c.drawRect(left, h - slot - 2 * d, left + slot * 9, h - 2 * d, p)
            p.style = Paint.Style.STROKE
            p.color = Color.argb(160, 255, 255, 255)
            for (i in 0 until 9) c.drawRect(left + i * slot, h - slot - 2 * d, left + (i + 1) * slot, h - 2 * d, p)
            p.style = Paint.Style.FILL
        }
    }
}
