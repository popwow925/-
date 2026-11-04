package com.aura.launcher.ui

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension

/** 内置浏览器内核(GeckoView):全进程只建一个运行时,并装上"央视直播页"小扩展。 */
object LiveGecko {
    private var rt: GeckoRuntime? = null

    /** 扩展发来的消息:type = playing / log。 */
    @Volatile var onMessage: ((String, String) -> Unit)? = null

    private val delegate = object : WebExtension.MessageDelegate {
        override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
            val j = message as? JSONObject
            onMessage?.invoke(j?.optString("type").orEmpty(), j?.optString("data").orEmpty())
            return null
        }
    }

    fun runtime(ctx: Context): GeckoRuntime {
        rt?.let { return it }
        val settings = GeckoRuntimeSettings.Builder()
            .consoleOutput(true)
            .autoplayDefault(GeckoRuntimeSettings.AUTOPLAY_DEFAULT_ALLOWED)
            .build()
        val r = GeckoRuntime.create(ctx.applicationContext, settings)
        rt = r
        r.webExtensionController.ensureBuiltIn("resource://android/assets/live-ext/", "live@aura")
            .accept(
                { ext -> ext?.setMessageDelegate(delegate, "aura") },
                { t -> Log.i("AuraLive", "extension install failed: $t") },
            )
        return r
    }
}
