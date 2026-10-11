package com.lsp102.tapdock.xposed

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import de.robv.android.xposed.XposedBridge

/**
 * 面板状态区那一行"游戏 · 图形API · 渲染分辨率 · 帧率 · 刷新率 · 前后台"，
 * 顺带决定 Dock 上那颗游戏图标亮不亮。
 *
 * 真正的采集在模块 App 里跑（那边有 root，见 GameInfoCollector）：这颗 ROM 没有 gamesdk 服务，
 * `dumpsys game` 也不带图形字段，只能读 /proc/&lt;pid&gt;/maps 和 gfxinfo 的 buffer 表。
 * 这边只做四件事：认出前台是不是游戏 → 每几秒请 App 采一次 → 读回来看新不新 → 把结论缓存下来。
 * 超过 STALE_MS 没更新就整行收起，不拿旧画面冒充当前状态。
 *
 * 缓存（[dockLive] / [background]）是给 Dock 每 3 秒那一拍用的：statusStates 只读这两个布尔，
 * 不在绘制路径上查 UsageStats、也不reload 偏好文件。
 */
object GameInfoSource {

    class Info(val label: String, val api: String, val resolution: String, val fps: String)

    /** 记录超过这个时间没更新就整行收起；要盖得住面板关着时 IDLE_MS 那一档的采集间隔 */
    private const val STALE_MS = 25_000L

    /** 面板开着时的采集间隔（帧率那格要接近实时） */
    private const val ASK_MS = 5_000L

    /** 面板关着时的间隔：Dock 那颗图标只分亮/灭，没必要在游戏里每五秒跑一次 dumpsys gfxinfo */
    private const val IDLE_MS = 10_000L

    /** 平时只看这一分钟内的前后台切换：事件条数少，五秒一拍也便宜 */
    private const val FOREGROUND_WINDOW_MS = 60_000L

    /** 刚重启 SystemUI、什么都还没认出来时兜一次底：游戏可能已经连着跑了几小时 */
    private const val COLD_WINDOW_MS = 6L * 3_600_000L

    private var askedAt = 0L

    /**
     * 上一拍认出来的前台游戏。
     *
     * UsageEvents 只在 Activity 切换时打点：游戏连着跑十分钟就一条新事件都没有，
     * 只按"最近一分钟里有切换"判定的话，一问就 null，采集停摆，记录过期后整行消失 —— 面板
     * 第一次打开有信息、过一会儿再打开什么都没有，就是这个。所以没有新切换时沿用这个值。
     */
    private var stickyGame: String? = null

    /** 还没落地识别过前台 = 第一次用 [COLD_WINDOW_MS] 扫 */
    private var cold = true

    /** 面板是否开着，决定采集间隔（见 [panelOpen]） */
    private var isOpen = false

    /** Dock 图标：前台是游戏、且元数据还新鲜 */
    @Volatile
    var dockLive = false
        private set

    /** 记录还没过期但人已经不在前台（刚切走 / 被游戏内设置页盖住） */
    @Volatile
    var background = false
        private set

    /** Dock 的 tick 和面板的每秒 tick 都调；内部整体按节流问 */
    fun refresh(context: Context) {
        val now = System.currentTimeMillis()
        if (now - askedAt < if (isOpen) ASK_MS else IDLE_MS) return
        askedAt = now
        val pkg = foregroundGame(context)
        val info = peek(context)
        background = pkg == null && info != null
        dockLive = pkg != null && info != null
        if (pkg != null) com.lsp102.tapdock.HookSync.queryGameInfo(context, pkg)
    }

    /**
     * 面板开/关时调：开着要 5 秒一拍的准实时数，关着降到 10 秒只维持图标，
     * 顺带丢掉节流 —— 打开面板这一问立刻发出去，别卡在上一拍的窗口里。
     */
    fun panelOpen(open: Boolean) {
        isOpen = open
        askedAt = 0L
    }

    fun peek(context: Context): Info? {
        val raw = HookDock.gameInfoRaw()
        if (raw.isBlank()) return null
        val parts = raw.split('|')
        if (parts.size != 5) return null
        val pkg = parts[0]
        val at = parts[4].toLongOrNull() ?: return null
        if (System.currentTimeMillis() - at > STALE_MS) return null
        val label = try {
            val ai = context.packageManager.getApplicationInfo(pkg, 0)
            // 和游戏判定同一条判据（这条记录本来就该只在是游戏时出现）
            if (!isGame(context, pkg)) return null
            context.packageManager.getApplicationLabel(ai).toString()
        } catch (_: Throwable) {
            return null
        }
        return Info(label, parts[1], parts[2], parts[3])
    }

    /** 结论变了才写日志：这一路几秒一拍，逐拍打会把日志刷满 */
    private var lastVerdict = ""

    private fun verdictChanged(pkg: String, game: Boolean): Boolean {
        val v = "$pkg=${game}"
        val changed = v != lastVerdict
        lastVerdict = v
        return changed
    }

    /** 一个包只查一次清单：判据要读 ApplicationInfo 和启动 Activity 的方向，别每拍都查 */
    private val gameCache = HashMap<String, Boolean>()

    /**
     * 是不是"该给它采游戏信息"的前台应用。
     *
     * 清单里的 `android.intent.category.GAME` 几乎没人写 —— 实测终末地的
     * ApplicationInfo.category 是 0（未定义），照它判就永远认不出游戏，面板那一行一次都没出现过。
     * 换个不依赖清单的说法：启动 Activity 把方向锁在横屏 = 当它是游戏（手游基本都这么声明）。
     * FULL_SENSOR / FULL_USER 那类不算：相机、视频 App 也全在里面，误判的代价是白跑 root 采集。
     * 清单里真声明了 GAME 分类的照旧直接认。
     */
    private fun isGame(context: Context, pkg: String): Boolean {
        gameCache[pkg]?.let { return it }
        val ai = try {
            context.packageManager.getApplicationInfo(pkg, 0)
        } catch (_: Throwable) {
            null
        }
        val landscape = launchOrientation(context, pkg) in LANDSCAPE_LOCKS
        val verdict = ai != null &&
            (ai.category == ApplicationInfo.CATEGORY_GAME || landscape)
        gameCache[pkg] = verdict
        return verdict
    }

    /** 锁横屏的四种声明；不含允许竖屏的 FULL_* 和 PORTRAIT_* */
    private val LANDSCAPE_LOCKS = setOf(
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
    )

    /** 启动 Activity 声明的方向；查不到回 UNSPECIFIED（= 这条判据不成立） */
    private fun launchOrientation(context: Context, pkg: String): Int = try {
        val cn = context.packageManager.getLaunchIntentForPackage(pkg)?.component
            ?: return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        context.packageManager.getActivityInfo(cn, 0).screenOrientation
    } catch (_: Throwable) {
        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    /**
     * 前台是不是游戏。
     *
     * 走 UsageStatsManager（SystemUI 有 PACKAGE_USAGE_STATS）：取窗口里最晚一条 RESUMED、
     * 并且它之后没有被 PAUSED/STOPPED 盖掉的那个包当前台。窗口里一条切换都没有就说明前台没动，
     * 沿用 [stickyGame]；屏幕熄了就没有"前台"可言，直接作废沿用值，免得为看不见的游戏每五秒跑一次 su。
     */
    private fun foregroundGame(context: Context): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        return try {
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(
                now - if (cold) COLD_WINDOW_MS else FOREGROUND_WINDOW_MS, now
            )
            val resumed = HashMap<String, Long>()
            val stopped = HashMap<String, Long>()
            val ev = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                // 宿主自己不算"前台换了人"：从控制中心点进任何系统页面都会给 SystemUI 打一条
                // RESUMED，照原样认就把沿用的游戏冲没了 —— 而游戏没被暂停，不会再有新事件补回来。
                val pkg = ev.packageName
                if (pkg == null || isOwn(pkg)) continue
                when (ev.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> resumed[pkg] = ev.timeStamp
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                        stopped[pkg] = ev.timeStamp
                }
            }
            val pkg = resumed.filter { (name, at) -> at >= (stopped[name] ?: 0L) }
                .maxByOrNull { it.value }?.key
            if (pkg == null) {
                // 熄屏要把沿用值一起清掉，所以这一步放在"没有切换就沿用"之前
                if (!screenOn(context)) {
                    if (stickyGame != null) {
                        stickyGame = null
                        XposedBridge.log("[TapDock] foreground: 熄屏，停止采集游戏信息")
                    }
                    null
                } else {
                    stickyGame
                }
            } else {
                cold = false
                val isGame = isGame(context, pkg)
                stickyGame = if (isGame) pkg else null
                if (verdictChanged(pkg, isGame)) {
                    XposedBridge.log("[TapDock] foreground: $pkg → ${if (isGame) "游戏" else "非游戏"}")
                }
                stickyGame
            }
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] foreground game lookup failed: $t")
            null
        }
    }

    /** 模块自己和宿主 SystemUI：这两颗包名的 Activity 打点不代表用户离开了游戏 */
    private fun isOwn(pkg: String): Boolean =
        pkg == HookDock.MODULE_PKG || pkg == "com.android.systemui"

    /** 屏幕亮着才有"前台应用"这件事；取不到当亮着处理，别因为反射失败就不采集 */
    @Suppress("DEPRECATION")
    private fun screenOn(context: Context): Boolean = try {
        context.getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: true
    } catch (_: Throwable) {
        true
    }
}
