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

    /**
     * 测试注入的取件码（同 [packageCode] 的编码格式），空 = 没有。
     * 走 PackageCodeTestReceiver 的广播写，用来在没有真短信的机器上验 Dock / 面板显示。
     */
    var packageCodeTest: String
        get() = sp.getString(KEY_PKG_CODE_TEST, "") ?: ""
        set(v) = sp.edit().putString(KEY_PKG_CODE_TEST, v).apply()

    /** 注入值的有效截止时间；过期后 current() 把它清掉，恢复成真实扫描 */
    var packageCodeTestUntil: Long
        get() = sp.getLong(KEY_PKG_CODE_TEST_UNTIL, 0L)
        set(v) = sp.edit().putLong(KEY_PKG_CODE_TEST_UNTIL, v).apply()

    /** Dock 最大宽度（dp），0 = 不限；内容超过它时进度条先收缩 */
    var dockMaxDp: Int
        get() = sp.getInt(KEY_DOCK_MAX_DP, DEFAULT_DOCK_MAX_DP)
        set(v) = sp.edit().putInt(KEY_DOCK_MAX_DP, v).apply()

    /**
     * App 侧 root 采集的游戏信息：`pkg|api|宽x高|帧率|时间戳|进程号`，帧率没差分出来就是空段。
     *
     * Hook 侧不从文件读它，而是随 DockConfigProvider 的 query 拿同一份内存值：这台机器上 LSPosed
     * 给模块 App 装了 preferences hook，偏好从不落盘（连 commit 都不生成文件），
     * XSharedPreferences 那条路永远只能读到默认值。
     */
    var gameInfo: String
        get() = sp.getString(KEY_GAME_INFO, "") ?: ""
        set(v) = sp.edit().putString(KEY_GAME_INFO, v).apply()

    /**
     * 前台游戏那颗 tile 换成它实际用的图形 API 图标（默认开）。
     * 关掉就回到原来的手柄图标，检测本身照旧跑（面板还要显示 API 文字）。
     */
    var apiBadge: Boolean
        get() = sp.getBoolean(KEY_API_BADGE, true)
        set(v) = sp.edit().putBoolean(KEY_API_BADGE, v).apply()

    /** 认不出 API 时是否也换成"未知"图标（默认关：拿不准就还是手柄，别画个误导人的徽标） */
    var apiBadgeUnknown: Boolean
        get() = sp.getBoolean(KEY_API_BADGE_UNKNOWN, false)
        set(v) = sp.edit().putBoolean(KEY_API_BADGE_UNKNOWN, v).apply()

    /** 每确认一次图形 API 就记一笔历史，给没在跑的游戏显示"上次检测到的 API"（默认开） */
    var apiHistoryEnabled: Boolean
        get() = sp.getBoolean(KEY_API_HISTORY_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_API_HISTORY_ENABLED, v).apply()

    /** 图形 API 历史：`pkg|api|时间戳` 用 `;` 连接，最新在前，由 GameInfoCollector 维护 */
    var apiHistory: String
        get() = sp.getString(KEY_API_HISTORY, "") ?: ""
        set(v) = sp.edit().putString(KEY_API_HISTORY, v).apply()

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
        private const val KEY_PKG_CODE_TEST = "pkg_code_test"
        private const val KEY_PKG_CODE_TEST_UNTIL = "pkg_code_test_until"
        private const val KEY_NOTIF_CARD = "notif_card"
        private const val KEY_DOCK_MAX_DP = "dock_max_dp"
        private const val KEY_GAME_INFO = "game_info"
        private const val KEY_API_BADGE = "api_badge"
        private const val KEY_API_BADGE_UNKNOWN = "api_badge_unknown"
        private const val KEY_API_HISTORY_ENABLED = "api_history_enabled"
        private const val KEY_API_HISTORY = "api_history"

        /** 默认上限：状态栏里留出足够位置给左边的时钟 / 通知图标 */
        const val DEFAULT_DOCK_MAX_DP = 220
        private const val KEY_NYAN_STYLE = "nyan_style"
        private const val KEY_ALBUM_BG = "album_art_bg"
        private const val KEY_ALBUM_THUMB = "album_thumb"
        private const val KEY_CPU_CHART = "cpu_chart"
    }
}
