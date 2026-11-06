package com.lsp102.tapdock

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.service.quicksettings.TileService

/**
 * 第三方 TileService 目录：App 自己就能枚举（清单里带 QUERY_ALL_PACKAGES），不用等 SystemUI 回报。
 * spec 用打平组件名 —— SystemUI 的 CustomTileParser 就是按 `unflattenFromString(spec)` 找服务的，
 * 两边必须一致，否则勾选了也点不亮。
 */
object SysTilesLocal {

    private const val BIND_TILE = "android.permission.BIND_QUICK_SETTINGS_TILE"

    fun customEntries(pm: PackageManager): List<SysEntry> = try {
        pm.queryIntentServices(Intent(TileService.ACTION_QS_TILE), 0)
            .orEmpty()
            .mapNotNull { info ->
                val si = info.serviceInfo ?: return@mapNotNull null
                // 只收系统认账的磁贴服务，普通 service 挂了这个 action 也起不来
                if (si.permission != BIND_TILE) return@mapNotNull null
                val spec = ComponentName(si.packageName, si.name).flattenToShortString()
                SysEntry(spec, info.loadLabel(pm).toString(), SysCatalog.KIND_CUSTOM)
            }
            .distinctBy { it.spec }
            .sortedBy { it.label.lowercase() }
    } catch (_: Throwable) {
        emptyList()
    }
}
