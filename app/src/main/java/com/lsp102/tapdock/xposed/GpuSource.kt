package com.lsp102.tapdock.xposed

import de.robv.android.xposed.XposedBridge
import java.io.File

/**
 * GPU 占用：Adreno 驱动把百分比直接写在 `/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage`（形如 "3 %"），
 * 取前导整数即可，不用像 CPU 那样自己两段差分。SM8475 上这一个节点就够，别的芯片不在支持范围内。
 */
object GpuSource {

    private const val NODE = "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"
    private var readFailed = false

    /** 0..100；读不到返回 null，调用方整行收起而不是画 0% 骗人 */
    fun percent(): Int? {
        if (readFailed) return null
        val line = try {
            File(NODE).bufferedReader().use { it.readLine() }
        } catch (t: Throwable) {
            readFailed = true
            XposedBridge.log("[TapDock] gpu load node unreadable: $t")
            null
        } ?: return null
        val digits = line.takeWhile { it.isDigit() }
        return if (digits.isEmpty()) null else digits.toInt().coerceAtMost(100)
    }

    /** 节点读不了就别再占着那一行 */
    fun available(): Boolean = !readFailed
}
