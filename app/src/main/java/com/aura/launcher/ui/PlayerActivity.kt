package com.aura.launcher.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aura.launcher.data.HubRepository
import com.aura.launcher.data.Prefs
import com.aura.launcher.data.HubItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class Phase { LOADING, WAITING, PLAYING, ENDED, FAILED }

/** 播放界面用到的全部可观察状态(只在主线程改)。 */
private class PlayerModel {
    var phase by mutableStateOf(Phase.LOADING)
    var item by mutableStateOf<HubItem?>(null)
    var message by mutableStateOf("")
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var paused by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var overlay by mutableStateOf(true)
    var lastTouch by mutableLongStateOf(0L)
    var seekTargetMs by mutableLongStateOf(-1L)
    var nextItem by mutableStateOf<HubItem?>(null)
    var countdown by mutableIntStateOf(0)

    /** 失败后可以让电脑按保底格式转码重试。 */
    var canForce by mutableStateOf(false)
}

/**
 * 全屏播放器:Media3 ExoPlayer + 自绘控制层(不依赖 media3-ui)。
 * 遥控器:确认=暂停/播放,左右=快退/快进(连按加速),上下=显示进度,返回=退出。
 * 每 10 秒和退出时把进度报给家庭中心;播完自动倒数播下一集;
 * 电视解不了的片子,让电脑转码,转好后自动开始。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var hub: HubRepository
    private val m = PlayerModel()
    private var player: ExoPlayer? = null
    private lateinit var surface: SurfaceView

    private var curId = -1
    private var nextId = 0
    private var safeCaps = false
    private var loadJob: Job? = null
    private var seekJob: Job? = null
    private var nextJob: Job? = null
    private var lastReportAt = 0L
    private var lastScrubAt = 0L
    private var lastStepAt = 0L
    private var streak = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hub = HubRepository(Prefs(applicationContext), applicationContext)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        surface = SurfaceView(this)
        val p = ExoPlayer.Builder(this)
            .setRenderersFactory(DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    // 局域网带宽充足:多缓冲一点,Wi-Fi 抖动时不容易卡
                    .setBufferDurationsMs(20_000, 60_000, 1_500, 4_000)
                    .build(),
            )
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        p.setVideoSurfaceView(surface)
        p.addListener(listener)
        player = p

        val id = intent.getIntExtra(EXTRA_ID, -1)
        val resume = intent.getBooleanExtra(EXTRA_RESUME, false)
        if (id < 0) {
            finish()
            return
        }

        setContent {
            PlayerRoot(
                m = m, surface = surface, hubRepo = hub,
                onBack = { finish() },
                onRetry = { load(curId, true, m.positionMs.takeIf { it > 0 }) },
                onForce = { safeCaps = true; load(curId, true, m.positionMs.takeIf { it > 0 }) },
                onNext = { playNext() },
            )
        }

        // 播放时每 250ms 刷新一次进度条;每 10 秒向家庭中心报告一次
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    tick()
                    delay(250)
                }
            }
        }
        load(id, resume, null)
    }

    // ───────────── 加载 / 等待转码 / 开始播放 ─────────────

    private fun load(id: Int, resume: Boolean, startOverrideMs: Long?) {
        reportNow()
        loadJob?.cancel()
        nextJob?.cancel()
        seekJob?.cancel()
        player?.run { stop(); clearMediaItems() }
        curId = id
        nextId = 0
        m.phase = Phase.LOADING
        m.message = ""
        m.nextItem = null
        m.seekTargetMs = -1
        m.canForce = false
        m.positionMs = 0
        m.durationMs = 0
        loadJob = lifecycleScope.launch {
            val repo = hub
            var asked = false
            while (isActive) {
                val d = repo.detail(id, safeCaps).getOrElse {
                    fail(it.message ?: "连接失败", force = false)
                    return@launch
                }
                m.item = d.item
                nextId = d.nextId
                if (d.item.playable) {
                    start(repo.streamUrl(d.streamPath), d.item, resume, startOverrideMs)
                    return@launch
                }
                when (d.item.play) {
                    "needs" -> {
                        if (!asked) {
                            asked = true
                            repo.requestTranscodeResult(id, safeCaps).onFailure {
                                fail(it.message ?: "无法请求电脑转码", force = false)
                                return@launch
                            }
                        }
                        m.phase = Phase.WAITING
                    }
                    "transcoding", "pending" -> m.phase = Phase.WAITING
                    "error" -> {
                        fail("电脑转码失败:" + d.item.terror.ifBlank { "请到电脑上的家庭中心查看原因" }, force = false)
                        return@launch
                    }
                    else -> {
                        fail("这部影片无法播放(文件可能已损坏)", force = false)
                        return@launch
                    }
                }
                delay(2500)
            }
        }
    }

    private fun start(url: String, item: HubItem, resume: Boolean, startOverrideMs: Long?) {
        val p = player ?: return
        val startMs = startOverrideMs
            ?: if (resume && item.inProgress) maxOf(0L, item.position * 1000L - 2000L) else 0L
        m.durationMs = item.duration * 1000L
        m.positionMs = startMs
        m.paused = false
        m.buffering = true
        p.setMediaItem(MediaItem.fromUri(url), startMs)
        p.prepare()
        p.playWhenReady = true
        m.phase = Phase.PLAYING
        touch()
        lastReportAt = SystemClock.elapsedRealtime()
    }

    private fun fail(msg: String, force: Boolean) {
        m.message = msg
        m.canForce = force && !safeCaps
        m.phase = Phase.FAILED
    }

    private fun playNext() {
        val id = m.nextItem?.id ?: nextId
        if (id > 0) {
            safeCaps = false
            load(id, false, null)
        } else {
            finish()
        }
    }

    // ───────────── 播放器事件 ─────────────

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val p = player ?: return
            m.buffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY && p.duration > 0) m.durationMs = p.duration
            if (state == Player.STATE_ENDED && m.phase == Phase.PLAYING) onEnded()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            m.paused = !playWhenReady
            if (!playWhenReady) reportNow()
            touch()
        }

        override fun onPlayerError(error: PlaybackException) {
            reportNow()
            val decoder = when (error.errorCode) {
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
                -> true
                else -> false
            }
            val net = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                -> true
                else -> false
            }
            m.positionMs = player?.currentPosition ?: m.positionMs
            fail(
                when {
                    decoder -> "这台电视解不了这个片子的视频或音频编码。可以让电脑把它转成电视能播的格式(需要电脑已装 ffmpeg)。"
                    net -> "和家庭中心的连接中断了,请确认电脑还开着、电视的 Wi-Fi 正常,然后重试(会从刚才的位置继续)。"
                    else -> "播放出错(${error.errorCodeName})。"
                },
                force = decoder,
            )
        }
    }

    private fun onEnded() {
        val p = player
        val dur = if (m.durationMs > 0) m.durationMs else (p?.duration ?: 0L)
        m.positionMs = dur
        reportProgress(curId, dur / 1000, dur / 1000)
        val nid = nextId
        if (nid <= 0) {
            finish()
            return
        }
        m.phase = Phase.ENDED
        m.countdown = 0
        nextJob?.cancel()
        nextJob = lifecycleScope.launch {
            val nxt = hub.detail(nid).getOrNull()?.item
            if (nxt == null) {
                finish()
                return@launch
            }
            m.nextItem = nxt
            if (!nxt.playable && nxt.play != "needs") {
                // 下一集还在转码 / 有问题:不自动连播,让用户自己决定
                m.countdown = -1
                return@launch
            }
            for (left in 8 downTo 1) {
                m.countdown = left
                delay(1000)
            }
            playNext()
        }
    }

    // ───────────── 进度条 / 上报 ─────────────

    private fun touch() {
        m.overlay = true
        m.lastTouch = SystemClock.elapsedRealtime()
    }

    private fun tick() {
        val p = player ?: return
        if (m.phase != Phase.PLAYING) return
        val now = SystemClock.elapsedRealtime()
        if (m.seekTargetMs < 0) m.positionMs = p.currentPosition
        val d = p.duration
        if (d != C.TIME_UNSET && d > 0) m.durationMs = d
        if (m.overlay && !m.paused && m.seekTargetMs < 0 && now - m.lastTouch > 4500) m.overlay = false
        if (!m.paused && !m.buffering && now - lastReportAt > 10_000) {
            lastReportAt = now
            reportNow()
        }
    }

    private fun reportNow() {
        val p = player ?: return
        if (m.phase != Phase.PLAYING || curId < 0) return
        val pos = (if (m.seekTargetMs >= 0) m.seekTargetMs else p.currentPosition) / 1000
        val dur = (if (m.durationMs > 0) m.durationMs else (m.item?.duration ?: 0) * 1000L) / 1000
        if (pos <= 0 && dur <= 0) return
        reportProgress(curId, pos, dur)
    }

    /** 即发即忘:用独立线程,Activity 正在退出时也能发出去。 */
    private fun reportProgress(id: Int, posSec: Long, durSec: Long) {
        val repo = hub
        Thread { repo.reportProgressBlocking(id, posSec, durSec) }.start()
    }

    // ───────────── 遥控器 ─────────────

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (m.phase != Phase.PLAYING) return super.dispatchKeyEvent(e)
        val code = e.keyCode
        val handled = when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_STOP,
            -> true
            else -> false
        }
        if (!handled) return super.dispatchKeyEvent(e)
        if (e.action != KeyEvent.ACTION_DOWN) return true
        val p = player ?: return true
        val first = e.repeatCount == 0
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> {
                if (first) {
                    if (m.seekTargetMs >= 0) commitSeek() else togglePlay(p)
                }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (first) togglePlay(p)
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p.playWhenReady = true; touch() }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.playWhenReady = false; touch() }
            KeyEvent.KEYCODE_MEDIA_STOP -> finish()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> scrub(-1, e.repeatCount)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> scrub(1, e.repeatCount)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> touch()
            KeyEvent.KEYCODE_MEDIA_NEXT -> if (first && nextId > 0) playNext()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (first) { p.seekTo(0); m.positionMs = 0; touch() }
        }
        return true
    }

    private fun togglePlay(p: ExoPlayer) {
        p.playWhenReady = !p.playWhenReady
        touch()
    }

    /** 连按左右键累计目标位置,停手 0.65 秒后一次性跳转;越按步子越大。 */
    private fun scrub(dir: Int, repeat: Int) {
        val p = player ?: return
        val now = SystemClock.elapsedRealtime()
        if (repeat > 0 && now - lastStepAt < 160) return // 长按时限速,不然一秒跳几十下
        streak = if (now - lastScrubAt < 700) streak + 1 else 1
        lastScrubAt = now
        lastStepAt = now
        val step = when {
            streak <= 3 -> 10_000L
            streak <= 8 -> 30_000L
            streak <= 16 -> 60_000L
            else -> 180_000L
        }
        val base = if (m.seekTargetMs >= 0) m.seekTargetMs else p.currentPosition
        val dur = if (m.durationMs > 0) m.durationMs else Long.MAX_VALUE
        val maxPos = if (dur == Long.MAX_VALUE) dur else maxOf(0L, dur - 1500L)
        m.seekTargetMs = (base + dir * step).coerceIn(0L, maxPos)
        touch()
        seekJob?.cancel()
        seekJob = lifecycleScope.launch {
            delay(650)
            commitSeek()
        }
    }

    private fun commitSeek() {
        seekJob?.cancel()
        val p = player ?: return
        val t = m.seekTargetMs
        if (t < 0) return
        p.seekTo(t)
        m.positionMs = t
        m.seekTargetMs = -1
        streak = 0
        touch()
        reportNow()
    }

    // ───────────── 生命周期 ─────────────

    override fun onStop() {
        // 切到别的应用 / 按了主页键:暂停并存好进度
        player?.playWhenReady = false
        reportNow()
        super.onStop()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        seekJob?.cancel()
        nextJob?.cancel()
        player?.run {
            removeListener(listener)
            release()
        }
        player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_RESUME = "resume"

        fun intent(ctx: Context, id: Int, resume: Boolean): Intent =
            Intent(ctx, PlayerActivity::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_RESUME, resume)
    }
}

// ───────────────────────────── 界面 ─────────────────────────────

@Composable
private fun PlayerRoot(
    m: PlayerModel,
    surface: SurfaceView,
    hubRepo: HubRepository,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onForce: () -> Unit,
    onNext: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current.density
        val ui = remember(maxWidth, density) { Ui(maxWidth.value / 1920f, density) }
        CompositionLocalProvider(LocalUi provides ui) {
            AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize())
            when (m.phase) {
                Phase.PLAYING -> PlayerOverlay(m)
                Phase.LOADING -> StatusScreen(m, hubRepo, busy = true, text = "正在准备播放…", buttons = emptyList(), onBack = onBack)
                Phase.WAITING -> {
                    val it = m.item
                    val pct = ((it?.tprogress ?: 0f) * 100).toInt()
                    StatusScreen(
                        m, hubRepo, busy = true, progress = it?.tprogress?.takeIf { p -> p > 0f },
                        text = when (it?.play) {
                            "pending" -> "电脑正在分析这部影片…"
                            "transcoding" -> if (pct > 0) "电脑正在转码… $pct%" else "电脑正在转码,排队中…"
                            else -> "正在通知电脑转码…"
                        },
                        detail = "电视不能直接播放这个格式,电脑正在转成电视能播的版本,转好后会自动开始。\n转码需要一段时间(取决于电脑性能和影片长度);你可以先返回,转完后它会直接可以播放。",
                        buttons = listOf("返回" to onBack), onBack = onBack,
                    )
                }
                Phase.FAILED -> StatusScreen(
                    m, hubRepo, busy = false, text = "没能播放", detail = m.message, danger = true,
                    buttons = buildList {
                        add("重试" to onRetry)
                        if (m.canForce) add("让电脑转码后再试" to onForce)
                        add("返回" to onBack)
                    },
                    onBack = onBack,
                )
                Phase.ENDED -> EndedScreen(m, hubRepo, onNext = onNext, onBack = onBack)
            }
        }
    }
}

@Composable
private fun Scrim(top: Boolean, modifier: Modifier = Modifier) {
    val colors = listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)
    Box(modifier.background(Brush.verticalGradient(if (top) colors else colors.reversed())))
}

@Composable
private fun PlayerOverlay(m: PlayerModel) {
    val ui = LocalUi.current
    val visible = m.overlay || m.paused || m.seekTargetMs >= 0
    val a by animateFloatAsState(if (visible) 1f else 0f, tween(220), label = "overlay")
    val item = m.item
    val seeking = m.seekTargetMs >= 0
    val pos = if (seeking) m.seekTargetMs else m.positionMs
    val frac = if (m.durationMs > 0) (pos.toFloat() / m.durationMs).coerceIn(0f, 1f) else 0f

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = a }) {
            Scrim(true, Modifier.fillMaxWidth().height(ui.dp(260)).align(Alignment.TopStart))
            Scrim(false, Modifier.fillMaxWidth().height(ui.dp(340)).align(Alignment.BottomStart))
            if (item != null) {
                Column(Modifier.align(Alignment.TopStart).padding(start = ui.dp(110), top = ui.dp(64), end = ui.dp(110))) {
                    Text(
                        item.series.ifBlank { item.title }, color = Palette.text, fontSize = ui.sp(48),
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (item.series.isNotBlank()) {
                        Text(episodeLabel(item), color = Palette.textDim, fontSize = ui.sp(28), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = ui.dp(110), end = ui.dp(110), bottom = ui.dp(70)),
                verticalArrangement = Arrangement.spacedBy(ui.dp(16)),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fmtClock(pos / 1000), color = Palette.text, fontSize = ui.sp(30), modifier = Modifier.width(ui.dp(130)))
                    Box(
                        Modifier.weight(1f).height(ui.dp(if (seeking) 14 else 10)).clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.28f)),
                    ) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(Color.White))
                    }
                    Text(
                        fmtClock(m.durationMs / 1000), color = Palette.textDim, fontSize = ui.sp(30),
                        textAlign = TextAlign.End, modifier = Modifier.width(ui.dp(130)),
                    )
                }
                Text(
                    "◀ ▶ 快退 / 快进(连按加速)    确认 暂停 / 播放    返回 退出",
                    color = Palette.textFaint, fontSize = ui.sp(22),
                )
            }
        }

        if (seeking) {
            val delta = (m.seekTargetMs - m.positionMs) / 1000
            val span = if (delta >= 0) "快进" else "快退"
            val n = kotlin.math.abs(delta)
            val txt = if (n >= 60) "$span ${n / 60} 分 ${n % 60} 秒" else "$span $n 秒"
            Text(
                "$txt   ${fmtClock(m.seekTargetMs / 1000)}", color = Color.White, fontSize = ui.sp(40), fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center).clip(CircleShape).background(Color(0xB3000000))
                    .padding(horizontal = ui.dp(44), vertical = ui.dp(20)),
            )
        } else if (m.buffering) {
            CircularProgressIndicator(
                color = Color.White, trackColor = Color.White.copy(alpha = 0.2f), strokeWidth = ui.dp(6),
                modifier = Modifier.align(Alignment.Center).size(ui.dp(96)),
            )
        } else if (m.paused) {
            PauseGlyph(Modifier.align(Alignment.Center).size(ui.dp(120)))
        }
    }
}

@Composable
private fun PauseGlyph(modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize(0.38f)) {
            val w = size.width * 0.34f
            drawRect(Color.White, Offset(0f, 0f), Size(w, size.height))
            drawRect(Color.White, Offset(size.width - w, 0f), Size(w, size.height))
        }
    }
}

@Composable
private fun StatusScreen(
    m: PlayerModel,
    hubRepo: HubRepository,
    busy: Boolean,
    text: String,
    buttons: List<Pair<String, () -> Unit>>,
    onBack: () -> Unit,
    detail: String? = null,
    progress: Float? = null,
    danger: Boolean = false,
) {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val item = m.item
    val first = remember { FocusRequester() }
    LaunchedEffect(buttons.size) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0D16))) {
        val art = item?.let { if (it.thumb) hubRepo.thumbUrl(it) else if (it.poster) hubRepo.posterUrl(it) else null }
        if (art != null) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(art).build(), contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.28f }, contentScale = ContentScale.Crop,
            )
        }
        Box(Modifier.fillMaxSize().background(Color(0xCC0B0D16)))
        Column(
            Modifier.align(Alignment.Center).width(ui.dp(1000)),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(ui.dp(18)),
        ) {
            if (item != null) {
                Text(
                    item.series.ifBlank { item.title } + if (item.series.isNotBlank()) "  ${episodeLabel(item)}" else "",
                    color = Palette.textDim, fontSize = ui.sp(30), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (busy) {
                CircularProgressIndicator(
                    color = Color.White, trackColor = Color.White.copy(alpha = 0.2f), strokeWidth = ui.dp(6),
                    modifier = Modifier.size(ui.dp(84)),
                )
            }
            Text(
                text, color = if (danger) Palette.danger else Palette.text, fontSize = ui.sp(52),
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
            if (progress != null) {
                Box(Modifier.width(ui.dp(640)).height(ui.dp(10)).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Color.White))
                }
            }
            if (!detail.isNullOrBlank()) {
                Text(detail, color = Palette.textDim, fontSize = ui.sp(27), lineHeight = ui.sp(40), textAlign = TextAlign.Center)
            }
            Column(
                Modifier.width(ui.dp(560)).padding(top = ui.dp(20)),
                verticalArrangement = Arrangement.spacedBy(ui.dp(14)),
            ) {
                val list = buttons.ifEmpty { listOf("返回" to onBack) }
                list.forEachIndexed { i, (label, act) ->
                    TvButton(
                        label, Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier), onClick = act,
                    )
                }
            }
        }
    }
}

@Composable
private fun EndedScreen(m: PlayerModel, hubRepo: HubRepository, onNext: () -> Unit, onBack: () -> Unit) {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val first = remember { FocusRequester() }
    val nxt = m.nextItem
    LaunchedEffect(nxt?.id) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color(0xE60B0D16))) {
        Column(
            Modifier.align(Alignment.Center).width(ui.dp(1000)),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(ui.dp(18)),
        ) {
            Text("播放完毕", color = Palette.textDim, fontSize = ui.sp(30))
            if (nxt == null) {
                CircularProgressIndicator(
                    color = Color.White, trackColor = Color.White.copy(alpha = 0.2f), strokeWidth = ui.dp(6),
                    modifier = Modifier.size(ui.dp(72)),
                )
            } else {
                Text("接下来", color = Palette.textDim, fontSize = ui.sp(30))
                val art = if (nxt.thumb) hubRepo.thumbUrl(nxt) else if (nxt.poster) hubRepo.posterUrl(nxt) else null
                if (art != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(ctx).data(art).crossfade(150).build(), contentDescription = null,
                        modifier = Modifier.width(ui.dp(560)).height(ui.dp(315)).clip(RoundedCornerShape(ui.dp(22))),
                        contentScale = ContentScale.Crop,
                    )
                }
                Text(
                    nxt.series.ifBlank { nxt.title } + if (nxt.series.isNotBlank()) "  ·  ${episodeLabel(nxt)}" else "",
                    color = Palette.text, fontSize = ui.sp(44), fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        m.countdown > 0 -> "${m.countdown} 秒后自动播放"
                        m.countdown < 0 -> "这一集还没有准备好(电脑正在转码),暂不自动播放"
                        else -> ""
                    },
                    color = Palette.textDim, fontSize = ui.sp(28),
                )
                Row(Modifier.padding(top = ui.dp(10)), horizontalArrangement = Arrangement.spacedBy(ui.dp(16))) {
                    TvButton(
                        "立即播放", Modifier.width(ui.dp(380)).focusRequester(first), lockHorizontal = false, onClick = onNext,
                    )
                    TvButton("返回", Modifier.width(ui.dp(380)), lockHorizontal = false, onClick = onBack)
                }
            }
        }
    }
}
