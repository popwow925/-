package com.aura.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.aura.launcher.data.AppEntry
import com.aura.launcher.data.TileArt
import com.aura.launcher.data.TileArtLoader

/** 应用磁贴:优先显示应用自带的 16:9 横幅,没有则用图标 + 取色渐变底。 */
@Composable
fun AppTile(
    entry: AppEntry,
    width: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    val ctx = LocalContext.current
    var art by remember(entry.packageName) { mutableStateOf(TileArtLoader.peek(entry.packageName)) }
    LaunchedEffect(entry.packageName) {
        if (art == null) art = TileArtLoader.load(ctx, entry)
    }
    val accent = art?.accent ?: Palette.fallbackTile
    TvTile(
        width = width,
        height = width * (9f / 16f),
        accent = accent,
        label = entry.label,
        modifier = modifier,
        onClick = onClick,
        onLongClick = onLongClick,
        onFocused = onFocused,
        focusRequester = focusRequester,
    ) {
        val a = art
        if (a?.banner != null) {
            Image(a.banner, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            IconCard(a, accent)
        }
    }
}

@Composable
private fun BoxScope.IconCard(art: TileArt?, accent: Color) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(lerp(accent, Color.White, 0.16f), lerp(accent, Color.Black, 0.5f))
                )
            )
    )
    if (art != null) {
        Image(
            bitmap = art.icon,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxHeight(0.56f)
                .aspectRatio(1f),
        )
    }
}

/** 文字磁贴:用于信号源和系统快捷入口。 */
@Composable
fun ShortcutTile(
    title: String,
    width: Dp,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
) {
    val ui = LocalUi.current
    TvTile(
        width = width,
        height = width * (9f / 16f),
        accent = accent,
        label = null,
        modifier = modifier,
        onClick = onClick,
        onFocused = onFocused,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(lerp(accent, Color.White, 0.10f), lerp(accent, Color.Black, 0.55f))))
        )
        Text(
            title,
            color = Palette.text,
            fontSize = ui.sp(30),
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}
