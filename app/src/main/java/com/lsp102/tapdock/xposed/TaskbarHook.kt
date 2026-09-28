package com.lsp102.tapdock.xposed

import android.view.ViewGroup
import android.widget.LinearLayout
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 把 Dock 注入 AOSP 12L+ 任务栏（运行在桌面进程）布局内部。
 * TaskbarView 是横向 LinearLayout：靠左=最前，靠右=最后。
 */
object TaskbarHook {

    fun hook(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClassIfExists(
                "com.android.launcher3.taskbar.TaskbarView", classLoader
            ) ?: return // 设备没有任务栏时静默跳过
            XposedBridge.hookAllMethods(cls, "onFinishInflate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    try {
                        val bar = param.thisObject as? ViewGroup ?: return
                        if (bar !is LinearLayout) return
                        HookDock.injectIntoLinearLayout(bar, compact = false)
                    } catch (t: Throwable) {
                        XposedBridge.log("[TapDock] taskbar inject error: $t")
                    }
                }
            })
            XposedBridge.log("[TapDock] taskbar hook installed")
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }
}
