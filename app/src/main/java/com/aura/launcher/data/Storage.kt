package com.aura.launcher.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import java.io.File

@Immutable
data class FileItem(
    val file: File,
    val label: String,
    val isDir: Boolean,
    val isApk: Boolean,
)

/** U 盘 / 内部存储浏览,只列出文件夹和 .apk 文件。 */
object Storage {

    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** Android 11+ 打开「所有文件访问」设置页;失败(部分电视没有该页面)返回 false。 */
    fun openAllFilesSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val pkg = Uri.parse("package:${context.packageName}")
        val tries = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
        for (i in tries) {
            if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
        }
        return false
    }

    fun adbGrantCommand(context: Context): String =
        "adb shell appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow"

    fun roots(): List<FileItem> {
        val list = mutableListOf<FileItem>()
        val internal = Environment.getExternalStorageDirectory()
        list += FileItem(internal, "内部存储", isDir = true, isApk = false)
        File("/storage").listFiles()?.forEach { f ->
            if (f.isDirectory && f.name != "emulated" && f.name != "self" && f.canRead()) {
                list += FileItem(f, "U盘 / 存储卡(${f.name})", isDir = true, isApk = false)
            }
        }
        return list
    }

    fun list(dir: File): List<FileItem> {
        val children = dir.listFiles() ?: return emptyList()
        return children
            .asSequence()
            .filter { !it.name.startsWith(".") }
            .filter { it.isDirectory || it.extension.equals("apk", ignoreCase = true) }
            .map { FileItem(it, it.name, it.isDirectory, !it.isDirectory) }
            .sortedWith(compareByDescending<FileItem> { it.isDir }.thenBy { it.label.lowercase() })
            .toList()
    }
}
