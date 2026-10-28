package cn.eririlily.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import cn.eririlily.launcher.game.GameActivity
import java.io.File

/** The launcher: the core's iOS-style UI in a full-screen WebView. */
class MainActivity : Activity(), Core.Listener {
    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var loadedUrl: String? = null
    private var pendingImport: Uri? = null
    lateinit var bridge: WebBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBars.edgeToEdge(window)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false

        if (BuildConfig.TEST_BUILD) WebView.setWebContentsDebuggingEnabled(true)
        web = WebView(this)
        web.setBackgroundColor(Color.TRANSPARENT)
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isVerticalScrollBarEnabled = false
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            textZoom = 100
            setSupportZoom(false)
            allowFileAccess = false
        }
        bridge = WebBridge(this, web)
        web.addJavascriptInterface(bridge, "ELNative")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.host == "127.0.0.1") return false
                openExternal(u.toString())
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pushInsets()
                pendingImport?.let { pendingImport = null; bridge.offerImport(it) }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                return runCatching { startActivityForResult(i, REQ_FILE); true }.getOrElse { fileCallback = null; false }
            }

            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                android.util.Log.i("EririLily-UI", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }
        val root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, insets -> pushInsets(insets); v.onApplyWindowInsets(insets) }

        Core.addListener(this)
        Core.start(this)
        handleIntent(intent)
    }

    override fun onDestroy() {
        Core.removeListener(this)
        web.destroy()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val uri = when (i?.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        } ?: return
        if (loadedUrl != null) bridge.offerImport(uri) else pendingImport = uri
    }

    // ---------------------------------------------------------------- core events

    override fun onUrl(url: String) {
        if (url != loadedUrl) {
            loadedUrl = url
            web.loadUrl(url)
        }
    }

    override fun onLaunch(spec: String) {
        bridge.haptic("success")
        // the game gets its own task: the launcher icon then returns here without closing the game
        startActivity(Intent(this, GameActivity::class.java).putExtra(GameActivity.EXTRA_SPEC, spec).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onOpen(path: String) {
        if (path.startsWith("https://") || path.startsWith("http://")) openExternal(path) else openFolder(path)
    }

    override fun onDied() {
        Toast.makeText(this, "启动器核心已重启", Toast.LENGTH_SHORT).show()
        loadedUrl = null
        web.postDelayed({ Core.start(this) }, 600)
    }

    fun openExternal(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, "没有可以打开链接的应用", Toast.LENGTH_SHORT).show() }
    }

    /** Opens a folder of the game files in the system Files app (through FilesProvider). */
    fun openFolder(path: String) {
        val root = (application as ELApp).launcherDir.absolutePath
        val rel = File(path).absolutePath.removePrefix(root).trimStart('/')
        val docId = if (rel.isEmpty()) FilesProvider.ROOT else FilesProvider.ROOT + "/" + rel
        val uri = DocumentsContract.buildDocumentUri(FilesProvider.AUTHORITY, docId)
        val tries = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR),
            Intent(Intent.ACTION_VIEW).setDataAndType(DocumentsContract.buildRootUri(FilesProvider.AUTHORITY, FilesProvider.ROOT), "vnd.android.document/root"),
        )
        for (t in tries) {
            t.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (runCatching { startActivity(t) }.isSuccess) return
        }
        Toast.makeText(this, "可在系统「文件」应用侧栏中找到「EririLily 游戏文件」", Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- insets / back

    private fun pushInsets(ins: WindowInsets? = window.decorView.rootWindowInsets) {
        ins ?: return
        val c = SystemBars.safeArea(ins)
        val d = resources.displayMetrics.density
        val js = "window.elInsets&&elInsets(${c[0] / d},${c[1] / d},${c[2] / d},${c[3] / d})"
        web.post { web.evaluateJavascript(js, null) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        web.evaluateJavascript("window.elSystemTheme&&elSystemTheme()", null)
        pushInsets()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.elBack?elBack():false") { handled ->
            if (handled != "true") moveTaskToBack(true)
        }
    }

    override fun onResume() {
        super.onResume()
        web.evaluateJavascript("window.elResume&&elResume()", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE) {
            val uri = data?.data
            fileCallback?.onReceiveValue(if (resultCode == RESULT_OK && uri != null) arrayOf(uri) else null)
            fileCallback = null
        } else if (requestCode == REQ_EXPORT && resultCode == RESULT_OK) {
            data?.data?.let { bridge.finishExport(it) }
        }
    }

    companion object {
        const val REQ_FILE = 1
        const val REQ_EXPORT = 2
    }
}
