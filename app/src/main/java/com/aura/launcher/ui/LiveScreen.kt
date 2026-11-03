package com.aura.launcher.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

/** 央视频道(官方公开直播页)。 */
data class LiveChannel(val slug: String, val num: String, val name: String, val color: Color) {
    val url: String get() = "https://tv.cctv.com/live/$slug/"
    val title: String get() = "$num $name"
}

object LiveChannels {
    val all = listOf(
        LiveChannel("cctv1", "CCTV-1", "综合", Color(0xFF2F6BFF)),
        LiveChannel("cctv2", "CCTV-2", "财经", Color(0xFF1E9E6A)),
        LiveChannel("cctv3", "CCTV-3", "综艺", Color(0xFFE0568A)),
        LiveChannel("cctv4", "CCTV-4", "中文国际", Color(0xFF7A5CFF)),
        LiveChannel("cctv5", "CCTV-5", "体育", Color(0xFFE8782A)),
        LiveChannel("cctv5plus", "CCTV-5+", "体育赛事", Color(0xFFD9602A)),
        LiveChannel("cctv6", "CCTV-6", "电影", Color(0xFFB3482F)),
        LiveChannel("cctv7", "CCTV-7", "国防军事", Color(0xFF4F7A3E)),
        LiveChannel("cctv8", "CCTV-8", "电视剧", Color(0xFFC2457A)),
        LiveChannel("cctv9", "CCTV-9", "纪录", Color(0xFF2B8A9E)),
        LiveChannel("cctv10", "CCTV-10", "科教", Color(0xFF3F7FBF)),
        LiveChannel("cctv11", "CCTV-11", "戏曲", Color(0xFF9E4FB8)),
        LiveChannel("cctv12", "CCTV-12", "社会与法", Color(0xFF6F6FD6)),
        LiveChannel("cctv13", "CCTV-13", "新闻", Color(0xFFD64545)),
        LiveChannel("cctv14", "CCTV-14", "少儿", Color(0xFFE0A21F)),
        LiveChannel("cctv15", "CCTV-15", "音乐", Color(0xFF3FB2A3)),
        LiveChannel("cctv16", "CCTV-16", "奥林匹克", Color(0xFF2F9E4F)),
        LiveChannel("cctv17", "CCTV-17", "农业农村", Color(0xFF7FA33A)),
    )
}

private const val PREF = "aura_live"

fun liveLastChannel(ctx: Context): Int =
    ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("last", -1)
        .takeIf { it in LiveChannels.all.indices } ?: -1

fun liveSaveChannel(ctx: Context, index: Int) {
    ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt("last", index).apply()
}

/** 直播页:大磁贴 + 一键进入。看电视时上/下键换台,返回键退出。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveScreen(modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val bridge = LocalFocusBridge.current
    val first = remember { FocusRequester() }
    var last by remember { mutableIntStateOf(liveLastChannel(ctx)) }
    val edge = remember(ui) { EdgeSpec(ui.px(150)) }

    DisposableEffect(bridge) {
        val enter: () -> Boolean = { runCatching { first.requestFocus() }.isSuccess }
        bridge.enterContent = enter
        onDispose { if (bridge.enterContent === enter) bridge.enterContent = null }
    }

    fun open(i: Int) {
        liveSaveChannel(ctx, i)
        last = i
        ctx.startActivity(LiveActivity.intent(ctx, i))
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides edge) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ui.dp(110), end = ui.dp(110), top = ui.dp(10), bottom = ui.dp(200)),
            horizontalArrangement = Arrangement.spacedBy(ui.dp(36)),
            verticalArrangement = Arrangement.spacedBy(ui.dp(30)),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(bottom = ui.dp(10))) {
                    Text("电视直播  ·  央视", color = Palette.text, fontSize = ui.sp(52), fontWeight = FontWeight.SemiBold)
                    Text(
                        "按「确定」键开始观看  ·  看电视时按 上 / 下 键换台  ·  按「返回」键退出",
                        color = Palette.textDim, fontSize = ui.sp(30),
                        modifier = Modifier.padding(top = ui.dp(10)),
                    )
                }
            }
            if (last >= 0) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    val ch = LiveChannels.all[last]
                    TvTile(
                        width = ui.dp(760), height = ui.dp(150), accent = ch.color, label = null,
                        focusRequester = first, onClick = { open(last) },
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("继续收看  ${ch.title}", color = Color.White, fontSize = ui.sp(46), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            itemsIndexed(LiveChannels.all, key = { _, c -> c.slug }) { i, ch ->
                val mod = if (last < 0 && i == 0) Modifier else Modifier
                TvTile(
                    width = ui.dp(360), height = ui.dp(200), accent = ch.color, label = null,
                    modifier = mod,
                    focusRequester = if (last < 0 && i == 0) first else null,
                    onClick = { open(i) },
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(ch.num, color = Color.White, fontSize = ui.sp(48), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Text(ch.name, color = Color.White.copy(alpha = 0.92f), fontSize = ui.sp(36), textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}
