/*
 * EririLily (unofficial modification of Zalith Launcher 2), GPL-3.0.
 */
package com.movtery.zalithlauncher.ui.screens.content.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.renderer.GpuInfo
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.ui.components.BackgroundCard

/**
 * EririLily: one-tap performance presets. They only touch the render resolution and Android's
 * sustained-performance mode (which trades peak speed for steadier frame rates when the phone
 * gets warm) — the renderer itself stays Krypton Wrapper unless the user changes it.
 */
enum class ElPerf(val nameRes: Int, val descRes: Int, val resolution: Int, val sustained: Boolean) {
    SMOOTH(R.string.el_perf_smooth, R.string.el_perf_smooth_desc, 70, true),
    BALANCED(R.string.el_perf_balanced, R.string.el_perf_balanced_desc, 85, true),
    QUALITY(R.string.el_perf_quality, R.string.el_perf_quality_desc, 100, false);

    fun apply() {
        AllSettings.resolutionRatio.save(resolution)
        AllSettings.sustainedPerformance.save(sustained)
    }
}

@Composable
fun ElPerformancePresets(modifier: Modifier = Modifier) {
    val res = AllSettings.resolutionRatio.state
    val sus = AllSettings.sustainedPerformance.state
    val cur = ElPerf.entries.firstOrNull { it.resolution == res && it.sustained == sus }
    val ring = MaterialTheme.colorScheme.primary
    BackgroundCard(modifier = modifier, shape = MaterialTheme.shapes.extraLarge) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.el_perf_title), style = MaterialTheme.typography.titleSmall)
            Text(
                modifier = Modifier.alpha(0.7f),
                text = stringResource(R.string.el_perf_summary, gpuName()),
                style = MaterialTheme.typography.bodySmall
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ElPerf.entries.forEach { p ->
                    val border by animateColorAsState(if (p == cur) ring else ring.copy(alpha = 0.18f), label = "elPerf")
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .border(if (p == cur) 2.dp else 1.dp, border, RoundedCornerShape(16.dp))
                            .clickable { p.apply() }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(stringResource(p.nameRes), style = MaterialTheme.typography.labelLarge)
                        Text(
                            modifier = Modifier.alpha(0.7f),
                            text = stringResource(p.descRes),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun gpuName(): String = when (GpuInfo.family) {
    GpuInfo.Family.ADRENO -> "Adreno"
    GpuInfo.Family.MALI -> "Mali"
    GpuInfo.Family.OTHER -> stringResource(R.string.el_gpu_other)
    GpuInfo.Family.UNKNOWN -> stringResource(R.string.el_gpu_unknown)
}
