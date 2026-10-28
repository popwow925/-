/*
 * The native runtime can route Minecraft's narrator to a small TTS engine. EririLily does not
 * ship it; these entry points just report "unavailable" so the narrator stays silent.
 */
package com.movtery.zalithlauncher.bridge

import androidx.annotation.Keep

@Keep
object FliteTts {
    @Keep @JvmStatic fun init(): Boolean = false
    @Keep @JvmStatic fun speak(pcm: ByteArray, rate: Float): Float = 0f
    @Keep @JvmStatic fun cancel() {}
}
