package com.lsp102.tapdock.xposed

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * 充电进度源：接电时显示电量百分比。
 * （下载进度改由 HookDock 从系统通知监听管线读取下载器通知；音乐进度走 MediaSession API）
 */
object ChargeProgressSource {

    /** 充电进度百分比；未充电返回 null */
    fun snapshot(context: Context): Int? {
        val intent = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Throwable) {
            null
        } ?: return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        if (!charging) return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return (level * 100 / scale).coerceIn(0, 100)
    }
}
