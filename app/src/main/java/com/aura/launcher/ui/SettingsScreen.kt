package com.aura.launcher.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.aura.launcher.BuildConfig
import com.aura.launcher.data.AppGraph

private const val ADB_HOME_HELP =
    "把 Aura 设为默认桌面(推荐用 adb,最可靠):\n\n" +
        "1. 电视 设置 → 关于 → 连点「版本号」开启开发者选项,再打开「USB 调试 / 网络调试」\n" +
        "2. 电脑执行:adb connect 电视IP:5555\n" +
        "3. 再执行:adb shell cmd package set-home-activity com.aura.launcher/.MainActivity\n\n" +
        "恢复原厂桌面:在电视 设置 → 应用 中清除 Aura 桌面的默认设置,或卸载 Aura。\n" +
        "注意:部分索尼机型的遥控器「主页」键可能仍固定进入原厂桌面,这是系统限制。"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(graph: AppGraph, onToast: (String) -> Unit, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val prefs = graph.prefs
    val wallpaper by prefs.wallpaper.collectAsState()
    val storeUrl by prefs.storeUrl.collectAsState()
    val hidden by prefs.hidden.collectAsState()
    var dialog by remember { mutableStateOf<String?>(null) }
    val edge = remember(ui) { EdgeSpec(ui.px(150)) }

    CompositionLocalProvider(LocalBringIntoViewSpec provides edge) {
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = ui.dp(110), end = ui.dp(110), bottom = ui.dp(200)),
        ) {
            Text("设置", color = Palette.text, fontSize = ui.sp(40), fontWeight = FontWeight.SemiBold)

            Text(
                "壁纸", color = Palette.textDim, fontSize = ui.sp(28),
                modifier = Modifier.padding(top = ui.dp(28), bottom = ui.dp(4)),
            )
            Row(
                Modifier.padding(vertical = ui.dp(30)),
                horizontalArrangement = Arrangement.spacedBy(ui.dp(36)),
            ) {
                WALLPAPERS.forEachIndexed { i, p ->
                    TvTile(
                        width = ui.dp(300), height = ui.dp(169), accent = p.glowA, label = p.name,
                        onClick = { prefs.setWallpaper(i) },
                    ) {
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.linearGradient(listOf(p.base, lerp(p.base, p.glowA, 0.75f), lerp(p.base, p.glowB, 0.8f)))
                            )
                        )
                        if (i == wallpaper) {
                            Text(
                                "✓", color = Palette.text, fontSize = ui.sp(44), fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.TopEnd).padding(ui.dp(14)),
                            )
                        }
                    }
                }
            }

            Column(Modifier.widthIn(max = ui.dp(1300)), verticalArrangement = Arrangement.spacedBy(ui.dp(16))) {
                TvButton(
                    "设为默认桌面", Modifier.fillMaxWidth(),
                    subtitle = "让遥控器的主页键回到 Aura 桌面",
                    onClick = { dialog = "home" },
                )
                TvButton(
                    "商店源地址", Modifier.fillMaxWidth(),
                    subtitle = storeUrl.ifBlank { "未配置(使用内置示例清单)" },
                    onClick = { dialog = "url" },
                )
                TvButton(
                    "恢复隐藏的应用", Modifier.fillMaxWidth(),
                    trailing = "${hidden.size} 个",
                    onClick = {
                        if (hidden.isEmpty()) onToast("没有被隐藏的应用") else {
                            prefs.unhideAll()
                            onToast("已恢复全部隐藏的应用")
                        }
                    },
                )
                TvButton(
                    "系统设置", Modifier.fillMaxWidth(),
                    subtitle = "网络、显示、声音、应用管理等",
                    onClick = {
                        val ok = runCatching {
                            ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.isSuccess
                        if (!ok) onToast("无法打开系统设置")
                    },
                )
                TvButton(
                    "关于", Modifier.fillMaxWidth(),
                    subtitle = "Aura 桌面 ${BuildConfig.VERSION_NAME}",
                    onClick = { dialog = "about" },
                )
            }
        }
    }

    when (dialog) {
        "url" -> TextInputDialog(
            title = "商店源地址",
            initial = storeUrl,
            hint = "填写商店清单 JSON 的 https 地址;留空使用内置示例。",
            onConfirm = { prefs.setStoreUrl(it); onToast("商店源已更新,进入「应用商店」点刷新") },
            onDismiss = { dialog = null },
        )
        "home" -> MenuDialog(
            title = "设为默认桌面",
            subtitle = "不同机型入口不同,先试系统设置,不行就用 adb。",
            items = listOf(
                MenuItem("尝试打开系统主屏幕设置") {
                    val ok = runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.isSuccess
                    if (!ok) onToast("此电视没有该设置页,请使用 adb 方法")
                },
                MenuItem("查看 adb 方法") { dialog = "adb" },
                MenuItem("关闭") {},
            ),
            onDismiss = { if (dialog == "home") dialog = null },
        )
        "adb" -> InfoDialog("设为默认桌面", ADB_HOME_HELP, onDismiss = { dialog = null })
        "about" -> InfoDialog(
            "Aura 桌面 ${BuildConfig.VERSION_NAME}",
            "tvOS 风格的 Android TV 桌面。\n不依赖 Google 服务,无广告、无统计,所有设置只保存在本机。",
            onDismiss = { dialog = null },
        )
    }
}
