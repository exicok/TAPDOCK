package com.lsp102.tapdock.xposed

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import de.robv.android.xposed.XposedBridge

/**
 * 面板状态区那一行"游戏 · 图形API · 渲染分辨率"。
 *
 * 真正的采集在模块 App 里跑（那边有 root，见 GameInfoCollector）：这颗 ROM 没有 gamesdk 服务，
 * `dumpsys game` 也不带图形字段，只能读 /proc/&lt;pid&gt;/maps 和 gfxinfo 的 buffer 表。
 * 这边只做三件事：认出前台是不是游戏 → 每几秒请 App 采一次 → 读回来看新不新。
 * 超过 STALE_MS 没更新就整行收起，不拿旧画面冒充当前状态。
 */
object GameInfoSource {

    class Info(val label: String, val api: String, val resolution: String)

    private const val STALE_MS = 15_000L
    private const val ASK_MS = 5_000L
    private const val FOREGROUND_WINDOW_MS = 60_000L

    private var askedAt = 0L

    /** 面板每秒 tick 调；内部按 ASK_MS 节流，并且只在认出游戏时才发广播 */
    fun refresh(context: Context) {
        val now = System.currentTimeMillis()
        if (now - askedAt < ASK_MS) return
        val pkg = foregroundGame(context) ?: return
        askedAt = now
        com.lsp102.tapdock.HookSync.queryGameInfo(context, pkg)
    }

    fun peek(context: Context): Info? {
        val raw = HookDock.gameInfoRaw()
        if (raw.isBlank()) return null
        val parts = raw.split('|')
        if (parts.size != 4) return null
        val pkg = parts[0]
        val at = parts[3].toLongOrNull() ?: return null
        if (System.currentTimeMillis() - at > STALE_MS) return null
        val label = try {
            val ai = context.packageManager.getApplicationInfo(pkg, 0)
            // 前台已经换掉或者根本不是游戏：这条记录就不该显示
            if (ai.category != ApplicationInfo.CATEGORY_GAME) return null
            context.packageManager.getApplicationLabel(ai).toString()
        } catch (_: Throwable) {
            return null
        }
        return Info(label, parts[1], parts[2])
    }

    /** 最近一次走到前台的包；不是游戏就 null（SystemUI 有 PACKAGE_USAGE_STATS） */
    private fun foregroundGame(context: Context): String? = try {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - FOREGROUND_WINDOW_MS, now)
        val last = HashMap<String, Long>()
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                last[ev.packageName] = ev.timeStamp
            }
        }
        val pkg = last.maxByOrNull { it.value }?.key ?: return null
        val ai = context.packageManager.getApplicationInfo(pkg, 0)
        if (ai.category == ApplicationInfo.CATEGORY_GAME) pkg else null
    } catch (t: Throwable) {
        XposedBridge.log("[TapDock] foreground game lookup failed: $t")
        null
    }
}
