package com.lsp102.tapdock.xposed

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 侧边栏用的通知列表。
 *
 * HookDock 那边只维护"有几个通知"（一个 key 集合，给 Dock 的徽标用），
 * 侧边栏要的是能读的内容，所以在同一条通知管线上再留一份结构化快照。
 *
 * 只收"用户看得见"的那些：
 *  - 分组汇总条（FLAG_GROUP_SUMMARY）跳过，一个组只列它下面真实的几条
 *  - 常驻通知（FLAG_ONGOING_EVENT）保留，但标记出来，界面上单独区分
 *  - 系统自己那些噪声（正在运行的后台服务、USB 选项等）按包名过滤掉
 *
 * 排序：常驻在后，其余按时间倒序 —— 最新的一条排最前，符合看通知的习惯。
 */
object NotifCenter {

    /** 一条通知的展示快照 */
    class Item(
        val key: String,
        val pkg: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val time: String,
        val icon: Drawable?,
        val ongoing: Boolean,
        /** 通知自带的小图标（没有大图标时用它兜底） */
        val small: Drawable?,
        /** 发布时间，排序用 */
        val postedAt: Long,
    )

    /**
     * 不值得出现在侧边栏里的通知来源。
     *
     * android = 系统自己的"正在通过 USB 充电""已连接为媒体设备"这类；
     * com.android.systemui = 它自己的调试/提示条目。
     */
    private val IGNORED_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
    )

    /** 最多记住多少条：侧边栏是窄条，再多也滚不完，还占内存 */
    private const val MAX_ITEMS = 40

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val items = LinkedHashMap<String, Item>()

    /** 版本号：内容变了就加一，侧边栏据此决定要不要重建列表 */
    @Volatile
    var version = 0
        private set

    /** 当前快照（已排好序），直接可以拿去渲染 */
    fun list(): List<Item> = synchronized(items) {
        items.values.sortedWith(
            compareBy<Item> { it.ongoing }.thenByDescending { it.postedAt }
        )
    }

    fun count(): Int = synchronized(items) { items.size }

    /** 通知到达 / 更新 */
    fun onPosted(context: Context, sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName in IGNORED_PACKAGES) return
        if (isGroupSummary(n)) return
        val item = build(context, sbn, n) ?: return
        synchronized(items) {
            val old = items[item.key]
            // 同 key 的更新通知（音乐进度条、下载进度）往往一秒好几次：
            // 侧边栏可见内容没变就不算变化，version 不动，那边就不会跟着重建整列
            if (old != null && old.sameVisibleContent(item)) return
            items[item.key] = item
            // 超量时先丢最旧的非常驻条目
            while (items.size > MAX_ITEMS) {
                val victim = items.values
                    .filterNot { it.ongoing }
                    .minByOrNull { it.postedAt }
                    ?: items.values.minByOrNull { it.postedAt }
                    ?: break
                items.remove(victim.key)
            }
        }
        version++
    }

    /** 通知被撤 */
    fun onRemoved(key: String) {
        val removed = synchronized(items) { items.remove(key) != null }
        if (removed) version++
    }

    /** 监听器刚接上时灌一次全量，避免只看到增量 */
    fun seed(context: Context, all: Array<StatusBarNotification>) {
        synchronized(items) {
            items.clear()
            for (sbn in all) {
                val n = sbn.notification ?: continue
                if (sbn.packageName in IGNORED_PACKAGES) continue
                if (isGroupSummary(n)) continue
                val item = build(context, sbn, n) ?: continue
                items[item.key] = item
            }
            while (items.size > MAX_ITEMS) {
                val victim = items.values.minByOrNull { it.postedAt } ?: break
                items.remove(victim.key)
            }
        }
        version++
    }

    private fun build(
        context: Context,
        sbn: StatusBarNotification,
        n: Notification,
    ): Item? {
        val extras = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
            ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
            ?: ""
        // 标题和正文都空的没有展示价值（典型的后台服务通知）
        if (title.isEmpty() && text.isEmpty()) return null

        val large = try {
            n.getLargeIcon()?.loadDrawable(context)
        } catch (_: Throwable) {
            null
        }
        val small = try {
            n.smallIcon?.loadDrawable(context)
        } catch (_: Throwable) {
            null
        }
        return Item(
            key = "${sbn.packageName}:${sbn.id}",
            pkg = sbn.packageName,
            appLabel = HookDock.appLabel(context, sbn.packageName),
            title = title.ifEmpty { HookDock.appLabel(context, sbn.packageName) },
            text = text,
            time = timeFmt.format(Date(sbn.postTime)),
            icon = large ?: HookDock.appIcon(context, sbn.packageName),
            small = small,
            ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            postedAt = sbn.postTime,
        )
    }

    private fun isGroupSummary(n: Notification): Boolean =
        (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0

    /**
     * 侧边栏一行能看到的全部内容（应用名、标题、正文、时间、常驻标记）。
     * 图标不参与比较：换图标的通知极少，不值得为它多做一次 Drawable 对比。
     */
    private fun Item.sameVisibleContent(o: Item): Boolean =
        appLabel == o.appLabel && title == o.title && text == o.text &&
            time == o.time && ongoing == o.ongoing

    /** 侧边栏关掉时不必清空：下次打开还要立刻看到上次的通知列表 */
}
