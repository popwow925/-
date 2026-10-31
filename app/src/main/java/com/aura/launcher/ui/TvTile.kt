package com.aura.launcher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * tvOS 风格的焦点磁贴:
 * 获得焦点时弹性放大并轻抬,底部泛出主题色光晕,表面扫过一道高光,
 * 并按最近一次方向键做一次小幅 3D 倾斜回弹;标题在磁贴下方淡入。
 * 所有动画值只在绘制/图层阶段读取,焦点切换不会触发重组。
 */
@Composable
fun TvTile(
    width: Dp,
    height: Dp,
    accent: Color,
    label: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val ui = LocalUi.current
    val navDir = LocalNavDir.current
    val focus = remember { Animatable(0f) }
    val tiltX = remember { Animatable(0f) }
    val tiltY = remember { Animatable(0f) }
    var focused by remember { mutableStateOf(false) }
    val longFired = remember { BooleanArray(1) }
    val shape = RoundedCornerShape(ui.dp(22))

    val curClick by rememberUpdatedState(onClick)
    val curLong by rememberUpdatedState(onLongClick)
    val curFocused by rememberUpdatedState(onFocused)

    androidx.compose.runtime.LaunchedEffect(focused) {
        if (focused) {
            launch { focus.animateTo(1f, spring(dampingRatio = 0.58f, stiffness = 320f)) }
            tiltX.snapTo(-navDir.dx * 7f)
            tiltY.snapTo(navDir.dy * 5f)
            launch { tiltX.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 90f)) }
            launch { tiltY.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 90f)) }
        } else {
            focus.animateTo(0f, spring(dampingRatio = 1f, stiffness = 500f))
        }
    }

    Column(modifier.size(width, height + ui.dp(64)), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width, height)
                .graphicsLayer {
                    val f = focus.value
                    val s = 1f + 0.10f * f
                    scaleX = s
                    scaleY = s
                    rotationY = tiltX.value
                    rotationX = tiltY.value
                    translationY = -8f * density * f
                    cameraDistance = 14f * density
                }
                .drawBehind {
                    val f = focus.value
                    if (f > 0.02f) {
                        // 圆形光晕:渐变半径与圆半径一致,边缘完全淡出,不会出现矩形硬边;
                        // 终点用同色透明,避免插值时发黑
                        val r = size.width * 0.62f
                        val c = Offset(size.width / 2f, size.height * 0.9f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(
                                    accent.copy(alpha = (0.5f * f).coerceIn(0f, 0.6f)),
                                    accent.copy(alpha = 0f),
                                ),
                                center = c,
                                radius = r,
                            ),
                            radius = r,
                            center = c,
                        )
                    }
                }
                .onKeyEvent { e ->
                    val k = e.key
                    if (k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter) {
                        when (e.type) {
                            KeyEventType.KeyDown -> {
                                if (e.nativeKeyEvent.repeatCount == 0) {
                                    longFired[0] = false
                                } else if (!longFired[0] && curLong != null) {
                                    longFired[0] = true
                                    curLong?.invoke()
                                }
                                true
                            }
                            KeyEventType.KeyUp -> {
                                if (!longFired[0]) curClick()
                                longFired[0] = false
                                true
                            }
                            else -> false
                        }
                    } else false
                }
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) curFocused()
                }
                .focusable()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { curClick() }, onLongPress = { curLong?.invoke() })
                }
                .clip(shape)
                .border(1.dp, Color.White.copy(alpha = 0.10f), shape),
        ) {
            content()
            // 表面高光:焦点时从左上角斜扫一层白光
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val a = focus.value
                        if (a > 0.02f) {
                            drawRect(
                                Brush.linearGradient(
                                    listOf(Color.White.copy(alpha = (0.24f * a).coerceIn(0f, 0.3f)), Color.Transparent),
                                    start = Offset.Zero,
                                    end = Offset(size.width, size.height * 1.3f),
                                )
                            )
                        }
                    }
            )
        }
        if (label != null) {
            Text(
                text = label,
                color = Palette.text,
                fontSize = ui.sp(26),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = ui.dp(22))
                    .height(ui.dp(36))
                    .graphicsLayer {
                        val f = focus.value.coerceIn(0f, 1f)
                        alpha = f
                        translationY = 10f * density * (1f - f)
                    },
            )
        }
    }
}

/** 顶部导航标签:聚焦时变成白色胶囊,当前页保持淡色底。 */
@Composable
fun TabItem(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    val ui = LocalUi.current
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        if (focused) Color.White else if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent,
        tween(160), label = "tabBg",
    )
    val fg by animateColorAsState(
        if (focused) Color.Black else if (selected) Color.White else Palette.textDim,
        tween(160), label = "tabFg",
    )
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, spring(0.7f, 400f), label = "tabScale")
    val curFocused by rememberUpdatedState(onFocused)

    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(bg)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) curFocused()
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = ui.dp(34), vertical = ui.dp(14)),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = fg, fontSize = ui.sp(30), fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** 列表式按钮(设置项、菜单项、文件行):聚焦时整行变白,文字变黑。 */
@Composable
fun TvButton(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    danger: Boolean = false,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    val ui = LocalUi.current
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (focused) Color.White else Palette.glass, tween(140), label = "btnBg")
    val fg by animateColorAsState(
        if (focused) Color.Black else if (danger) Palette.danger else Palette.text,
        tween(140), label = "btnFg",
    )
    val sub by animateColorAsState(
        if (focused) Color.Black.copy(alpha = 0.6f) else Palette.textDim, tween(140), label = "btnSub",
    )
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, spring(0.75f, 420f), label = "btnScale")
    val curFocused by rememberUpdatedState(onFocused)

    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(ui.dp(18)))
            .background(bg)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) curFocused()
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = ui.dp(32), vertical = ui.dp(20)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(Modifier.fillMaxWidth(if (trailing != null) 0.78f else 1f)) {
            Text(title, color = fg, fontSize = ui.sp(30), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle, color = sub, fontSize = ui.sp(23), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = ui.dp(6)),
                )
            }
        }
        if (trailing != null) {
            Text(
                trailing, color = sub, fontSize = ui.sp(26),
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}
