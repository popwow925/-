package com.aura.launcher.data

import android.content.Context
import com.aura.launcher.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 轻量偏好存储:收藏顺序、隐藏列表、最近使用、壁纸、商店源地址。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("aura", Context.MODE_PRIVATE)

    private val _favorites = MutableStateFlow(readList("favorites"))
    val favorites: StateFlow<List<String>> = _favorites.asStateFlow()

    private val _hidden = MutableStateFlow(readList("hidden").toSet())
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    private val _recents = MutableStateFlow(readList("recents"))
    val recents: StateFlow<List<String>> = _recents.asStateFlow()

    private val _wallpaper = MutableStateFlow(sp.getInt("wallpaper", 0))
    val wallpaper: StateFlow<Int> = _wallpaper.asStateFlow()

    private val _storeUrl = MutableStateFlow(sp.getString("storeUrl", null) ?: BuildConfig.STORE_URL)
    val storeUrl: StateFlow<String> = _storeUrl.asStateFlow()

    private fun readList(key: String): List<String> =
        sp.getString(key, "").orEmpty().split('\n').filter { it.isNotBlank() }

    private fun writeList(key: String, list: Collection<String>) {
        sp.edit().putString(key, list.joinToString("\n")).apply()
    }

    fun toggleFavorite(pkg: String) {
        val cur = _favorites.value
        val next = if (pkg in cur) cur - pkg else cur + pkg
        _favorites.value = next
        writeList("favorites", next)
    }

    fun moveFavoriteToFront(pkg: String) {
        val next = listOf(pkg) + _favorites.value.filter { it != pkg }
        _favorites.value = next
        writeList("favorites", next)
    }

    fun hide(pkg: String) {
        val next = _hidden.value + pkg
        _hidden.value = next
        writeList("hidden", next)
    }

    fun unhide(pkg: String) {
        val next = _hidden.value - pkg
        _hidden.value = next
        writeList("hidden", next)
    }

    fun unhideAll() {
        _hidden.value = emptySet()
        writeList("hidden", emptyList())
    }

    fun markLaunched(pkg: String) {
        val next = (listOf(pkg) + _recents.value.filter { it != pkg }).take(12)
        _recents.value = next
        writeList("recents", next)
    }

    fun setWallpaper(index: Int) {
        _wallpaper.value = index
        sp.edit().putInt("wallpaper", index).apply()
    }

    fun setStoreUrl(url: String) {
        val v = url.trim()
        _storeUrl.value = v.ifEmpty { BuildConfig.STORE_URL }
        sp.edit().putString("storeUrl", v).apply()
    }
}
