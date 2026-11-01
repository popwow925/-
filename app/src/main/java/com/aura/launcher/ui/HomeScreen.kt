package com.aura.launcher.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.aura.launcher.data.AppEntry
import com.aura.launcher.data.AppGraph
import com.aura.launcher.data.SignalSource
import com.aura.launcher.data.TileArtLoader
import com.aura.launcher.data.TvInputs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private class ShortcutItem(val key: String, val title: String, val accent: Color, val action: () -> Unit)

@Composable
fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(60_000L - value % 60_000L + 50L)
    }
}

private fun greeting(now: Long): String {
    val h = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
    return when (h) {
        in 0..4 -> "夜深了"
        in 5..10 -> "早上好"
        in 11..12 -> "中午好"
        in 13..17 -> "下午好"
        else -> "晚上好"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    graph: AppGraph,
    heroLabel: String?,
    onHeroLabel: (String?) -> Unit,
    focusToken: Int,
    onLaunch: (AppEntry) -> Unit,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bridge = LocalFocusBridge.current

    val apps by graph.apps.apps.collectAsState()
    val favorites by graph.prefs.favorites.collectAsState()
    val hidden by graph.prefs.hidden.collectAsState()
    val recents by graph.prefs.recents.collectAsState()

    val visible = remember(apps, hidden) { apps.filter { it.packageName !in hidden } }
    val byPkg = remember(visible) { visible.associateBy { it.packageName } }
    val favApps = remember(visible, favorites) {
        if (favorites.isEmpty()) visible.take(10) else favorites.mapNotNull { byPkg[it] }
    }
    val recentApps = remember(visible, recents) { recents.mapNotNull { byPkg[it] }.take(10) }

    val sources by produceState(emptyList<SignalSource>()) {
        value = withContext(Dispatchers.IO) { TvInputs.list(ctx) }
    }
    val shortcuts = remember(sources) {
        buildList {
            sources.forEach { s ->
                add(ShortcutItem("src:${s.id}", s.label, Color(0xFF2F6BFF)) {
                    if (!TvInputs.open(ctx, s)) onToast("无法切换到「${s.label}」")
                })
            }
            listOf(
                "网络" to Settings.ACTION_WIFI_SETTINGS,
                "蓝牙" to Settings.ACTION_BLUETOOTH_SETTINGS,
                "显示" to Settings.ACTION_DISPLAY_SETTINGS,
                "声音" to Settings.ACTION_SOUND_SETTINGS,
                "全部设置" to Settings.ACTION_SETTINGS,
            ).forEach { (t, action) ->
                add(ShortcutItem("set:$action", t, Color(0xFF5B6478)) {
                    if (!openSettings(ctx, action)) onToast("此电视没有「$t」设置页面")
                })
            }
        }
    }

    var menuApp by remember { mutableStateOf<AppEntry?>(null) }
    val scroll = rememberScrollState()
    val rowTops = remember { FloatArray(4) }
    var currentRow by remember { mutableIntStateOf(-1) }
    val firstTile = remember { FocusRequester() }
    // 最近一次获得焦点的磁贴;从顶栏按"下"时回到这里。普通数组,更新它不会触发重组
    val lastTile = remember { arrayOfNulls<FocusRequester>(1) }

    DisposableEffect(bridge) {
        val enter: () -> Boolean = {
            listOfNotNull(lastTile[0], firstTile).any { r -> runCatching { r.requestFocus() }.isSuccess }
        }
        bridge.enterContent = enter
        // 切换标签时新旧页面会短暂同时存在,只清除自己登记的那一份
        onDispose { if (bridge.enterContent === enter) bridge.enterContent = null }
    }

    // 提前把图标和横幅都解码好,聚焦/滚动时磁贴不会"先空后闪出图"
    LaunchedEffect(favApps, recentApps) {
        (favApps + recentApps).forEach { TileArtLoader.load(ctx, it) }
    }

    // 启动时自动聚焦第一个磁贴,但此时保留时钟大字,等用户第一次按方向键才切换成应用名
    var suppressHero by remember { mutableStateOf(false) }
    LaunchedEffect(focusToken, favApps.isNotEmpty()) {
        suppressHero = true
        delay(120)
        runCatching { firstTile.requestFocus() }
        delay(250)
        suppressHero = false
    }

    fun focusRow(i: Int) {
        if (currentRow == i) return
        currentRow = i
        val target = if (i == 0) 0 else (rowTops[i] - ui.px(150)).toInt().coerceAtLeast(0)
        scope.launch { scroll.animateScrollTo(target, spring(dampingRatio = 1f, stiffness = 330f)) }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides NoScrollSpec) {
        Column(modifier.fillMaxSize().verticalScroll(scroll)) {
            Hero(
                heroLabel = heroLabel,
                modifier = Modifier.graphicsLayer {
                    alpha = (1f - scroll.value / ui.px(380)).coerceIn(0f, 1f)
                },
            )

            if (favApps.isEmpty()) {
                Text(
                    "还没有读取到应用,稍等片刻……",
                    color = Palette.textDim, fontSize = ui.sp(30),
                    modifier = Modifier.padding(start = ui.dp(110), top = ui.dp(40)),
                )
            }

            TileRow(
                title = "常用应用", items = favApps, key = { it.packageName },
                onPositioned = { rowTops[0] = it },
                // 第一行按"上":回到当前选中的标签(默认的就近查找会落到正上方随便哪个标签)
                onUp = { bridge.enterTabs?.invoke() == true },
            ) { index, app ->
                val own = remember { FocusRequester() }
                val fr = if (index == 0) firstTile else own
                AppTile(
                    entry = app, width = ui.dp(400),
                    focusRequester = fr,
                    onClick = { onLaunch(app) },
                    onLongClick = { menuApp = app },
                    onFocused = { if (!suppressHero) onHeroLabel(app.label); lastTile[0] = fr; focusRow(0) },
                )
            }

            if (recentApps.isNotEmpty()) {
                TileRow(
                    title = "最近使用", items = recentApps, key = { "r:" + it.packageName },
                    onPositioned = { rowTops[1] = it },
                ) { _, app ->
                    val own = remember { FocusRequester() }
                    AppTile(
                        entry = app, width = ui.dp(310),
                        focusRequester = own,
                        onClick = { onLaunch(app) },
                        onLongClick = { menuApp = app },
                        onFocused = { onHeroLabel(app.label); lastTile[0] = own; focusRow(1) },
                    )
                }
            }

            TileRow(
                title = if (sources.isNotEmpty()) "信号源与快捷设置" else "快捷设置",
                items = shortcuts, key = { it.key },
                onPositioned = { rowTops[2] = it },
            ) { _, s ->
                val own = remember { FocusRequester() }
                ShortcutTile(
                    title = s.title, width = ui.dp(310), accent = s.accent,
                    focusRequester = own,
                    onClick = s.action,
                    onFocused = { onHeroLabel(null); lastTile[0] = own; focusRow(2) },
                )
            }

            Box(Modifier.height(ui.dp(520)))
        }
    }

    menuApp?.let { app ->
        AppActionDialog(app, favorites, graph.apps, graph.prefs, onLaunch = onLaunch, onDismiss = { menuApp = null })
    }
}

private fun openSettings(ctx: Context, action: String): Boolean = runCatching {
    ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess

@Composable
private fun Hero(heroLabel: String?, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val now by rememberNow()
    Box(modifier.fillMaxWidth().height(ui.dp(330)).padding(start = ui.dp(110), top = ui.dp(36))) {
        Crossfade(targetState = heroLabel, animationSpec = tween(240), label = "hero") { label ->
            if (label == null) {
                Column {
                    Text(greeting(now), color = Palette.textDim, fontSize = ui.sp(32))
                    Text(
                        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(now)),
                        color = Palette.text, fontSize = ui.sp(150), fontWeight = FontWeight.Light,
                    )
                    Text(
                        SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date(now)),
                        color = Palette.textDim, fontSize = ui.sp(34),
                    )
                }
            } else {
                Column {
                    Text("已选中", color = Palette.textDim, fontSize = ui.sp(32))
                    Text(
                        label, color = Palette.text, fontSize = ui.sp(96), fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text("按住 OK 键可管理此应用", color = Palette.textFaint, fontSize = ui.sp(30))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun <T> TileRow(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    onPositioned: (Float) -> Unit,
    onUp: (() -> Boolean)? = null,
    itemContent: @Composable (Int, T) -> Unit,
) {
    if (items.isEmpty()) return
    val ui = LocalUi.current
    val edge = remember(ui) { EdgeSpec(ui.px(150)) }
    val curUp by rememberUpdatedState(onUp)
    Column(
        Modifier
            .onGloballyPositioned { onPositioned(it.positionInParent().y) }
            .onPreviewKeyEvent { e ->
                e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp && curUp?.invoke() == true
            }
    ) {
        Text(
            title, color = Palette.text, fontSize = ui.sp(36), fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = ui.dp(110), bottom = ui.dp(6)),
        )
        CompositionLocalProvider(LocalBringIntoViewSpec provides edge) {
            LazyRow(
                // 下方多留一点空间给聚焦磁贴的光晕,否则光晕会在行的裁剪边界处被切出一条横线
                contentPadding = PaddingValues(start = ui.dp(110), end = ui.dp(110), top = ui.dp(52), bottom = ui.dp(84)),
                horizontalArrangement = Arrangement.spacedBy(ui.dp(40)),
            ) {
                itemsIndexed(items, key = { _, it -> key(it) }) { index, item -> itemContent(index, item) }
            }
        }
    }
}
