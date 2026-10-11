package com.lsp102.tapdock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 面板的小窗卡片要画面时由 SystemUI 广播唤醒。
 *
 * 一次 root 截屏加裁剪编码几百毫秒，跑完就退（[goAsync]），不为此常驻服务 —— 同 GameQueryReceiver。
 */
class WindowShotQueryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val crops = intent.getStringExtra(HookSync.HookActions.EXTRA_SHOT_CROPS) ?: return
        val pending = goAsync()
        Thread {
            try {
                WindowShotCollector.collect(context.applicationContext, crops)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
