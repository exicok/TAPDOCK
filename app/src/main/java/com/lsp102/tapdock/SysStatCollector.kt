package com.lsp102.tapdock

import android.content.Context

/**
 * 状态区三张图（CPU 全核心 / GPU / zram）用 root 采一次，结果广播回 SystemUI。
 *
 * 为什么在 App 侧跑：SystemUI 在这台机上是 platform_app 域，`/proc/stat`、`/proc/swaps`、
 * kgsl 节点三个全 EACCES（面板日志里能看到），只有 root 读得到。和 GameInfoCollector 同一条路子。
 * 回推走广播而不是 prefs：这台 ROM 把 App 的 SharedPreferences 重定向进了 apexdata 目录。
 *
 * 差分不在这里做：内核只给累计 ticks，本文件按核输出 `busy total`，两帧相减由 Hook 侧算。
 */
object SysStatCollector {

    private const val TAG = "TapDockSysStat"

    private const val SCRIPT =
        "grep -E '^cpu[0-9]+' /proc/stat 2>/dev/null; " +
            "grep zram /proc/swaps 2>/dev/null; " +
            "cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null"

    /** 写进 pref 的一帧：`时间戳|busy,total;busy,total…|zram已用,zram容量|GPU%`，缺的字段留空 */
    fun collect(context: Context): Boolean {
        val out = runSu() ?: return false
        val cores = ArrayList<String>(16)
        var zram = ""
        var gpu = ""
        out.lineSequence().forEach { line ->
            val cols = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            when {
                coreId(cols.firstOrNull() ?: "") && cols.size >= 5 -> {
                    val v = cols.drop(1).map { it.toLongOrNull() ?: 0L }
                    // 第 4、5 列是 idle 和 iowait，其余都算 busy；缺列的老内核按 idle 处理
                    val idle = v[3] + (if (v.size > 4) v[4] else 0L)
                    var busy = 0L
                    v.indices.forEach { if (it != 3 && it != 4) busy += v[it] }
                    cores.add("$busy,${busy + idle}")
                }
                // /proc/swaps 数据行：Filename Type Size Used Priority，单位 kB
                cols.size >= 5 && cols[0].contains("zram") ->
                    zram = "${cols[3]},${cols[2]}"
                cols.isNotEmpty() && cols[0].all { it.isDigit() } && gpu.isEmpty() ->
                    gpu = cols[0]
            }
        }
        if (cores.isEmpty() && zram.isEmpty() && gpu.isEmpty()) return false
        HookSync.pushSysStat(
            context,
            "${System.currentTimeMillis()}|${cores.joinToString(";")}|$zram|$gpu"
        )
        return true
    }

    /** 只有 cpuN 行算一个核；聚合行 "cpu ..." 的 cpu 后面没有数字 */
    private fun coreId(row: String): Boolean =
        row.length > 3 && row.startsWith("cpu") && row.drop(3).all { it.isDigit() }

    private fun runSu(): String? = try {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", SCRIPT))
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
