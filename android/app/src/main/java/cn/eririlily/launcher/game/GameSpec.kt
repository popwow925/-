package cn.eririlily.launcher.game

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The launch spec the core writes (Go: mc.AndroidSpec). */
class GameSpec(val file: File, o: JSONObject) {
    val id = o.optString("id")
    val name = o.optString("name", id)
    val game = o.optString("game")
    val loader = o.optString("loader")
    val runDir = o.optString("runDir")
    val javaHome = o.optString("javaHome")
    val javaMajor = o.optInt("javaMajor")
    val libJvm = o.optString("libJvm")
    val jvmArgs = strings(o.optJSONArray("jvmArgs"))
    val classpath = strings(o.optJSONArray("classpath"))
    val mainClass = o.optString("mainClass")
    val gameArgs = strings(o.optJSONArray("gameArgs"))
    val libPath = strings(o.optJSONArray("libPath"))
    val logFile = o.optString("logFile")
    val exitFile = o.optString("exitFile")
    val pidFile = o.optString("pidFile")
    val account = o.optString("account")
    val renderer: JSONObject = o.optJSONObject("renderer") ?: JSONObject()
    val env: Map<String, String> = o.optJSONObject("env")?.let { e -> e.keys().asSequence().associateWith { e.optString(it) } } ?: emptyMap()
    val settings: JSONObject = o.optJSONObject("settings") ?: JSONObject()

    val rendererName: String get() = renderer.optString("name", "?")
    val rendererLib: String get() = renderer.optString("lib")
    val gles: Int get() = renderer.optInt("gles", 3)

    companion object {
        fun load(path: String): GameSpec {
            val f = File(path)
            return GameSpec(f, JSONObject(f.readText()))
        }

        private fun strings(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.optString(i) } } ?: emptyList()
    }
}

/**
 * In-game settings. They start from the launcher config (inside the spec) and
 * changes made in the control centre are written to game_settings.json, which
 * the core merges back into its config.
 */
class GameSettings(private val dataDir: File, s: JSONObject) {
    var renderScale = s.optInt("renderScale", 100).coerceIn(25, 100)
    var fpsLimit = s.optInt("fpsLimit", 0)
    var vsync = s.optBoolean("vsync", false)
    var perfHud = s.optString("perfHud", "fps").ifEmpty { "fps" }
    var keyOverlay = s.optBoolean("keyOverlay", true)
    val theme: String = s.optString("theme", "sakura").ifEmpty { "sakura" }
    var sustainedPerf = s.optBoolean("sustainedPerf", true)
    var bigCores = s.optBoolean("bigCores", true)
    var perfHint = s.optBoolean("perfHint", true)
    var gyroAim = s.optBoolean("gyroAim", false)
    var gyroSens = s.optInt("gyroSens", 100)
    var mouseSpeed = s.optInt("mouseSpeed", 100)
    var controlsOpacity = s.optInt("controlsOpacity", 70)
    var controlsLayout = s.optString("controlsLayout", "default").ifEmpty { "default" }
    var haptics = s.optBoolean("haptics", true)
    var guiScale = s.optInt("guiScale", 0)
    var dynamicRes = s.optBoolean("dynamicRes", false)

    fun save() {
        val o = JSONObject()
            .put("renderScale", renderScale).put("fpsLimit", fpsLimit).put("vsync", vsync).put("perfHud", perfHud).put("keyOverlay", keyOverlay)
            .put("sustainedPerf", sustainedPerf).put("bigCores", bigCores).put("perfHint", perfHint)
            .put("gyroAim", gyroAim).put("gyroSens", gyroSens).put("mouseSpeed", mouseSpeed)
            .put("controlsOpacity", controlsOpacity).put("controlsLayout", controlsLayout).put("haptics", haptics)
            .put("time", System.currentTimeMillis())
        runCatching {
            val f = File(dataDir, "game_settings.json")
            val tmp = File(dataDir, "game_settings.json.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        }
    }
}
