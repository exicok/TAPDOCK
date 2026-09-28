package com.lsp102.tapdock

import android.content.Context
import android.content.Intent

/** App 侧 → Hook 侧（SystemUI / 桌面进程）的实时配置同步 */
object HookSync {

    /**
     * 把当前配置通过广播【直接携带】给 Hook 侧：
     * Hook 侧收到后按 extra 立即生效，不依赖 XSharedPreferences 的实时读取。
     */
    fun notify(
        context: Context,
        show: Boolean,
        align: String,
        progressColor: Int,
        downloadPkgs: List<String>,
        cardOrder: List<String>,
        nyan: Boolean,
        album: Boolean,
        thumb: Boolean,
        cpu: Boolean,
    ) {
        for (pkg in listOf(
            "com.android.systemui",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
        )) {
            try {
                val intent = Intent(HookActions.RELOAD)
                    .setPackage(pkg)
                    .putExtra(HookActions.EXTRA_SHOW, show)
                    .putExtra(HookActions.EXTRA_ALIGN, align)
                    .putExtra(HookActions.EXTRA_PCOLOR, progressColor)
                    .putExtra(HookActions.EXTRA_DL, downloadPkgs.joinToString("|"))
                    .putExtra(HookActions.EXTRA_ORDER, cardOrder.joinToString(","))
                    .putExtra(HookActions.EXTRA_NYAN, nyan)
                    .putExtra(HookActions.EXTRA_ALBUM, album)
                    .putExtra(HookActions.EXTRA_THUMB, thumb)
                    .putExtra(HookActions.EXTRA_CPU, cpu)
                context.sendBroadcast(intent)
            } catch (_: Exception) {
            }
        }
    }

    /** 与 Hook 侧常量保持一致（避免 App 进程引用 Xposed API 类） */
    object HookActions {
        const val RELOAD = "com.lsp102.tapdock.HOOK_RELOAD"
        const val ACTIVE = "com.lsp102.tapdock.HOOK_ACTIVE"
        const val EXTRA_SHOW = "show"
        const val EXTRA_ALIGN = "align"
        const val EXTRA_PCOLOR = "pcolor"
        const val EXTRA_DL = "dlpkgs"
        const val EXTRA_ORDER = "card_order"
        const val EXTRA_NYAN = "nyan"
        const val EXTRA_ALBUM = "album"
        const val EXTRA_THUMB = "thumb"
        const val EXTRA_CPU = "cpu"
    }
}
