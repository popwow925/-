package cn.eririlily.launcher.game

import android.view.KeyEvent

/** GLFW key codes and the Android → GLFW mapping. */
object Keys {
    const val SPACE = 32
    const val APOSTROPHE = 39
    const val COMMA = 44
    const val MINUS = 45
    const val PERIOD = 46
    const val SLASH = 47
    const val N0 = 48
    const val SEMICOLON = 59
    const val EQUAL = 61
    const val A = 65
    const val LEFT_BRACKET = 91
    const val BACKSLASH = 92
    const val RIGHT_BRACKET = 93
    const val GRAVE = 96
    const val ESCAPE = 256
    const val ENTER = 257
    const val TAB = 258
    const val BACKSPACE = 259
    const val INSERT = 260
    const val DELETE = 261
    const val RIGHT = 262
    const val LEFT = 263
    const val DOWN = 264
    const val UP = 265
    const val PAGE_UP = 266
    const val PAGE_DOWN = 267
    const val HOME = 268
    const val END = 269
    const val CAPS_LOCK = 280
    const val SCROLL_LOCK = 281
    const val NUM_LOCK = 282
    const val PRINT_SCREEN = 283
    const val PAUSE = 284
    const val F1 = 290
    const val KP_0 = 320
    const val KP_DECIMAL = 330
    const val KP_DIVIDE = 331
    const val KP_MULTIPLY = 332
    const val KP_SUBTRACT = 333
    const val KP_ADD = 334
    const val KP_ENTER = 335
    const val KP_EQUAL = 336
    const val LEFT_SHIFT = 340
    const val LEFT_CONTROL = 341
    const val LEFT_ALT = 342
    const val LEFT_SUPER = 343
    const val RIGHT_SHIFT = 344
    const val RIGHT_CONTROL = 345
    const val RIGHT_ALT = 346
    const val RIGHT_SUPER = 347
    const val MENU = 348

    const val MOD_SHIFT = 1
    const val MOD_CONTROL = 2
    const val MOD_ALT = 4
    const val MOD_SUPER = 8
    const val MOD_CAPS = 16
    const val MOD_NUM = 32

    // special actions used by on-screen buttons (negative so they never clash with keys)
    const val MOUSE_LEFT = -1
    const val MOUSE_RIGHT = -2
    const val MOUSE_MIDDLE = -3
    const val SCROLL_UP = -4
    const val SCROLL_DOWN = -5
    const val KEYBOARD = -10
    const val CONTROL_CENTER = -11
    const val TOGGLE_CONTROLS = -12
    const val TOGGLE_HUD = -13

    fun fromAndroid(code: Int): Int = when (code) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> A + (code - KeyEvent.KEYCODE_A)
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> N0 + (code - KeyEvent.KEYCODE_0)
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> F1 + (code - KeyEvent.KEYCODE_F1)
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> KP_0 + (code - KeyEvent.KEYCODE_NUMPAD_0)
        KeyEvent.KEYCODE_SPACE -> SPACE
        KeyEvent.KEYCODE_APOSTROPHE -> APOSTROPHE
        KeyEvent.KEYCODE_COMMA -> COMMA
        KeyEvent.KEYCODE_MINUS -> MINUS
        KeyEvent.KEYCODE_PERIOD -> PERIOD
        KeyEvent.KEYCODE_SLASH -> SLASH
        KeyEvent.KEYCODE_SEMICOLON -> SEMICOLON
        KeyEvent.KEYCODE_EQUALS -> EQUAL
        KeyEvent.KEYCODE_LEFT_BRACKET -> LEFT_BRACKET
        KeyEvent.KEYCODE_BACKSLASH -> BACKSLASH
        KeyEvent.KEYCODE_RIGHT_BRACKET -> RIGHT_BRACKET
        KeyEvent.KEYCODE_GRAVE -> GRAVE
        KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> ESCAPE
        KeyEvent.KEYCODE_ENTER -> ENTER
        KeyEvent.KEYCODE_TAB -> TAB
        KeyEvent.KEYCODE_DEL -> BACKSPACE
        KeyEvent.KEYCODE_INSERT -> INSERT
        KeyEvent.KEYCODE_FORWARD_DEL -> DELETE
        KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
        KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
        KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
        KeyEvent.KEYCODE_DPAD_UP -> UP
        KeyEvent.KEYCODE_PAGE_UP -> PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> PAGE_DOWN
        KeyEvent.KEYCODE_MOVE_HOME -> HOME
        KeyEvent.KEYCODE_MOVE_END -> END
        KeyEvent.KEYCODE_CAPS_LOCK -> CAPS_LOCK
        KeyEvent.KEYCODE_SCROLL_LOCK -> SCROLL_LOCK
        KeyEvent.KEYCODE_NUM_LOCK -> NUM_LOCK
        KeyEvent.KEYCODE_SYSRQ -> PRINT_SCREEN
        KeyEvent.KEYCODE_BREAK -> PAUSE
        KeyEvent.KEYCODE_NUMPAD_DOT -> KP_DECIMAL
        KeyEvent.KEYCODE_NUMPAD_DIVIDE -> KP_DIVIDE
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> KP_MULTIPLY
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> KP_SUBTRACT
        KeyEvent.KEYCODE_NUMPAD_ADD -> KP_ADD
        KeyEvent.KEYCODE_NUMPAD_ENTER -> KP_ENTER
        KeyEvent.KEYCODE_NUMPAD_EQUALS -> KP_EQUAL
        KeyEvent.KEYCODE_SHIFT_LEFT -> LEFT_SHIFT
        KeyEvent.KEYCODE_SHIFT_RIGHT -> RIGHT_SHIFT
        KeyEvent.KEYCODE_CTRL_LEFT -> LEFT_CONTROL
        KeyEvent.KEYCODE_CTRL_RIGHT -> RIGHT_CONTROL
        KeyEvent.KEYCODE_ALT_LEFT -> LEFT_ALT
        KeyEvent.KEYCODE_ALT_RIGHT -> RIGHT_ALT
        KeyEvent.KEYCODE_META_LEFT -> LEFT_SUPER
        KeyEvent.KEYCODE_META_RIGHT -> RIGHT_SUPER
        KeyEvent.KEYCODE_MENU -> MENU
        else -> 0
    }

    fun mods(e: KeyEvent): Int {
        var m = 0
        if (e.isShiftPressed) m = m or MOD_SHIFT
        if (e.isCtrlPressed) m = m or MOD_CONTROL
        if (e.isAltPressed) m = m or MOD_ALT
        if (e.isMetaPressed) m = m or MOD_SUPER
        if (e.isCapsLockOn) m = m or MOD_CAPS
        if (e.isNumLockOn) m = m or MOD_NUM
        return m
    }

    /** Keys offered in the layout editor, with short labels. */
    val choices: List<Pair<Int, String>> by lazy {
        val l = mutableListOf(
            MOUSE_LEFT to "鼠标左键", MOUSE_RIGHT to "鼠标右键", MOUSE_MIDDLE to "鼠标中键",
            SCROLL_UP to "滚轮上", SCROLL_DOWN to "滚轮下",
            KEYBOARD to "软键盘", CONTROL_CENTER to "控制中心", TOGGLE_CONTROLS to "隐藏键位", TOGGLE_HUD to "帧率显示",
            ESCAPE to "Esc", TAB to "Tab", ENTER to "Enter", BACKSPACE to "Backspace", SPACE to "空格",
            LEFT_SHIFT to "左 Shift", LEFT_CONTROL to "左 Ctrl", LEFT_ALT to "左 Alt",
            RIGHT_SHIFT to "右 Shift", RIGHT_CONTROL to "右 Ctrl", RIGHT_ALT to "右 Alt",
            UP to "↑", DOWN to "↓", LEFT to "←", RIGHT to "→",
        )
        for (i in 0 until 26) l += (A + i) to ('A' + i).toString()
        for (i in 0..9) l += (N0 + i) to i.toString()
        for (i in 0 until 12) l += (F1 + i) to "F${i + 1}"
        l += listOf(GRAVE to "`", MINUS to "-", EQUAL to "=", LEFT_BRACKET to "[", RIGHT_BRACKET to "]",
            SEMICOLON to ";", APOSTROPHE to "'", COMMA to ",", PERIOD to ".", SLASH to "/", BACKSLASH to "\\")
        l
    }

    fun label(code: Int): String = choices.firstOrNull { it.first == code }?.second ?: "#$code"
}
