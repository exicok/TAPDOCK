package com.lsp102.tapdock.xposed

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 通知栏背景色开关：
 *
 * 下拉 shade 的底色由 ScrimController 的两层 ScrimView 画出来
 * （behind = 面板底色，notifications = 通知区底色），颜色和透明度每帧经
 * updateScrimColor 下发。开关关掉后，在解锁 shade 状态下把这两层的透明度强制成 0，
 * 只留下窗口级模糊和壁纸；锁屏 / bouncer / AOD 等其他 ScrimState 不受影响。
 */
object ShadeBackgroundHook {

    private const val SCRIM_CONTROLLER = "com.android.systemui.statusbar.phone.ScrimController"

    /** true = 保留系统背景色（默认）；false = 只留模糊与壁纸 */
    @Volatile
    private var keepBackground = true

    /** 每个配置值只打一次应用日志，方便确认真的拦截到了 */
    @Volatile
    private var appliedLogged = false

    fun hook(classLoader: ClassLoader) {
        refresh()
        val cls = XposedHelpers.findClassIfExists(SCRIM_CONTROLLER, classLoader) ?: run {
            XposedBridge.log("[TapDock] ScrimController not found in this SystemUI")
            return
        }
        XposedBridge.hookAllMethods(cls, "updateScrimColor", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (keepBackground) return
                val target = param.args.getOrNull(0) as? View ?: return
                val controller = param.thisObject
                val state = XposedHelpers.getObjectField(controller, "mState")?.toString() ?: return
                if (state != "UNLOCKED" && state != "SHADE_LOCKED") return
                val behind = XposedHelpers.getObjectField(controller, "mScrimBehind") as? View
                val notifications =
                    XposedHelpers.getObjectField(controller, "mNotificationsScrim") as? View
                if (target === behind || target === notifications) {
                    param.args[2] = 0f
                    if (!appliedLogged) {
                        appliedLogged = true
                        XposedBridge.log("[TapDock] shade background color disabled")
                    }
                }
            }
        })
        XposedBridge.log("[TapDock] shade background hook installed")
    }

    /** App 侧配置变了（广播 / Provider 轮询）由 HookDock 调过来刷新缓存 */
    fun refresh() {
        val value = HookDock.shadeBackground()
        if (value != keepBackground) {
            keepBackground = value
            appliedLogged = false
        }
    }
}
