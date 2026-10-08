package com.lsp102.tapdock.xposed

import android.content.ContentValues
import android.content.Context
import com.lsp102.tapdock.DockConfigProvider
import com.lsp102.tapdock.PackageCodeCollector
import de.robv.android.xposed.XposedBridge

/**
 * Dock / 面板用的取件码状态：内容来自模块 App 扫未读短信（见 [PackageCodeCollector]），
 * 由 HookDock 每轮配置轮询从 ContentProvider 读回来灌进这里。
 *
 * SystemUI 侧不读短信库：它没有 READ_SMS，也不该为一个小图标去申请敏感权限。
 * 面板上点"已取"时把短信 _id 写回 Provider，同时本地立刻认这条作废，
 * 免得等下一次轮询又把同一个码挂回来。
 */
object PackageCodeSource {

    /** 最近一次写回后作废的短信 _id */
    @Volatile
    private var dismissedId = -1L

    private val pushed = java.util.concurrent.atomic.AtomicReference("")

    fun ingest(raw: String) {
        if (pushed.get() == raw) return
        pushed.set(raw)
        XposedBridge.log("[TapDock] package code refreshed")
    }

    /** 当前该显示的取件码；没有、或者已经在面板上标过"已取"就是 null */
    fun current(): PackageCodeCollector.Hit? {
        val hit = PackageCodeCollector.decode(pushed.get()) ?: return null
        if (hit.id == dismissedId) return null
        return hit
    }

    /** 面板上的"已取"：本地先认作废，再写回 Provider 让 App 记住 */
    fun markTaken(context: Context, hit: PackageCodeCollector.Hit) {
        dismissedId = hit.id
        pushed.set("")
        try {
            val values = ContentValues().apply {
                put(DockConfigProvider.COLUMN_PKG_DISMISSED, hit.id.toString())
            }
            context.contentResolver.insert(DockConfigProvider.CONTENT_URI, values)
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] pkg dismiss writeback failed: $t")
        }
    }

    /** 开关关掉时一起丢掉缓存，别留一个不会再亮的图标 */
    fun clear() {
        pushed.set("")
        dismissedId = -1L
    }
}
