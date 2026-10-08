package com.lsp102.tapdock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 面板要游戏信息时由 SystemUI 广播唤醒。
 *
 * 采集在 goAsync 里跑：一次 su 几百毫秒，跑完就退，不为这个功能常驻一个服务。
 */
class GameQueryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(HookSync.HookActions.EXTRA_GAME_PKG) ?: return
        val pending = goAsync()
        Thread {
            try {
                GameInfoCollector.collect(context.applicationContext, pkg)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
