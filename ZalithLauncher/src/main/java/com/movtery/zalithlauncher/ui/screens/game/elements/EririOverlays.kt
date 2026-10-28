/*
 * EririLily (unofficial modification of Zalith Launcher 2)
 * Copyright (C) 2026 D0cK and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.ui.screens.game.elements

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.border
import androidx.compose.ui.unit.sp
import com.movtery.zalithlauncher.game.input.KeystrokeTracker
import com.movtery.zalithlauncher.ui.components.FloatingBall
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Sakura = Color(0xFFE48AA0)
private val KeyIdle = Color.Transparent
private val KeyOutline = Color(0x8CFFFFFF)
private val KeyText = Color(0xF2FFFFFF)

/** Text with a soft dark shadow so it stays readable without any background. */
private val Outlined = TextStyle(shadow = Shadow(color = Color(0xCC000000), offset = Offset(0f, 1.5f), blurRadius = 4f))

/** Tiny chip shown when an overlay is collapsed; tap it to expand again. */
@Composable
private fun CollapsedChip(label: String) {
    Box(
        modifier = Modifier
            .border(1.dp, KeyOutline, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = KeyText, fontSize = 10.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold, style = Outlined)
    }
}

/**
 * EririLily: draggable keystrokes overlay — W A S D, Shift, Space, both mouse
 * buttons with clicks per second. Pressed keys light up in sakura pink.
 */
@Composable
fun KeystrokesOverlay(
    position: Offset,
    onPositionChanged: (Offset) -> Unit,
    onSavePos: () -> Unit,
    scale: Float,
    alpha: Float,
    showCps: Boolean,
    collapsed: Boolean = false,
    onToggleCollapsed: () -> Unit = {},
) {
    val unit = (24 * scale).dp
    val gap = (3 * scale).dp
    val font = (10 * scale).sp
    FloatingBall(
        modifier = Modifier.focusProperties { canFocus = false },
        position = position,
        onPositionChanged = onPositionChanged,
        onSavePos = onSavePos,
        onClick = onToggleCollapsed,
        alpha = alpha,
        color = Color.Transparent,
        contentColor = KeyText,
        shape = RoundedCornerShape(8.dp),
    ) {
        if (collapsed) {
            CollapsedChip("⌨")
        } else {
            val t = KeystrokeTracker
            var lCps by remember { mutableIntStateOf(0) }
            var rCps by remember { mutableIntStateOf(0) }
            if (showCps) {
                LaunchedEffect(Unit) {
                    while (true) {
                        lCps = t.cps(true); rCps = t.cps(false)
                        delay(200)
                    }
                }
            }
            Column(
                modifier = Modifier.padding(gap),
                verticalArrangement = Arrangement.spacedBy(gap),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Spacer(Modifier.size(unit))
                    Key("W", t.w, unit, unit, font)
                    Spacer(Modifier.size(unit))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Key("A", t.a, unit, unit, font)
                    Key("S", t.s, unit, unit, font)
                    Key("D", t.d, unit, unit, font)
                }
                val wide = unit * 3 + gap * 2
                val half = (wide - gap) / 2
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Key(if (showCps) "LMB\n$lCps CPS" else "LMB", t.lmb, half, unit, font * 0.82f)
                    Key(if (showCps) "RMB\n$rCps CPS" else "RMB", t.rmb, half, unit, font * 0.82f)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Key("⇧", t.shift, unit, unit * 0.7f, font)
                    Key("━━━", t.space, wide - unit - gap, unit * 0.7f, font * 0.8f)
                }
            }
        }
    }
}

@Composable
private fun Key(label: String, pressed: Boolean, w: Dp, h: Dp, font: androidx.compose.ui.unit.TextUnit) {
    val bg by animateColorAsState(if (pressed) Sakura else KeyIdle, tween(if (pressed) 40 else 180), label = "key")
    val fg by animateColorAsState(if (pressed) Color.White else KeyText, tween(120), label = "keyText")
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .size(w, h)
            .background(bg, shape)
            .border(1.dp, if (pressed) Sakura else KeyOutline, shape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = font,
            lineHeight = font,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            style = Outlined
        )
    }
}

/**
 * EririLily: phone status — battery (and charging), battery temperature, the time
 * and how long this session has been running. Useful when playing on a phone that
 * gets hot or runs low on battery.
 */
@Composable
fun DeviceHud(
    position: Offset,
    onPositionChanged: (Offset) -> Unit,
    onSavePos: () -> Unit,
    alpha: Float,
    fps: () -> Int?,
    collapsed: Boolean = false,
    onToggleCollapsed: () -> Unit = {},
) {
    val context = LocalContext.current
    var battery by remember { mutableIntStateOf(-1) }
    var charging by remember { mutableStateOf(false) }
    var tempC by remember { mutableIntStateOf(-1) }
    var clock by remember { mutableStateOf("") }
    val start = remember { System.currentTimeMillis() }
    var played by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            readBattery(context)?.let { (lvl, chg, t) -> battery = lvl; charging = chg; tempC = t }
            clock = fmt.format(Date())
            played = (System.currentTimeMillis() - start) / 1000
            delay(2000)
        }
    }
    FloatingBall(
        modifier = Modifier.focusProperties { canFocus = false },
        position = position,
        onPositionChanged = onPositionChanged,
        onSavePos = onSavePos,
        onClick = onToggleCollapsed,
        alpha = alpha,
        color = Color.Transparent,
        contentColor = KeyText,
        shape = RoundedCornerShape(50),
    ) {
        if (collapsed) {
            CollapsedChip(fps()?.let { "$it FPS" } ?: "◷")
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                fps()?.let { HudItem("$it", "FPS", if (it < 30) Color(0xFFFF8A80) else KeyText) }
                if (battery >= 0) HudItem(
                    "$battery%" + if (charging) " ⚡" else "",
                    "电量",
                    if (battery <= 15 && !charging) Color(0xFFFF8A80) else KeyText
                )
                if (tempC > 0) HudItem("$tempC°C", "温度", if (tempC >= 42) Color(0xFFFF8A80) else if (tempC >= 38) Color(0xFFFFD180) else KeyText)
                HudItem(clock, "时间", KeyText)
                HudItem(formatPlayed(played), "本次", Sakura)
            }
        }
    }
}

@Composable
private fun HudItem(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, lineHeight = 12.sp, style = Outlined)
        Text(label, color = KeyText.copy(alpha = 0.75f), fontSize = 8.sp, lineHeight = 9.sp, style = Outlined)
    }
}

private fun formatPlayed(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) "${h}h${m.toString().padStart(2, '0')}m" else "${m}m"
}

private fun readBattery(context: Context): Triple<Int, Boolean, Int>? {
    val i: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).takeIf { it > 0 } ?: 100
    val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) // tenths of a degree
    val pct = if (level >= 0) level * 100 / scale else -1
    val chg = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return Triple(pct, chg, if (temp > 0) temp / 10 else -1)
}
