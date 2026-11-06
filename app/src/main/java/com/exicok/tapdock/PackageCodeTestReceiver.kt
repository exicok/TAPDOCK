package com.lsp102.tapdock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 取件码显示的手动测试台：没有真短信也能把一条"取件短信"挂到 Dock 和面板上。
 *
 * 为什么不直接往短信库里插一条：写收件箱要 WRITE_SMS，只有默认短信应用能写；
 * 为测试把这台机的默认短信应用换掉，代价远大于收益。所以注入值走模块自己的 pref，
 * 但正文一定过 [PackageCodeCollector] 的真实解析链路 —— 关键词、码型、营销短信反例
 * 全部照判，测出来的就是线上那条路径，不是一条特供假数据。
 *
 * 用法（设备已连 adb）：
 *  `adb shell am broadcast -n com.lsp102.tapdock/.PackageCodeTestReceiver -f 0x20 --es case cainiao`
 *  `... --es body "【丰巢】您有快件待取，取件码 A1B2C3"`
 *  `... --es case off`
 *
 * 指名组件 + `-f 0x20`（INCLUDE_STOPPED_PACKAGES）是必需的：刚装完 / 更新过 APK 时本 App 处于
 * stopped 状态，隐式广播会被系统直接过滤掉（实测收不到）。
 *
 * 注入值 5 分钟后自己过期（[PackageCodeCollector] 里的 TEST_TTL_MS），面板上点"已取"也会清掉它。
 */
class PackageCodeTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val body = intent.getStringExtra(EXTRA_BODY) ?: PRESETS[intent.getStringExtra(EXTRA_CASE) ?: ""]
        if (body == null) {
            Log.w(TAG, "unknown case; try one of ${PRESETS.keys.joinToString("/")}")
            return
        }
        val app = context.applicationContext
        val code = if (body.isBlank()) {
            PackageCodeCollector.clearTest(app)
            null
        } else {
            PackageCodeCollector.setTest(app, body)
        }
        // 立刻推给 Hook 侧：等 Provider 那一拍（3 秒）会让"注入"和"出现"之间看不出因果
        HookSync.pushPackageCode(app, PackageCodeCollector.current(app))
        Log.i(TAG, if (code == null) "cleared / no match: ${body.take(40)}" else "showing $code")
    }

    companion object {

        private const val TAG = "TapDockPkgTest"
        private const val EXTRA_CASE = "case"
        private const val EXTRA_BODY = "body"

        /** 每种码型一条：前两条有"取件码"字样，第三条只有三段码形状，第四条是反例 */
        private val PRESETS = mapOf(
            "cainiao" to "【菜鸟驿站】您的包裹已到站，请凭取件码 8-3-1024 到 12 号货架自取，超时退回。",
            "locker" to "【丰巢】快件已存入 A12 号柜，取件码 8H3K92，24 小时内取出，逾期收费。",
            "triple" to "【兔喜超市】您有快递待取，货架位置 3-1-2048，请携带手机到店自提。",
            "reject" to "【菜鸟】双 11 优惠券已到账，运单号 8-3-1024，退订回T",
            "off" to "",
        )
    }
}
