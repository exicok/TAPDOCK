package com.lsp102.tapdock.xposed

import android.content.Context
import de.robv.android.xposed.XposedBridge
import java.io.DataOutputStream

/**
 * 游戏性能模式：把 CPU / GPU 调频策略切成几档预设，由侧边栏一键切换。
 *
 * 设计成**尽力而为**：调频节点在 ROM / 芯片之间并不统一，能写哪条写哪条，
 * 写不动的（节点不存在 / 权限不足）跳过并记日志，绝不让一次失败打断整批。
 *
 * 生效靠 root：性能调节要写 `/sys/devices/system/cpu/...` 和 kgsl 节点，
 * SystemUI 域写不动，所以走和 [SysStatCollector] 同一条 `su`。
 */
object PerformanceMode {

    /** 一档性能预设 */
    enum class Level(val id: Int, val label: String, val desc: String) {
        SAVE(0, "省电", "限频降温，续航优先"),
        BALANCED(1, "均衡", "默认调度，日常游戏"),
        PERF(2, "性能", "最高主频，帧率优先"),
        EXTREME(3, "极速", "性能模式常驻，发热最大"),
    }

    private val LEVELS = Level.values()

    fun fromId(id: Int): Level = LEVELS.firstOrNull { it.id == id } ?: Level.BALANCED

    fun all(): List<Level> = LEVELS.toList()

    private const val TAG = "[TapDock] perf"

    /**
     * 应用一档性能模式。
     *
     * @return true = 至少成功写入一条节点；false = 整批都没写进去（多半是没 root / 节点全不在）
     */
    fun apply(ctx: Context, level: Level): Boolean {
        val cpuGov = when (level) {
            Level.SAVE -> "powersave"
            Level.BALANCED -> "schedutil"
            Level.PERF, Level.EXTREME -> "performance"
        }
        val gpuGov = when (level) {
            Level.SAVE -> "powersave"
            Level.BALANCED -> "simple_ondemand"
            Level.PERF, Level.EXTREME -> "performance"
        }
        val script = buildScript(cpuGov, gpuGov)
        return runSu(script)
    }

    /**
     * 拼 su 脚本。每条节点独立 `2>/dev/null`，缺哪条跳哪条；
     * 前面加 `set +e`，某条失败不影响后面的。
     */
    private fun buildScript(cpuGov: String, gpuGov: String): String = buildString {
        appendLine("set +e")
        // CPU：先把调频策略切过去，再让所有在线核跟随 policy0（同簇共用一套 governor）
        appendLine("for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do")
        appendLine("  echo $cpuGov > \"\$g\" 2>/dev/null")
        appendLine("done")
        appendLine("if [ -f /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor ]; then")
        appendLine("  echo $cpuGov > /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null")
        appendLine("fi")
        // 高通大小核还有独立的 policy 节点
        appendLine("for g in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor; do")
        appendLine("  echo $cpuGov > \"\$g\" 2>/dev/null")
        appendLine("done")
        // GPU 调频策略（高通 kgsl）
        appendLine("echo $gpuGov > /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null")
    }

    private fun runSu(script: String): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec("su")
            val out = DataOutputStream(proc.outputStream)
            out.writeBytes(script)
            out.writeBytes("exit\n")
            out.flush()
            val ok = proc.waitFor() == 0
            if (!ok) XposedBridge.log("$TAG su exited non-zero")
            ok
        } catch (t: Throwable) {
            XposedBridge.log("$TAG apply failed: $t")
            false
        }
    }
}
