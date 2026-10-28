/*
 * EririLily (unofficial modification of Zalith Launcher 2), GPL-3.0.
 */
package com.movtery.zalithlauncher.game.renderer

import android.annotation.SuppressLint
import android.os.Build

/**
 * EririLily: rough GPU family of this phone, read from system properties. Used only to hide
 * renderers that cannot work on this GPU (Freedreno is Adreno-only, Panfrost is Mali-only).
 * When we are not sure, we say UNKNOWN and hide nothing.
 */
object GpuInfo {
    enum class Family { ADRENO, MALI, OTHER, UNKNOWN }

    val family: Family by lazy { detect() }

    @SuppressLint("PrivateApi")
    private fun prop(name: String): String = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java).invoke(null, name) as? String
    }.getOrNull().orEmpty().lowercase()

    private fun detect(): Family {
        val egl = prop("ro.hardware.egl")
        val vk = prop("ro.hardware.vulkan")
        val hints = "$egl $vk"
        return when {
            "adreno" in hints -> Family.ADRENO
            "mali" in hints -> Family.MALI
            egl.isNotBlank() || vk.isNotBlank() -> Family.OTHER
            Build.VERSION.SDK_INT >= 31 && Build.SOC_MANUFACTURER.equals("QTI", true) -> Family.ADRENO
            else -> Family.UNKNOWN
        }
    }

    /** Should a renderer that only targets [only] be offered on this phone? */
    fun allows(only: Family): Boolean = family == Family.UNKNOWN || family == only
}
