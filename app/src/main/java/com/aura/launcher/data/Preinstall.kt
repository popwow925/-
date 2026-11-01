package com.aura.launcher.data

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 一个"内置应用":随桌面一起分发、无需联网即可安装的 APK。 */
@Immutable
data class PreApp(
    val file: File,
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val sizeBytes: Long,
    val state: InstallState,
    val icon: ImageBitmap?,
    val accent: Color,
)

/**
 * 内置应用有两个来源,会合并显示(同一包名取版本更高的):
 *  1. 编译进桌面安装包的 `assets/preinstall/*.apk`;
 *  2. 外部目录 `/sdcard/Android/data/<包名>/files/preinstall/*.apk`,
 *     可以用 `adb push` 直接放入,不用重新编译。
 */
object Preinstall {
    const val DIR = "preinstall"

    fun externalDir(context: Context): File? = context.getExternalFilesDir(DIR)

    suspend fun scan(context: Context): List<PreApp> = withContext(Dispatchers.IO) {
        val files = buildList {
            addAll(assetFiles(context))
            externalDir(context)
                ?.listFiles { f -> f.isFile && f.name.endsWith(".apk", ignoreCase = true) }
                ?.let { addAll(it) }
        }
        val pm = context.packageManager
        files.mapNotNull { f -> runCatching { describe(pm, f) }.getOrNull() }
            .groupBy { it.packageName }
            .map { (_, list) -> list.maxByOrNull { it.versionCode }!! }
            .sortedBy { it.label }
    }

    /** 把 assets 里的 APK 复制到缓存目录(PackageInstaller 需要普通文件)。桌面升级后缓存目录随之更换。 */
    private fun assetFiles(context: Context): List<File> {
        val names = runCatching { context.assets.list(DIR) }.getOrNull().orEmpty()
            .filter { it.endsWith(".apk", ignoreCase = true) }
        if (names.isEmpty()) return emptyList()

        val stamp = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L).toString()
        val root = File(context.cacheDir, DIR)
        root.listFiles()?.forEach { if (it.name != stamp) it.deleteRecursively() }
        val dir = File(root, stamp).apply { mkdirs() }

        return names.map { name ->
            val out = File(dir, name)
            if (!out.exists() || out.length() == 0L) {
                val part = File(dir, "$name.part")
                context.assets.open("$DIR/$name").use { input ->
                    part.outputStream().use { os -> input.copyTo(os, 128 * 1024) }
                }
                out.delete()
                part.renameTo(out)
            }
            out
        }
    }

    private fun describe(pm: PackageManager, f: File): PreApp? {
        val pi = pm.getPackageArchiveInfo(f.absolutePath, 0) ?: return null
        val ai = pi.applicationInfo ?: return null
        ai.sourceDir = f.absolutePath
        ai.publicSourceDir = f.absolutePath

        val label = ai.loadLabel(pm).toString().ifBlank { pi.packageName }
        val bmp = runCatching { ai.loadIcon(pm).toBitmap(160, 160, Bitmap.Config.ARGB_8888) }.getOrNull()
        val code = pi.longVersionCode
        val installed = runCatching { pm.getPackageInfo(pi.packageName, 0) }.getOrNull()
        val state = when {
            installed == null -> InstallState.NOT_INSTALLED
            code > installed.longVersionCode -> InstallState.UPDATABLE
            else -> InstallState.INSTALLED
        }
        return PreApp(
            file = f,
            packageName = pi.packageName,
            label = label,
            versionName = pi.versionName ?: "",
            versionCode = code,
            sizeBytes = f.length(),
            state = state,
            icon = bmp?.asImageBitmap(),
            accent = bmp?.let { TileArtLoader.accentOf(it) } ?: Color(0xFF3A3F5C),
        )
    }
}
