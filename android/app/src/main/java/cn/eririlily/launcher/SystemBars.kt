package cn.eririlily.launcher

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController

/** Edge-to-edge and system bar helpers on the framework API (no AndroidX needed). */
object SystemBars {
    fun edgeToEdge(w: Window) {
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = w.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    /** Immersive: bars hidden, a swipe shows them briefly. */
    fun hide(w: Window) {
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
            w.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    /** Dark icons on light content (iOS light mode) or the reverse. */
    fun darkIcons(w: Window, dark: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            w.insetsController?.setSystemBarsAppearance(if (dark) mask else 0, mask)
        } else {
            @Suppress("DEPRECATION")
            var f = w.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            val mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            f = if (dark) f or mask else f and mask.inv()
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = f
        }
    }

    /** top, right, bottom, left in pixels: system bars plus the display cutout. */
    fun safeArea(ins: WindowInsets): IntArray {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return intArrayOf(i.top, i.right, i.bottom, i.left)
        }
        val c = if (Build.VERSION.SDK_INT >= 28) ins.displayCutout else null
        @Suppress("DEPRECATION")
        return intArrayOf(
            maxOf(ins.systemWindowInsetTop, c?.safeInsetTop ?: 0), maxOf(ins.systemWindowInsetRight, c?.safeInsetRight ?: 0),
            maxOf(ins.systemWindowInsetBottom, c?.safeInsetBottom ?: 0), maxOf(ins.systemWindowInsetLeft, c?.safeInsetLeft ?: 0),
        )
    }
}
