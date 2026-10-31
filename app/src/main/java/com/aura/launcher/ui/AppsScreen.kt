package com.aura.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.aura.launcher.data.AppEntry
import com.aura.launcher.data.AppGraph

/** 全部应用:5 列网格,长按 OK 键管理应用。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(
    graph: AppGraph,
    onLaunch: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = LocalUi.current
    val apps by graph.apps.apps.collectAsState()
    val hidden by graph.prefs.hidden.collectAsState()
    val favorites by graph.prefs.favorites.collectAsState()
    val visible = remember(apps, hidden) { apps.filter { it.packageName !in hidden } }
    var menuApp by remember { mutableStateOf<AppEntry?>(null) }
    val edge = remember(ui) { EdgeSpec(ui.px(150)) }

    CompositionLocalProvider(LocalBringIntoViewSpec provides edge) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ui.dp(110), end = ui.dp(110), top = ui.dp(10), bottom = ui.dp(200)),
            horizontalArrangement = Arrangement.spacedBy(ui.dp(40)),
            verticalArrangement = Arrangement.spacedBy(ui.dp(26)),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "全部应用  ·  ${visible.size}",
                    color = Palette.text, fontSize = ui.sp(40), fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = ui.dp(24)),
                )
            }
            items(visible, key = { it.packageName }) { app ->
                AppTile(
                    entry = app, width = ui.dp(308),
                    onClick = { onLaunch(app) },
                    onLongClick = { menuApp = app },
                )
            }
        }
    }

    menuApp?.let { app ->
        AppActionDialog(app, favorites, graph.apps, graph.prefs, onLaunch = onLaunch, onDismiss = { menuApp = null })
    }
}
