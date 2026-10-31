package com.aura.launcher.data

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@Immutable
data class StoreItem(
    val id: String,
    val name: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val sizeBytes: Long,
    val sha256: String,
    val apkUrl: String,
    val iconUrl: String,
    val bannerUrl: String,
    val summary: String,
    val category: String,
)

enum class StoreSource { NETWORK, CACHE, DEMO }

@Immutable
data class StoreResult(
    val title: String,
    val items: List<StoreItem>,
    val source: StoreSource,
    val message: String?,
)

enum class InstallState { NOT_INSTALLED, INSTALLED, UPDATABLE }

/**
 * 内置应用商店:从一份 JSON 清单读取应用列表,下载 → 校验 → 安装。
 * 清单托管在你自己的服务器 / 对象存储 / Gitee Pages 即可,国内访问稳定。
 */
object StoreRepository {

    suspend fun load(context: Context, url: String): StoreResult = withContext(Dispatchers.IO) {
        val cacheFile = File(context.filesDir, "store_cache.json")
        if (url.isBlank()) {
            return@withContext parse(readAsset(context), StoreSource.DEMO, "尚未配置商店源,当前显示内置示例清单")
        }
        try {
            val text = httpGet(url)
            val parsed = parse(text, StoreSource.NETWORK, null)
            cacheFile.writeText(text)
            parsed
        } catch (e: Exception) {
            val cached = runCatching { parse(cacheFile.readText(), StoreSource.CACHE, "网络不可用,显示上次缓存的清单") }
            cached.getOrElse {
                parse(readAsset(context), StoreSource.DEMO, "商店源无法访问(${e.message ?: "未知错误"}),显示内置示例清单")
            }
        }
    }

    private fun readAsset(context: Context): String =
        context.assets.open("store.json").bufferedReader().use { it.readText() }

    private fun httpGet(url: String): String {
        if (!url.startsWith("https://")) throw IOException("商店源必须使用 https")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("服务器返回 ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(text: String, source: StoreSource, message: String?): StoreResult {
        val root = JSONObject(text)
        val arr = root.optJSONArray("apps")
        val items = buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        StoreItem(
                            id = o.optString("id", o.optString("packageName")),
                            name = o.optString("name"),
                            packageName = o.getString("packageName"),
                            versionCode = o.optLong("versionCode", 0),
                            versionName = o.optString("versionName"),
                            sizeBytes = o.optLong("size", 0),
                            sha256 = o.optString("sha256"),
                            apkUrl = o.optString("apkUrl"),
                            iconUrl = o.optString("iconUrl"),
                            bannerUrl = o.optString("bannerUrl"),
                            summary = o.optString("summary"),
                            category = o.optString("category"),
                        )
                    )
                }
            }
        }
        return StoreResult(root.optString("name", "应用商店"), items, source, message)
    }

    fun stateOf(context: Context, item: StoreItem): InstallState {
        val info = runCatching { context.packageManager.getPackageInfo(item.packageName, 0) }.getOrNull()
            ?: return InstallState.NOT_INSTALLED
        return if (item.versionCode > info.longVersionCode) InstallState.UPDATABLE else InstallState.INSTALLED
    }

    /** 下载到缓存目录,校验 SHA-256 与包名后返回 APK 文件。协程取消时中断下载。 */
    suspend fun download(context: Context, item: StoreItem, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            if (!item.apkUrl.startsWith("https://")) throw IOException("下载地址必须是 https")
            val dir = File(context.cacheDir, "apk").apply { mkdirs() }
            val safeId = item.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val part = File(dir, "$safeId.apk.part")
            val out = File(dir, "$safeId.apk")

            val conn = (URL(item.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 15000
                instanceFollowRedirects = true
            }
            try {
                if (conn.responseCode !in 200..299) throw IOException("服务器返回 ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: item.sizeBytes
                val digest = MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { input ->
                    part.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var lastTick = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            ensureActive()
                            os.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            read += n
                            val now = System.nanoTime()
                            if (total > 0 && now - lastTick > 80_000_000L) {
                                lastTick = now
                                onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                if (item.sha256.isNotBlank()) {
                    val hex = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!hex.equals(item.sha256.trim(), ignoreCase = true)) {
                        part.delete()
                        throw IOException("文件校验失败(SHA-256 不一致)")
                    }
                }
                val info = context.packageManager.getPackageArchiveInfo(part.absolutePath, 0)
                if (info == null || info.packageName != item.packageName) {
                    part.delete()
                    throw IOException("安装包与清单中的包名不一致")
                }
                out.delete()
                if (!part.renameTo(out)) throw IOException("无法保存安装包")
                onProgress(1f)
                out
            } finally {
                conn.disconnect()
            }
        }
}
