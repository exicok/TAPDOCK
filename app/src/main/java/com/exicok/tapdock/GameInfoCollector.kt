package com.lsp102.tapdock

import android.content.Context

/**
 * 用 root 采当前前台游戏的图形 API、渲染分辨率和帧率，结果写进 DockPrefs.gameInfo，
 * 面板每秒从 XSharedPreferences 读。
 *
 * 为什么在 App 侧跑而不是 Hook 侧：这个 App 的 root 授权已经存在（重启 SystemUI 那颗按钮就是走 su），
 * 而 SystemUI 是 platform_app 域，exec su 不保证放行，也不该在人家进程里反复起特权进程。
 *
 * 三个数各自的来路：
 *  - 分辨率：`dumpsys gfxinfo <pkg>` 的 GraphicBuffer 表，行形状 `154 ( 192) x 154`（宽 (stride) x 高），
 *    取面积最大的一行 —— 那就是游戏自己提交的 swapchain 尺寸，不是屏幕尺寸。
 *  - 图形 API：认的是进程**实际映射了哪门子图形库**，不是游戏叫什么、也不是它是不是 Unity 打的包：
 *    Vulkan 那条认厂商驱动本体（`/vendor/lib64/hw/vulkan.adreno.so` 这一类），不是 `libvulkan.so`：
 *    后者是 `/system` 里的加载桩，桌面和 SystemUI 都挂着它，照它判等于所有游戏都算有 VK。
 *    GLES 那侧同理不可信 —— HWUI 自己必挂 `libGLESv2*`（实测桌面进程 7 行），所以两边同时命中不代表游戏在用 GL。
 *    ANGLE 认路径里带 `angle` 的库本体（`libEGL_angle.so` / `libGLESv2_angle.so` 这一类）：
 *    ANGLE 是把 GLES 翻译成 Vulkan 跑，所以它**一定**也会映射厂商 vulkan 驱动 —— 判 API 时 ANGLE 排在驱动前面，
 *    不然所有跑 ANGLE 的游戏都会被误报成原生 Vulkan。
 *    结论只给一个：ANGLE > 厂商 Vulkan 驱动 = VULKAN > 只有 swiftshader = VK(软件) > 只有 GLES 桩 = GLES > 未知。
 *    实测：终末地映射 vulkan.adreno.so → VULKAN；桌面 / SystemUI 无驱动 → GLES。
 *  - 帧率：`Total frames rendered` 是进程累计提交帧数，本对象留着上一次的读数做差分。
 *    只 dump 一次 gfxinfo，两次读数之间隔多久就除多久；第一次没有参照，那格留空。
 */
object GameInfoCollector {

    private const val TAG = "TapDockGame"

    /**
     * K 行只列厂商 Vulkan 驱动（`vulkan.xxx.so` / `libvulkan_xxx.so` / swiftshader），
     * 加载桩 `libvulkan.so` 匹配不上 —— 它谁都有。A 行只列 ANGLE 本体（路径含 angle 的 .so）。
     * P 行是这一帧读到的进程号：Hook 侧拿它分辨"同一个游戏换了进程"，别把上一局的结论当本局的。
     * `pidof -s` 防止同名多进程把路径拼坏。
     */
    private const val SCRIPT =
        "P=\$(pidof -s %1\$s); [ -z \"\$P\" ] || { " +
            "echo \"P \$P\"; " +
            "echo \"K \$(grep -oE '/[^ ]*(vulkan\\.[a-z0-9_-]+|vk_swiftshader|libvulkan_[a-z0-9_-]+)\\.so' /proc/\$P/maps 2>/dev/null | sort -u | head -2 | tr '\\n' ' ')\"; " +
            "echo \"A \$(grep -ioE '/[^ ]*angle[^ ]*\\.so' /proc/\$P/maps 2>/dev/null | sort -u | head -2 | tr '\\n' ' ')\"; " +
            "echo \"G \$(grep -c libGLESv2 /proc/\$P/maps 2>/dev/null)\"; }; " +
            "D=\$(dumpsys gfxinfo %1\$s 2>/dev/null); " +
            "echo \"T \$(echo \"\$D\" | grep -m1 -oE 'Total frames rendered: *[0-9]+' | grep -oE '[0-9]+')\"; " +
            "echo \"\$D\" | grep -oE '[0-9]{3,5} *\\( *[0-9]+ *\\) *[xX] *[0-9]{3,5}'"

    /** 表里的 buffer 行：宽 (stride) x 高 */
    private val BUFFER = Regex("(\\d{3,5}) *\\( *\\d+ *\\) *[xX] *(\\d{3,5})")

    // 帧率差分的上一拍读数；换游戏、进程重启（计数往回走）都要作废
    private var fpsPkg = ""
    private var fpsFrames = 0L
    private var fpsAt = 0L

    /** 返回 true 表示写入了新值（游戏没跑 / 拿不到就什么都不写） */
    fun collect(context: Context, pkg: String): Boolean {
        val out = runSu(pkg) ?: return false
        var drivers: List<String> = emptyList()
        var angle: List<String> = emptyList()
        var gles = 0
        var bestW = 0
        var bestH = 0
        var bestArea = 0
        var frames = -1L
        var pid = 0
        out.lineSequence().forEach { line ->
            when {
                line.startsWith("K ") -> drivers = line.substring(2).split(' ').filter { it.isNotEmpty() }
                line.startsWith("A ") -> angle = line.substring(2).split(' ').filter { it.isNotEmpty() }
                line.startsWith("G ") -> gles = line.substring(2).trim().toIntOrNull() ?: 0
                line.startsWith("T ") -> frames = line.substring(2).trim().toLongOrNull() ?: -1L
                line.startsWith("P ") -> pid = line.substring(2).trim().toIntOrNull() ?: 0
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
        if (bestW <= 0 || bestH <= 0) {
            android.util.Log.w(TAG, "no GraphicBuffer row for $pkg")
            return false
        }
        val realVk = drivers.any { !it.contains("swiftshader") }
        val api = when {
            angle.isNotEmpty() -> "ANGLE"
            realVk -> "VULKAN"
            drivers.isNotEmpty() -> "VK(软件)"
            gles > 0 -> "GLES"
            else -> "未知"
        }
        val fps = fpsText(pkg, frames)
        DockPrefs(context).let { prefs ->
            prefs.gameInfo =
                "$pkg|$api|${bestW}x$bestH|$fps|${System.currentTimeMillis()}|$pid"
        }
        android.util.Log.i(TAG, "collected $pkg: $api ${bestW}x$bestH fps=$fps frames=$frames")
        return true
    }

    /** 与上一拍做差分；没有可比读数就返回空串，让面板那格留空而不是写 0 */
    private fun fpsText(pkg: String, frames: Long): String {
        val now = System.currentTimeMillis()
        val usable = frames >= 0 && pkg == fpsPkg && frames > fpsFrames && now > fpsAt
        val fps =
            if (usable) (frames - fpsFrames) * 1000.0 / (now - fpsAt) else -1.0
        fpsPkg = pkg
        fpsFrames = frames
        fpsAt = now
        if (fps < 1.0 || fps > 1000.0) return ""
        val tenths = (fps * 10).toLong()
        return "${tenths / 10}.${tenths % 10}"
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
