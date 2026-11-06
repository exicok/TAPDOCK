package com.lsp102.tapdock.xposed

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 把 Dock 注入 SystemUI 状态栏（屏幕顶部的任务栏）布局内部：
 * 优先插到 status_bar_contents（LinearLayout）里，靠左=最前、靠右=最后；
 * 找不到 status_bar_contents 时退化为直接加在 PhoneStatusBarView 的 END/START 侧。
 */
object StatusBarHook {

    fun hook(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.phone.PhoneStatusBarView", classLoader
            )
            if (cls == null) {
                XposedBridge.log("[TapDock] PhoneStatusBarView not found in this SystemUI")
                return
            }
            XposedBridge.hookAllMethods(cls, "onFinishInflate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    try {
                        val bar = param.thisObject as? ViewGroup ?: return
                        val res = bar.resources
                        val contentsId =
                            res.getIdentifier("status_bar_contents", "id", bar.context.packageName)
                        val contents =
                            if (contentsId != 0) bar.findViewById<ViewGroup>(contentsId) else null
                        if (contents is LinearLayout) {
                            HookDock.injectIntoLinearLayout(contents, compact = true)
                        } else {
                            HookDock.injectIntoFrameLayout(
                                bar, Gravity.END or Gravity.CENTER_VERTICAL, compact = true
                            )
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log("[TapDock] status bar inject error: $t")
                    }
                }
            })
            XposedBridge.log("[TapDock] status bar hook installed")
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }
}
