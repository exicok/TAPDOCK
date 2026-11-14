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

    /**
     * 系统内存（整机视角，不是某个进程）。单位 kB。
     *
     * [totalKb] 来自 /proc/meminfo 的 MemTotal，[availKb] 是 MemAvailable ——
     * 侧边栏那张大卡片要显示"已用 / 总量"，用 MemAvailable 算已用才和系统设置页对得上
     * （MemFree 会把可回收的 page cache 当成不可用，数字会虚高）。
     */
    class Mem(val totalKb: Long, val availKb: Long) {
        val usedKb: Long get() = (totalKb - availKb).coerceAtLeast(0L)
        val fraction: Float get() = if (totalKb <= 0L) 0f else usedKb.toFloat() / totalKb
    }

    /** GPU 频率（MHz）：当前 / 上限。读不到就是 0 */
    class GpuClock(val curMhz: Int, val maxMhz: Int)

    /** 一帧：每核 0..100（顺序 = cpu0..cpuN）、GPU%、zram、按包名的进程读数；空 / null = 这项没有 */
    class Frame(
        val cores: FloatArray,
        val gpu: Int?,
        val zram: Stat?,
        val apps: Map<String, App> = emptyMap(),
        val mem: Mem? = null,
        val gpuClock: GpuClock? = null,
    ) {
        /** 全核平均占用（0..100）；没有核心数据时为 null，调用方据此显示"--"而不是假的 0% */
        val cpuAvg: Float?
            get() = if (cores.isEmpty()) null else cores.average().toFloat()

        /** 最高的那一核，用来在副标题里点出"最忙的核" */
        val cpuPeak: Float?
            get() = if (cores.isEmpty()) null else cores.max()
    }

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

    /** 落盘 / 读盘的宿主上下文（SystemUI application context） */
    private var appCtx: Context? = null

    /**
     * 采集端广播回来的一帧。
     *
     * 顺手落盘一份：SystemUI 进程被重启（模块生效、系统更新、低内存杀进程）后，
     * 侧边栏再次打开时先用磁盘上这一帧把数值填出来，不必从 "--" 重新等 su 往返。
     * 写的是 SystemUI 自己的私有目录，不涉及跨进程共享（这台 ROM 上 App 的 prefs 不落盘）。
     */
    fun ingest(context: Context?, raw: String) {
        if (raw.isEmpty()) return
        // 采集帧可能先于任何一次 poll 到达，这里补一次上下文，落盘才不会因为
        // "还没人调用过 poll" 而静默跳过
        if (appCtx == null && context != null) appCtx = context.applicationContext
        pushed.set(raw)
        persist(raw)
    }

    /** 落盘用的轻量存储：只存原始串，解码逻辑始终只有一份 */
    private const val STORE_FILE = "tapdock_sysstat.txt"

    /**
     * 两次落盘的最小间隔。这只是"最后一帧"的缓存，不必每秒都写：
     * ingest 跑在 SystemUI 主线程的广播回调里，真正写盘挪去了后台线程，
     * 这里的节流再把写盘频率压到 10 秒一次 —— 断电 / 被杀顶多丢最近 10 秒的缓存帧。
     */
    private const val PERSIST_MIN_MS = 10_000L

    /** 落盘走后台线程，别占广播回调所在的主线程 */
    private val persistExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "TapDockSysStat").apply { isDaemon = true }
    }

    @Volatile
    private var lastPersistAt = 0L

    /** 探测成功的落盘文件（null = 还没探过）；失败不缓存，下次再试 */
    @Volatile
    private var storeFileCache: java.io.File? = null

    /**
     * 落盘位置：优先用 SystemUI 自己的 files 目录；这台 ColorOS 上那个目录不存在
     * （prefs 被重定向进了 apexdata），建不出来就退到 cache 目录。
     * 目标是"SystemUI 进程重启后还能读到"，cache 满足这一点；被系统清掉也只是丢一次缓存。
     */
    private fun storeFile(context: Context): java.io.File? {
        storeFileCache?.let { return it }
        val ctx = context.applicationContext
        val dirs = listOfNotNull(ctx.filesDir, ctx.cacheDir)
        for (dir in dirs) {
            try {
                if (!dir.exists()) dir.mkdirs()
                if (dir.isDirectory && dir.canWrite()) {
                    val f = java.io.File(dir, STORE_FILE)
                    storeFileCache = f
                    return f
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /** 落盘结果只记一次，避免刷屏 */
    private var persistLogged = false

    private fun persist(raw: String) {
        val ctx = appCtx ?: return
        persistExecutor.execute {
            try {
                val now = System.currentTimeMillis()
                if (now - lastPersistAt < PERSIST_MIN_MS) return@execute
                val f = storeFile(ctx) ?: run {
                    if (!persistLogged) {
                        persistLogged = true
                        de.robv.android.xposed.XposedBridge.log(
                            "[TapDock] sysstat persist: no writable dir (filesDir/cacheDir)"
                        )
                    }
                    return@execute
                }
                f.writeText(raw)
                lastPersistAt = now
                if (!persistLogged) {
                    persistLogged = true
                    de.robv.android.xposed.XposedBridge.log(
                        "[TapDock] sysstat persist ok: ${f.absolutePath} (${raw.length}B)"
                    )
                }
            } catch (t: Throwable) {
                if (!persistLogged) {
                    persistLogged = true
                    de.robv.android.xposed.XposedBridge.log("[TapDock] sysstat persist failed: $t")
                }
            }
        }
    }

    /**
     * 启动时把上次落盘的一帧读回来。
     *
     * 只做"填数"，不做差分：CPU 的百分比需要两帧相减，所以 [Frame.cores] 这一格会是空的，
     * 直到新的两帧到达；GPU / 内存这类瞬时值可以直接显示。
     * 返回读到的旧帧（没有就是 null），由调用方决定怎么用。
     */
    fun restore(context: Context): Frame? {
        appCtx = context.applicationContext
        val raw = try {
            storeFile(context)?.takeIf { it.isFile }?.readText()?.trim().orEmpty()
        } catch (_: Throwable) {
            ""
        }
        if (raw.isEmpty()) return null
        val at = raw.substringBefore('|').toLongOrNull() ?: return null
        lastAt = at
        val f = decode(raw.substringAfter('|'))
        // 差分基准不能跟着旧帧带进来：旧帧可能是几小时前采的，不清的话
        // 第一条真实帧会把整个停机期平均进去，冒出一个假读数。
        // 清掉让下一帧重新建基准，cores 多空一拍是正确行为。
        prevBusy = null
        prevTotal = null
        prevAppTicks = emptyMap()
        // 差分基准此时还不存在，cores 一定是空的；把瞬时值留下就够了
        cached = f
        lastRaw = raw
        fresh = false
        return f
    }

    /** 面板每秒 tick 调；内部按 ASK_MS 节流发采集请求。[apps] 是这一帧想顺带按进程读的包名 */
    /**
     * 每秒取一次当前帧。
     *
     * 取数失败或超时**不**清空 [cached]：侧边栏 / 面板关掉再打开、或者 su 偶发失败时，
     * 先把上一次读到的数值继续显示出来，等新帧到了再就地覆盖。
     * 只有 [reset] 会主动丢掉基准，供"不想把中间那段平均进来"的场景显式调用。
     *
     * [fresh] 标记这一帧是不是刚采到的：调用方据此决定要不要在标题上显示"等待采集"，
     * 但数值本身始终给最后一次已知的。
     */
    fun poll(context: Context, apps: List<String> = emptyList()): Frame {
        if (appCtx == null) appCtx = context.applicationContext
        val now = System.currentTimeMillis()
        val cadence = if (warm) WARM_MS else ASK_MS
        if (now - askedAt >= cadence) {
            askedAt = now
            HookSync.querySysStat(context, apps)
        }
        val raw = pushed.get()
        if (raw.isEmpty()) return cached
        val at = raw.substringBefore('|').toLongOrNull() ?: return cached
        if (raw != lastRaw) {
            lastRaw = raw
            cached = decode(raw.substringAfter('|'))
            lastAt = at
        }
        // 超过 STALE_MS 只标记"数据不新鲜"，仍然返回上一次读到的内容
        fresh = now - lastAt <= STALE_MS
        return cached
    }

    /** 上一帧的时间戳（ms），0 = 还没采到过 */
    @Volatile
    var lastAt = 0L
        private set

    /** 这一帧是否还在保鲜期内；false 表示数值是上一次的，界面上可以标注 */
    @Volatile
    var fresh = false
        private set

    /** 重新打开面板时丢掉基准，免得把面板关着的那段时间平均进来 */
    fun reset() {
        prevBusy = null
        prevTotal = null
        prevAppTicks = emptyMap()
        lastRaw = ""
        cached = EMPTY
        warm = true
        fresh = false
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
        return Frame(
            cores = cores,
            gpu = parts.getOrNull(2)?.toIntOrNull(),
            zram = zram,
            apps = apps(parts.getOrNull(3), prevT, nextT),
            mem = mem(parts.getOrNull(4)),
            gpuClock = gpuClock(parts.getOrNull(5)),
        )
    }

    /** 第 6 段 `总量,可用`（kB）→ 系统内存；两段缺一就不给，免得拿 0 算出一个 100% */
    private fun mem(body: String?): Mem? {
        if (body.isNullOrEmpty()) return null
        val v = body.split(',')
        if (v.size < 2) return null
        val total = v[0].toLongOrNull() ?: return null
        val avail = v[1].toLongOrNull() ?: return null
        if (total <= 0L) return null
        return Mem(total, avail)
    }

    /** 第 7 段 `当前MHz,上限MHz` → GPU 频率；全 0 就当读不到 */
    private fun gpuClock(body: String?): GpuClock? {
        if (body.isNullOrEmpty()) return null
        val v = body.split(',')
        val cur = v.getOrNull(0)?.toIntOrNull() ?: 0
        val max = v.getOrNull(1)?.toIntOrNull() ?: 0
        return if (cur <= 0 && max <= 0) null else GpuClock(cur, max)
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
