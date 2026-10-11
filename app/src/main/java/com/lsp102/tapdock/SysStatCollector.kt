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
 * 小窗口卡片要的按进程 CPU / 内存也搭这一趟车（[apps]），省一次 su 往返。
 */
object SysStatCollector {

    private const val TAG = "TapDockSysStat"

    private const val SCRIPT =
        "grep -E '^cpu[0-9]+' /proc/stat 2>/dev/null; " +
            "grep zram /proc/swaps 2>/dev/null; " +
            "cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null"

    /** 包名要拼进 su 的命令行，只放行正常包名字符，别把 shell 元字符带进去 */
    private val PKG_CHARS = Regex("^[A-Za-z0-9._:-]+$")

    /** 写进 pref 的一帧：`时间戳|busy,total;busy,total…|zram已用,zram容量|GPU%|pkg:ticks,rssKb;…`，缺的字段留空 */
    fun collect(context: Context, apps: List<String> = emptyList()): Boolean {
        val appScript = apps.filter { it.matches(PKG_CHARS) }.joinToString("; ") { appLoop(it) }
        val out = runSu(if (appScript.isEmpty()) SCRIPT else "$SCRIPT; $appScript") ?: return false
        val cores = ArrayList<String>(16)
        var zram = ""
        var gpu = ""
        val cpuByPkg = HashMap<String, Long>()
        val rssByPkg = HashMap<String, Long>()
        var pkg: String? = null
        out.lineSequence().forEach { line ->
            val cols = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            when {
                // 按进程读回的三行：APP 打头切当前包名，后面两行归它
                cols.firstOrNull() == "APP" && cols.size >= 2 -> pkg = cols[1]
                cols.firstOrNull() == "CPU" && pkg != null ->
                    cpuByPkg.merge(pkg!!, cols.getOrNull(1)?.toLongOrNull() ?: 0L, Long::plus)
                cols.firstOrNull() == "RSS" && pkg != null ->
                    rssByPkg.merge(pkg!!, cols.getOrNull(1)?.toLongOrNull() ?: 0L, Long::plus)
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
        val appFrame = (cpuByPkg.keys + rssByPkg.keys).distinct().joinToString(";") { name ->
            "${name}:${cpuByPkg[name] ?: 0L},${rssByPkg[name] ?: 0L}"
        }
        if (cores.isEmpty() && zram.isEmpty() && gpu.isEmpty() && appFrame.isEmpty()) return false
        HookSync.pushSysStat(
            context,
            "${System.currentTimeMillis()}|${cores.joinToString(";")}|$zram|$gpu|$appFrame"
        )
        return true
    }

    /**
     * 一个包名的进程读数：`utime+stime` 累计 ticks 和 VmRSS。
     * /proc/pid/stat 第 2 个字段是带空格也带括号的进程名，所以从最后一个 ')' 之后重新数下标，
     * state 起算的第 12、13 项才是 utime / stime。
     */
    private fun appLoop(pkg: String): String =
        "for p in \$(pidof $pkg 2>/dev/null); do echo \"APP $pkg \$p\"; " +
            "awk '{n=index(\$0,\")\"); if (n>0) {split(substr(\$0,n+2),f,\" \"); print \"CPU\", f[12]+f[13]}}' " +
            "/proc/\$p/stat 2>/dev/null; " +
            "awk '/VmRSS/{print \"RSS\", \$2}' /proc/\$p/status 2>/dev/null; done"

    /** 只有 cpuN 行算一个核；聚合行 "cpu ..." 的 cpu 后面没有数字 */
    private fun coreId(row: String): Boolean =
        row.length > 3 && row.startsWith("cpu") && row.drop(3).all { it.isDigit() }

    private fun runSu(script: String): String? = try {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
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
