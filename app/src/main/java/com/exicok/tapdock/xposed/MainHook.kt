package com.lsp102.tapdock.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** LSPosed / Xposed 模块入口 */
class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            // 屏幕顶部的系统栏：把 Dock 注入右上角
            "com.android.systemui" -> {
                StatusBarHook.hook(lpparam.classLoader)
                ShadeBlurHook.hook(lpparam.classLoader)
                ShadeBackgroundHook.hook(lpparam.classLoader)
                FreeformTopHook.hook(lpparam.classLoader)
                FreeformCaptionHook.hook(lpparam.classLoader)
            }

            // 12L 任务栏（运行在桌面进程）
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            -> {
                TaskbarHook.hook(lpparam.classLoader)
                RecentsLayoutHook.hook(lpparam.classLoader)
            }

            // system_server（LSPosed 作用域里的"Android 系统"）：全应用强制竖屏的名单闸门
            "android" -> ForcePortraitHook.hook(lpparam.classLoader)
        }
    }
}
