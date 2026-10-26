package cn.eririlily.launcher

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The launcher core (the Go program shared with the Windows build) runs as a
 * child process: it ships as liberirilily_core.so so Android installs it as an
 * executable file. It serves the UI on 127.0.0.1 and talks back on stdout:
 *   @@URL <url>      the UI address
 *   @@LAUNCH <spec>  start the game with this launch spec
 *   @@OPEN <path>    show a folder or a link
 */
object Core {
    interface Listener {
        fun onUrl(url: String) {}
        fun onLaunch(spec: String) {}
        fun onOpen(path: String) {}
        fun onDied() {}
    }

    private const val TAG = "EririLily-Core"
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var process: Process? = null
    @Volatile var url: String? = null
        private set

    fun addListener(l: Listener) {
        listeners += l
        url?.let { u -> main.post { l.onUrl(u) } }
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    @Synchronized
    fun start(ctx: Context) {
        if (process?.isAlive == true) return
        val app = ctx.applicationContext
        val exe = File(app.applicationInfo.nativeLibraryDir, "liberirilily_core.so")
        if (!exe.exists()) {
            Log.e(TAG, "core binary missing: $exe")
            main.post { listeners.forEach { it.onDied() } }
            return
        }
        val data = (app as ELApp).launcherDir
        val pb = ProcessBuilder(exe.absolutePath).directory(data).redirectErrorStream(false)
        pb.environment().apply {
            put("ERIRILILY_DATA", data.absolutePath)
            put("ERIRILILY_NATIVE_DIR", app.applicationInfo.nativeLibraryDir)
            put("ERIRILILY_APK", app.applicationInfo.sourceDir)
            put("ERIRILILY_CACHE", app.cacheDir.absolutePath)
            put("ERIRILILY_ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a")
            put("ERIRILILY_DEVICE", DeviceInfo.json(app).toString())
            put("ERIRILILY_DNS", dnsServers(app))
            put("ERIRILILY_PARENT_PIPE", "1")
            put("HOME", data.absolutePath)
            put("TMPDIR", app.cacheDir.absolutePath)
            put("SSL_CERT_DIR", "/system/etc/security/cacerts")
            put("GODEBUG", "madvdontneed=1")
        }
        val p = pb.start()
        process = p
        Thread({
            p.inputStream.bufferedReader().forEachLine { line ->
                if (!line.startsWith("@@")) {
                    Log.i(TAG, line); return@forEachLine
                }
                val kind = line.substring(2).substringBefore(' ')
                val arg = line.substringAfter(' ', "")
                main.post {
                    when (kind) {
                        "URL" -> { url = arg; listeners.forEach { it.onUrl(arg) } }
                        "LAUNCH" -> listeners.forEach { it.onLaunch(arg) }
                        "OPEN" -> listeners.forEach { it.onOpen(arg) }
                    }
                }
            }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            Log.w(TAG, "core exited: $code")
            synchronized(this) { if (process === p) { process = null; url = null } }
            main.post { listeners.forEach { it.onDied() } }
        }, "core-stdout").start()
        Thread({ p.errorStream.bufferedReader().forEachLine { Log.w(TAG, it) } }, "core-stderr").start()
    }

    fun restart(ctx: Context) {
        synchronized(this) { process?.destroy(); process = null; url = null }
        start(ctx)
    }

    private fun dnsServers(ctx: Context): String = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)!!
        cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.joinToString(",") { it.hostAddress ?: "" } ?: ""
    }.getOrDefault("")
}
