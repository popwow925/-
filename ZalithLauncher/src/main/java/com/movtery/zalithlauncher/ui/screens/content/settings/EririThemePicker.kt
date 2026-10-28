/*
 * EririLily (unofficial modification of Zalith Launcher 2), GPL-3.0.
 */
package com.movtery.zalithlauncher.ui.screens.content.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.materialkolor.PaletteStyle
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.setting.enums.DarkMode
import com.movtery.zalithlauncher.ui.components.BackgroundCard
import com.movtery.zalithlauncher.ui.theme.ColorThemeType
import com.movtery.zalithlauncher.viewmodel.LocalBackgroundViewModel

/** EririLily's three looks, same as the Windows launcher: sakura, pure white, pure black. */
enum class ElTheme(val key: String, val nameRes: Int, val swatch: Color, val ring: Color, val color: Int, val style: PaletteStyle, val dark: DarkMode) {
    SAKURA("sakura", R.string.el_theme_sakura, Color(0xFFF6D3DB), Color(0xFFE48AA0), 0xFFE48AA0.toInt(), PaletteStyle.TonalSpot, DarkMode.Disable),
    WHITE("white", R.string.el_theme_white, Color(0xFFFFFFFF), Color(0xFFBDBDBD), 0xFF8A8A8A.toInt(), PaletteStyle.Monochrome, DarkMode.Disable),
    BLACK("black", R.string.el_theme_black, Color(0xFF121212), Color(0xFF5A5A5A), 0xFF8A8A8A.toInt(), PaletteStyle.Monochrome, DarkMode.Enable);

    fun apply() {
        AllSettings.launcherColorTheme.save(ColorThemeType.CUSTOM)
        AllSettings.launcherCustomColor.save(color)
        AllSettings.launcherCustomPaletteStyle.save(style)
        AllSettings.launcherDarkMode.save(dark)
    }

    companion object {
        @Composable
        fun current(): ElTheme? {
            if (AllSettings.launcherColorTheme.state != ColorThemeType.CUSTOM) return null
            val c = AllSettings.launcherCustomColor.state
            val st = AllSettings.launcherCustomPaletteStyle.state
            val dm = AllSettings.launcherDarkMode.state
            return entries.firstOrNull { it.color == c && it.style == st && it.dark == dm }
        }
    }
}

@Composable
fun ElThemePicker(modifier: Modifier = Modifier) {
    val cur = ElTheme.current()
    val context = LocalContext.current
    val background = LocalBackgroundViewModel.current
    BackgroundCard(modifier = modifier, shape = MaterialTheme.shapes.extraLarge) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.el_theme_title), style = MaterialTheme.typography.titleSmall)
            Text(
                modifier = Modifier.alpha(0.7f),
                text = stringResource(R.string.el_theme_summary),
                style = MaterialTheme.typography.bodySmall
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ElTheme.entries.forEach { t ->
                    val selected = t == cur
                    val border by animateColorAsState(if (selected) t.ring else Color.Transparent, label = "elThemeRing")
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .border(2.dp, border, RoundedCornerShape(16.dp))
                            .clickable {
                                t.apply()
                                background?.followTheme(context, t.key)
                            }
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(t.swatch, CircleShape)
                                .border(1.dp, t.ring, CircleShape)
                        )
                        Text(stringResource(t.nameRes), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
