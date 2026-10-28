/*
 * Zalith Launcher 2
 * Copyright (C) 2025-2026 MovTery <movtery228@qq.com> and contributors
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

package com.movtery.zalithlauncher.utils.device

import androidx.annotation.Keep
import android.util.Log
import org.apache.commons.io.FileUtils
import java.io.File

private const val TAG = "VulkanCapabilities"

/**
 * 设备的原始 Vulkan 支持情况
 */
@Keep
data class VulkanCapabilities(
    val apiVersionMajor: Int,
    val apiVersionMinor: Int,
    val apiVersionPatch: Int,
    val extensions: List<String>,
    val features: Map<String, Boolean>
) {
    /** Vulkan 版本字符串 */
    val versionString: String
        get() = "$apiVersionMajor.$apiVersionMinor.$apiVersionPatch"

    /** 检查 Vulkan 版本是否至少为 1.2 */
    val isVersionSupported: Boolean
        get() = apiVersionMajor > 1 || (apiVersionMajor == 1 && apiVersionMinor >= 2)
}

@Keep
fun interface VulkanLogCallback {
    fun log(level: String, message: String)
}

@Keep
object VulkanChecker {
    init {
        try {
            System.loadLibrary("vulkan_check")
            nativeSetLogCallback { level, msg ->
                when (level) {
                    "INFO" -> Log.i(TAG, msg)
                    "WARN" -> Log.w(TAG, msg)
                    "ERROR" -> Log.e(TAG, msg)
                    else -> Log.i(TAG, msg)
                }
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load vulkan_check library", e)
        }
    }

    /**
     * 查询系统 Vulkan 支持情况
     * @return 如果不支持 Vulkan 或初始化失败，返回 null
     */
    fun checkCapabilities(
        driverPath: String?,
        nativeDir: String?,
        cacheDir: String?
    ): VulkanCapabilities? {
        return try {
            nativeCheckVulkan(
                driverPath = driverPath,
                nativeDir = nativeDir,
                cacheDir = cacheDir,
            )?.also { caps ->
                Log.i(TAG, "Vulkan version: ${caps.versionString}")
                Log.i(TAG, "Version >= 1.2: ${caps.isVersionSupported}")
                caps.profileSupport().forEach { profile ->
                    Log.i(
                        TAG,
                        "Minecraft ${profile.versionRangeText} Vulkan supported: ${profile.supported}"
                    )
                    if (!profile.supported) {
                        val support = caps.supportFor(profile.since)
                        val missing = (support.missingRequired + support.missingOptional)
                            .joinToString { it.dependency.name }
                        Log.w(
                            TAG,
                            "Minecraft ${profile.versionRangeText} missing: $missing"
                        )
                    }
                }
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native library or method not found", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Native check failed", e)
            null
        } finally {
            if (nativeDir != null && cacheDir != null) {
                FileUtils.deleteQuietly(File(cacheDir))
            }
        }
    }

    @Keep
    @JvmStatic
    private external fun nativeSetLogCallback(callback: VulkanLogCallback)

    @Keep
    @JvmStatic
    private external fun nativeCheckVulkan(
        driverPath: String?,
        nativeDir: String?,
        cacheDir: String?
    ): VulkanCapabilities?
}
