package com.aura.launcher.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.aura.launcher.data.ApkInstaller
import com.aura.launcher.data.AppGraph
import com.aura.launcher.data.FileItem
import com.aura.launcher.data.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 本地安装:浏览内部存储 / U 盘,选中 APK 即安装。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InstallScreen(
    graph: AppGraph,
    onRequestStoragePermission: () -> Unit,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val resume by graph.resumeTick.collectAsState()
    val hasAccess = remember(resume) { Storage.hasAccess(ctx) }
    val canInstall = remember(resume) { ApkInstaller.canRequestInstalls(ctx) }
    var dir by remember { mutableStateOf<File?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val edge = remember(ui) { EdgeSpec(ui.px(150)) }

    val entries by produceState(emptyList<FileItem>(), dir, resume, hasAccess) {
        value = if (!hasAccess) emptyList() else withContext(Dispatchers.IO) {
            dir?.let { Storage.list(it) } ?: Storage.roots()
        }
    }

    fun parentOf(d: File): File? {
        val isRoot = Storage.roots().any { it.file.absolutePath == d.absolutePath }
        return if (isRoot) null else d.parentFile
    }

    BackHandler(enabled = dir != null) { dir = dir?.let { parentOf(it) } }

    fun install(file: File) {
        if (!ApkInstaller.canRequestInstalls(ctx)) {
            dialog = "unknown"
            return
        }
        scope.launch {
            onToast("正在提交安装:${file.name}")
            ApkInstaller.install(ctx, file).onFailure { onToast("安装失败:${it.message ?: "未知错误"}") }
        }
    }

    Column(
        modifier.fillMaxSize().padding(start = ui.dp(110), end = ui.dp(110)),
        verticalArrangement = Arrangement.spacedBy(ui.dp(8)),
    ) {
        Text("本地安装", color = Palette.text, fontSize = ui.sp(40), fontWeight = FontWeight.SemiBold)
        Text(
            when {
                !hasAccess -> "需要先授予文件访问权限,才能读取 U 盘"
                dir == null -> "选择存储位置,插入 U 盘后会自动出现"
                else -> dir!!.absolutePath
            },
            color = Palette.textDim, fontSize = ui.sp(26), maxLines = 1,
        )

        if (!hasAccess) {
            Column(
                Modifier.padding(top = ui.dp(24)).widthIn(max = ui.dp(1200)),
                verticalArrangement = Arrangement.spacedBy(ui.dp(16)),
            ) {
                TvButton(
                    "授予文件访问权限",
                    subtitle = if (Build.VERSION.SDK_INT >= 30) "将打开系统的「所有文件访问」页面" else "将弹出系统权限请求",
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 30) {
                            if (!Storage.openAllFilesSettings(ctx)) dialog = "adb"
                        } else onRequestStoragePermission()
                    },
                )
                Text(
                    "如果这台电视没有该设置页面,可用电脑执行:\n${Storage.adbGrantCommand(ctx)}",
                    color = Palette.textFaint, fontSize = ui.sp(24), lineHeight = ui.sp(36),
                )
            }
        } else {
            if (!canInstall) {
                TvButton(
                    "允许安装未知应用",
                    subtitle = "安装前需要为 Aura 桌面打开此权限",
                    modifier = Modifier.padding(top = ui.dp(12)).widthIn(max = ui.dp(1200)),
                    onClick = { dialog = "unknown" },
                )
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides edge) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = ui.dp(20), bottom = ui.dp(200)),
                    verticalArrangement = Arrangement.spacedBy(ui.dp(14)),
                ) {
                    val cur = dir
                    if (cur != null) {
                        item(key = "up") {
                            TvButton("返回上一级", Modifier.widthIn(max = ui.dp(1400)).fillMaxWidth(), onClick = { dir = parentOf(cur) })
                        }
                    }
                    if (entries.isEmpty() && cur != null) {
                        item(key = "empty") {
                            Text("这个文件夹里没有 APK 或子文件夹。", color = Palette.textDim, fontSize = ui.sp(28))
                        }
                    }
                    items(entries, key = { it.file.absolutePath }) { f ->
                        if (f.isDir) {
                            TvButton(
                                f.label, Modifier.widthIn(max = ui.dp(1400)).fillMaxWidth(),
                                trailing = "打开 ›", onClick = { dir = f.file },
                            )
                        } else {
                            ApkRow(f.file) { install(f.file) }
                        }
                    }
                }
            }
        }
    }

    when (dialog) {
        "unknown" -> MenuDialog(
            title = "需要允许安装未知应用",
            subtitle = "请在接下来的页面里为「Aura 桌面」打开开关,返回后再选择 APK。",
            items = listOf(
                MenuItem("前往设置") {
                    if (!ApkInstaller.openInstallPermissionSettings(ctx)) {
                        onToast("此电视没有该设置页,请在 设置 → 应用 → 特殊应用权限 中开启")
                    }
                },
                MenuItem("取消") {},
            ),
            onDismiss = { dialog = null },
        )
        "adb" -> InfoDialog(
            "无法打开系统页面",
            "请用电脑通过 adb 连接电视后执行:\n${Storage.adbGrantCommand(ctx)}\n然后回到这里即可。",
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun ApkRow(file: File, onClick: () -> Unit) {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val info by produceState<String?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val pm = ctx.packageManager
                val pi = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: return@runCatching null
                val ai = pi.applicationInfo ?: return@runCatching null
                ai.sourceDir = file.absolutePath
                ai.publicSourceDir = file.absolutePath
                "${ai.loadLabel(pm)}  ·  ${pi.versionName ?: ""}  ·  ${"%.1f MB".format(file.length() / 1048576.0)}"
            }.getOrNull()
        }
    }
    TvButton(
        file.name,
        Modifier.widthIn(max = ui.dp(1400)).fillMaxWidth(),
        subtitle = info ?: "%.1f MB".format(file.length() / 1048576.0),
        trailing = "安装",
        onClick = onClick,
    )
}
