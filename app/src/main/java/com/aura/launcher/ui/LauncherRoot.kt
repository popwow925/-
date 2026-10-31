package com.aura.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import com.aura.launcher.data.AppEntry
import com.aura.launcher.data.AppGraph
import com.aura.launcher.data.InstallEvents
import kotlinx.coroutines.delay

enum class Tab(val title: String) {
    HOME("主页"), APPS("全部应用"), STORE("应用商店"), INSTALL("本地安装"), SETTINGS("设置")
}

@Composable
fun LauncherRoot(graph: AppGraph, homeSignal: Int, onRequestStoragePermission: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current.density
        val ui = remember(maxWidth, density) { Ui(maxWidth.value / 1920f, density) }
        val navDir = remember { NavDir() }
        var tab by remember { mutableStateOf(Tab.HOME) }
        var heroLabel by remember { mutableStateOf<String?>(null) }
        var focusToken by remember { mutableIntStateOf(0) }
        var toast by remember { mutableStateOf<String?>(null) }
        val wallpaper by graph.prefs.wallpaper.collectAsState()

        // 按遥控器"主页"键回到桌面:切回主页并把焦点放回第一个应用
        LaunchedEffect(homeSignal) {
            if (homeSignal > 0) {
                tab = Tab.HOME
                heroLabel = null
                focusToken++
            }
        }
        LaunchedEffect(Unit) { InstallEvents.flow.collect { toast = it } }
        LaunchedEffect(toast) {
            if (toast != null) {
                delay(3500)
                toast = null
            }
        }

        // 桌面本身不应被"返回"键退出
        BackHandler(enabled = true) { if (tab != Tab.HOME) tab = Tab.HOME }

        val launch: (AppEntry) -> Unit = { entry ->
            if (!graph.launch(entry)) toast = "无法打开「${entry.label}」"
        }

        CompositionLocalProvider(LocalUi provides ui, LocalNavDir provides navDir) {
            Box(
                Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown) {
                        when (e.key) {
                            Key.DirectionLeft -> { navDir.dx = -1f; navDir.dy = 0f }
                            Key.DirectionRight -> { navDir.dx = 1f; navDir.dy = 0f }
                            Key.DirectionUp -> { navDir.dx = 0f; navDir.dy = -1f }
                            Key.DirectionDown -> { navDir.dx = 0f; navDir.dy = 1f }
                            else -> Unit
                        }
                    }
                    false
                }
            ) {
                WallpaperBackground(wallpaper)

                Column(Modifier.fillMaxSize()) {
                    TopBar(tab, onSelect = { tab = it }, onFocusTab = { heroLabel = null })
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Crossfade(tab, Modifier.fillMaxSize(), animationSpec = tween(220), label = "tab") { t ->
                            when (t) {
                                Tab.HOME -> HomeScreen(
                                    graph = graph, heroLabel = heroLabel, onHeroLabel = { heroLabel = it },
                                    focusToken = focusToken, onLaunch = launch, onToast = { toast = it },
                                )
                                Tab.APPS -> AppsScreen(graph, launch)
                                Tab.STORE -> StoreScreen(graph, onToast = { toast = it })
                                Tab.INSTALL -> InstallScreen(graph, onRequestStoragePermission, onToast = { toast = it })
                                Tab.SETTINGS -> SettingsScreen(graph, onToast = { toast = it })
                            }
                        }
                    }
                }

                Crossfade(
                    toast, Modifier.align(Alignment.BottomCenter).padding(bottom = ui.dp(64)),
                    animationSpec = tween(200), label = "toast",
                ) { t ->
                    if (t != null) {
                        Text(
                            t, color = Palette.text, fontSize = ui.sp(28),
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0xE61B1E2C))
                                .padding(horizontal = ui.dp(40), vertical = ui.dp(18)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(selected: Tab, onSelect: (Tab) -> Unit, onFocusTab: () -> Unit) {
    val ui = LocalUi.current
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.40f), Color.Transparent)))
            .height(ui.dp(150))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = ui.dp(40)),
            horizontalArrangement = Arrangement.spacedBy(ui.dp(14), Alignment.CenterHorizontally),
        ) {
            Tab.entries.forEach { t ->
                TabItem(
                    title = t.title, selected = t == selected,
                    onFocused = onFocusTab, onClick = { onSelect(t) },
                )
            }
        }
    }
}
