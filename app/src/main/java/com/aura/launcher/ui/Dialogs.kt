package com.aura.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aura.launcher.data.AppEntry
import com.aura.launcher.data.AppRepository
import com.aura.launcher.data.Prefs
import kotlinx.coroutines.delay

class MenuItem(val title: String, val danger: Boolean = false, val onClick: () -> Unit)

@Composable
private fun DialogFrame(title: String, subtitle: String? = null, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val ui = LocalUi.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(ui.dp(760))
                    .clip(RoundedCornerShape(ui.dp(32)))
                    .background(Color(0xF21B1E2C))
                    .padding(ui.dp(44)),
                verticalArrangement = Arrangement.spacedBy(ui.dp(14)),
            ) {
                Text(title, color = Palette.text, fontSize = ui.sp(38), fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (subtitle != null) Text(subtitle, color = Palette.textDim, fontSize = ui.sp(24), maxLines = 3)
                content()
            }
        }
    }
}

/** 通用菜单弹窗,第一项自动获得焦点,返回键关闭。 */
@Composable
fun MenuDialog(title: String, items: List<MenuItem>, subtitle: String? = null, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    DialogFrame(title, subtitle, onDismiss) {
        items.forEachIndexed { i, item ->
            TvButton(
                title = item.title,
                danger = item.danger,
                modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                onClick = { item.onClick(); onDismiss() },
            )
        }
    }
}

/** 单行文字输入弹窗(用于商店源地址)。 */
@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    hint: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ui = LocalUi.current
    var text by remember { mutableStateOf(initial) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { fieldFocus.requestFocus() }
    }
    DialogFrame(title, hint, onDismiss) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = ui.sp(28)),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onConfirm(text); onDismiss() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(fieldFocus)
                .clip(RoundedCornerShape(ui.dp(16)))
                .background(Palette.glassStrong)
                .padding(horizontal = ui.dp(26), vertical = ui.dp(20)),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ui.dp(16))) {
            TvButton("确定", Modifier.weight(1f), lockHorizontal = false, onClick = { onConfirm(text); onDismiss() })
            TvButton("取消", Modifier.weight(1f), lockHorizontal = false, onClick = onDismiss)
        }
    }
}

/** 纯文字说明弹窗,只有一个"知道了"按钮。 */
@Composable
fun InfoDialog(title: String, body: String, onDismiss: () -> Unit) {
    val ui = LocalUi.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    DialogFrame(title, null, onDismiss) {
        Text(body, color = Palette.textDim, fontSize = ui.sp(26), lineHeight = ui.sp(40))
        TvButton("知道了", Modifier.fillMaxWidth().focusRequester(first), onClick = onDismiss)
    }
}

/** 长按应用磁贴弹出的操作菜单。 */
@Composable
fun AppActionDialog(
    entry: AppEntry,
    favorites: List<String>,
    repo: AppRepository,
    prefs: Prefs,
    onLaunch: (AppEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val isFav = entry.packageName in favorites
    val items = buildList {
        add(MenuItem("打开") { onLaunch(entry) })
        add(MenuItem(if (isFav) "从「常用应用」移除" else "添加到「常用应用」") { prefs.toggleFavorite(entry.packageName) })
        if (isFav) add(MenuItem("移到最前") { prefs.moveFavoriteToFront(entry.packageName) })
        add(MenuItem("从桌面隐藏") { prefs.hide(entry.packageName) })
        add(MenuItem("应用信息") { repo.openAppInfo(entry.packageName) })
        add(MenuItem("卸载", danger = true) { repo.uninstall(entry.packageName) })
    }
    MenuDialog(title = entry.label, items = items, onDismiss = onDismiss)
}
