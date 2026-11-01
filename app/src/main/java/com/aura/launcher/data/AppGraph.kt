package com.aura.launcher.data

import android.content.Context

/** 应用级单例集合,由 MainActivity 创建并向下传递。 */
class AppGraph(context: Context) {
    val apps = AppRepository(context.applicationContext)
    val prefs = Prefs(context.applicationContext)

    /** 家庭中心(影视库)连接。 */
    val hub = HubRepository(prefs, context.applicationContext)

    /** 每次回到桌面(onResume)或权限结果返回时 +1,界面据此重新检查权限/安装状态。 */
    val resumeTick = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** 启动应用并记入"最近使用";失败返回 false。 */
    fun launch(entry: AppEntry): Boolean {
        val ok = apps.launch(entry)
        if (ok) prefs.markLaunched(entry.packageName)
        return ok
    }
}
