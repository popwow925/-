/*
 * EririLily: which keys/buttons the game currently sees as held, plus click timestamps for CPS.
 * Fed from CallbackBridge; read by the in-game keystrokes overlay.
 */
package com.movtery.zalithlauncher.game.input

object KeystrokeTracker {
    @Volatile @JvmField var w = false
    @Volatile @JvmField var a = false
    @Volatile @JvmField var s = false
    @Volatile @JvmField var d = false
    @Volatile @JvmField var space = false
    @Volatile @JvmField var shift = false
    @Volatile @JvmField var ctrl = false
    @Volatile @JvmField var lmb = false
    @Volatile @JvmField var rmb = false

    private val left = ArrayDeque<Long>()
    private val right = ArrayDeque<Long>()

    @JvmStatic
    fun onKey(key: Int, down: Boolean) {
        when (key) {
            87 -> w = down
            65 -> a = down
            83 -> s = down
            68 -> d = down
            32 -> space = down
            340, 344 -> shift = down
            341, 345 -> ctrl = down
        }
    }

    @JvmStatic
    fun onMouse(button: Int, down: Boolean) {
        when (button) {
            0 -> { lmb = down; if (down) synchronized(left) { left.addLast(System.currentTimeMillis()) } }
            1 -> { rmb = down; if (down) synchronized(right) { right.addLast(System.currentTimeMillis()) } }
        }
    }

    /** clicks in the last second */
    fun cps(isLeft: Boolean): Int {
        val q = if (isLeft) left else right
        val now = System.currentTimeMillis()
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first() > 1000) q.removeFirst()
            return q.size
        }
    }

    fun reset() {
        w = false; a = false; s = false; d = false; space = false; shift = false; ctrl = false; lmb = false; rmb = false
        synchronized(left) { left.clear() }
        synchronized(right) { right.clear() }
    }
}
