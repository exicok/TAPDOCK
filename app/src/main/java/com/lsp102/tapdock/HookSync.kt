package com.lsp102.tapdock

import android.content.Context
import android.content.Intent

/** App 侧 → Hook 侧（SystemUI / 桌面进程）的实时配置同步 */
object HookSync {

    /** 模块自己的包名：Hook 侧要指名道姓地发给它 */
    const val MODULE_PKG = "com.lsp102.tapdock"

    /**
     * 把当前配置通过广播【直接携带】给 Hook 侧：
     * Hook 侧收到后按 extra 立即生效，不依赖 XSharedPreferences 的实时读取。
     */
    fun notify(context: Context, prefs: DockPrefs) {
        for (pkg in listOf(
            "com.android.systemui",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
        )) {
            try {
                val intent = Intent(HookActions.RELOAD)
                    .setPackage(pkg)
                    .putExtra(HookActions.EXTRA_SHOW, prefs.hookEnabled)
                    .putExtra(HookActions.EXTRA_ALIGN, prefs.dockAlign)
                    .putExtra(HookActions.EXTRA_PCOLOR, prefs.progressColor)
                    .putExtra(HookActions.EXTRA_DL, prefs.downloadPackages.joinToString("|"))
                    .putExtra(HookActions.EXTRA_ORDER, prefs.cardOrder.joinToString(","))
                    .putExtra(HookActions.EXTRA_NYAN, prefs.nyanStyle)
                    .putExtra(HookActions.EXTRA_ALBUM, prefs.albumArtBg)
                    .putExtra(HookActions.EXTRA_THUMB, prefs.albumThumb)
                    .putExtra(HookActions.EXTRA_CPU, prefs.cpuChart)
                    .putExtra(HookActions.EXTRA_BADGE, prefs.notifBadge)
                    .putExtra(HookActions.EXTRA_OTP, prefs.showOtp)
                    .putExtra(HookActions.EXTRA_PKG, prefs.packageCodeEnabled)
                    .putExtra(HookActions.EXTRA_NCARD, prefs.notifCard)
                    .putExtra(HookActions.EXTRA_MAXW, prefs.dockMaxDp)
                context.sendBroadcast(intent)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 请 SystemUI 重新枚举系统控制块并把目录写回本 App 的 Provider。
     * 只有 SystemUI 能创建 / 绑定这些磁贴，App 自己查不到内置磁贴的标签。
     */
    fun requestCatalog(context: Context) {
        for (pkg in listOf("com.android.systemui", "com.android.launcher3")) {
            try {
                context.sendBroadcast(
                    Intent(HookActions.SYS_REFRESH).setPackage(pkg)
                )
            } catch (_: Exception) {
            }
        }
    }

    /** 请 SystemUI 回报一次 Dock 当前显示的内容（App 首页顶部预览用） */
    fun queryDockState(context: Context) {
        try {
            context.sendBroadcast(
                Intent(HookActions.DOCK_QUERY).setPackage("com.android.systemui")
            )
        } catch (_: Exception) {
        }
    }

    /**
     * 请模块 App 用 root 采一次当前游戏的图形 API / 分辨率（面板在 SystemUI 进程里，
     * 那边 exec su 不保证放行，所以让已经拿到授权的 App 去做，结果写回 prefs）。
     */
    fun queryGameInfo(context: Context, pkg: String) {
        try {
            context.sendBroadcast(
                Intent(HookActions.GAME_QUERY)
                    .setPackage(MODULE_PKG)
                    .putExtra(HookActions.EXTRA_GAME_PKG, pkg)
            )
        } catch (_: Exception) {
        }
    }

    /** 请模块 App 用 root 读一次 /proc/stat、/proc/swaps 和 kgsl 节点（SystemUI 域读不到） */
    fun querySysStat(context: Context) {
        try {
            context.sendBroadcast(
                Intent(HookActions.SYSSTAT_QUERY).setPackage(MODULE_PKG)
            )
        } catch (_: Exception) {
        }
    }

    /**
     * 把采回来的一帧推给 SystemUI。
     *
     * 不走 prefs：这台 ColorOS 把 App 的 SharedPreferences 重定向到了
     * /data/misc/apexdata/<uuid>/prefs/...，Hook 侧 XSharedPreferences(pkg, name) 找不到文件。
     */
    fun pushSysStat(context: Context, raw: String) {
        try {
            context.sendBroadcast(
                Intent(HookActions.SYSSTAT_STATE)
                    .setPackage("com.android.systemui")
                    .putExtra(HookActions.EXTRA_SYSSTAT, raw)
            )
        } catch (_: Exception) {
        }
    }

    /** 与 Hook 侧常量保持一致（避免 App 进程引用 Xposed API 类） */
    object HookActions {
        const val RELOAD = "com.lsp102.tapdock.HOOK_RELOAD"
        const val ACTIVE = "com.lsp102.tapdock.HOOK_ACTIVE"
        const val SYS_REFRESH = "com.lsp102.tapdock.SYS_REFRESH"

        /** SystemUI → App：请它跑一次 root 采集 */
        const val GAME_QUERY = "com.lsp102.tapdock.GAME_QUERY"
        const val EXTRA_GAME_PKG = "game_pkg"
        const val SYSSTAT_QUERY = "com.lsp102.tapdock.SYSSTAT_QUERY"
        /** App → SystemUI：root 采回来的一帧 CPU / GPU / zram */
        const val SYSSTAT_STATE = "com.lsp102.tapdock.SYSSTAT_STATE"
        const val EXTRA_SYSSTAT = "sysstat"

        /** SystemUI → App：Dock 此刻真实渲染的内容快照 */
        const val DOCK_STATE = "com.lsp102.tapdock.DOCK_STATE"
        /** App → SystemUI：请求回报一次 */
        const val DOCK_QUERY = "com.lsp102.tapdock.DOCK_QUERY"
        const val EXTRA_M_BADGE = "m_badge"
        const val EXTRA_M_OTP = "m_otp"
        const val EXTRA_M_ICONS = "m_icons"
        const val EXTRA_M_PERCENT = "m_percent"
        const val EXTRA_M_NYAN = "m_nyan"
        const val EXTRA_M_COLOR = "m_color"
        const val EXTRA_M_THUMB = "m_thumb"
        const val EXTRA_SHOW = "show"
        const val EXTRA_ALIGN = "align"
        const val EXTRA_PCOLOR = "pcolor"
        const val EXTRA_DL = "dlpkgs"
        const val EXTRA_ORDER = "card_order"
        const val EXTRA_NYAN = "nyan"
        const val EXTRA_ALBUM = "album"
        const val EXTRA_THUMB = "thumb"
        const val EXTRA_CPU = "cpu"
        const val EXTRA_BADGE = "badge"
        const val EXTRA_OTP = "otp"
        const val EXTRA_PKG = "pkgcode"
        const val EXTRA_NCARD = "ncard"
        const val EXTRA_MAXW = "maxw"
    }
}
