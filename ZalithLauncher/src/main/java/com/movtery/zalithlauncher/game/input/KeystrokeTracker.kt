/*
 * EririLily (unofficial modification of Zalith Launcher 2)
 * Copyright (C) 2026 D0cK and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * EririLily: remembers which movement keys and mouse buttons are held, and how fast
 * the mouse buttons are clicked, for the in-game keystrokes overlay.
 * Fed from CallbackBridge, i.e. every input that reaches the game (touch controls,
 * keyboard, mouse, gamepad mappings) is seen here.
 */
object KeystrokeTracker {
    // GLFW key codes
    private const val KEY_W = 87
    private const val KEY_A = 65
    private const val KEY_S = 83
    private const val KEY_D = 68
    private const val KEY_SPACE = 32
    private const val KEY_LEFT_SHIFT = 340
    private const val KEY_LEFT_CONTROL = 341

    var w by mutableStateOf(false)
        private set
    var a by mutableStateOf(false)
        private set
    var s by mutableStateOf(false)
        private set
    var d by mutableStateOf(false)
        private set
    var space by mutableStateOf(false)
        private set
    var shift by mutableStateOf(false)
        private set
    var ctrl by mutableStateOf(false)
        private set
    var lmb by mutableStateOf(false)
        private set
    var rmb by mutableStateOf(false)
        private set

    private val leftClicks = ConcurrentLinkedDeque<Long>()
    private val rightClicks = ConcurrentLinkedDeque<Long>()

    @JvmStatic
    fun onKey(key: Int, down: Boolean) {
        when (key) {
            KEY_W -> if (w != down) w = down
            KEY_A -> if (a != down) a = down
            KEY_S -> if (s != down) s = down
            KEY_D -> if (d != down) d = down
            KEY_SPACE -> if (space != down) space = down
            KEY_LEFT_SHIFT -> if (shift != down) shift = down
            KEY_LEFT_CONTROL -> if (ctrl != down) ctrl = down
        }
    }

    @JvmStatic
    fun onMouse(button: Int, down: Boolean) {
        when (button) {
            0 -> {
                if (down && !lmb) leftClicks.addLast(System.currentTimeMillis())
                if (lmb != down) lmb = down
            }
            1 -> {
                if (down && !rmb) rightClicks.addLast(System.currentTimeMillis())
                if (rmb != down) rmb = down
            }
        }
    }

    /** Clicks in the last second (called by the overlay a few times per second). */
    fun cps(left: Boolean): Int {
        val q = if (left) leftClicks else rightClicks
        val cut = System.currentTimeMillis() - 1000
        while (true) {
            val first = q.peekFirst() ?: break
            if (first < cut) q.pollFirst() else break
        }
        return q.size
    }

    /** Clears held keys, e.g. when the game window loses focus. */
    fun reset() {
        w = false; a = false; s = false; d = false
        space = false; shift = false; ctrl = false; lmb = false; rmb = false
        leftClicks.clear(); rightClicks.clear()
    }
}
