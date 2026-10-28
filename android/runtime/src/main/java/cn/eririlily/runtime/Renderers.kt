/*
 * EririLily game runtime — the renderers. The libraries themselves come from Zalith Launcher 2
 * (Krypton Wrapper / NG-GL4ES, GL4ES, Mesa Zink / Freedreno / Panfrost / VirGL); this table is
 * what the launcher needs to pick and configure one.
 */
package cn.eririlily.runtime

import android.annotation.SuppressLint

data class Renderer(
    val key: String,          // EririLily's own id (stored in settings)
    val name: String,
    val rendererId: String,   // POJAV_RENDERER for the native layer
    val lib: String,          // OpenGL library LWJGL loads
    val egl: String? = null,  // EGL override (Zink)
    val env: Map<String, String> = emptyMap(),
    val maxGame: String? = null,  // highest supported Minecraft version (inclusive), null = any
    val gpu: Gpu = Gpu.ANY,
) {
    enum class Gpu { ANY, ADRENO, MALI }
    val isGl4es get() = rendererId.startsWith("opengles")
}

object Renderers {
    val all = listOf(
        Renderer("krypton", "Krypton Wrapper", "opengles3", "libng_gl4es.so",
            env = mapOf("LIBGL_USE_MC_COLOR" to "1", "LIBGL_GL" to "31", "LIBGL_ES" to "3", "LIBGL_NORMALIZE" to "1", "LIBGL_NOERROR" to "1")),
        Renderer("gl4es", "GL4ES", "opengles2", "libgl4es_114.so", maxGame = "1.21.4",
            env = mapOf("LIBGL_ES" to "2", "LIBGL_MIPMAP" to "3", "LIBGL_NOERROR" to "1", "LIBGL_NOINTOVLHACK" to "1", "LIBGL_NORMALIZE" to "1")),
        Renderer("zink", "Kopper Zink", "opengles3_desktopgl_zink_kopper", "libglxshim.so", egl = "libEGL_mesa.so",
            env = mapOf("LIBGL_ES" to "3")),
        Renderer("virgl", "VirGL", "gallium_virgl", "libOSMesa_2121.so",
            env = mapOf("VTEST_SOCKET_NAME" to "{cache}/.virgl_test")),
        Renderer("freedreno", "Freedreno (Adreno)", "gallium_freedreno", "libOSMesa_8.so", gpu = Renderer.Gpu.ADRENO),
        Renderer("panfrost", "Panfrost (Mali)", "gallium_panfrost", "libOSMesa_2300d.so", maxGame = "1.21.4", gpu = Renderer.Gpu.MALI),
    )

    fun byKey(key: String?): Renderer = all.firstOrNull { it.key == key } ?: all.first()

    /** Renderers that make sense on this phone (GPU-specific Mesa drivers only on their GPU). */
    fun available(): List<Renderer> = all.filter { it.gpu == Renderer.Gpu.ANY || it.gpu == gpu }

    val gpu: Renderer.Gpu by lazy {
        val hints = (prop("ro.hardware.egl") + " " + prop("ro.hardware.vulkan")).lowercase()
        when {
            "adreno" in hints -> Renderer.Gpu.ADRENO
            "mali" in hints -> Renderer.Gpu.MALI
            else -> Renderer.Gpu.ANY
        }
    }

    @SuppressLint("PrivateApi")
    private fun prop(name: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrDefault("")
}
