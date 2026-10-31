package com.aura.launcher.data

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import androidx.compose.runtime.Immutable

@Immutable
data class SignalSource(val id: String, val label: String)

/** 读取 HDMI / AV 等直通信号源,点击后切换。没有电视框架的设备返回空列表。 */
object TvInputs {

    fun list(context: Context): List<SignalSource> = runCatching {
        val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            ?: return@runCatching emptyList()
        tim.tvInputList
            .filter { it.isPassthroughInput }
            .map { info ->
                val label = info.loadCustomLabel(context)?.toString()?.takeIf { it.isNotBlank() }
                    ?: info.loadLabel(context)?.toString().orEmpty().ifBlank { "信号源" }
                SignalSource(info.id, label)
            }
    }.getOrDefault(emptyList())

    fun open(context: Context, source: SignalSource): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(source.id))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess
}
