package com.lsp102.tapdock

import android.content.Intent

/**
 * Hook 侧（SystemUI）回报的「Dock 此刻真实渲染的内容」，App 首页顶部按它画预览。
 *
 * 只信回报，不在 App 里猜：没收到之前预览区给出等待提示，不画假数字。
 * 图标位掩码的顺序与 HookDock 的 iconRes 一致：0 WLAN、1 蓝牙、2 定位、3 VPN、4 摄像头、5 麦克风。
 */
data class DockMirror(
    val badge: Int,
    val otp: String,
    val iconBits: Int,
    val percent: Int,
    val nyan: Boolean,
    val color: Int,
    val thumb: Boolean,
) {
    /** 该显示出来的图标资源，按位取 */
    fun icons(): List<Int> = ICON_RES.indices.filter { (iconBits and (1 shl it)) != 0 }.map { ICON_RES[it] }

    companion object {
        @Volatile
        var last: DockMirror? = null

        val ICON_RES = intArrayOf(
            R.drawable.ic_tile_wifi,
            R.drawable.ic_tile_bt,
            R.drawable.ic_tile_location,
            R.drawable.ic_tile_vpn,
            R.drawable.ic_tile_camera,
            R.drawable.ic_tile_mic,
        )

        private val A = HookSync.HookActions

        fun from(intent: Intent): DockMirror = DockMirror(
            badge = intent.getIntExtra(A.EXTRA_M_BADGE, 0),
            otp = intent.getStringExtra(A.EXTRA_M_OTP) ?: "",
            iconBits = intent.getIntExtra(A.EXTRA_M_ICONS, 0),
            percent = intent.getIntExtra(A.EXTRA_M_PERCENT, -1),
            nyan = intent.getBooleanExtra(A.EXTRA_M_NYAN, false),
            color = intent.getIntExtra(A.EXTRA_M_COLOR, -1),
            thumb = intent.getBooleanExtra(A.EXTRA_M_THUMB, false),
        )
    }
}
