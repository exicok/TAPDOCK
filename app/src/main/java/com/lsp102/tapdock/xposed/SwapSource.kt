package com.lsp102.tapdock.xposed

import de.robv.android.xposed.XposedBridge
import java.io.File

/**
 * zram 那块（内核把 zram 注册成分区型 swap）的占用。来源只有一个，列序固定：
 * `/proc/swaps` 的数据行 `Filename Type Size Used Priority`，Size/Used 单位 kB。
 *
 * 刻意不读 `/sys/block/zram0/mm_stat`：这台机的内核是 5.10-android12 的厂商改版，那行有 8 个字段，
 * 和上游 6 / 7 字段的文档都对不上，字段序只能猜，猜错就是假数据。
 */
object SwapSource {

    /** 用量与容量，单位 kB */
    class Stat(val usedKb: Long, val totalKb: Long) {
        val percent: Float get() = if (totalKb <= 0L) 0f else (usedKb * 100f) / totalKb
    }

    private var swapsFailed = false

    /** 没有 zram 行就返回 null（调用方收起那一行） */
    fun zram(): Stat? {
        if (swapsFailed) return null
        return try {
            var out: Stat? = null
            File("/proc/swaps").useLines { lines ->
                lines.forEach { line ->
                    if (!line.contains("zram")) return@forEach
                    // 表头之外每行 5 列：Filename Type Size Used Priority，Size/Used 单位是 kB
                    val cols = line.trim().split(Regex("\\s+"))
                    if (cols.size < 5) return@forEach
                    val size = cols[2].toLongOrNull() ?: return@forEach
                    val used = cols[3].toLongOrNull() ?: return@forEach
                    out = Stat(used, size)
                }
            }
            out
        } catch (t: Throwable) {
            swapsFailed = true
            XposedBridge.log("[TapDock] zram source failed: $t")
            null
        }
    }

    /** kB → 人类可读，状态区一格只有 ~34dp，最多六个字符 */
    fun fmt(kb: Long): String {
        val m = kb / 1024
        return when {
            m >= 1024 -> "%.1fG".format(kb / 1_048_576f)
            m > 0 -> "${m}M"
            else -> "0M"
        }
    }
}
