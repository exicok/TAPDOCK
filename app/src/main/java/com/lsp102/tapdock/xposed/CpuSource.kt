package com.lsp102.tapdock.xposed

import de.robv.android.xposed.XposedBridge
import java.io.File

/**
 * 全核心占用：读 `/proc/stat` 的 cpu0..cpuN 累计 ticks，和上一次做差得到这一段的百分比。
 * 内核只会给累计值，所以第一次 `sample()` 只能建基准（返回空数组），调用方要在建面板时先取一次。
 */
object CpuSource {

    private const val PROC = "/proc/stat"

    private var lastBusy: LongArray? = null
    private var lastTotal: LongArray? = null
    private var readFailed = false

    /** 丢掉基准：面板重新打开时调用，免得第一段把关闭期间那段长时间平均进来 */
    fun reset() {
        lastBusy = null
        lastTotal = null
    }

    /** /proc/stat 读不了（被 SELinux 挡 / 内核没导出每核行）时让调用方收起整行 */
    fun available(): Boolean = !readFailed

    /** 每核 0..100，顺序 = cpu0..cpuN；还没基准或读不到时返回空数组（整块不显示而不是画 0%） */
    fun sample(): FloatArray {
        val rows = readRows() ?: return FloatArray(0)
        val size = rows.first.size
        val prevBusy = lastBusy
        val prevTotal = lastTotal
        lastBusy = rows.first
        lastTotal = rows.second
        // 核心数变了（热插拔）就重开一段，旧索引对不上不能混着差分
        if (prevBusy == null || prevTotal == null || prevBusy.size != size) return FloatArray(0)
        return FloatArray(size) { i ->
            val dTotal = rows.second[i] - prevTotal[i]
            if (dTotal <= 0L) 0f else ((rows.first[i] - prevBusy[i]) * 100f) / dTotal
        }
    }

    /** 返回每核的 (累计 busy ticks, 累计 busy+idle ticks)，与 cpuN 行同序 */
    private fun readRows(): Pair<LongArray, LongArray>? {
        if (readFailed) return null
        return try {
            val busy = ArrayList<Long>(16)
            val total = ArrayList<Long>(16)
            File(PROC).useLines { lines ->
                lines.forEach { line ->
                    // "cpu0  23245 432 7832 1523322 4231 0 91 0 0 0"
                    // 聚合行是 "cpu ..."（第 4 个字符不是数字），正好被同一个判断跳过
                    if (line.length < 5 || !line[3].isDigit()) return@forEach
                    val v = line.substring(4).trim().split(' ').filter { it.isNotEmpty() }
                        .map { it.toLongOrNull() ?: 0L }
                    if (v.size < 4) return@forEach
                    val idle = v[3] + (if (v.size > 4) v[4] else 0L)   // idle + iowait
                    var busySum = 0L
                    for (i in v.indices) if (i != 3 && i != 4) busySum += v[i]
                    busy.add(busySum)
                    total.add(busySum + idle)
                }
            }
            if (busy.isEmpty()) {
                readFailed = true
                XposedBridge.log("[TapDock] no per-core lines in $PROC")
                null
            } else {
                busy.toLongArray() to total.toLongArray()
            }
        } catch (t: Throwable) {
            readFailed = true
            XposedBridge.log("[TapDock] $PROC read failed: $t")
            null
        }
    }
}
