package cn.eririlily.launcher.game

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One on-screen button. Position is the centre as a fraction of the screen; size in dp. */
class ControlButton(
    var name: String,
    var keys: MutableList<Int>,
    var x: Float,
    var y: Float,
    var size: Float = 56f,
    var wide: Float = 1f,          // width / height
    var round: Boolean = true,     // circle (or rounded rectangle)
    var toggle: Boolean = false,   // press once to hold, again to release
    var show: String = SHOW_ALWAYS,
    var swipe: Boolean = false,    // fingers sliding over it press it too (hotbar-style rows)
) {
    // runtime state
    var down = false
    var pointer = -1

    fun json(): JSONObject = JSONObject().put("name", name).put("keys", JSONArray(keys)).put("x", x.toDouble()).put("y", y.toDouble())
        .put("size", size.toDouble()).put("wide", wide.toDouble()).put("round", round).put("toggle", toggle).put("show", show).put("swipe", swipe)

    fun copy() = ControlButton(name, keys.toMutableList(), x, y, size, wide, round, toggle, show, swipe)

    companion object {
        const val SHOW_ALWAYS = "always"
        const val SHOW_INGAME = "ingame"
        const val SHOW_MENU = "menu"

        fun from(o: JSONObject): ControlButton {
            val ks = o.optJSONArray("keys")
            return ControlButton(
                o.optString("name"), (0 until (ks?.length() ?: 0)).map { ks!!.optInt(it) }.toMutableList(),
                o.optDouble("x", .5).toFloat(), o.optDouble("y", .5).toFloat(), o.optDouble("size", 56.0).toFloat(),
                o.optDouble("wide", 1.0).toFloat(), o.optBoolean("round", true), o.optBoolean("toggle"),
                o.optString("show", SHOW_ALWAYS), o.optBoolean("swipe"),
            )
        }
    }
}

class ControlLayout(
    val buttons: MutableList<ControlButton>,
    var stickX: Float = .14f,
    var stickY: Float = .72f,
    var stickSize: Float = 150f, // dp
    var stick: Boolean = true,
) {
    fun json(): JSONObject = JSONObject().put("version", 1)
        .put("buttons", JSONArray(buttons.map { it.json() }))
        .put("stick", JSONObject().put("on", stick).put("x", stickX.toDouble()).put("y", stickY.toDouble()).put("size", stickSize.toDouble()))

    fun save(dir: File, name: String) {
        dir.mkdirs()
        File(dir, "$name.json").writeText(json().toString(1))
    }

    companion object {
        fun load(dir: File, name: String): ControlLayout = runCatching {
            val o = JSONObject(File(dir, "$name.json").readText())
            val bs = o.getJSONArray("buttons")
            val st = o.optJSONObject("stick") ?: JSONObject()
            ControlLayout(
                (0 until bs.length()).map { ControlButton.from(bs.getJSONObject(it)) }.toMutableList(),
                st.optDouble("x", .14).toFloat(), st.optDouble("y", .72).toFloat(), st.optDouble("size", 150.0).toFloat(), st.optBoolean("on", true),
            )
        }.getOrElse { default() }

        /** A layout close to Bedrock Edition's touch controls, so it feels familiar. */
        fun default(): ControlLayout {
            val I = ControlButton.SHOW_INGAME
            val b = mutableListOf(
                ControlButton("跳跃", mutableListOf(Keys.SPACE), .885f, .72f, 72f, show = I),
                ControlButton("潜行", mutableListOf(Keys.LEFT_SHIFT), .14f, .40f, 52f, toggle = true, show = I),
                ControlButton("疾跑", mutableListOf(Keys.LEFT_CONTROL), .24f, .40f, 46f, toggle = true, show = I),
                ControlButton("攻击", mutableListOf(Keys.MOUSE_LEFT), .79f, .52f, 56f, show = I),
                ControlButton("使用", mutableListOf(Keys.MOUSE_RIGHT), .885f, .45f, 56f, show = I),
                ControlButton("背包", mutableListOf(Keys.A + 4), .955f, .92f, 44f, show = I),          // E
                ControlButton("丢弃", mutableListOf(Keys.A + 16), .955f, .78f, 40f, show = I),         // Q
                ControlButton("副手", mutableListOf(Keys.A + 5), .79f, .88f, 40f, show = I),           // F
                ControlButton("聊天", mutableListOf(Keys.A + 19), .30f, .06f, 40f, wide = 1.4f, round = false),  // T
                ControlButton("视角", mutableListOf(Keys.F1 + 4), .38f, .06f, 40f, wide = 1.4f, round = false),  // F5
                ControlButton("玩家", mutableListOf(Keys.TAB), .46f, .06f, 40f, wide = 1.4f, round = false, show = I),
                ControlButton("⏸", mutableListOf(Keys.ESCAPE), .04f, .08f, 42f),
                ControlButton("⌨", mutableListOf(Keys.KEYBOARD), .11f, .08f, 42f),
                ControlButton("控制中心", mutableListOf(Keys.CONTROL_CENTER), .5f, .025f, 22f, wide = 4.2f, round = false),
                ControlButton("滚轮↑", mutableListOf(Keys.SCROLL_UP), .955f, .30f, 38f, show = ControlButton.SHOW_MENU),
                ControlButton("滚轮↓", mutableListOf(Keys.SCROLL_DOWN), .955f, .44f, 38f, show = ControlButton.SHOW_MENU),
            )
            return ControlLayout(b)
        }
    }
}
