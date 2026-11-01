package com.aura.launcher.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 所有尺寸按 1920×1080 的"设计像素"书写,再按屏幕实际宽度缩放,
 * 这样 1080p / 4K、不同系统 dpi 的索尼电视看到的布局比例一致。
 */
@Immutable
class Ui(val u: Float, val density: Float = 1f) {
    fun dp(n: Number): Dp = (n.toFloat() * u).dp
    fun sp(n: Number): TextUnit = (n.toFloat() * u).sp

    /** 设计像素 → 屏幕物理像素(用于滚动距离等以 px 计的场合)。 */
    fun px(n: Number): Float = n.toFloat() * u * density
}

val LocalUi = staticCompositionLocalOf { Ui(1f) }

/** 最近一次方向键的方向,磁贴获得焦点时据此做轻微的倾斜回弹。 */
class NavDir {
    var dx = 0f
    var dy = 0f
}

val LocalNavDir = staticCompositionLocalOf { NavDir() }

/**
 * 顶栏与页面内容之间的焦点交接。
 * Compose 默认按几何位置就近找下一个焦点,所以从顶栏按"下"会落在正下方的某个磁贴上,
 * 而不是你刚才离开的那个;从内容按"上"也可能落到不是当前页的标签。
 * 这里让页面注册"回到内容"的动作,让顶栏注册"回到当前标签"的动作,两边显式交接。
 */
class FocusBridge {
    /** 当前页面注册:把焦点交还给上次聚焦(或第一个)内容,成功返回 true。 */
    var enterContent: (() -> Boolean)? = null

    /** 顶栏注册:把焦点交给当前选中的标签,成功返回 true。 */
    var enterTabs: (() -> Boolean)? = null
}

val LocalFocusBridge = staticCompositionLocalOf { FocusBridge() }

object Palette {
    val text = Color.White
    val textDim = Color.White.copy(alpha = 0.64f)
    val textFaint = Color.White.copy(alpha = 0.40f)
    val glass = Color.White.copy(alpha = 0.10f)
    val glassStrong = Color.White.copy(alpha = 0.18f)
    val accent = Color(0xFF5B7CFF)
    val danger = Color(0xFFFF6B6B)
    val fallbackTile = Color(0xFF3A3F5C)
}

/** 焦点项只在快要出屏时才滚动,并留出边距;滚动用无回弹的柔和弹簧。 */
@OptIn(ExperimentalFoundationApi::class)
class EdgeSpec(private val marginPx: Float) : BringIntoViewSpec {
    // 刚度调高后横向滚动更跟手:连按方向键时焦点不会跑到视口外再被追回
    override val scrollAnimationSpec: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 420f)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            size + marginPx * 2 >= containerSize -> offset - marginPx
            offset < marginPx -> offset - marginPx
            trailing > containerSize - marginPx -> trailing - (containerSize - marginPx)
            else -> 0f
        }
    }
}

/** 主页纵向滚动由"按行吸附"接管,所以关掉系统自带的自动滚动。 */
@OptIn(ExperimentalFoundationApi::class)
object NoScrollSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}
