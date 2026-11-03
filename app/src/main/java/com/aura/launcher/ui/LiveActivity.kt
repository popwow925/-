package com.aura.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 央视直播:在全屏网页里打开央视官方公开直播页,自动开始播放并铺满屏幕。
 * 遥控器:上/下 或 频道+/- 换台,确定 暂停/播放,返回 退出。
 */
class LiveActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var tip: TextView
    private lateinit var root: FrameLayout
    private var index = 0
    private val handler = Handler(Looper.getMainLooper())
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var tick = 0

    private val keepPlaying = object : Runnable {
        override fun run() {
            injectPlay()
            if (++tick < 40) handler.postDelayed(this, 1500)
        }
    }
    private val hideTip = Runnable { tip.visibility = View.GONE }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        index = intent.getIntExtra(EXTRA, 0).coerceIn(LiveChannels.all.indices)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = false
            isFocusableInTouchMode = false
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = DESKTOP_UA
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    tick = 0
                    handler.removeCallbacks(keepPlaying)
                    handler.postDelayed(keepPlaying, 800)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    customView = view
                    customCb = callback
                    root.addView(view, ViewGroup.LayoutParams(-1, -1))
                    web.visibility = View.INVISIBLE
                }

                override fun onHideCustomView() {
                    customView?.let { root.removeView(it) }
                    customView = null
                    customCb?.onCustomViewHidden()
                    customCb = null
                    web.visibility = View.VISIBLE
                }
            }
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
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

    private fun load() {
        val ch = LiveChannels.all[index]
        liveSaveChannel(this, index)
        showTip("${ch.title}  ·  正在打开…")
        web.loadUrl(ch.url)
    }

    private fun showTip(s: String) {
        tip.text = s
        tip.visibility = View.VISIBLE
        handler.removeCallbacks(hideTip)
        handler.postDelayed(hideTip, 3500)
    }

    /** 让页面里的视频有声播放并铺满屏幕(官方页面改版时尽力而为)。 */
    private fun injectPlay() {
        web.evaluateJavascript(PLAY_JS, null)
    }

    private fun toggle() {
        web.evaluateJavascript(
            "(function(){var v=document.querySelector('video');if(v){if(v.paused){v.play()}else{v.pause()}}})()", null,
        )
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
                KeyEvent.KEYCODE_BACK -> { if (customView != null) web.webChromeClient?.onHideCustomView() else finish(); return true }
            }
        }
        if (e.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(e)
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
        handler.removeCallbacks(keepPlaying)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        (web.parent as? ViewGroup)?.removeView(web)
        web.loadUrl("about:blank")
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA = "channel"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val PLAY_JS = """
(function(){
  try{
    var s=document.getElementById('aura_css');
    if(!s){s=document.createElement('style');s.id='aura_css';
      s.textContent='html,body{overflow:hidden!important;background:#000!important}'+
      'video{position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;z-index:2147483647!important;background:#000!important;object-fit:contain!important}';
      document.head.appendChild(s);}
    var v=document.querySelector('video');
    if(v){v.muted=false;v.volume=1;if(v.paused){var p=v.play();if(p&&p.catch){p.catch(function(){})}}}
  }catch(e){}
})()
"""

        fun intent(ctx: Context, index: Int): Intent =
            Intent(ctx, LiveActivity::class.java).putExtra(EXTRA, index)
    }
}
