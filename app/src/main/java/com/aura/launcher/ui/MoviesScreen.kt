package com.aura.launcher.ui

import android.view.KeyEvent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aura.launcher.data.AppGraph
import com.aura.launcher.data.HubDiscovery
import com.aura.launcher.data.HubFound
import com.aura.launcher.data.HubItem
import com.aura.launcher.data.HubLibrary
import com.aura.launcher.data.HubRepository
import com.aura.launcher.data.HubState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ───────────────────────────── 数据整理 ─────────────────────────────

/** 片库里的一个"条目":一部电影 / 一个视频,或一整部剧集(把同名剧集折叠成一张磁贴)。 */
private sealed interface Entry {
    val key: String
    val title: String
    val rep: HubItem
    val added: Long

    class Single(val item: HubItem) : Entry {
        override val key get() = "i${item.id}"
        override val title get() = item.title
        override val rep get() = item
        override val added get() = item.added
    }

    class Group(val name: String, val episodes: List<HubItem>) : Entry {
        override val key get() = "s:$name"
        override val title get() = name
        override val rep get() = episodes.firstOrNull { it.poster } ?: episodes.first()
        override val added get() = episodes.maxOf { it.added }
    }
}

private class RowDef(val key: String, val title: String, val entries: List<Entry>, val portrait: Boolean)

private fun buildEntries(items: List<HubItem>): List<Entry> {
    val out = ArrayList<Entry>()
    items.filter { it.series.isBlank() }.forEach { out += Entry.Single(it) }
    items.filter { it.series.isNotBlank() }.groupBy { it.series }.forEach { (name, eps) ->
        out += if (eps.size == 1) Entry.Single(eps[0])
        else Entry.Group(name, eps.sortedWith(compareBy({ it.season }, { it.episode }, { it.id })))
    }
    return out.sortedByDescending { it.added }
}

private fun buildRows(lib: HubLibrary): List<RowDef> {
    val byId = lib.items.associateBy { it.id }
    val all = buildEntries(lib.items)
    val rows = ArrayList<RowDef>()
    val cont = lib.continueIds.mapNotNull { byId[it] }.map { Entry.Single(it) }
    if (cont.isNotEmpty()) rows += RowDef("continue", "继续观看", cont, false)
    rows += RowDef("recent", "最近添加", all.take(12), false)
    val posters = all.filter { it.rep.poster }
    if (posters.isNotEmpty()) rows += RowDef("posters", "电影与剧集", posters, true)
    if (all.size > 12) rows += RowDef("all", "全部影片", all.sortedBy { it.title }, false)
    return rows
}

internal fun fmtDuration(sec: Int): String {
    val h = sec / 3600
    val m = sec % 3600 / 60
    return if (h > 0) "${h} 小时 ${m} 分钟" else "${maxOf(m, 1)} 分钟"
}

internal fun fmtClock(sec: Long): String {
    val s = maxOf(sec, 0L)
    val h = s / 3600
    val m = s % 3600 / 60
    val x = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%d:%02d".format(m, x)
}

private fun metaLine(item: HubItem): String = listOfNotNull(
    item.year.ifBlank { null },
    item.duration.takeIf { it > 0 }?.let(::fmtDuration),
    item.height.takeIf { it > 0 }?.let { "${it}p" },
).joinToString("  ·  ")

private fun statusBadge(item: HubItem): String? = when (item.play) {
    "transcoding" -> if (item.tprogress > 0f) "转码中 ${(item.tprogress * 100).toInt()}%" else "等待转码"
    "needs" -> "需要转码"
    "error" -> "转码失败"
    "pending" -> "分析中"
    "broken" -> "无法播放"
    else -> null
}

private fun accentFor(title: String): Color {
    val h = (title.hashCode().toLong() and 0x7fffffffL) % 360
    return Color.hsv(h.toFloat(), 0.55f, 0.72f)
}

private fun artUrl(repo: HubRepository, item: HubItem, preferPoster: Boolean): String? = when {
    preferPoster && item.poster -> repo.posterUrl(item)
    item.thumb -> repo.thumbUrl(item)
    item.poster -> repo.posterUrl(item)
    else -> null
}

// ───────────────────────────── 页面入口 ─────────────────────────────

@Composable
fun MoviesScreen(graph: AppGraph, onToast: (String) -> Unit, modifier: Modifier = Modifier) {
    val repo = graph.hub
    val state by repo.state.collectAsState()
    val url by repo.url.collectAsState()
    val resume by graph.resumeTick.collectAsState()
    val scope = rememberCoroutineScope()
    val bridge = LocalFocusBridge.current
    var connectDialog by remember { mutableStateOf(false) }
    // 连接 / 重试成功后,连接面板上的按钮会消失,焦点会被系统甩到顶栏第一个标签;这里把它接回片库
    var focusPending by remember { mutableStateOf(false) }
    var detailId by remember { mutableStateOf<Int?>(null) }
    var groupName by remember { mutableStateOf<String?>(null) }

    // 进入页面、地址变化、从播放器回来(进度变了)时刷新
    LaunchedEffect(url, resume) { repo.refresh(silent = repo.state.value is HubState.Ready) }

    val hasLibrary = (state as? HubState.Ready)?.lib?.items?.isNotEmpty() == true
    LaunchedEffect(focusPending, hasLibrary) {
        if (focusPending && hasLibrary) {
            delay(300)
            bridge.enterContent?.invoke()
            focusPending = false
        } else if (focusPending) {
            delay(10_000) // 一直没有片子出现就放弃,免得之后自动刷出片库时抢走焦点
            focusPending = false
        }
    }

    // 电脑正在转码 / 分析时,每 3 秒刷新一次进度
    val working = (state as? HubState.Ready)?.lib?.items?.any { it.play == "transcoding" || it.play == "pending" } == true
    LaunchedEffect(working, url) {
        while (working) {
            delay(3000)
            repo.refresh(silent = true)
        }
    }

    Box(modifier.fillMaxSize()) {
        when (val st = state) {
            is HubState.Ready -> {
                if (st.lib.items.isEmpty()) {
                    SetupPanel(
                        title = "影视库还是空的",
                        body = "在电脑浏览器打开  $url  → 「影视」页:\n粘贴 B 站 / YouTube 链接或 mp4、mkv、m3u8 直链即可下载,\n也可以添加电脑上已有的影片文件夹。",
                        error = null, searching = false,
                        primary = "刷新" to { focusPending = true; scope.launch { repo.refresh() } },
                        onAuto = null, onManual = { connectDialog = true },
                    )
                } else {
                    Library(
                        lib = st.lib, repo = repo,
                        onOpen = { e ->
                            when (e) {
                                is Entry.Single -> detailId = e.item.id
                                is Entry.Group -> groupName = e.name
                            }
                        },
                    )
                }
            }
            is HubState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val ui = LocalUi.current
                Text("正在连接家庭中心…", color = Palette.textDim, fontSize = ui.sp(34))
            }
            is HubState.Offline -> SetupPanel(
                title = "连不上家庭中心", body = "请确认电脑已开机并运行着家庭中心(双击 start.bat),且和电视在同一个 Wi-Fi。",
                error = st.message, searching = false,
                primary = "重试连接" to { focusPending = true; scope.launch { repo.refresh() } },
                onAuto = { connectDialog = true }, onManual = { connectDialog = true },
            )
            HubState.NotConfigured -> SetupPanel(
                title = "连接家庭中心",
                body = "把电脑上下载好的影片放到电视上看:\n1. 电脑上运行家庭中心(双击 start.bat)\n2. 电视和电脑连同一个 Wi-Fi\n3. 点下面的「自动搜索」",
                error = null, searching = false, primary = null,
                onAuto = { connectDialog = true }, onManual = { connectDialog = true },
            )
        }
    }

    val lib = (state as? HubState.Ready)?.lib
    detailId?.let { id ->
        val item = lib?.items?.firstOrNull { it.id == id }
        if (item == null) detailId = null else DetailDialog(item, repo, onToast, onDismiss = { detailId = null })
    }
    groupName?.let { name ->
        val eps = lib?.items?.filter { it.series == name }?.sortedWith(compareBy({ it.season }, { it.episode }, { it.id }))
        if (eps.isNullOrEmpty()) groupName = null else SeriesDialog(name, eps, repo, onToast, onDismiss = { groupName = null })
    }
    if (connectDialog) HubConnectDialogs(graph, onToast, onConnected = { focusPending = true }, onDismiss = { connectDialog = false })
}

// ───────────────────────────── 连接面板 ─────────────────────────────

@Composable
private fun SetupPanel(
    title: String,
    body: String,
    error: String?,
    searching: Boolean,
    primary: Pair<String, () -> Unit>?,
    onAuto: (() -> Unit)?,
    onManual: (() -> Unit)?,
) {
    val ui = LocalUi.current
    val bridge = LocalFocusBridge.current
    val first = remember { FocusRequester() }
    DisposableEffect(bridge) {
        val enter: () -> Boolean = { runCatching { first.requestFocus() }.isSuccess }
        bridge.enterContent = enter
        onDispose { if (bridge.enterContent === enter) bridge.enterContent = null }
    }
    Column(
        Modifier.fillMaxSize().padding(start = ui.dp(110), end = ui.dp(110), top = ui.dp(20)),
        verticalArrangement = Arrangement.spacedBy(ui.dp(18)),
    ) {
        Text(title, color = Palette.text, fontSize = ui.sp(60), fontWeight = FontWeight.SemiBold)
        Text(body, color = Palette.textDim, fontSize = ui.sp(30), lineHeight = ui.sp(46))
        if (error != null) Text(error, color = Palette.danger, fontSize = ui.sp(26), lineHeight = ui.sp(38))
        Column(
            Modifier.width(ui.dp(760)).padding(top = ui.dp(16)),
            verticalArrangement = Arrangement.spacedBy(ui.dp(16)),
        ) {
            var i = 0
            primary?.let { (label, act) ->
                TvButton(label, Modifier.fillMaxWidth().focusRequester(first), onClick = act)
                i++
            }
            onAuto?.let {
                TvButton("自动搜索家庭中心", Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    subtitle = "在局域网里找电脑上运行的家庭中心", onClick = it)
                i++
            }
            onManual?.let {
                TvButton("手动输入地址", Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    subtitle = "例如 192.168.1.23:8080", onClick = it)
            }
        }
    }
}

/** 连接家庭中心的全部弹窗:菜单 → 自动搜索 / 手动输入。设置页和影视页共用。 */
@Composable
fun HubConnectDialogs(graph: AppGraph, onToast: (String) -> Unit, onDismiss: () -> Unit, onConnected: () -> Unit = {}) {
    val repo = graph.hub
    val scope = rememberCoroutineScope()
    val url by repo.url.collectAsState()
    var stage by remember { mutableStateOf("menu") }
    var picks by remember { mutableStateOf<List<HubFound>>(emptyList()) }
    var typed by remember { mutableStateOf(url.removePrefix("http://").ifBlank { "192.168." }) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun connect(target: String) {
        stage = "connecting"
        scope.launch {
            repo.connect(target)
                .onSuccess { name ->
                    onToast("已连接家庭中心" + if (name.isNotBlank()) "「$name」" else "")
                    onConnected()
                    onDismiss()
                }
                .onFailure {
                    onToast(it.message ?: "连接失败")
                    stage = "keypad"
                }
        }
    }

    when (stage) {
        "menu" -> MenuDialog(
            title = "连接家庭中心",
            subtitle = if (url.isBlank()) "还没有连接" else "当前:$url",
            items = buildList {
                add(MenuItem("自动搜索(推荐)") {
                    stage = "searching"
                    job = scope.launch {
                        val found = HubDiscovery.find()
                        when {
                            found.isEmpty() -> {
                                onToast("没找到家庭中心。请确认电脑上已运行,或手动输入地址")
                                stage = "menu"
                            }
                            found.size == 1 -> connect(found[0].url)
                            else -> {
                                picks = found
                                stage = "pick"
                            }
                        }
                    }
                })
                add(MenuItem("手动输入地址") { stage = "keypad" })
                if (url.isNotBlank()) add(MenuItem("断开 / 清除地址", danger = true) { repo.disconnect(); onToast("已断开") })
                add(MenuItem("关闭") {})
            },
            onDismiss = { if (stage == "menu") onDismiss() },
        )
        "searching" -> BusyDialog("正在局域网里搜索…", onCancel = { job?.cancel(); stage = "menu" })
        "connecting" -> BusyDialog("正在连接…", onCancel = { stage = "menu" })
        "pick" -> MenuDialog(
            title = "找到多个家庭中心",
            items = picks.map { f -> MenuItem("${f.name.ifBlank { "家庭中心" }}   ${f.url.removePrefix("http://")}") { connect(f.url) } },
            onDismiss = { if (stage == "pick") stage = "menu" },
        )
        "keypad" -> AddressKeypadDialog(
            initial = typed,
            onConfirm = { typed = it; connect(it) },
            onDismiss = { stage = "menu" },
        )
    }
}

@Composable
private fun BusyDialog(text: String, onCancel: () -> Unit) {
    val ui = LocalUi.current
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.clip(RoundedCornerShape(ui.dp(32))).background(Color(0xF21B1E2C)).padding(ui.dp(56)),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(ui.dp(14)),
            ) {
                Text(text, color = Palette.text, fontSize = ui.sp(38), fontWeight = FontWeight.SemiBold)
                Text("按返回键取消", color = Palette.textDim, fontSize = ui.sp(26))
            }
        }
    }
}

@Composable
private fun KeypadKey(label: String, modifier: Modifier = Modifier, strong: Boolean = false, onClick: () -> Unit) {
    val ui = LocalUi.current
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        if (focused) Color.White else if (strong) Palette.accent.copy(alpha = 0.55f) else Palette.glass, tween(120), label = "keyBg",
    )
    val fg by animateColorAsState(if (focused) Color.Black else Palette.text, tween(120), label = "keyFg")
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, spring(0.75f, 420f), label = "keyScale")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(ui.dp(16)))
            .background(bg)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = ui.dp(18)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = ui.sp(34), fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** 用遥控器的方向键 + 确认键输入 IP 地址(也支持遥控器自带的数字键)。 */
@Composable
fun AddressKeypadDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val ui = LocalUi.current
    var text by remember { mutableStateOf(initial) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)).onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) {
                    val code = e.nativeKeyEvent.keyCode
                    if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                        text += (code - KeyEvent.KEYCODE_0).toString()
                        true
                    } else if (code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_FORWARD_DEL) {
                        text = text.dropLast(1)
                        true
                    } else false
                } else false
            },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.width(ui.dp(820)).clip(RoundedCornerShape(ui.dp(32))).background(Color(0xF21B1E2C)).padding(ui.dp(44)),
                verticalArrangement = Arrangement.spacedBy(ui.dp(14)),
            ) {
                Text("输入家庭中心地址", color = Palette.text, fontSize = ui.sp(38), fontWeight = FontWeight.SemiBold)
                Text(
                    "在电脑浏览器打开家庭中心,「影视」页最上面会显示形如 192.168.1.23:8080 的地址;端口是 8080 时可以不写。",
                    color = Palette.textDim, fontSize = ui.sp(24), lineHeight = ui.sp(34),
                )
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(ui.dp(16))).background(Palette.glassStrong)
                        .padding(horizontal = ui.dp(26), vertical = ui.dp(18)),
                ) {
                    Text(
                        text.ifEmpty { " " } + "▏", color = Palette.text, fontSize = ui.sp(40),
                        fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Clip,
                    )
                }
                val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(".", "0", ":"))
                keys.forEachIndexed { r, rowKeys ->
                    Row(horizontalArrangement = Arrangement.spacedBy(ui.dp(12))) {
                        rowKeys.forEachIndexed { c, k ->
                            KeypadKey(
                                k, Modifier.weight(1f).then(if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier),
                            ) { text += k }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(ui.dp(12))) {
                    KeypadKey("删除", Modifier.weight(1f)) { text = text.dropLast(1) }
                    KeypadKey("清空", Modifier.weight(1f)) { text = "" }
                    KeypadKey("连接", Modifier.weight(1f), strong = true) { onConfirm(text) }
                }
            }
        }
    }
}

// ───────────────────────────── 片库 ─────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Library(lib: HubLibrary, repo: HubRepository, onOpen: (Entry) -> Unit, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val bridge = LocalFocusBridge.current
    val scope = rememberCoroutineScope()
    val rows = remember(lib) { buildRows(lib) }
    val scroll = rememberScrollState()
    val rowTops = remember { FloatArray(8) }
    var currentRow by remember { mutableIntStateOf(-1) }
    val firstTile = remember { FocusRequester() }
    val lastTile = remember { arrayOfNulls<FocusRequester>(1) }
    var focused by remember { mutableStateOf<Entry?>(null) }
    val curOpen by rememberUpdatedState(onOpen)

    DisposableEffect(bridge) {
        val enter: () -> Boolean = {
            listOfNotNull(lastTile[0], firstTile).any { r -> runCatching { r.requestFocus() }.isSuccess }
        }
        bridge.enterContent = enter
        onDispose { if (bridge.enterContent === enter) bridge.enterContent = null }
    }

    fun focusRow(i: Int) {
        if (currentRow == i) return
        currentRow = i
        val target = if (i == 0) 0 else (rowTops[i] - ui.px(150)).toInt().coerceAtLeast(0)
        scope.launch { scroll.animateScrollTo(target, spring(dampingRatio = 1f, stiffness = 330f)) }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides NoScrollSpec) {
        Column(modifier.fillMaxSize().verticalScroll(scroll)) {
            Hero(
                entryProvider = { focused }, count = lib.items.size, server = lib.serverName,
                modifier = Modifier.graphicsLayer { alpha = (1f - scroll.value / ui.px(380)).coerceIn(0f, 1f) },
            )
            rows.forEachIndexed { ri, row ->
                EntryRow(
                    row = row, onPositioned = { rowTops[ri] = it },
                    onUp = if (ri == 0) ({ bridge.enterTabs?.invoke() == true }) else null,
                ) { index, entry ->
                    val own = remember { FocusRequester() }
                    val fr = if (ri == 0 && index == 0) firstTile else own
                    EntryTile(
                        entry = entry, portrait = row.portrait, repo = repo, focusRequester = fr,
                        onClick = { curOpen(entry) },
                        onFocused = { focused = entry; lastTile[0] = fr; focusRow(ri) },
                    )
                }
            }
            Box(Modifier.height(ui.dp(520)))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    row: RowDef,
    onPositioned: (Float) -> Unit,
    onUp: (() -> Boolean)?,
    itemContent: @Composable (Int, Entry) -> Unit,
) {
    val ui = LocalUi.current
    val edge = remember(ui) { EdgeSpec(ui.px(150)) }
    val curUp by rememberUpdatedState(onUp)
    Column(
        Modifier
            .onGloballyPositioned { onPositioned(it.positionInParent().y) }
            .onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp && curUp?.invoke() == true },
    ) {
        Text(
            row.title, color = Palette.text, fontSize = ui.sp(36), fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = ui.dp(110), bottom = ui.dp(6)),
        )
        CompositionLocalProvider(LocalBringIntoViewSpec provides edge) {
            LazyRow(
                contentPadding = PaddingValues(start = ui.dp(110), end = ui.dp(110), top = ui.dp(52), bottom = ui.dp(84)),
                horizontalArrangement = Arrangement.spacedBy(ui.dp(40)),
            ) {
                itemsIndexed(row.entries, key = { _, e -> row.key + ":" + e.key }) { index, e -> itemContent(index, e) }
            }
        }
    }
}

@Composable
private fun Hero(entryProvider: () -> Entry?, count: Int, server: String, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val entry = entryProvider()
    Box(modifier.fillMaxWidth().height(ui.dp(330)).padding(start = ui.dp(110), top = ui.dp(36), end = ui.dp(110))) {
        Crossfade(targetState = entry, animationSpec = tween(220), label = "movieHero") { e ->
            if (e == null) {
                Column {
                    Text("影视", color = Palette.textDim, fontSize = ui.sp(32))
                    Text("家庭影院", color = Palette.text, fontSize = ui.sp(120), fontWeight = FontWeight.Light)
                    Text(
                        "$count 部影片" + if (server.isNotBlank()) "  ·  来自「$server」" else "",
                        color = Palette.textDim, fontSize = ui.sp(34),
                    )
                }
            } else {
                Column {
                    val rep = e.rep
                    Text(
                        when (e) {
                            is Entry.Group -> "剧集  ·  共 ${e.episodes.size} 集"
                            is Entry.Single -> if (e.item.inProgress) "继续观看  ·  已看 ${(e.item.progress * 100).toInt()}%" else if (e.item.watched) "已看过" else "影片"
                        },
                        color = Palette.textDim, fontSize = ui.sp(30),
                    )
                    Text(e.title, color = Palette.text, fontSize = ui.sp(84), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val meta = metaLine(rep)
                    if (meta.isNotBlank()) Text(meta, color = Palette.textDim, fontSize = ui.sp(30))
                    val overview = (e as? Entry.Group)?.episodes?.firstOrNull { it.overview.isNotBlank() }?.overview ?: rep.overview
                    if (overview.isNotBlank()) {
                        Text(
                            overview, color = Palette.textFaint, fontSize = ui.sp(26), lineHeight = ui.sp(36),
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(0.7f).padding(top = ui.dp(6)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryTile(
    entry: Entry,
    portrait: Boolean,
    repo: HubRepository,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    onFocused: () -> Unit,
) {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val rep = entry.rep
    val art = artUrl(repo, rep, portrait)
    val accent = remember(entry.title) { accentFor(entry.title) }
    TvTile(
        width = if (portrait) ui.dp(250) else ui.dp(400),
        height = if (portrait) ui.dp(375) else ui.dp(225),
        accent = accent, label = entry.title,
        onClick = onClick, onFocused = onFocused, focusRequester = focusRequester,
    ) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(accent.copy(alpha = 0.6f), Color(0xFF14172A)))))
        if (art != null) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(art).crossfade(180).build(),
                contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                entry.title, color = Palette.text, fontSize = ui.sp(34), fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(ui.dp(24)),
            )
        }
        val single = (entry as? Entry.Single)?.item
        if (single != null && single.inProgress) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(ui.dp(8)).background(Color.White.copy(alpha = 0.28f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(single.progress).background(Color.White))
            }
        }
        val badge = when (entry) {
            is Entry.Single -> statusBadge(entry.item)
            is Entry.Group -> null
        }
        if (badge != null) Chip(badge, Modifier.align(Alignment.TopStart))
        if (entry is Entry.Group) Chip("${entry.episodes.size} 集", Modifier.align(Alignment.TopEnd))
        else if (single != null && single.watched) Chip("✓", Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun Chip(text: String, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    Text(
        text, color = Color.White, fontSize = ui.sp(22), maxLines = 1,
        modifier = modifier.padding(ui.dp(12)).clip(CircleShape).background(Color(0xB3000000))
            .padding(horizontal = ui.dp(14), vertical = ui.dp(5)),
    )
}

// ───────────────────────────── 详情 / 剧集弹窗 ─────────────────────────────

@Composable
private fun FullDialog(onDismiss: () -> Unit, backdrop: String?, content: @Composable BoxScope.() -> Unit) {
    val ctx = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0D16))) {
            if (backdrop != null) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(backdrop).build(), contentDescription = null,
                    modifier = Modifier.fillMaxSize().alpha(0.34f), contentScale = ContentScale.Crop,
                )
            }
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF00B0D16), Color(0x990B0D16)))))
            content()
        }
    }
}

@Composable
private fun Cover(item: HubItem, repo: HubRepository, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val portrait = item.poster
    val art = artUrl(repo, item, true)
    val accent = remember(item.title) { accentFor(item.title) }
    Box(
        modifier.width(if (portrait) ui.dp(330) else ui.dp(600))
            .aspectRatio(if (portrait) 2f / 3f else 16f / 9f)
            .clip(RoundedCornerShape(ui.dp(26)))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.6f), Color(0xFF14172A)))),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(art).crossfade(150).build(), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            )
        } else {
            Text(item.title, color = Palette.text, fontSize = ui.sp(38), textAlign = TextAlign.Center, modifier = Modifier.padding(ui.dp(24)))
        }
    }
}

private fun startPlayer(ctx: android.content.Context, item: HubItem, resume: Boolean) {
    ctx.startActivity(PlayerActivity.intent(ctx, item.id, resume))
}

@Composable
private fun DetailDialog(item: HubItem, repo: HubRepository, onToast: (String) -> Unit, onDismiss: () -> Unit) {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    FullDialog(onDismiss, artUrl(repo, item, false)) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = ui.dp(140), vertical = ui.dp(80)),
            horizontalArrangement = Arrangement.spacedBy(ui.dp(70)), verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(item, repo)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ui.dp(14))) {
                Text(item.title, color = Palette.text, fontSize = ui.sp(68), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = metaLine(item)
                if (meta.isNotBlank()) Text(meta, color = Palette.textDim, fontSize = ui.sp(30))
                if (item.overview.isNotBlank()) {
                    Text(
                        item.overview, color = Palette.textDim, fontSize = ui.sp(27), lineHeight = ui.sp(40),
                        maxLines = 5, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!item.playable) {
                    Text(
                        when (item.play) {
                            "transcoding" -> "电脑正在转码(${(item.tprogress * 100).toInt()}%),完成后这里就能播放。"
                            "needs" -> "这部影片的格式电视不能直接播放,需要让电脑转码。"
                            "error" -> "转码失败:" + item.terror.ifBlank { "请到电脑上的家庭中心查看原因" }
                            "pending" -> "电脑还在分析这部影片…"
                            else -> "这部影片暂时无法播放。"
                        },
                        color = Palette.danger, fontSize = ui.sp(27), lineHeight = ui.sp(38),
                    )
                }
                Column(Modifier.padding(top = ui.dp(14)), verticalArrangement = Arrangement.spacedBy(ui.dp(14))) {
                    if (item.playable) {
                        if (item.inProgress) {
                            TvButton(
                                "继续播放  ${fmtClock(item.position.toLong())}", Modifier.width(ui.dp(560)).focusRequester(first),
                                subtitle = "上次看到 ${(item.progress * 100).toInt()}%",
                                onClick = { startPlayer(ctx, item, true) },
                            )
                            TvButton("从头播放", Modifier.width(ui.dp(560)), onClick = { startPlayer(ctx, item, false) })
                        } else {
                            TvButton(
                                if (item.watched) "重新播放" else "播放", Modifier.width(ui.dp(560)).focusRequester(first),
                                onClick = { startPlayer(ctx, item, false) },
                            )
                        }
                    } else if (item.play == "needs" || item.play == "error") {
                        TvButton(
                            "让电脑转码", Modifier.width(ui.dp(560)).focusRequester(first),
                            onClick = { scope.launch { onToast(repo.requestTranscode(item.id)); repo.refresh(silent = true) } },
                        )
                    } else {
                        TvButton("返回", Modifier.width(ui.dp(560)).focusRequester(first), onClick = onDismiss)
                    }
                    TvButton(
                        if (item.watched) "标记为未看" else "标记为已看", Modifier.width(ui.dp(560)),
                        onClick = { scope.launch { repo.setWatched(item.id, !item.watched); repo.refresh(silent = true) } },
                    )
                }
            }
        }
    }
}

internal fun episodeLabel(ep: HubItem): String {
    val generic = ep.series.isNotBlank() &&
        Regex("^" + Regex.escape(ep.series) + "\\s+(S\\d+E\\d+|第\\d+集)$").matches(ep.title)
    val num = when {
        ep.season > 0 && ep.episode > 0 -> "第${ep.season}季 第${ep.episode}集"
        ep.episode > 0 -> "第${ep.episode}集"
        else -> ""
    }
    return when {
        generic && num.isNotEmpty() -> num
        num.isNotEmpty() -> "$num  ·  ${ep.title}"
        else -> ep.title
    }
}

@Composable
private fun SeriesDialog(name: String, episodes: List<HubItem>, repo: HubRepository, onToast: (String) -> Unit, onDismiss: () -> Unit) {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val first = remember { FocusRequester() }
    val start = episodes.indexOfFirst { !it.watched }.coerceAtLeast(0)
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { first.requestFocus() }
    }
    val rep = episodes.firstOrNull { it.poster } ?: episodes.first()
    FullDialog(onDismiss, artUrl(repo, rep, false)) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = ui.dp(140), vertical = ui.dp(70)),
            horizontalArrangement = Arrangement.spacedBy(ui.dp(70)),
        ) {
            Column(Modifier.width(ui.dp(560)), verticalArrangement = Arrangement.spacedBy(ui.dp(14))) {
                Cover(rep, repo)
                Text(name, color = Palette.text, fontSize = ui.sp(56), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "共 ${episodes.size} 集  ·  已看 ${episodes.count { it.watched }} 集", color = Palette.textDim, fontSize = ui.sp(28),
                )
                val ov = episodes.firstOrNull { it.overview.isNotBlank() }?.overview.orEmpty()
                if (ov.isNotBlank()) Text(ov, color = Palette.textFaint, fontSize = ui.sp(24), lineHeight = ui.sp(34), maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = ui.dp(10)),
                verticalArrangement = Arrangement.spacedBy(ui.dp(12)),
            ) {
                episodes.forEachIndexed { i, ep ->
                    val badge = statusBadge(ep)
                    TvButton(
                        title = episodeLabel(ep),
                        subtitle = listOfNotNull(
                            ep.duration.takeIf { it > 0 }?.let(::fmtDuration),
                            if (ep.inProgress) "看到 ${fmtClock(ep.position.toLong())}" else null,
                        ).joinToString("  ·  ").ifBlank { null },
                        trailing = badge ?: if (ep.watched) "✓ 已看" else if (ep.inProgress) "${(ep.progress * 100).toInt()}%" else null,
                        modifier = Modifier.fillMaxWidth().then(if (i == start) Modifier.focusRequester(first) else Modifier),
                        onClick = {
                            if (ep.playable) startPlayer(ctx, ep, ep.inProgress)
                            else onToast(badge ?: "这一集暂时无法播放")
                        },
                    )
                }
            }
        }
    }
}
