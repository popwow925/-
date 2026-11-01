package com.aura.launcher.data

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URL

/** 家庭中心里的一部影片(电影 / 一集 / 下载的视频)。 */
@Immutable
data class HubItem(
    val id: Int,
    val title: String,
    val year: String,
    val overview: String,
    val series: String,
    val season: Int,
    val episode: Int,
    val duration: Int,
    val width: Int,
    val height: Int,
    val size: Long,
    val added: Long,
    val position: Int,
    val watched: Boolean,
    val lastPlayed: Long,
    val poster: Boolean,
    val thumb: Boolean,
    val v: Long,
    val play: String,
    val tprogress: Float,
    val terror: String,
) {
    /** 电视可以直接播放(原片或已转码的版本)。 */
    val playable: Boolean get() = play == "direct" || play == "transcoded"
    val inProgress: Boolean get() = !watched && position > 10 && duration > 0
    val progress: Float get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    companion object {
        fun parse(o: JSONObject) = HubItem(
            id = o.getInt("id"), title = o.optString("title"), year = o.optString("year"),
            overview = o.optString("overview"), series = o.optString("series"),
            season = o.optInt("season"), episode = o.optInt("episode"), duration = o.optInt("duration"),
            width = o.optInt("width"), height = o.optInt("height"), size = o.optLong("size"),
            added = o.optLong("added"), position = o.optInt("position"), watched = o.optBoolean("watched"),
            lastPlayed = o.optLong("last_played"), poster = o.optBoolean("poster"), thumb = o.optBoolean("thumb"),
            v = o.optLong("v"), play = o.optString("play", "direct"), tprogress = o.optDouble("tprogress", 0.0).toFloat(),
            terror = o.optString("terror"),
        )
    }
}

@Immutable
data class HubLibrary(val items: List<HubItem>, val continueIds: List<Int>, val serverName: String)

@Immutable
data class HubDetail(val item: HubItem, val nextId: Int, val streamPath: String)

@Immutable
data class HubFound(val url: String, val name: String)

sealed interface HubState {
    data object NotConfigured : HubState
    data object Loading : HubState
    data class Offline(val message: String) : HubState
    data class Ready(val lib: HubLibrary) : HubState
}

/** 电视能解哪些编码,告诉家庭中心,不能解的由电脑转码。 */
object HubCaps {
    /** 保底能力:几乎所有电视都能解。电视"报了但其实播不了"时,用它让电脑转成最保守的格式。 */
    const val SAFE = "h264,aac,mp3"

    @Volatile private var cached: String? = null

    fun string(ctx: Context): String = cached ?: compute().also { cached = it }

    private fun compute(): String {
        val out = linkedSetOf("h264", "aac", "mp3")
        val codecs = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }
            .getOrDefault(emptyList())
        fun has(mime: String) = codecs.any { c -> c.supportedTypes.any { it.equals(mime, true) } }
        if (has("video/hevc")) {
            out += "hevc"
            val main10 = codecs.any { c ->
                c.supportedTypes.any { it.equals("video/hevc", true) } &&
                    runCatching {
                        c.getCapabilitiesForType("video/hevc").profileLevels.any {
                            it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                        }
                    }.getOrDefault(false)
            }
            if (main10) out += "hevc10"
        }
        if (has("video/x-vnd.on2.vp9")) out += "vp9"
        if (has("video/av01")) out += "av1"
        if (has("video/mpeg2")) out += "mpeg2"
        if (has("audio/ac3")) out += "ac3"
        if (has("audio/eac3")) out += "eac3"
        if (has("audio/opus")) out += "opus"
        if (has("audio/flac")) out += "flac"
        if (has("audio/vorbis")) out += "vorbis"
        return out.joinToString(",")
    }
}

/** 同步 HTTP 客户端(在 IO 线程调用)。 */
class HubClient(private val baseUrl: () -> String) {
    private fun open(path: String, method: String = "GET", timeoutMs: Int = 8000): HttpURLConnection {
        val base = baseUrl().trimEnd('/')
        require(base.isNotEmpty()) { "未设置家庭中心地址" }
        return (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 4000
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            useCaches = false
        }
    }

    private fun readJson(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
            throw java.io.IOException(msg.ifEmpty { "家庭中心返回 $code" })
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val c = open(path, "POST")
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            return readJson(c)
        } finally {
            c.disconnect()
        }
    }

    fun hello(): String {
        val c = open("/api/tv/hello", timeoutMs = 4000)
        try {
            val j = readJson(c)
            if (!j.optBoolean("tv")) throw java.io.IOException("这不是家庭中心(或版本太旧,请更新电脑上的家庭中心)")
            return j.optString("name")
        } finally {
            c.disconnect()
        }
    }

    fun library(caps: String): HubLibrary {
        val c = open("/api/tv/library?caps=" + java.net.URLEncoder.encode(caps, "UTF-8"), timeoutMs = 15000)
        try {
            val j = readJson(c)
            val arr = j.optJSONArray("items") ?: JSONArray()
            val items = (0 until arr.length()).map { HubItem.parse(arr.getJSONObject(it)) }
            val cont = j.optJSONArray("continue")?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty()
            return HubLibrary(items, cont, j.optJSONObject("server")?.optString("name").orEmpty())
        } finally {
            c.disconnect()
        }
    }

    fun detail(id: Int, caps: String): HubDetail {
        val c = open("/api/tv/item/$id?caps=" + java.net.URLEncoder.encode(caps, "UTF-8"))
        try {
            val j = readJson(c)
            return HubDetail(HubItem.parse(j.getJSONObject("item")), j.optInt("next"), j.optString("stream"))
        } finally {
            c.disconnect()
        }
    }

    /** 请求电脑转码;返回 true 表示已排队 / 已完成,false 表示本来就能直接播。 */
    fun requestTranscode(id: Int, caps: String): Boolean {
        val j = post("/api/tv/item/$id/transcode", JSONObject().put("caps", caps))
        return !j.optBoolean("direct")
    }

    fun setWatched(id: Int, watched: Boolean) {
        post("/api/tv/item/$id/" + if (watched) "watched" else "unwatch", JSONObject())
    }

    fun reportProgress(id: Int, positionSec: Long, durationSec: Long) {
        runCatching {
            post("/api/tv/progress", JSONObject().put("id", id).put("position", positionSec).put("duration", durationSec))
        }
    }

    fun url(path: String): String = baseUrl().trimEnd('/') + path
}

/** 局域网里找家庭中心:发 UDP 广播,电脑端回复自己的端口。 */
object HubDiscovery {
    private const val PORT = 47821

    suspend fun find(timeoutMs: Int = 3000): List<HubFound> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, HubFound>()
        val socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(0))
                soTimeout = 300
            }
        }.getOrNull() ?: return@withContext emptyList()
        try {
            val msg = "AURA_HUB_DISCOVER".toByteArray()
            val targets = linkedSetOf<InetAddress>()
            runCatching { targets += InetAddress.getByName("255.255.255.255") }
            runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { ni ->
                    if (ni.isUp && !ni.isLoopback) ni.interfaceAddresses.forEach { ia -> ia.broadcast?.let { targets += it } }
                }
            }
            // 模拟器里电脑(宿主机)固定是 10.0.2.2
            if (Build.FINGERPRINT.contains("generic") || Build.PRODUCT.contains("sdk") || Build.MODEL.contains("Emulator")) {
                runCatching { targets += InetAddress.getByName("10.0.2.2") }
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            var lastSend = 0L
            val buf = ByteArray(1024)
            while (System.currentTimeMillis() < deadline) {
                if (System.currentTimeMillis() - lastSend > 700) {
                    lastSend = System.currentTimeMillis()
                    targets.forEach { t -> runCatching { socket.send(DatagramPacket(msg, msg.size, t, PORT)) } }
                }
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    val j = JSONObject(String(p.data, 0, p.length, Charsets.UTF_8))
                    if (j.optString("app") == "family-hub") {
                        val url = "http://${p.address.hostAddress}:${j.optInt("port", 8080)}"
                        found.putIfAbsent(url, HubFound(url, j.optString("name")))
                    }
                } catch (_: java.net.SocketTimeoutException) {
                } catch (_: Exception) {
                }
                if (found.isNotEmpty() && System.currentTimeMillis() - lastSend > 400) break
            }
        } finally {
            socket.close()
        }
        found.values.toList()
    }

    /** 把用户输入整理成 http://主机:端口(没写端口默认 8080)。 */
    fun normalize(input: String): String {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
        val hostPart = s.substringAfter("://")
        if (!hostPart.contains(':') ) s += ":8080"
        return s
    }
}

class HubRepository(private val prefs: Prefs, private val appContext: Context) {
    private val client = HubClient { prefs.hubUrl.value }
    private val _state = MutableStateFlow<HubState>(if (prefs.hubUrl.value.isBlank()) HubState.NotConfigured else HubState.Loading)
    val state: StateFlow<HubState> = _state.asStateFlow()

    val url: StateFlow<String> get() = prefs.hubUrl

    fun caps(): String = HubCaps.string(appContext)

    fun thumbUrl(item: HubItem): String = client.url("/tv/thumb/${item.id}?v=${item.v}")
    fun posterUrl(item: HubItem): String = client.url("/tv/poster/${item.id}?v=${item.v}")

    suspend fun refresh(silent: Boolean = false) {
        if (prefs.hubUrl.value.isBlank()) {
            _state.value = HubState.NotConfigured
            return
        }
        if (!silent && _state.value !is HubState.Ready) _state.value = HubState.Loading
        val caps = caps()
        runCatching { withContext(Dispatchers.IO) { client.library(caps) } }
            .onSuccess { _state.value = HubState.Ready(it) }
            .onFailure {
                if (!silent || _state.value !is HubState.Ready) {
                    _state.value = HubState.Offline(friendly(it))
                }
            }
    }

    /** 验证并保存地址;成功返回电脑名,失败返回 null 并给出原因。 */
    suspend fun connect(rawUrl: String): Result<String> {
        val url = HubDiscovery.normalize(rawUrl)
        if (url.isEmpty()) return Result.failure(IllegalArgumentException("地址是空的"))
        return runCatching { withContext(Dispatchers.IO) { HubClient { url }.hello() } }
            .map { name ->
                prefs.setHubUrl(url)
                _state.value = HubState.Loading
                name
            }
            .recoverCatching { throw IllegalStateException(friendly(it)) }
    }

    /** 请求电脑转码;成功时 true=已排队/已完成,false=本来就能直接播。 */
    suspend fun requestTranscodeResult(id: Int, safe: Boolean = false): Result<Boolean> = runCatching {
        val caps = if (safe) HubCaps.SAFE else caps()
        withContext(Dispatchers.IO) { client.requestTranscode(id, caps) }
    }.recoverCatching { throw IllegalStateException(friendly(it)) }

    /** 请求电脑转码;返回人能读的结果说明。 */
    suspend fun requestTranscode(id: Int): String = requestTranscodeResult(id).fold(
        onSuccess = { queued -> if (queued) "已通知电脑转码,完成后就能播放" else "这部影片可以直接播放" },
        onFailure = { it.message ?: "请求失败" },
    )

    /** 取一部影片的最新状态和播放地址(电视解不了的会由电脑转码后给出转码版地址)。safe=true 时按保底能力判断。 */
    suspend fun detail(id: Int, safe: Boolean = false): Result<HubDetail> = runCatching {
        val caps = if (safe) HubCaps.SAFE else caps()
        withContext(Dispatchers.IO) { client.detail(id, caps) }
    }.recoverCatching { throw IllegalStateException(friendly(it)) }

    fun streamUrl(path: String): String = client.url(path)

    /** 同步上报播放进度(在后台线程调用,失败静默)。 */
    fun reportProgressBlocking(id: Int, positionSec: Long, durationSec: Long) {
        client.reportProgress(id, positionSec, durationSec)
    }

    suspend fun setWatched(id: Int, watched: Boolean): Boolean = runCatching {
        withContext(Dispatchers.IO) { client.setWatched(id, watched) }
    }.isSuccess

    fun disconnect() {
        prefs.setHubUrl("")
        _state.value = HubState.NotConfigured
    }

    private fun friendly(e: Throwable): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
            "连不上家庭中心(${prefs.hubUrl.value}),请确认电脑已开机、运行着家庭中心,并且和电视在同一个 Wi-Fi"
        else -> e.message ?: "连接失败"
    }
}
