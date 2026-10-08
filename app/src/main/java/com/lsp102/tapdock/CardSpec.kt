package com.lsp102.tapdock

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 控制中心卡片的元数据：App 里的排序界面和 SystemUI 里的面板共用这一份，
 * 标签 / 图标 / 长按跳转目标只写一次。枚举声明顺序 = 默认排序。
 */
enum class CardSpec(
    val id: String,
    val label: String,
    val iconRes: Int,
    /** 长按要打开的设置页候选，按顺序试到第一个能拉起来的 */
    val settingsActions: List<String>,
) {
    BT("bt", "蓝牙", R.drawable.ic_tile_bt, listOf(Settings.ACTION_BLUETOOTH_SETTINGS)),
    WIFI("wifi", "WLAN", R.drawable.ic_tile_wifi, listOf(Settings.ACTION_WIFI_SETTINGS)),
    // ACTION_TETHER_SETTINGS 在公开 SDK 里是 @hide，用它的字面量
    HOTSPOT("ap", "热点", R.drawable.ic_tile_hotspot, listOf("android.settings.TETHER_SETTINGS")),
    DATA(
        "data", "移动数据", R.drawable.ic_tile_data,
        listOf("android.settings.DATA_USAGE_SETTINGS", Settings.ACTION_NETWORK_OPERATOR_SETTINGS)
    ),
    LOCATION("loc", "定位", R.drawable.ic_tile_location, listOf(Settings.ACTION_LOCATION_SOURCE_SETTINGS)),
    TORCH("torch", "手电筒", R.drawable.ic_tile_torch, listOf(Settings.ACTION_DISPLAY_SETTINGS)),
    CAMERA("cam", "摄像头", R.drawable.ic_tile_camera, listOf(Settings.ACTION_PRIVACY_SETTINGS)),
    MIC("mic", "麦克风", R.drawable.ic_tile_mic, listOf(Settings.ACTION_PRIVACY_SETTINGS)),
    ;

    /**
     * 长按卡片打开该开关的设置页（对齐系统 QS 的长按行为）。
     * 不去 resolveActivity：模块 App 没有 QUERY_ALL_PACKAGES，包可见性过滤会让解析结果不全，
     * 直接试拉、失败换下一个候选更可靠。
     */
    fun openSettings(context: Context): Boolean {
        for (action in settingsActions) {
            try {
                context.startActivity(
                    Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    companion object {
        private val byId = values().associateBy { it.id }

        /** 按 App 里存的顺序排；顺序表没列到的（比如新版本新加的卡片）按默认顺序排在后面 */
        fun ordered(order: List<String>): List<CardSpec> {
            val rest = LinkedHashMap(byId)
            return order.mapNotNull { rest.remove(it) } + rest.values
        }
    }
}

/** 排序界面上一张卡片格子的显示数据 */
data class CardInfo(val id: String, val label: String, val iconRes: Int)

/**
 * 把 `card_order`（可能混着原生 id 和 `sys:<spec>`）解析成显示用的格子列表。
 * 系统磁贴的标签来自 SystemUI 回报的目录，目录里查不到就用 spec 顶上——卡片仍然会出现在面板里，
 * 只是这一刻还没有中文名可读。
 */
object Cards {

    fun resolve(order: List<String>, catalog: List<SysEntry>): List<CardInfo> {
        val sysBySpec = catalog.associateBy { it.spec }
        val rest = CardSpec.values().associateBy({ it.id }, { it }).toMutableMap()
        val out = ArrayList<CardInfo>(order.size)
        for (id in order) {
            if (CardIds.isSys(id)) {
                val spec = CardIds.specOf(id)
                out.add(
                    CardInfo(id, sysBySpec[spec]?.label ?: spec, R.drawable.ic_tile_sys)
                )
            } else {
                val native = rest.remove(id) ?: continue
                out.add(CardInfo(native.id, native.label, native.iconRes))
            }
        }
        // 原生卡片一条都不能丢：顺序表没列到的按声明顺序补在后面
        rest.values.forEach { out.add(CardInfo(it.id, it.label, it.iconRes)) }
        return out
    }
}
