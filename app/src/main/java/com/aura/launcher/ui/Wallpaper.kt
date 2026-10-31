package com.aura.launcher.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

@Immutable
class WallpaperPreset(val name: String, val base: Color, val glowA: Color, val glowB: Color)

val WALLPAPERS = listOf(
    WallpaperPreset("极光", Color(0xFF070B1A), Color(0xFF3B5BFF), Color(0xFF18C7A0)),
    WallpaperPreset("暮光", Color(0xFF12081F), Color(0xFFB04AFF), Color(0xFFFF6A88)),
    WallpaperPreset("深海", Color(0xFF03121C), Color(0xFF0A84FF), Color(0xFF30D5C8)),
    WallpaperPreset("熔岩", Color(0xFF160805), Color(0xFFFF6B35), Color(0xFFFFB347)),
    WallpaperPreset("石墨", Color(0xFF0B0B0D), Color(0xFF4A4F5C), Color(0xFF8E94A3)),
)

/**
 * 纯渐变壁纸:不用位图、不用模糊、不做持续动画,电视 SoC 上几乎零开销;
 * 切换壁纸时只做一次交叉淡入。
 */
@Composable
fun WallpaperBackground(index: Int, modifier: Modifier = Modifier) {
    Crossfade(
        targetState = index.coerceIn(WALLPAPERS.indices),
        modifier = modifier,
        animationSpec = tween(700),
        label = "wallpaper",
    ) { i ->
        val p = WALLPAPERS[i]
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val a = Brush.radialGradient(
                        listOf(p.glowA.copy(alpha = 0.55f), p.glowA.copy(alpha = 0f)),
                        center = Offset(w * 0.80f, h * 0.10f), radius = w * 0.60f,
                    )
                    val b = Brush.radialGradient(
                        listOf(p.glowB.copy(alpha = 0.38f), p.glowB.copy(alpha = 0f)),
                        center = Offset(w * 0.10f, h * 0.98f), radius = w * 0.70f,
                    )
                    val scrim = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)))
                    onDrawBehind {
                        drawRect(p.base)
                        drawRect(a)
                        drawRect(b)
                        drawRect(scrim)
                    }
                },
        )
    }
}
