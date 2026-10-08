package com.lsp102.tapdock

import android.content.Context
import android.content.SharedPreferences

/**
 * TapDock 基础版配置：Hook 侧（SystemUI / 桌面进程）通过 LSPosed 的
 * New XSharedPreferences 机制读取同一个文件（dock_prefs）。
 */
class DockPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Hook 总开关：关闭后 Hook 侧立即隐藏 Dock */
    var hookEnabled: Boolean
        get() = sp.getBoolean(KEY_HOOK_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_HOOK_ENABLED, v).apply()

    /** Dock 在任务栏中的位置：靠左 / 靠右 */
    var dockAlign: String
        get() = sp.getString(KEY_DOCK_ALIGN, ALIGN_RIGHT) ?: ALIGN_RIGHT
        set(v) = sp.edit().putString(KEY_DOCK_ALIGN, v).apply()

    /** 进度条填充颜色 */
    var progressColor: Int
        get() = sp.getInt(KEY_PROGRESS_COLOR, DEFAULT_PROGRESS_COLOR)
        set(v) = sp.edit().putInt(KEY_PROGRESS_COLOR, v).apply()

    /** 下载进度只跟踪这些应用的下载通知（空 = 全部应用） */
    var downloadPackages: List<String>
        get() {
            val raw = sp.getString(KEY_DOWNLOAD_PACKAGES, "") ?: ""
            return raw.split('|').filter { it.isNotBlank() }
        }
        set(v) = sp.edit().putString(KEY_DOWNLOAD_PACKAGES, v.joinToString("|")).apply()

    /** 控制中心卡片顺序（卡片 id 列表，空 = 用默认顺序） */
    var cardOrder: List<String>
        get() = (sp.getString(KEY_CARD_ORDER, "") ?: "").split(',').filter { it.isNotBlank() }
        set(v) = sp.edit().putString(KEY_CARD_ORDER, v.joinToString(",")).apply()

    /** 播放音乐时 Dock 背景是否显示模糊后的专辑封面 */
    var albumArtBg: Boolean
        get() = sp.getBoolean(KEY_ALBUM_BG, false)
        set(v) = sp.edit().putBoolean(KEY_ALBUM_BG, v).apply()

    /** 进度条左侧显示封面小图 */
    var albumThumb: Boolean
        get() = sp.getBoolean(KEY_ALBUM_THUMB, true)
        set(v) = sp.edit().putBoolean(KEY_ALBUM_THUMB, v).apply()

    /** 控制中心状态区是否显示 CPU 全核心占用图（默认关） */
    var cpuChart: Boolean
        get() = sp.getBoolean(KEY_CPU_CHART, false)
        set(v) = sp.edit().putBoolean(KEY_CPU_CHART, v).apply()

    /** Dock 左侧的通知数量徽标（默认开） */
    var notifBadge: Boolean
        get() = sp.getBoolean(KEY_NOTIF_BADGE, true)
        set(v) = sp.edit().putBoolean(KEY_NOTIF_BADGE, v).apply()

    /** 收到短信验证码时短暂显示在 Dock 上（默认开） */
    var showOtp: Boolean
        get() = sp.getBoolean(KEY_SHOW_OTP, true)
        set(v) = sp.edit().putBoolean(KEY_SHOW_OTP, v).apply()

    /** 未读短信里有快递取件码时，Dock 亮一颗取件图标（默认开） */
    var packageCodeEnabled: Boolean
        get() = sp.getBoolean(KEY_PKG_CODE_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_PKG_CODE_ENABLED, v).apply()

    /**
     * 通知横幅由 Dock 接管（默认开）：开 = 关掉系统原生 heads-up，
     * 改在 Dock 下方滑出一张卡片；关 = 把全局设置恢复成 1，交还给系统。
     */
    var notifCard: Boolean
        get() = sp.getBoolean(KEY_NOTIF_CARD, true)
        set(v) = sp.edit().putBoolean(KEY_NOTIF_CARD, v).apply()

    /** 最近一次扫描得到的取件码：`id|code|发件|日期|摘要`，空 = 没有 */
    var packageCode: String
        get() = sp.getString(KEY_PKG_CODE, "") ?: ""
        set(v) = sp.edit().putString(KEY_PKG_CODE, v).apply()

    /** 面板上点过"已取"的短信 _id，用 | 分隔，不再重复提示 */
    var packageCodeDismissed: String
        get() = sp.getString(KEY_PKG_CODE_BLOCKED, "") ?: ""
        set(v) = sp.edit().putString(KEY_PKG_CODE_BLOCKED, v).apply()

    /** Dock 最大宽度（dp），0 = 不限；内容超过它时进度条先收缩 */
    var dockMaxDp: Int
        get() = sp.getInt(KEY_DOCK_MAX_DP, DEFAULT_DOCK_MAX_DP)
        set(v) = sp.edit().putInt(KEY_DOCK_MAX_DP, v).apply()

    /** App 侧 root 采集的游戏信息：`pkg|api|宽x高|时间戳`，空 = 没有 */
    var gameInfo: String
        get() = sp.getString(KEY_GAME_INFO, "") ?: ""
        set(v) = sp.edit().putString(KEY_GAME_INFO, v).apply()

    /** SystemUI 枚举回来的系统控制块目录（spec<TAB>label<TAB>kind，逐行） */
    var sysCatalog: List<SysEntry>
        get() = SysCatalog.decode(sp.getString(KEY_SYS_CATALOG, "") ?: "")
        set(v) = sp.edit().putString(KEY_SYS_CATALOG, SysCatalog.encode(v)).apply()

    /** 进度条彩虹猫风格：开 = nyan 彩虹带流动 + 猫，关 = 单色进度条 */
    var nyanStyle: Boolean
        get() = sp.getBoolean(KEY_NYAN_STYLE, false)
        set(v) = sp.edit().putBoolean(KEY_NYAN_STYLE, v).apply()

    companion object {
        const val FILE = "dock_prefs"

        const val ALIGN_LEFT = "left"
        const val ALIGN_RIGHT = "right"

        const val DEFAULT_PROGRESS_COLOR = 0xFFFFFFFF.toInt()

        private const val KEY_HOOK_ENABLED = "hook_enabled"
        private const val KEY_DOCK_ALIGN = "dock_align"
        private const val KEY_PROGRESS_COLOR = "progress_color"
        private const val KEY_DOWNLOAD_PACKAGES = "download_packages"
        private const val KEY_CARD_ORDER = "card_order"
        private const val KEY_NOTIF_BADGE = "notif_badge"
        private const val KEY_SYS_CATALOG = "sys_catalog"
        private const val KEY_SHOW_OTP = "show_otp"
        private const val KEY_PKG_CODE_ENABLED = "pkg_code_enabled"
        private const val KEY_PKG_CODE = "pkg_code"
        private const val KEY_PKG_CODE_BLOCKED = "pkg_code_blocked"
        private const val KEY_NOTIF_CARD = "notif_card"
        private const val KEY_DOCK_MAX_DP = "dock_max_dp"
        private const val KEY_GAME_INFO = "game_info"

        /** 默认上限：状态栏里留出足够位置给左边的时钟 / 通知图标 */
        const val DEFAULT_DOCK_MAX_DP = 220
        private const val KEY_NYAN_STYLE = "nyan_style"
        private const val KEY_ALBUM_BG = "album_art_bg"
        private const val KEY_ALBUM_THUMB = "album_thumb"
        private const val KEY_CPU_CHART = "cpu_chart"
    }
}
