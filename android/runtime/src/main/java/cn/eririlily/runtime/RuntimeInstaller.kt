/*
 * EririLily game runtime — unpacks what ships inside the APK (Java runtimes, LWJGL, AWT/Cacio,
 * launch helpers, JNA) into the app's private storage. Each part carries a version file so it is
 * only unpacked again when the APK brings a newer copy.
 */
package cn.eririlily.runtime

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

class RuntimePaths(context: Context) {
    val root = File(context.filesDir, "runtime")
    val jres = File(root, "java")
    val components = File(root, "components")
    val cache: File = File(context.cacheDir, "game").apply { mkdirs() }
    val nativeLibDir: String = context.applicationInfo.nativeLibraryDir

    /** The ABI this APK was installed with (arm64-v8a on phones; x86_64 for emulator test builds). */
    val abi: String = if (nativeLibDir.endsWith("x86_64")) "x86_64" else "arm64-v8a"

    fun jre(major: Int) = File(jres, "jre-$major")
    fun lwjgl(version: String) = File(components, "lwjgl/$version")
    fun lwjglNatives(version: String) = File(lwjgl(version), "natives/$abi")
    val cacio8 get() = File(components, "caciocavallo")
    val cacio17 get() = File(components, "caciocavallo17")
    val launcher get() = File(components, "launcher")
    val authlib get() = File(components, "auth_libs")
    val jna get() = File(root, "jna")
}

object RuntimeInstaller {
    /** Java versions bundled in the APK. */
    val bundledJava = listOf(8, 17, 21, 25)

    /** The bundled Java a Minecraft version asks for (major), rounded up to what we ship. */
    fun pickJava(required: Int): Int = bundledJava.firstOrNull { it >= required } ?: bundledJava.last()

    /** Make sure everything a launch with this Java / LWJGL needs is unpacked. [progress] gets a short label. */
    fun ensure(context: Context, javaMajor: Int, lwjgl: String, progress: (String) -> Unit = {}) {
        val p = RuntimePaths(context)
        ensureJre(context, p, javaMajor, progress)
        ensureDir(context, "app_runtime/lwjgl/$lwjgl", p.lwjgl(lwjgl), progress)
        ensureDir(context, "components/caciocavallo", p.cacio8, progress)
        ensureDir(context, "components/caciocavallo17", p.cacio17, progress)
        ensureDir(context, "components/launcher", p.launcher, progress)
        ensureDir(context, "components/auth_libs", p.authlib, progress)
        runCatching { ensureJna(context, p, progress) } // only a few mods need JNA; not every ABI ships it
    }

    private fun assetVersion(context: Context, path: String): String? =
        runCatching { context.assets.open("$path/version").bufferedReader().use { it.readText().trim() } }.getOrNull()

    private fun installedVersion(dir: File): String? =
        File(dir, ".el_version").takeIf { it.isFile }?.readText()?.trim()

    private fun markInstalled(dir: File, v: String?) {
        File(dir, ".el_version").writeText(v ?: "0")
    }

    private fun ensureJre(context: Context, p: RuntimePaths, major: Int, progress: (String) -> Unit) {
        val asset = "runtimes/jre-$major"
        val dest = p.jre(major)
        val v = assetVersion(context, asset)
        if (installedVersion(dest) == v && File(dest, "bin/java").exists() || installedVersion(dest) == v && File(dest, "jre/bin/java").exists()) return
        progress("Java $major")
        dest.deleteRecursively()
        dest.mkdirs()
        context.assets.open("$asset/universal.tar.xz").use { untarXz(it, dest) }
        context.assets.open("$asset/bin-${if (p.abi == "x86_64") "x86_64" else "arm64"}.tar.xz").use { untarXz(it, dest) }
        unpack200(p.nativeLibDir, dest)
        postPrepare(p, dest)
        markInstalled(dest, v)
    }

    /** Copy an asset folder as-is (jars, natives). */
    private fun ensureDir(context: Context, asset: String, dest: File, progress: (String) -> Unit) {
        val v = assetVersion(context, asset)
        if (dest.isDirectory && installedVersion(dest) == v && v != null) return
        progress(asset.substringAfterLast('/'))
        dest.deleteRecursively()
        copyAssetDir(context, asset, dest)
        markInstalled(dest, v)
    }

    private fun copyAssetDir(context: Context, asset: String, dest: File) {
        val list = context.assets.list(asset) ?: return
        if (list.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(asset).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (name in list) copyAssetDir(context, "$asset/$name", File(dest, name))
    }

    private fun ensureJna(context: Context, p: RuntimePaths, progress: (String) -> Unit) {
        val v = assetVersion(context, "runtimes/jna")
        if (p.jna.isDirectory && installedVersion(p.jna) == v && v != null) return
        progress("JNA")
        p.jna.deleteRecursively()
        p.jna.mkdirs()
        context.assets.open("runtimes/jna/jna-${if (p.abi == "x86_64") "x86_64" else "arm64"}.zip").use { input ->
            ZipInputStream(input).use { zip ->
                generateSequence { zip.nextEntry }.forEach { e ->
                    val f = File(p.jna, e.name)
                    if (!f.canonicalPath.startsWith(p.jna.canonicalPath)) throw IOException("bad zip entry ${e.name}")
                    if (e.isDirectory) f.mkdirs() else {
                        f.parentFile?.mkdirs()
                        f.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }
        markInstalled(p.jna, v)
    }

    private fun untarXz(input: InputStream, dest: File) {
        val root = dest.canonicalPath
        TarArchiveInputStream(XZCompressorInputStream(input.buffered())).use { tar ->
            generateSequence { tar.nextEntry }.forEach { e ->
                val f = File(dest, e.name)
                if (!f.canonicalPath.startsWith(root)) throw IOException("bad tar entry ${e.name}")
                f.parentFile?.mkdirs()
                when {
                    e.isSymbolicLink -> runCatching { if (f.exists()) f.delete(); Os.symlink(e.linkName, f.absolutePath) }
                    e.isDirectory -> f.mkdirs()
                    else -> {
                        f.outputStream().use { tar.copyTo(it) }
                        if (e.mode and 0b001_001_001 != 0) f.setExecutable(true, false)
                    }
                }
            }
        }
    }

    /** Some runtimes ship jars as .pack files; the bundled libunpack200.so turns them back into jars. */
    private fun unpack200(nativeLibDir: String, runtime: File) {
        runtime.walkTopDown().filter { it.isFile && it.name.endsWith(".pack") }.forEach { pack ->
            runCatching {
                ProcessBuilder("./libunpack200.so", "-r", pack.absolutePath, pack.absolutePath.removeSuffix(".pack"))
                    .directory(File(nativeLibDir)).start().waitFor()
            }
        }
    }

    /** Same fix-ups Zalith/Pojav apply after unpacking: freetype name and the AWT X11 stub. */
    private fun postPrepare(p: RuntimePaths, dest: File) {
        val lib = JavaLayout(dest).libDir
        val ftIn = File(lib, "libfreetype.so.6")
        val ftOut = File(lib, "libfreetype.so")
        if (ftIn.exists() && (!ftOut.exists() || ftIn.length() != ftOut.length())) ftIn.renameTo(ftOut)
        val xawt = File(p.nativeLibDir, "libawt_xawt.so")
        if (xawt.exists()) xawt.copyTo(File(lib, "libawt_xawt.so"), overwrite = true)
    }
}

/** Where things live inside an unpacked Java runtime. */
class JavaLayout(val home: File) {
    val isJdk8 = File(home, "jre").isDirectory && File(home, "bin/javac").exists()
    val javaHome: File = if (isJdk8) File(home, "jre") else home
    val libDir: File = run {
        val base = File(javaHome, "lib")
        listOf("aarch64", "arm64", "amd64", "x86_64").map { File(base, it) }.firstOrNull { it.isDirectory } ?: base
    }
    val jliDir: File = File(libDir, "jli").takeIf { File(it, "libjli.so").exists() } ?: libDir
    val jvmDir: File = File(libDir, "server").takeIf { File(it, "libjvm.so").exists() } ?: File(libDir, "client")
}
