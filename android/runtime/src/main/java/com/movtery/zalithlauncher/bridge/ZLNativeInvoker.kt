/*
 * Called from the native game runtime (Zalith Launcher 2's libpojavexec, GPL-3.0).
 * The class name and method signatures must stay as they are: the C code looks them up.
 * EririLily keeps only what the game needs: links, clipboard and the JVM exit hook.
 */
package com.movtery.zalithlauncher.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.Keep
import cn.eririlily.runtime.RuntimeEnv

@Keep
object ZLNativeInvoker {
    @Keep
    @JvmStatic
    fun openLink(link: String) {
        val activity = RuntimeEnv.activity ?: return
        activity.runOnUiThread {
            runCatching {
                if (link.startsWith("file:")) {
                    RuntimeEnv.onOpenPath?.invoke(Uri.parse(link).path ?: return@runOnUiThread)
                } else {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }

    @Keep
    @JvmStatic
    fun querySystemClipboard() {
        val activity = RuntimeEnv.activity ?: return ZLBridge.clipboardReceived(null, null)
        activity.runOnUiThread {
            val clip = (activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
            if (text == null) ZLBridge.clipboardReceived(null, null) else ZLBridge.clipboardReceived(text.toString(), "plain")
        }
    }

    @Keep
    @JvmStatic
    fun putClipboardData(data: String, mimeType: String) {
        val activity = RuntimeEnv.activity ?: return
        activity.runOnUiThread {
            val clip = when (mimeType) {
                "text/plain" -> ClipData.newPlainText("EririLily", data)
                "text/html" -> ClipData.newHtmlText("EririLily", data, data)
                else -> null
            } ?: return@runOnUiThread
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(clip)
        }
    }

    @Keep
    @JvmStatic
    fun jvmExit(exitCode: Int, isSignal: Boolean) {
        RuntimeEnv.onExit?.invoke(exitCode, isSignal)
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
