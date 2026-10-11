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

    /** 一个包的进程读数：cpu = 占全部核心的百分比（没有基准帧时为 null），rss = kB */
    class App(val cpu: Int?, val rssKb: Long?)

    /** 一帧：每核 0..100（顺序 = cpu0..cpuN）、GPU%、zram、按包名的进程读数；空 / null = 这项没有 */
    class Frame(
        val cores: FloatArray,
        val gpu: Int?,
        val zram: Stat?,
        val apps: Map<String, App> = emptyMap(),
    )

    private val EMPTY = Frame(FloatArray(0), null, null)

    /** 超过这个时间没新帧就是采集端不工作了（没授权 / App 被杀） */
    private const val STALE_MS = 6_000L
    private const val ASK_MS = 1_000L

    /**
     * 还没有基准帧时的采样间隔。CPU 条要两帧 root 读数相减才有数，按 ASK_MS 问就是
     * "打开面板两秒后条子才出现"；250ms 一问，第一根条子落在半秒内。
     * 一次 su 往返一两百毫秒，所以这个间隔不会把 App 侧问爆。
     */
    private const val WARM_MS = 250L

    private var askedAt = 0L
    private var lastRaw = ""
    private var cached = EMPTY
    private var prevBusy: LongArray? = null
    private var prevTotal: LongArray? = null
    /** 按包名的上一帧累计 ticks，和小窗卡的 CPU 差分对着用 */
    private var prevAppTicks: Map<String, Long> = emptyMap()
    /** 还没有第二帧可做差分 = 处于补基准阶段，用快间隔问 */
    private var warm = true

    /** 采集端广播回来的一帧（prefs 在这台 ROM 上跨不过进程） */
    private val pushed = java.util.concurrent.atomic.AtomicReference("")

    fun ingest(raw: String) {
        pushed.set(raw)
    }

    /** 面板每秒 tick 调；内部按 ASK_MS 节流发采集请求。[apps] 是这一帧想顺带按进程读的包名 */
    fun poll(context: Context, apps: List<String> = emptyList()): Frame {
        val now = System.currentTimeMillis()
        val cadence = if (warm) WARM_MS else ASK_MS
        if (now - askedAt >= cadence) {
            askedAt = now
            HookSync.querySysStat(context, apps)
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
        prevAppTicks = emptyMap()
        lastRaw = ""
        cached = EMPTY
        warm = true
        // 立刻允许下一次问：不等节流窗口，打开面板的第一帧就去采
        askedAt = 0L
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
        val nextB = busy.toLongArray()
        val nextT = total.toLongArray()
        prevBusy = nextB
        prevTotal = nextT
        // 核心数变了（热插拔）旧索引对不上，重开一段
        val cores = if (prevB == null || prevT == null || prevB.size != busy.size) {
            FloatArray(0)
        } else {
            FloatArray(busy.size) { i ->
                val dTotal = nextT[i] - prevT[i]
                if (dTotal <= 0L) 0f else ((nextB[i] - prevB[i]) * 100f) / dTotal
            }
        }
        // 差分出来了就退出补基准，采样间隔回到每秒一问
        if (cores.isNotEmpty()) warm = false
        val zram = parts.getOrNull(1)?.split(',')?.let {
            if (it.size == 2) {
                val u = it[0].toLongOrNull()
                val t = it[1].toLongOrNull()
                if (u != null && t != null) Stat(u, t) else null
            } else null
        }
        return Frame(cores, parts.getOrNull(2)?.toIntOrNull(), zram, apps(parts.getOrNull(3), prevT, nextT))
    }

    /**
     * 第 5 段 `pkg:ticks,rssKb;…` → 按包名的读数。
     *
     * ticks 是进程累计值，和小核那套一样要两帧相减；分母取同一对帧里所有核心的总 ticks
     * 之和，出来的就是"占全部核心的百分比"，和状态区的总 CPU 条能对着看。
     * 没有基准帧的那一帧只给内存，CPU 留 null，不画 0% 骗人。
     */
    private fun apps(body: String?, prevT: LongArray?, nextT: LongArray?): Map<String, App> {
        if (body.isNullOrEmpty()) {
            prevAppTicks = emptyMap()
            return emptyMap()
        }
        // 核心数变了做不了差分，这时进程 CPU 没有可信的分母
        var span = 0L
        if (prevT != null && nextT != null && prevT.size == nextT.size) {
            nextT.indices.forEach { span += nextT[it] - prevT[it] }
        }
        val out = HashMap<String, App>()
        val next = HashMap<String, Long>()
        body.split(';').forEach { cell ->
            val name = cell.substringBefore(':')
            if (name.isEmpty() || name == cell) return@forEach
            val v = cell.substringAfter(':').split(',').mapNotNull { it.toLongOrNull() }
            if (v.isEmpty()) return@forEach
            val ticks = v[0]
            val rss = if (v.size > 1) v[1] else null
            val last = prevAppTicks[name]
            next[name] = ticks
            val cpu = if (last == null || span <= 0L) {
                null
            } else {
                val d = ticks - last
                // 进程重启后计数归零，差分是负数；这种帧当没有
                if (d < 0L) null else (d * 100L / span).toInt().coerceAtMost(100)
            }
            out[name] = App(cpu, rss)
        }
        prevAppTicks = next
        return out
    }
}
