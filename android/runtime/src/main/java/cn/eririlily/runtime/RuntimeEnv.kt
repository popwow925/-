/*
 * EririLily game runtime — process-wide state shared with the native layer.
 */
package cn.eririlily.runtime

import android.app.Activity
import android.content.Context

object RuntimeEnv {
    /** The game activity while it is showing (the native layer opens links / clipboard through it). */
    @JvmStatic
    var activity: Activity? = null

    @JvmStatic
    lateinit var app: Context
        private set

    /** Show the Android IME automatically when an SDL game asks for text input. */
    @JvmStatic
    var sdlAutoShowIme: Boolean = true

    /** Called when the JVM exits (code, killed-by-signal). */
    var onExit: ((Int, Boolean) -> Unit)? = null

    /** Called when the game asks to open a local folder (e.g. the screenshots link). */
    var onOpenPath: ((String) -> Unit)? = null

    @JvmStatic
    fun init(context: Context) {
        app = context.applicationContext
    }

    @JvmStatic
    fun getAppContext(): Context = activity ?: app
}
