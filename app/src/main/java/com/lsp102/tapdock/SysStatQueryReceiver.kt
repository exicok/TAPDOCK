package com.lsp102.tapdock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 面板每秒要 CPU / GPU / zram 时由 SystemUI 广播唤醒。
 *
 * 和 GameQueryReceiver 一样走 goAsync：一次 su 一两百毫秒，跑完就退，不为此常驻服务。
 */
class SysStatQueryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                SysStatCollector.collect(context.applicationContext)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
