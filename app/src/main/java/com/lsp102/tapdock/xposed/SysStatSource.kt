package com.lsp102.tapdock.xposed

import android.content.Context
import com.lsp102.tapdock.HookSync

/**
 * 状态区三张图（CPU 全核心 / GPU / zram）的取数入口。
 *
 * SystemUI 在这台机上是 platform_app 域，`/proc/stat`、`/proc/swaps`、kgsl 节点三个全 EACCES，
 * 所以每秒请模块 App 用 root 读一帧（见 SysStatCollector），这边只读回 pref 里那一帧并做差分。
 *
 * 内核只给累计 ticks，两帧相减才是这一段的比例；拿不到新帧就整行收起，不画 0% 骗人。
 */
object SysStatSource {

    /** zram 用量与容量，单位 kB */
    class Stat(val usedKb: Long, val totalKb: Long) {
        val percent: Float get() = if (totalKb <= 0L) 0f else (usedKb * 100f) / totalKb
    }

    /** 一帧：每核 0..100（顺序 = cpu0..cpuN）、GPU%、zram；空数组 / null = 这项没有 */
    class Frame(val cores: FloatArray, val gpu: Int?, val zram: Stat?)

    private val EMPTY = Frame(FloatArray(0), null, null)

    /** 超过这个时间没新帧就是采集端不工作了（没授权 / App 被杀） */
    private const val STALE_MS = 6_000L
    private const val ASK_MS = 1_000L

    private var askedAt = 0L
    private var lastRaw = ""
    private var cached = EMPTY
    private var prevBusy: LongArray? = null
    private var prevTotal: LongArray? = null

    /** 采集端广播回来的一帧（prefs 在这台 ROM 上跨不过进程） */
    private val pushed = java.util.concurrent.atomic.AtomicReference("")

    fun ingest(raw: String) {
        pushed.set(raw)
    }

    /** 面板每秒 tick 调；内部按 ASK_MS 节流发采集请求 */
    fun poll(context: Context): Frame {
        val now = System.currentTimeMillis()
        if (now - askedAt >= ASK_MS) {
            askedAt = now
            HookSync.querySysStat(context)
        }
        val raw = pushed.get()
        val at = raw.substringBefore('|').toLongOrNull() ?: return EMPTY
        if (now - at > STALE_MS) return EMPTY
        if (raw != lastRaw) {
            lastRaw = raw
            cached = decode(raw.substringAfter('|'))
        }
        return cached
    }

    /** 重新打开面板时丢掉基准，免得把面板关着的那段时间平均进来 */
    fun reset() {
        prevBusy = null
        prevTotal = null
        lastRaw = ""
        cached = EMPTY
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

    private fun decode(body: String): Frame {
        val parts = body.split('|')
        val busy = ArrayList<Long>(16)
        val total = ArrayList<Long>(16)
        parts.getOrNull(0)?.split(';')?.forEach { cell ->
            val v = cell.split(',').mapNotNull { it.toLongOrNull() }
            if (v.size == 2) {
                busy.add(v[0])
                total.add(v[1])
            }
        }
        val prevB = prevBusy
        val prevT = prevTotal
        prevBusy = busy.toLongArray()
        prevTotal = total.toLongArray()
        // 核心数变了（热插拔）旧索引对不上，重开一段
        val cores = if (prevB == null || prevT == null || prevB.size != busy.size) {
            FloatArray(0)
        } else {
            FloatArray(busy.size) { i ->
                val dTotal = total[i] - prevT[i]
                if (dTotal <= 0L) 0f else ((busy[i] - prevB[i]) * 100f) / dTotal
            }
        }
        val zram = parts.getOrNull(1)?.split(',')?.let {
            if (it.size == 2) {
                val u = it[0].toLongOrNull()
                val t = it[1].toLongOrNull()
                if (u != null && t != null) Stat(u, t) else null
            } else null
        }
        return Frame(cores, parts.getOrNull(2)?.toIntOrNull(), zram)
    }
}
