package com.lsp102.tapdock.xposed

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Constructor

/**
 * 下拉通知栏（shade）背景模糊度：
 *
 * - Android 17 / LOS 24：NotificationShadeDepthController 每帧把模糊半径（px）
 *   写进 WindowRootViewBlurRepository，再统一下发到窗口根 Surface。
 *   这里把算出来的半径按百分比缩放，0% 即关掉。
 * - 旧版本（Android 16 及以前）：DepthController 直接走 BlurUtils.applyBlur，
 *   目标 ViewRootImpl 的根视图是 NotificationShadeWindowView，按同一个百分比缩放。
 *
 * 百分比相对系统上限：100% 保持系统原样，不做任何替换。
 */
object ShadeBlurHook {

    private const val DEPTH_CONTROLLER =
        "com.android.systemui.statusbar.NotificationShadeDepthController"
    private const val BLUR_UTILS = "com.android.systemui.statusbar.BlurUtils"
    private const val SHADE_VIEW_CLASS = "NotificationShadeWindowView"

    /**
     * 当前模糊度百分比。DepthController 按帧调用，不能每次都去读 prefs 文件，
     * 配置变化由 [refresh] 同步过来。
     */
    @Volatile
    private var percent = 100

    /** 每个配置值只打一次应用日志，方便确认缩放真的走到了 */
    @Volatile
    private var appliedLogged = false

    /** 系统 classLoader 里的 kotlin.Pair 构造器：跨 classLoader 直接 new 自己的 Pair 会被 check-cast 拒掉 */
    private var pairCtor: Constructor<*>? = null

    fun hook(classLoader: ClassLoader) {
        refresh()
        hookDepthController(classLoader)
        hookBlurUtils(classLoader)
    }

    /** App 侧配置变了（广播 / Provider 轮询）由 HookDock 调过来刷新缓存 */
    fun refresh() {
        val value = HookDock.shadeBlurPercent()
        if (value != percent) {
            percent = value
            appliedLogged = false
        }
    }

    /**
     * Android 17：computeBlurAndZoomOut() 返回 Pair<模糊半径 px, zoomOut>，
     * scheduleUpdate 把半径写进 blurRequestedByShade，这里替换成缩放后的半径。
     */
    private fun hookDepthController(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(DEPTH_CONTROLLER, classLoader) ?: return
        val pairCls = XposedHelpers.findClassIfExists("kotlin.Pair", classLoader) ?: return
        pairCtor = try {
            pairCls.getConstructor(Any::class.java, Any::class.java)
        } catch (_: Throwable) {
            null
        } ?: return
        XposedBridge.hookAllMethods(cls, "computeBlurAndZoomOut", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (percent >= 100) return
                val result = param.result ?: return
                val radius = XposedHelpers.callMethod(result, "component1") as? Int ?: return
                if (radius <= 0) return
                val zoomOut = XposedHelpers.callMethod(result, "component2") as? Float ?: 0f
                val scaled = scale(radius)
                param.result = try {
                    pairCtor?.newInstance(Integer.valueOf(scaled), java.lang.Float.valueOf(zoomOut))
                } catch (_: Throwable) {
                    null
                } ?: param.result
            }
        })
        XposedBridge.log("[TapDock] shade blur depth hook installed")
    }

    /** Android 16 及以前的回调：只处理根视图就是通知栏 shade 的那次调用 */
    private fun hookBlurUtils(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(BLUR_UTILS, classLoader) ?: return
        XposedBridge.hookAllMethods(cls, "applyBlur", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (percent >= 100) return
                val root = param.args.getOrNull(0) ?: return
                val radius = param.args.getOrNull(1) as? Int ?: return
                if (radius <= 0) return
                val view = try {
                    XposedHelpers.callMethod(root, "getView") as? View
                } catch (_: Throwable) {
                    null
                } ?: return
                if (!isShadeView(view)) return
                param.args[1] = scale(radius)
            }
        })
    }

    private fun scale(radius: Int): Int {
        if (!appliedLogged) {
            appliedLogged = true
            XposedBridge.log("[TapDock] shade blur applied at $percent%")
        }
        return if (percent <= 0) 0 else (radius * percent / 100f).toInt().coerceAtLeast(1)
    }

    private fun isShadeView(view: View): Boolean {
        var cls: Class<*>? = view.javaClass
        while (cls != null) {
            if (cls.name.endsWith(SHADE_VIEW_CLASS)) return true
            cls = cls.superclass
        }
        return false
    }
}
