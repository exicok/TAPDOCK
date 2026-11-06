package com.lsp102.tapdock.xposed

import android.content.Context
import android.graphics.drawable.Drawable
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference

/**
 * 模块资源加载器：Hook 代码运行在 SystemUI / 桌面进程里，
 * 直接用 R 常量 setImageResource/getString 会到宿主进程的资源表解析（必然失败）。
 * 必须通过 createPackageContext 拿到模块自己的资源上下文再加载。
 */
object ModuleRes {

    @Volatile
    private var moduleCtx: WeakReference<Context>? = null

    fun context(host: Context): Context? {
        moduleCtx?.get()?.let { return it }
        return try {
            val c = host.createPackageContext(
                HookDock.MODULE_PKG, Context.CONTEXT_IGNORE_SECURITY
            )
            moduleCtx = WeakReference(c)
            c
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] createPackageContext failed: $t")
            null
        }
    }

    fun drawable(host: Context, resId: Int): Drawable? = try {
        context(host)?.getDrawable(resId)
    } catch (t: Throwable) {
        XposedBridge.log("[TapDock] module drawable $resId failed: $t")
        null
    }

    fun string(host: Context, resId: Int): String? = try {
        context(host)?.getString(resId)
    } catch (_: Throwable) {
        null
    }
}
