package com.aura.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.Color as AColor

/** 一个应用磁贴所需的全部图像:横幅(可空)、图标、主题色。 */
@Immutable
data class TileArt(
    val banner: ImageBitmap?,
    val icon: ImageBitmap,
    val accent: Color,
)

object TileArtLoader {
    private val cache = LruCache<String, TileArt>(96)

    fun peek(packageName: String): TileArt? = cache.get(packageName)

    suspend fun load(context: Context, entry: AppEntry): TileArt {
        cache.get(entry.packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val art = runCatching {
                val ai = pm.getActivityInfo(entry.component, 0)
                val icon = ai.loadIcon(pm).toBitmap(160, 160, Bitmap.Config.ARGB_8888)
                val banner = (ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm))
                    ?.toBitmap(480, 270, Bitmap.Config.ARGB_8888)
                TileArt(banner?.asImageBitmap(), icon.asImageBitmap(), accentOf(icon))
            }.getOrElse {
                val icon = pm.defaultActivityIcon.toBitmap(160, 160, Bitmap.Config.ARGB_8888)
                TileArt(null, icon.asImageBitmap(), Color(0xFF3A3F5C))
            }
            cache.put(entry.packageName, art)
            art
        }
    }

    /** 取图标里最鲜艳的颜色,再压到适合做背景的明度,用来生成磁贴渐变底色。 */
    fun accentOf(bmp: Bitmap): Color {
        val small = Bitmap.createScaledBitmap(bmp, 8, 8, true)
        var bestScore = -1f
        var best = 0
        val hsv = FloatArray(3)
        for (x in 0 until 8) for (y in 0 until 8) {
            val p = small.getPixel(x, y)
            if (AColor.alpha(p) < 200) continue
            AColor.colorToHSV(p, hsv)
            val score = hsv[1] * hsv[2]
            if (score > bestScore) {
                bestScore = score
                best = p
            }
        }
        if (small !== bmp) small.recycle()
        if (bestScore < 0f) return Color(0xFF3A3F5C)
        AColor.colorToHSV(best, hsv)
        hsv[1] = hsv[1].coerceIn(0.35f, 0.85f)
        hsv[2] = hsv[2].coerceIn(0.45f, 0.80f)
        return Color(AColor.HSVToColor(hsv))
    }
}
