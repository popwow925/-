package com.aura.launcher.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import coil.compose.AsyncImage
import com.aura.launcher.data.AppGraph
import com.aura.launcher.data.ApkInstaller
import com.aura.launcher.data.InstallEvents
import com.aura.launcher.data.InstallState
import com.aura.launcher.data.PreApp
import com.aura.launcher.data.Preinstall
import com.aura.launcher.data.StoreItem
import com.aura.launcher.data.StoreRepository
import com.aura.launcher.data.StoreResult
import com.aura.launcher.data.StoreSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

private sealed interface Progress {
    data class Downloading(val fraction: Float) : Progress
    data object Installing : Progress
    data class Failed(val message: String) : Progress
}

private fun openApp(ctx: Context, pkg: String): Boolean {
    val pm = ctx.packageManager
    val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
    return runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StoreScreen(graph: AppGraph, onToast: (String) -> Unit, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val storeUrl by graph.prefs.storeUrl.collectAsState()
    val apps by graph.apps.apps.collectAsState() // 应用装/卸后刷新"已安装/可更新"状态
    val resume by graph.resumeTick.collectAsState()
    var reload by remember { mutableIntStateOf(0) }
    val result by produceState<StoreResult?>(null, storeUrl, reload) {
        value = null
        value = StoreRepository.load(ctx, storeUrl)
    }
    // 内置应用:随桌面分发的 APK;装/卸应用或回到桌面时重新扫描,以刷新"已安装/可更新"状态
    val preApps by produceState(emptyList<PreApp>(), apps, resume, reload) {
        value = Preinstall.scan(ctx)
    }
    val progress = remember { mutableStateMapOf<String, Progress>() }
    val jobs = remember { HashMap<String, Job>() }
    var focusedItem by remember { mutableStateOf<StoreItem?>(null) }
    var focusedPre by remember { mutableStateOf<PreApp?>(null) }
    var needInstallPermission by remember { mutableStateOf(false) }
    val edge = remember(ui) { EdgeSpec(ui.px(150)) }

    LaunchedEffect(Unit) {
        InstallEvents.flow.collect { progress.clear() }
    }

    fun act(item: StoreItem) {
        when (progress[item.id]) {
            is Progress.Downloading -> {
                jobs[item.id]?.cancel()
                progress.remove(item.id)
                onToast("已取消下载")
                return
            }
            is Progress.Installing -> return
            else -> Unit
        }
        if (StoreRepository.stateOf(ctx, item) == InstallState.INSTALLED) {
            if (!openApp(ctx, item.packageName)) onToast("无法打开「${item.name}」")
            return
        }
        if (!item.apkUrl.startsWith("https://")) {
            onToast("「${item.name}」没有可用的下载地址")
            return
        }
        if (!ApkInstaller.canRequestInstalls(ctx)) {
            needInstallPermission = true
            return
        }
        jobs[item.id] = scope.launch {
            progress[item.id] = Progress.Downloading(0f)
            try {
                val file = StoreRepository.download(ctx, item) { f -> progress[item.id] = Progress.Downloading(f) }
                progress[item.id] = Progress.Installing
                ApkInstaller.install(ctx, file).getOrThrow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: "下载失败"
                progress[item.id] = Progress.Failed(msg)
                onToast("「${item.name}」$msg")
            }
        }
    }

    fun actPre(app: PreApp) {
        if (app.state == InstallState.INSTALLED) {
            if (!openApp(ctx, app.packageName)) onToast("无法打开「${app.label}」")
            return
        }
        if (!ApkInstaller.canRequestInstalls(ctx)) {
            needInstallPermission = true
            return
        }
        scope.launch {
            onToast("正在提交安装:${app.label}")
            ApkInstaller.install(ctx, app.file).onFailure { onToast("安装失败:${it.message ?: "未知错误"}") }
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = ui.dp(110), end = ui.dp(110), bottom = ui.dp(18)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(result?.title ?: "应用商店", color = Palette.text, fontSize = ui.sp(40), fontWeight = FontWeight.SemiBold, maxLines = 1)
                val f = focusedItem
                val p = focusedPre
                val sub = when {
                    p != null -> buildString {
                        append("内置应用  ·  ").append(p.packageName)
                        if (p.versionName.isNotBlank()) append("  ·  ").append(p.versionName)
                        append("  ·  ").append("%.1f MB".format(p.sizeBytes / 1048576.0))
                    }
                    result == null -> "正在加载清单……"
                    f != null -> buildString {
                        append(f.summary.ifBlank { f.packageName })
                        if (f.versionName.isNotBlank()) append("  ·  ").append(f.versionName)
                        if (f.sizeBytes > 0) append("  ·  ").append("%.1f MB".format(f.sizeBytes / 1048576.0))
                    }
                    else -> result?.message ?: "选中应用后按 OK 键下载并安装"
                }
                Text(sub, color = Palette.textDim, fontSize = ui.sp(26), maxLines = 1, modifier = Modifier.padding(top = ui.dp(6)))
            }
            TvButton("刷新", Modifier.width(ui.dp(200)), onClick = { reload++ })
        }

        val storeItems = result?.items.orEmpty()
        if (result != null && storeItems.isEmpty()) {
            Text("清单里还没有应用。", color = Palette.textDim, fontSize = ui.sp(30), modifier = Modifier.padding(start = ui.dp(110), top = ui.dp(30)))
        }
        if (result?.source == StoreSource.DEMO) {
            Text(
                "提示:当前是内置示例清单,示例条目没有真实下载地址。请到「设置 → 商店源地址」填入你自己的清单。",
                color = Palette.textFaint, fontSize = ui.sp(24),
                modifier = Modifier.padding(start = ui.dp(110), end = ui.dp(110), bottom = ui.dp(10)),
            )
        }

        CompositionLocalProvider(LocalBringIntoViewSpec provides edge) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = ui.dp(110), end = ui.dp(110), top = ui.dp(48), bottom = ui.dp(200)),
                horizontalArrangement = Arrangement.spacedBy(ui.dp(40)),
                verticalArrangement = Arrangement.spacedBy(ui.dp(24)),
            ) {
                if (preApps.isNotEmpty()) {
                    item(key = "h-pre", span = { GridItemSpan(maxLineSpan) }) { SectionTitle("内置应用") }
                    items(preApps, key = { "pre-" + it.packageName }) { app ->
                        PreTile(
                            app = app,
                            onClick = { actPre(app) },
                            onFocused = { focusedPre = app; focusedItem = null },
                        )
                    }
                    if (storeItems.isNotEmpty()) {
                        item(key = "h-store", span = { GridItemSpan(maxLineSpan) }) { SectionTitle("更多应用") }
                    }
                }
                items(storeItems, key = { it.id }) { item ->
                    val state = remember(item, apps, resume) { StoreRepository.stateOf(ctx, item) }
                    StoreTile(
                        item = item, state = state, progress = progress[item.id],
                        onClick = { act(item) },
                        onFocused = { focusedItem = item; focusedPre = null },
                    )
                }
            }
        }
    }

    if (needInstallPermission) {
        MenuDialog(
            title = "需要允许安装未知应用",
            subtitle = "请在接下来的页面里为「Aura 桌面」打开「允许安装未知应用」,然后返回再点一次。",
            items = listOf(
                MenuItem("前往设置") {
                    if (!ApkInstaller.openInstallPermissionSettings(ctx)) {
                        onToast("此电视没有该设置页,请在 设置 → 应用 → 特殊应用权限 中开启")
                    }
                },
                MenuItem("取消") {},
            ),
            onDismiss = { needInstallPermission = false },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    val ui = LocalUi.current
    Text(
        text, color = Palette.text, fontSize = ui.sp(30), fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = ui.dp(8)),
    )
}

/** 内置应用磁贴:图标取自 APK 本身,左上角带"内置"角标。 */
@Composable
private fun PreTile(app: PreApp, onClick: () -> Unit, onFocused: () -> Unit) {
    val ui = LocalUi.current
    val status = when (app.state) {
        InstallState.NOT_INSTALLED -> "安装"
        InstallState.INSTALLED -> "已安装 · 打开"
        InstallState.UPDATABLE -> "更新"
    }
    TvTile(
        width = ui.dp(540), height = ui.dp(304), accent = app.accent,
        label = app.label, onClick = onClick, onFocused = onFocused,
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(lerp(app.accent, Color.White, 0.14f), lerp(app.accent, Color.Black, 0.55f)))
            )
        )
        val icon = app.icon
        if (icon != null) {
            Image(
                bitmap = icon, contentDescription = null,
                modifier = Modifier.align(Alignment.Center).fillMaxHeight(0.5f).aspectRatio(1f),
            )
        } else {
            Text(
                app.label.take(1), color = Palette.text.copy(alpha = 0.9f), fontSize = ui.sp(120),
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center),
            )
        }
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(ui.dp(16))
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.22f))
                .padding(horizontal = ui.dp(18), vertical = ui.dp(6)),
        ) {
            Text("内置", color = Palette.text, fontSize = ui.sp(22), maxLines = 1)
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(ui.dp(16))
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.58f))
                .padding(horizontal = ui.dp(20), vertical = ui.dp(8)),
        ) {
            Text(status, color = Palette.text, fontSize = ui.sp(24), maxLines = 1)
        }
    }
}

@Composable
private fun StoreTile(
    item: StoreItem,
    state: InstallState,
    progress: Progress?,
    onClick: () -> Unit,
    onFocused: () -> Unit,
) {
    val ui = LocalUi.current
    val hue = (abs(item.name.hashCode()) % 360).toFloat()
    val accent = Color.hsv(hue, 0.55f, 0.62f)
    val status = when (progress) {
        is Progress.Downloading -> "下载中 ${(progress.fraction * 100).toInt()}%"
        is Progress.Installing -> "等待系统确认…"
        is Progress.Failed -> "失败,点击重试"
        null -> when (state) {
            InstallState.NOT_INSTALLED -> "获取"
            InstallState.INSTALLED -> "已安装 · 打开"
            InstallState.UPDATABLE -> "更新"
        }
    }
    TvTile(
        width = ui.dp(540), height = ui.dp(304), accent = accent,
        label = item.name, onClick = onClick, onFocused = onFocused,
    ) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(lerp(accent, Color.White, 0.14f), lerp(accent, Color.Black, 0.55f)))))
        when {
            item.bannerUrl.isNotBlank() ->
                AsyncImage(item.bannerUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            item.iconUrl.isNotBlank() ->
                AsyncImage(item.iconUrl, null, Modifier.align(Alignment.Center).fillMaxHeight(0.5f), contentScale = ContentScale.Fit)
            else -> Text(
                item.name.take(1), color = Palette.text.copy(alpha = 0.9f), fontSize = ui.sp(120),
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center),
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(ui.dp(16))
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.58f))
                .padding(horizontal = ui.dp(20), vertical = ui.dp(8)),
        ) {
            Text(status, color = Palette.text, fontSize = ui.sp(24), maxLines = 1)
        }
        if (progress is Progress.Downloading) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress.fraction.coerceIn(0.02f, 1f))
                    .height(ui.dp(6))
                    .background(Color.White)
            )
        }
    }
}
