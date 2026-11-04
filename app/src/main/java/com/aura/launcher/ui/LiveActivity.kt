package com.aura.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.ByteArrayInputStream
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

/**
 * 央视直播:全屏打开央视官方公开直播页,没有任何浏览器界面。
 * - 系统 WebView 够新就用它;太旧(老电视常见)就用内置的 GeckoView 浏览器内核;
 * - 出画前盖一层纯黑"正在打开 CCTV-X"遮罩;开始播放后只保留播放器,铺满全屏;
 * - 25 秒还没出画就提示换台/返回,并显示诊断信息。
 * 遥控器:上/下 或 频道+/- 换台,确定 暂停/播放,左/右/菜单 显示台标,返回 退出。
 */
class LiveActivity : ComponentActivity() {
    private var web: WebView? = null
    private var geckoView: GeckoView? = null
    private var geckoSession: GeckoSession? = null
    private var useGecko = false
    private lateinit var root: FrameLayout
    private lateinit var cover: LinearLayout
    private lateinit var coverTitle: TextView
    private lateinit var coverMsg: TextView
    private lateinit var coverSpin: ProgressBar
    private lateinit var tip: TextView
    private var index = 0
    private var playing = false
    private var loadSeq = 0
    private val handler = Handler(Looper.getMainLooper())
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var tick = 0

    /** 诊断信息:打不开时显示在屏幕上,方便拍照反馈。 */
    @Volatile private var diag = linkedMapOf<String, String>()
    private fun note(k: String, v: String) { synchronized(this) { diag[k] = v }; Log.i("AuraLive", "$k=$v") }

    private val keepPlaying = object : Runnable {
        override fun run() {
            web?.evaluateJavascript(PLAY_JS, null)
            if (++tick < 60) handler.postDelayed(this, if (playing) 3000L else 700L)
        }
    }
    private val hideTip = Runnable { tip.visibility = View.GONE }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        index = intent.getIntExtra(EXTRA, 0).coerceIn(LiveChannels.all.indices)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val wvVersion = webViewVersion()
        val wvMajor = wvVersion.substringAfterLast(' ').substringBefore('.').toIntOrNull() ?: 0
        useGecko = intent.getStringExtra(ENGINE) == "gecko" || (intent.getStringExtra(ENGINE) != "webview" && wvMajor < 90)
        note("内核", if (useGecko) "内置 Gecko(系统 WebView $wvVersion)" else "系统 WebView $wvVersion")
        if (useGecko) createGecko() else createWebView()

        // 出画前的黑色遮罩
        cover = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            isClickable = false
        }
        coverTitle = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 56f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        coverSpin = ProgressBar(this).apply { isIndeterminate = true }
        coverMsg = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt()); textSize = 28f; gravity = Gravity.CENTER; setPadding(0, 36, 0, 0)
        }
        cover.addView(coverTitle)
        cover.addView(coverSpin, LinearLayout.LayoutParams(140, 140).apply { topMargin = 48 })
        cover.addView(coverMsg)
        root.addView(cover, FrameLayout.LayoutParams(-1, -1))

        tip = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xB0000000.toInt())
            textSize = 34f
            setPadding(48, 24, 48, 24)
            visibility = View.GONE
        }
        root.addView(tip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(64, 56, 0, 0) })
        setContentView(root)
        load()
    }

    private fun webViewVersion(): String =
        runCatching { WebView.getCurrentWebViewPackage()?.let { it.packageName.substringAfterLast('.') + " " + it.versionName } }
            .getOrNull() ?: "无"

    // ------------------------------------------------------------ 系统 WebView
    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun createWebView() {
        val w = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = false
            isFocusableInTouchMode = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = DESKTOP_UA
                setSupportZoom(false)
            }
            addJavascriptInterface(Bridge(), "AuraLive")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                    // 网页里的图片、字体对看直播没用:不下载,省带宽
                    if (req.isForMainFrame) return null
                    val path = req.url.path?.lowercase() ?: return null
                    if (BLOCK_EXT.any { path.endsWith(it) }) {
                        return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }
                    return null
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { startInject() }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (request.isForMainFrame) note("网页", "加载失败 " + error.errorCode + " " + error.description)
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, resp: WebResourceResponse) {
                    if (request.isForMainFrame) note("网页", "HTTP " + resp.statusCode)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if ("网页" !in diag) note("网页", "已打开")
                    startInject()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR && "报错" !in diag) {
                        note("报错", m.message().take(70))
                    }
                    return false
                }

                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    customView = view
                    customCb = callback
                    root.addView(view, 1, ViewGroup.LayoutParams(-1, -1))
                }

                override fun onHideCustomView() {
                    customView?.let { root.removeView(it) }
                    customView = null
                    customCb?.onCustomViewHidden()
                    customCb = null
                }
            }
        }
        web = w
        root.addView(w, FrameLayout.LayoutParams(-1, -1))
    }

    // ------------------------------------------------------------ 内置 GeckoView
    private fun createGecko() {
        val rt = LiveGecko.runtime(this)
        val session = GeckoSession(
            GeckoSessionSettings.Builder()
                .usePrivateMode(true)
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
                .build(),
        )
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                note("网页", if (success) "已打开" else "加载失败")
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCrash(session: GeckoSession) { note("浏览器", "崩溃") }
            override fun onKill(session: GeckoSession) { note("浏览器", "被系统结束") }
        }
        session.open(rt)
        geckoSession = session
        val gv = GeckoView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = false
            isFocusableInTouchMode = false
        }
        gv.setSession(session)
        geckoView = gv
        root.addView(gv, FrameLayout.LayoutParams(-1, -1))
        LiveGecko.onMessage = { type, data ->
            handler.post {
                when (type) {
                    "playing" -> onPlaying()
                    "log" -> {
                        Log.i("AuraLive", data.take(300))
                        if (data.startsWith("url=")) note("页面脚本", "已运行")
                        if (data.startsWith("video found")) note("视频元素", "有")
                        if (data.startsWith("js error")) note("脚本", data.take(80))
                    }
                }
            }
        }
    }

    private fun startInject() {
        tick = 0
        handler.removeCallbacks(keepPlaying)
        handler.post(keepPlaying)
    }

    private fun load() {
        val ch = LiveChannels.all[index]
        liveSaveChannel(this, index)
        playing = false
        val seq = ++loadSeq
        synchronized(this) { val k = diag["内核"]; diag = linkedMapOf(); if (k != null) diag["内核"] = k }
        coverMsg.textSize = 28f
        coverTitle.text = ch.title
        coverMsg.text = "正在打开,请稍候…"
        coverSpin.visibility = View.VISIBLE
        cover.visibility = View.VISIBLE
        tip.visibility = View.GONE
        if (useGecko) {
            geckoSession?.load(GeckoSession.Loader().uri(ch.url))
        } else {
            web?.stopLoading()
            web?.loadUrl(ch.url)
        }
        handler.postDelayed({
            if (seq == loadSeq && !playing) {
                coverSpin.visibility = View.GONE
                val info = synchronized(this) { diag.entries.joinToString("  ·  ") { it.key + ":" + it.value } }
                coverMsg.text = "暂时打不开这个频道\n按 上 / 下 键换台,按「返回」键退出\n\n诊断(拍照发给开发者):" + info
                coverMsg.textSize = 24f
            }
        }, 25_000)
    }

    private fun onPlaying() {
        if (playing) return
        playing = true
        cover.visibility = View.GONE
        showTip(LiveChannels.all[index].title)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun playing() { handler.post { onPlaying() } }

        @JavascriptInterface
        fun log(s: String) {
            Log.i("AuraLive", s.take(2000))
            when {
                s.startsWith("url=") -> note("网页", "已运行 " + Regex("videos=\\d+").find(s)?.value.orEmpty())
                s.startsWith("video found") -> note("网页视频", "有")
                s.startsWith("js error") -> note("脚本", s.take(80))
            }
        }
    }

    private fun showTip(s: String) {
        tip.text = s
        tip.visibility = View.VISIBLE
        handler.removeCallbacks(hideTip)
        handler.postDelayed(hideTip, 3500)
    }

    /** 暂停 / 播放:Gecko 靠扩展里的脚本,这里用一次遥控器"确定"事件交给页面。 */
    private fun toggle() {
        if (useGecko) {
            geckoView?.let { gv ->
                val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE)
                val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE)
                gv.dispatchKeyEvent(down); gv.dispatchKeyEvent(up)
            }
        } else {
            web?.evaluateJavascript(
                "(function(){var v=document.querySelector('video');if(v){if(v.paused){v.play()}else{v.pause()}}})()", null,
            )
        }
    }

    private fun step(d: Int) {
        val n = LiveChannels.all.size
        index = ((index + d) % n + n) % n
        load()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { step(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { step(1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { toggle(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU -> {
                    showTip("${LiveChannels.all[index].title}    上/下 换台    返回 退出"); return true
                }
                KeyEvent.KEYCODE_BACK -> { if (customView != null) web?.webChromeClient?.onHideCustomView() else finish(); return true }
            }
        }
        if (e.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(e)
    }

    override fun onPause() {
        super.onPause()
        web?.onPause()
        handler.removeCallbacks(keepPlaying)
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
        if (playing && !useGecko) startInject()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        LiveGecko.onMessage = null
        geckoSession?.close()
        geckoSession = null
        web?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.loadUrl("about:blank")
            it.destroy()
        }
        web = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA = "channel"
        private const val ENGINE = "engine"
        private val BLOCK_EXT = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".woff", ".woff2", ".ttf", ".otf")
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val PLAY_JS = """
(function(){
  try{
    var B=window.AuraLive;
    var v=null,vs=document.getElementsByTagName('video');
    for(var i=0;i<vs.length;i++){if(!v||vs[i].offsetWidth*vs[i].offsetHeight>v.offsetWidth*v.offsetHeight)v=vs[i];}
    if(!window.__auraLogged){window.__auraLogged=1;
      var ids=[];var all=document.querySelectorAll('[id]');for(var k=0;k<all.length&&ids.length<40;k++){var d=all[k].id;if(/play|video|live/i.test(d))ids.push(all[k].tagName+'#'+d);}
      B&&B.log('url='+location.href+' videos='+vs.length+' iframes='+document.getElementsByTagName('iframe').length+' ids='+ids.join(','));}
    if(!v)return;
    if(!v.__aura){v.__aura=1;
      v.addEventListener('playing',function(){B&&B.playing();});
      v.addEventListener('timeupdate',function(){if(v.currentTime>0.3){B&&B.playing();}});
      B&&B.log('video found src='+(v.currentSrc||v.src||'').slice(0,160));}
    var olds=document.querySelectorAll('[data-aura-keep]');for(var j=0;j<olds.length;j++)olds[j].removeAttribute('data-aura-keep');
    var e=v;while(e&&e.nodeType===1){e.setAttribute('data-aura-keep','1');e=e.parentElement;}
    var s=document.getElementById('aura_css');
    if(!s){s=document.createElement('style');s.id='aura_css';(document.head||document.documentElement).appendChild(s);}
    s.textContent='html,body{overflow:hidden!important;background:#000!important;margin:0!important}'+
      'body *:not([data-aura-keep]):not(video){visibility:hidden!important}'+
      '[data-aura-keep]{transform:none!important;filter:none!important;animation:none!important;transition:none!important}'+
      'video[data-aura-keep]{visibility:visible!important;position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;z-index:2147483647!important;background:#000!important;object-fit:contain!important}';
    v.muted=false;v.volume=1;
    if(v.paused){var p=v.play();if(p&&p.catch){p.catch(function(){})}}
    if(!v.paused&&v.currentTime>0.3){B&&B.playing();}
  }catch(err){window.AuraLive&&window.AuraLive.log('js error '+err);}
})()
"""

        fun intent(ctx: Context, index: Int): Intent =
            Intent(ctx, LiveActivity::class.java).putExtra(EXTRA, index)
    }
}
