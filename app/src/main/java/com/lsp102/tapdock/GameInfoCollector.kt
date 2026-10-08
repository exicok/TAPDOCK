package com.lsp102.tapdock

import android.content.Context
import java.io.DataOutputStream

/**
 * 用 root 采当前前台游戏的图形 API 与渲染分辨率，结果写进 DockPrefs.gameInfo，
 * 面板每秒从 XSharedPreferences 读。
 *
 * 为什么在 App 侧跑而不是 Hook 侧：这个 App 的 root 授权已经存在（重启 SystemUI 那颗按钮就是走 su），
 * 而 SystemUI 是 platform_app 域，exec su 不保证放行，也不该在人家进程里反复起特权进程。
 *
 * 两个数各自的来路：
 *  - 分辨率：`dumpsys gfxinfo <pkg>` 的 GraphicBuffer 表，行形状 `154 ( 192) x 154`（宽 (stride) x 高），
 *    取面积最大的一行 —— 那就是游戏自己提交的 swapchain 尺寸，不是屏幕尺寸。
 *  - 图形 API：只能看进程链了什么库。libvulkan 和厂商 GLES 库经常同时存在（UE4 就是），
 *    那种情况如实标 `VK+GLES`，不假装知道它此刻用哪条链提交画面。
 */
object GameInfoCollector {

    private const val TAG = "TapDockGame"

    private const val SCRIPT =
        "P=\$(pidof %1\$s); [ -z \"\$P\" ] || { " +
            "echo \"V \$(grep -c libvulkan /proc/\$P/maps 2>/dev/null)\"; " +
            "echo \"G \$(grep -c -E 'libGLESv2_adreno|libEGL_adreno' /proc/\$P/maps 2>/dev/null)\"; }; " +
            "dumpsys gfxinfo %1\$s 2>/dev/null | " +
            "grep -oE '[0-9]{3,5} *\\( *[0-9]+ *\\) *[xX] *[0-9]{3,5}'"

    /** 表里的 buffer 行：宽 (stride) x 高 */
    private val BUFFER = Regex("(\\d{3,5}) *\\( *\\d+ *\\) *[xX] *(\\d{3,5})")

    /** 返回 true 表示写入了新值（游戏没跑 / 拿不到就什么都不写） */
    fun collect(context: Context, pkg: String): Boolean {
        val out = runSu(pkg) ?: return false
        var vulkan = 0
        var gles = 0
        var bestW = 0
        var bestH = 0
        var bestArea = 0
        out.lineSequence().forEach { line ->
            when {
                line.startsWith("V ") -> vulkan = line.substring(2).trim().toIntOrNull() ?: 0
                line.startsWith("G ") -> gles = line.substring(2).trim().toIntOrNull() ?: 0
                else -> {
                    val m = BUFFER.find(line) ?: return@forEach
                    val w = m.groupValues[1].toIntOrNull() ?: return@forEach
                    val h = m.groupValues[2].toIntOrNull() ?: return@forEach
                    if (w.toLong() * h > bestArea) {
                        bestArea = (w.toLong() * h).toInt()
                        bestW = w
                        bestH = h
                    }
                }
            }
        }
        if (bestW <= 0 || bestH <= 0) return false
        val api = when {
            vulkan > 0 && gles > 0 -> "VK+GLES"
            vulkan > 0 -> "VULKAN"
            gles > 0 -> "GLES"
            else -> "未知"
        }
        DockPrefs(context).gameInfo = "$pkg|$api|${bestW}x$bestH|${System.currentTimeMillis()}"
        return true
    }

    private fun runSu(pkg: String): String? = try {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", SCRIPT.format(pkg)))
        val text = proc.inputStream.bufferedReader().use { it.readText() }
        val err = proc.errorStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        if (text.isBlank()) {
            android.util.Log.w(TAG, "su produced nothing: ${err.take(120)}")
            null
        } else {
            text
        }
    } catch (t: Throwable) {
        android.util.Log.w(TAG, "su failed: $t")
        null
    }
}
