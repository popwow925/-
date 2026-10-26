package cn.eririlily.launcher.game

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/** An invisible view that turns the soft keyboard (including CJK IMEs) into GLFW char/key events. */
class KeyboardInput(ctx: Context) : View(ctx) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(out: EditorInfo): InputConnection {
        out.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        out.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                text.toString().codePoints().forEach { cp ->
                    if (cp == '\n'.code) Bridge.tapKey(Keys.ENTER) else Bridge.sendChar(cp, 0)
                }
                return true
            }

            override fun deleteSurroundingText(before: Int, after: Int): Boolean {
                repeat(maxOf(1, before)) { Bridge.tapKey(Keys.BACKSPACE) }
                return true
            }

            override fun sendKeyEvent(e: KeyEvent): Boolean {
                val k = Keys.fromAndroid(e.keyCode)
                if (k != 0) Bridge.sendKey(k, 0, if (e.action == KeyEvent.ACTION_DOWN) Bridge.PRESS else Bridge.RELEASE, Keys.mods(e))
                if (e.action == KeyEvent.ACTION_DOWN && e.unicodeChar != 0 && k != Keys.ENTER && k != Keys.BACKSPACE) Bridge.sendChar(e.unicodeChar, 0)
                return true
            }

            override fun performEditorAction(action: Int): Boolean {
                Bridge.tapKey(Keys.ENTER)
                return true
            }
        }
    }

    fun toggle() {
        val imm = context.getSystemService(InputMethodManager::class.java)!!
        requestFocus()
        if (!imm.isActive(this) || !shown) {
            imm.showSoftInput(this, InputMethodManager.SHOW_FORCED)
            shown = true
        } else {
            imm.hideSoftInputFromWindow(windowToken, 0)
            shown = false
        }
    }

    private var shown = false
}
