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
        get() = sp.getBoolean(KEY_ALBUM_BG, true)
        set(v) = sp.edit().putBoolean(KEY_ALBUM_BG, v).apply()

    /** 进度条左侧显示封面小图 */
    var albumThumb: Boolean
        get() = sp.getBoolean(KEY_ALBUM_THUMB, true)
        set(v) = sp.edit().putBoolean(KEY_ALBUM_THUMB, v).apply()

    /** 控制中心状态区是否显示 CPU 全核心占用图（默认关） */
    var cpuChart: Boolean
        get() = sp.getBoolean(KEY_CPU_CHART, false)
        set(v) = sp.edit().putBoolean(KEY_CPU_CHART, v).apply()

    /** 进度条彩虹猫风格：开 = nyan 彩虹带流动 + 猫，关 = 单色进度条 */    var nyanStyle: Boolean
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
        private const val KEY_NYAN_STYLE = "nyan_style"
        private const val KEY_ALBUM_BG = "album_art_bg"
        private const val KEY_ALBUM_THUMB = "album_thumb"
        private const val KEY_CPU_CHART = "cpu_chart"
    }
}
