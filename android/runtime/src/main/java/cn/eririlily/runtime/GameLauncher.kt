/*
 * EririLily game runtime — starts Minecraft inside this process on Zalith Launcher 2's native
 * runtime (libpojavexec: in-process JVM, EGL/GLFW bridge) with one of its renderers.
 *
 * The launch sequence (library paths, environment, dlopen order, JVM properties, the mio
 * wrapper and patcher agent, Cacio AWT) follows Zalith's Launcher/GameLauncher/LaunchArgs so the
 * native layer sees exactly what it expects. Everything else — which game, which files, which
 * arguments — comes from EririLily's own core as a [LaunchSpec].
 */
package cn.eririlily.runtime

import android.content.Context
import android.os.Build
import android.system.Os
import com.movtery.zalithlauncher.bridge.LoggerBridge
import com.movtery.zalithlauncher.bridge.ZLBridge
import com.oracle.dalvik.VMLauncher
import org.json.JSONObject
import org.lwjgl.glfw.CallbackBridge
import java.io.File
import java.util.Locale
import java.util.TimeZone

/** What the core hands over: a desktop-style launch, minus anything LWJGL. */
data class LaunchSpec(
    val id: String,
    val gameVersion: String,
    val gameDir: String,       // .minecraft (libraries, assets, versions)
    val runDir: String,        // where the game runs (saves, mods, options.txt)
    val javaMajor: Int,        // what the version asks for
    val lwjgl: String,         // "3.3.3" or "3.4.1"
    val mainClass: String,
    val classpath: List<String>,  // libraries + client jar, LWJGL removed
    val jvmArgs: List<String>,    // version/loader JVM args, no -cp, no library path
    val gameArgs: List<String>,
    val modernArgs: Boolean,      // version json has "arguments" (1.13+)
    val renderer: String,
    val lwjgl2: Boolean = false,  // 1.12.2 and older: LWJGL 2 games run through the lwjglx bridge
    val ramMb: Int,
    val clientJar: String,
    val logFile: String,
    val extraJvm: List<String> = emptyList(),
) {
    companion object {
        fun parse(json: String): LaunchSpec {
            val o = JSONObject(json)
            fun list(k: String) = o.optJSONArray(k)?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            return LaunchSpec(
                id = o.getString("id"), gameVersion = o.optString("gameVersion"), gameDir = o.getString("gameDir"),
                runDir = o.getString("runDir"), javaMajor = o.optInt("javaMajor", 21), lwjgl = o.optString("lwjgl", "3.3.3"),
                mainClass = o.getString("mainClass"), classpath = list("classpath"), jvmArgs = list("jvmArgs"),
                gameArgs = list("gameArgs"), modernArgs = o.optBoolean("modernArgs", true), renderer = o.optString("rendererKey", o.optString("renderer")), lwjgl2 = o.optBoolean("lwjgl2"),
                ramMb = o.optInt("ramMb", 2048), clientJar = o.optString("clientJar"), logFile = o.optString("logFile"),
                extraJvm = list("extraJvm"),
            )
        }
    }
}

class GameLauncher(private val context: Context, private val spec: LaunchSpec) {
    private val paths = RuntimePaths(context)
    private val javaMajor = RuntimeInstaller.pickJava(spec.javaMajor)
    // looked up lazily: on the first launch the runtime is only unpacked by prepare()
    private val java by lazy { JavaLayout(paths.jre(javaMajor)) }
    private val renderer = Renderers.byKey(spec.renderer)
    private val lwjglNatives = paths.lwjglNatives(spec.lwjgl).absolutePath
    private val nativeDir = paths.nativeLibDir

    /** Unpack anything missing. Call off the main thread before [launch]. */
    fun prepare(progress: (String) -> Unit = {}) =
        RuntimeInstaller.ensure(context, javaMajor, spec.lwjgl, progress)

    /**
     * Blocks until the JVM exits and returns its exit code. The surface must already be attached
     * with ZLBridge.setupBridgeWindow and the window size sent to CallbackBridge.
     */
    fun launch(width: Int, height: Int): Int {
        LoggerBridge.appendTitle("EririLily")
        LoggerBridge.appendInfo("Game: ${spec.id} (${spec.gameVersion})  Java $javaMajor  LWJGL ${spec.lwjgl}  Renderer ${renderer.name}")

        CallbackBridge.nativeSetUseInputStackQueue(spec.modernArgs)
        ZLBridge.setLdLibraryPath(runtimeLibraryPath())

        LoggerBridge.appendTitle("Env Map")
        env(width, height).forEach { (k, v) ->
            LoggerBridge.appendInfo("$k = $v")
            runCatching { Os.setenv(k, v, true) }
        }

        LoggerBridge.appendTitle("DLOPEN Java Runtime")
        dlopenJava()
        ZLBridge.dlopen("$nativeDir/libopenal.so")
        LoggerBridge.appendTitle("DLOPEN Renderer")
        if (!ZLBridge.dlopen(renderer.lib) && !ZLBridge.dlopen(findLib(renderer.lib))) {
            LoggerBridge.appendInfo("Failed to load renderer ${renderer.lib}")
        }

        val args = javaArgs(width, height)
        LoggerBridge.appendTitle("JVM Args")
        var hide = false
        args.forEach { a ->
            LoggerBridge.appendInfo(if (hide) "********" else a)
            hide = a == "--accessToken"
        }

        ZLBridge.setupExitMethod(context.applicationContext)
        ZLBridge.initializeGameExitHook()
        ZLBridge.chdir(spec.runDir)
        val code = VMLauncher.launchJVM(args.toTypedArray())
        LoggerBridge.append("Java Exit code: $code")
        return code
    }

    // ---------------------------------------------------------------- library paths

    private fun runtimeLibraryPath(): String = buildList {
        add(java.jliDir.absolutePath)
        add(java.jvmDir.absolutePath)
        if (java.isJdk8) add(java.libDir.absolutePath)
        add("/system/lib64"); add("/vendor/lib64"); add("/vendor/lib64/hw"); add("/system_ext/lib64")
        add(paths.jna.absolutePath)
        add(lwjglNatives)
        add(nativeDir)
    }.joinToString(":")

    /** LD_LIBRARY_PATH for the game: LWJGL natives first so the APK's copies never win. */
    private fun libraryPath(): String = listOf(
        lwjglNatives, "/system/lib64", "/vendor/lib64", "/vendor/lib64/hw", "/system_ext/lib64", nativeDir
    ).joinToString(":")

    private fun findLib(name: String): String =
        libraryPath().split(":").map { File(it, name) }.firstOrNull { it.isFile }?.absolutePath ?: name

    // ---------------------------------------------------------------- environment

    private fun env(width: Int, height: Int): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m["POJAV_NATIVEDIR"] = nativeDir
        m["JAVA_HOME"] = java.javaHome.absolutePath
        m["HOME"] = (context.getExternalFilesDir(null) ?: context.filesDir).absolutePath
        m["TMPDIR"] = paths.cache.absolutePath
        m["LD_LIBRARY_PATH"] = libraryPath()
        m["PATH"] = listOfNotNull("${java.home}/bin", Os.getenv("PATH")).joinToString(":")
        m["AWTSTUB_WIDTH"] = width.toString()
        m["AWTSTUB_HEIGHT"] = height.toString()
        m["DALVIK_JAVAVM"] = ZLBridge.getJavaVMPointer().toString()
        m["DALVIK_APPLICATION"] = ZLBridge.jObjectToString(context.applicationContext)
        m["ALSOFT_DRIVERS"] = "opensl"

        // renderer
        m["SDL_OPENGL_LIBRARY"] = renderer.rendererId
        renderer.env.forEach { (k, v) -> m[k] = v.replace("{cache}", paths.cache.absolutePath) }
        renderer.egl?.let { egl ->
            m["POJAVEXEC_EGL"] = egl
            File(nativeDir, egl).takeIf { it.isFile }?.let { m["SDL_EGL_LIBRARY"] = it.absolutePath }
        }
        m["POJAV_RENDERER"] = renderer.rendererId
        if (!renderer.isGl4es) {
            m["MESA_LOADER_DRIVER_OVERRIDE"] = "zink"
            m["MESA_GLSL_CACHE_DIR"] = paths.cache.absolutePath
            m["MESA_GL_VERSION_OVERRIDE"] = "4.6"
            m["MESA_GLSL_VERSION_OVERRIDE"] = "460"
            m["force_glsl_extensions_warn"] = "true"
            m["allow_higher_compat_version"] = "true"
            m["allow_glsl_extension_directive_midshader"] = "true"
            m["LIB_MESA_NAME"] = renderer.lib
        }
        if (!m.containsKey("LIBGL_ES")) m["LIBGL_ES"] = "3"
        return m
    }

    private fun dlopenJava() {
        val lib = java.libDir.absolutePath
        ZLBridge.dlopen("${java.jliDir}/libjli.so")
        ZLBridge.dlopen("${java.jvmDir}/libjvm.so")
        for (n in listOf("libfreetype.so", "libverify.so", "libjava.so", "libnet.so", "libnio.so", "libawt.so", "libawt_headless.so", "libfontmanager.so")) {
            ZLBridge.dlopen("$lib/$n")
        }
        java.home.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.forEach { ZLBridge.dlopen(it.absolutePath) }
    }

    // ---------------------------------------------------------------- JVM arguments

    private fun javaArgs(width: Int, height: Int): List<String> {
        val args = mutableListOf("${java.home}/bin/java")

        // system properties the native layer and the patched LWJGL rely on
        val props = linkedMapOf(
            "java.home" to java.javaHome.absolutePath,
            "java.io.tmpdir" to paths.cache.absolutePath,
            "jna.boot.library.path" to paths.jna.absolutePath,
            "user.home" to File(spec.gameDir).parent!!,
            "user.language" to (System.getProperty("user.language") ?: "en"),
            "user.country" to Locale.getDefault().country,
            "user.timezone" to TimeZone.getDefault().id,
            "os.name" to "Linux",
            "os.version" to "Android-${Build.VERSION.RELEASE}",
            "pojav.path.minecraft" to spec.gameDir,
            "pojav.path.private.account" to File(paths.root, "accounts").absolutePath,
            "org.lwjgl.vulkan.libname" to "libvulkan.so",
            "org.lwjgl.librarypath" to lwjglNatives,
            "glfwstub.windowWidth" to width.toString(),
            "glfwstub.windowHeight" to height.toString(),
            "glfwstub.initEgl" to "false",
            "ext.net.resolvPath" to resolvConf().absolutePath,
            "log4j2.formatMsgNoLookups" to "true",
            "java.rmi.server.useCodebaseOnly" to "true",
            "com.sun.jndi.rmi.object.trustURLCodebase" to "false",
            "com.sun.jndi.cosnaming.object.trustURLCodebase" to "false",
            "net.minecraft.clientmodname" to "EririLily",
            "fml.earlyprogresswindow" to "false",
            "fml.ignoreInvalidMinecraftCertificates" to "true",
            "fml.ignorePatchDiscrepancies" to "true",
            "loader.disable_forked_guis" to "true",
            "jdk.lang.Process.launchMechanism" to "FORK",
            "sodium.checks.issue2561" to "false",
            "file.encoding" to "UTF-8",
            "sun.stdout.encoding" to "UTF-8",
            "sun.stderr.encoding" to "UTF-8",
            "cpu.name" to socName(),
        )
        val user = spec.extraJvm
        props.forEach { (k, v) -> if (user.none { it.startsWith("-D$k=") }) args += "-D$k=$v" }
        args += user.filterNot { purged(it) }

        // memory and LWJGL library names (Zalith's progressFinalUserArgs)
        args += "-Xms${spec.ramMb}M"
        args += "-Xmx${spec.ramMb}M"
        args += "-Dorg.lwjgl.openal.libname=$nativeDir/libopenal.so"
        args += "-Dorg.lwjgl.freetype.libname=$lwjglNatives/libfreetype.so"
        args += "-Dorg.lwjgl.spvc.libname=spirv-cross-c-shared"
        args += "-Dorg.lwjgl.system.allocator=system"
        args += "-XX:ActiveProcessorCount=${Runtime.getRuntime().availableProcessors()}"
        args += "-Dorg.lwjgl.opengl.libname=${renderer.lib}"

        // AWT through Cacio (some mods and installers open windows)
        args += cacioArgs(width, height)
        args += "-javaagent:${File(paths.launcher, "MioLibPatcher.jar").absolutePath}"
        args += "-Dlog4j.configurationFile=${log4jConfig().absolutePath}"
        args += "-Dminecraft.client.jar=${spec.clientJar}"
        args += "-Dminecraft.launcher.brand=EririLily"

        // the version's own JVM args (loaders add opens/exports, ignoreList, …)
        args += spec.jvmArgs.filterNot { purged(it) }.map { a ->
            when {
                a.startsWith("-Djava.library.path=") -> "-Djava.library.path=${runtimeLibraryPath()}"
                a.startsWith("-Dio.netty.native.workdir=") || a.startsWith("-Djna.tmpdir=") ||
                    a.startsWith("-Dorg.lwjgl.system.SharedLibraryExtractPath=") -> a.substringBefore('=') + "=" + paths.cache.absolutePath
                else -> a
            }
        }

        args += "-cp"
        args += (lwjglJars() + File(paths.launcher, "MioLaunchWrapper.jar").absolutePath +
            spec.classpath.filter { File(it).exists() }).joinToString(":")

        if (javaMajor > 8) {
            val pkg = spec.mainClass.substringBeforeLast('.')
            args += "--add-exports"
            args += "$pkg/$pkg=ALL-UNNAMED"
        }
        args += "mio.Wrapper"
        args += spec.mainClass
        args += spec.gameArgs
        return args
    }

    private fun purged(a: String) = listOf(
        "-Xms", "-Xmx", "-d32", "-d64", "-Xint", "-XX:+UseTransparentHugePages", "-XX:+UseLargePagesInMetaspace",
        "-XX:+UseLargePages", "-Dorg.lwjgl.opengl.libname", "-Dorg.lwjgl.freetype.libname", "-XX:ActiveProcessorCount",
        "-cp", "-classpath",
    ).any { a.startsWith(it) }

    /** lwjgl.jar first, then the merged modules jar, then the rest (lwjglx only for LWJGL 2 games). */
    private fun lwjglJars(): List<String> {
        val dir = paths.lwjgl(spec.lwjgl)
        val order = mapOf("lwjgl.jar" to 0, "lwjgl-${spec.lwjgl}-merged-modules.jar" to 1)
        return (dir.listFiles { f -> f.name.endsWith(".jar") && (spec.lwjgl2 || f.name != "lwjgl-lwjglx.jar") } ?: emptyArray())
            .sortedBy { if (it.name == "lwjgl-lwjglx.jar") 3 else order[it.name] ?: 2 }.map { it.absolutePath }
    }

    private fun cacioArgs(width: Int, height: Int): List<String> {
        val java8 = javaMajor == 8
        val a = mutableListOf(
            "-Djava.awt.headless=false",
            "-Dcacio.managed.screensize=${width}x$height",
            "-Dcacio.font.fontmanager=sun.awt.X11FontManager",
            "-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler",
            "-Dswing.defaultlaf=javax.swing.plaf.nimbus.NimbusLookAndFeel",
        )
        if (java8) {
            a += "-Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit"
            a += "-Djava.awt.graphicsenv=net.java.openjdk.cacio.ctc.CTCGraphicsEnvironment"
        } else {
            a += "-Dawt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit"
            a += "-Djava.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment"
            a += "-javaagent:${File(paths.cacio17, "cacio-agent.jar").absolutePath}"
            for (e in listOf("java.desktop/java.awt", "java.desktop/java.awt.peer", "java.desktop/sun.awt.image", "java.desktop/sun.java2d",
                "java.desktop/java.awt.dnd.peer", "java.desktop/sun.awt", "java.desktop/sun.awt.event", "java.desktop/sun.awt.datatransfer",
                "java.desktop/sun.font", "java.base/sun.security.action")) a += "--add-exports=$e=ALL-UNNAMED"
            for (o in listOf("java.base/java.util", "java.desktop/java.awt", "java.desktop/sun.font", "java.desktop/sun.java2d",
                "java.base/java.lang.reflect", "java.base/java.net")) a += "--add-opens=$o=ALL-UNNAMED"
        }
        val jars = (if (java8) paths.cacio8 else paths.cacio17).listFiles { f -> f.name.endsWith(".jar") }?.map { it.absolutePath } ?: emptyList()
        a += "-Xbootclasspath/" + (if (java8) "p" else "a") + jars.joinToString("") { ":$it" }
        return a
    }

    private fun log4jConfig(): File {
        val f = File(paths.cache, "log4j2.xml")
        val legacy = spec.gameVersion.split('.').getOrNull(1)?.toIntOrNull()?.let { spec.gameVersion.startsWith("1.") && it < 12 } == true
        runCatching {
            context.assets.open(if (legacy) "components/log4j-1.7.xml" else "components/log4j-1.12.xml").use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
        }
        return f
    }

    private fun resolvConf(): File {
        val f = File(paths.cache, "resolv.conf")
        val servers = listOf("223.5.5.5", "119.29.29.29", "8.8.8.8", "1.1.1.1")
        val text = servers.joinToString("\n") { "nameserver $it" }
        if (!f.exists() || f.readText().trim() != text) f.writeText(text)
        return f
    }

    private fun socName(): String = runCatching {
        ProcessBuilder("getprop", "ro.soc.model").start().inputStream.bufferedReader().use { it.readLine() }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.HARDWARE
}
