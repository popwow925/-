package com.aura.launcher.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

@Immutable
data class AppEntry(
    val packageName: String,
    val activityName: String,
    val label: String,
) {
    val component: ComponentName get() = ComponentName(packageName, activityName)
}

/** 读取已安装应用,并在安装/卸载/更新时自动刷新。 */
class AppRepository(private val context: Context) {
    private val pm = context.packageManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var reloadJob: Job? = null

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = reload()
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        reload()
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private var firstLoad = true

    fun reload() {
        reloadJob?.cancel()
        val wait = !firstLoad
        firstLoad = false
        reloadJob = scope.launch {
            if (wait) delay(150) // 合并短时间内连续到来的广播;开机第一次立即加载
            _apps.value = query()
        }
    }

    private fun query(): List<AppEntry> {
        val self = context.packageName
        val found = LinkedHashMap<String, AppEntry>()
        // 先查电视入口(LEANBACK),再补手机入口(LAUNCHER),每个包只保留一个入口
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == self || found.containsKey(ai.packageName)) continue
                found[ai.packageName] = AppEntry(ai.packageName, ai.name, ri.loadLabel(pm).toString())
            }
        }
        val collator = Collator.getInstance(Locale.CHINA)
        return found.values.sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    fun launch(entry: AppEntry): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .setComponent(entry.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        )
    }.isSuccess

    fun uninstall(packageName: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun openAppInfo(packageName: String) {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
