package com.lsp102.tapdock.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** LSPosed / Xposed 模块入口 */
class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            // 屏幕顶部的系统栏：把 Dock 注入右上角
            "com.android.systemui" -> {
                FpsMonitor.hook()
                StatusBarHook.hook(lpparam.classLoader)
                FreeformTopHook.hook(lpparam.classLoader)
            }

            // 12L 任务栏（运行在桌面进程）
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            -> {
                FpsMonitor.hook()
                TaskbarHook.hook(lpparam.classLoader)
            }
        }
    }
}
