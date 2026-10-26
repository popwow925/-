package cn.eririlily.launcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.OpenableColumns
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import cn.eririlily.launcher.game.ControlsEditorActivity
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/** `window.ELNative` for the UI: haptics, files, links and the system bars. */
class WebBridge(private val act: MainActivity, private val web: WebView) {
    private val main = Handler(Looper.getMainLooper())
    private var exportSource: File? = null

    @JavascriptInterface
    fun haptic(kind: String) = main.post {
        when (kind) {
            "light" -> web.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            "medium" -> web.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            "heavy" -> web.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            "select" -> web.performHapticFeedback(if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.TEXT_HANDLE_MOVE else HapticFeedbackConstants.CLOCK_TICK)
            else -> vibrate(kind)
        }
    }

    private fun vibrate(kind: String) {
        val v = act.getSystemService(Vibrator::class.java) ?: return
        val effect = when (kind) {
            "success" -> VibrationEffect.createWaveform(longArrayOf(0, 18, 60, 28), intArrayOf(0, 120, 0, 200), -1)
            "error" -> VibrationEffect.createWaveform(longArrayOf(0, 30, 50, 30, 50, 40), intArrayOf(0, 200, 0, 200, 0, 255), -1)
            else -> VibrationEffect.createOneShot(12, 120)
        }
        runCatching { v.vibrate(effect) }
    }

    @JavascriptInterface
    fun openUrl(url: String) = main.post { act.openExternal(url) }

    @JavascriptInterface
    fun openFolder(path: String) = main.post { act.openFolder(path) }

    @JavascriptInterface
    fun copy(text: String) = main.post {
        act.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("EririLily", text))
    }

    @JavascriptInterface
    fun share(text: String) = main.post {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        runCatching { act.startActivity(Intent.createChooser(i, "分享")) }
    }

    @JavascriptInterface
    fun device(): String = DeviceInfo.json(act).toString()

    @JavascriptInterface
    fun appVersion(): String = BuildConfig.VERSION_NAME

    /** Foreground notification while a long task runs; label "" ends it. */
    @JavascriptInterface
    fun setTask(label: String, detail: String, progress: Int) = main.post {
        CoreService.update(act, label.ifEmpty { null }, detail, progress)
    }

    @JavascriptInterface
    fun statusBar(darkContent: Boolean) = main.post {
        SystemBars.darkIcons(act.window, darkContent)
    }

    @JavascriptInterface
    fun keepScreenOn(on: Boolean) = main.post {
        if (on) act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @JavascriptInterface
    fun editControls(name: String) = main.post {
        act.startActivity(Intent(act, ControlsEditorActivity::class.java).putExtra("layout", name))
    }

    /** Saves a file the core produced (a log, a backup) to a place the user picks. */
    @JavascriptInterface
    fun exportFile(path: String, name: String) = main.post {
        val f = File(path)
        if (!f.exists()) return@post
        exportSource = f
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_TITLE, name)
        @Suppress("DEPRECATION")
        act.startActivityForResult(i, MainActivity.REQ_EXPORT)
    }

    fun finishExport(uri: Uri) {
        val src = exportSource ?: return
        thread {
            val ok = runCatching {
                act.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
            }.isSuccess
            js("window.elToast&&elToast(${JSONObject.quote(if (ok) "已保存" else "保存失败")})")
        }
    }

    @JavascriptInterface
    fun restartCore() = main.post { Core.restart(act) }

    /** A .mrpack (or JRE / renderer) opened from another app: hand it to the core. */
    fun offerImport(uri: Uri) {
        val base = Core.url ?: return
        thread {
            val name = displayName(uri) ?: "import.mrpack"
            val low = name.lowercase()
            val endpoint = when {
                low.endsWith(".mrpack") -> "upload?name="
                low.endsWith(".tar.xz") || low.endsWith(".tar.gz") -> "import?kind=jre&name="
                low.endsWith(".so") -> "import?kind=renderer&name="
                else -> "upload?name="
            }
            val origin = base.substringBefore("/#")
            val token = base.substringAfter("#")
            val err = runCatching {
                val c = URL("$origin/api/$endpoint" + URLEncoder.encode(name, "UTF-8")).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.setChunkedStreamingMode(1 shl 16)
                c.setRequestProperty("X-Token", token)
                act.contentResolver.openInputStream(uri)?.use { it.copyTo(c.outputStream) }
                c.outputStream.close()
                val code = c.responseCode
                if (code != 200) (c.errorStream ?: c.inputStream).bufferedReader().readText().let { JSONObject(it).optString("error", "导入失败") } else null
            }.getOrElse { it.message ?: "导入失败" }
            js("window.elToast&&elToast(${JSONObject.quote(err ?: "正在导入 $name")}, ${err != null})")
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        act.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private fun js(code: String) = main.post { web.evaluateJavascript(code, null) }
}
