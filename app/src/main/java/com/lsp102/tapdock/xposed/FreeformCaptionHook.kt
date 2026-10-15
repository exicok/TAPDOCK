package com.lsp102.tapdock.xposed

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference

/**
 * 小窗口（自由窗口）标题栏里把"最小化"那颗按钮藏掉。
 *
 * 标题栏不是 App 自己的视图，而是 SystemUI 里 WM Shell 给每个自由窗口挂的一棵装饰视图
 * （这台 AOSP 16 上是 `WindowDecorLinearLayout`，窗口名 "Caption of Task=N"，
 * 底下排着 back_button / minimize_window / maximize_window / close_window）。
 * 这里拦在视图上屏的那一刻：任何新出现的 View 根都走 `ViewRootImpl#setView`，
 * 认出是装饰视图之后把 minimize 那颗设成 GONE 就行。
 *
 * 认按钮用资源名，并且两个名字都试（`minimize_window` 是当前实现，`minimize_button` 是
 * 更早版本用过的）；比硬写 Shell 内部类名稳，也不会误伤别的窗口上的按钮——
 * 不是装饰视图的根直接放过，找不到按钮就什么都不做。
 */
object FreeformCaptionHook {

    private val ID_NAMES = listOf("minimize_window", "minimize_button")

    /** 已经处理过的按钮：系统每次刷标题栏都会把可见性设回去，靠这份列表重新按下去 */
    private val hidden = arrayListOf<WeakReference<View>>()

    fun hook(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists("android.view.ViewRootImpl", classLoader)
        if (cls == null) {
            XposedBridge.log("[TapDock] 小窗标题栏：没有 ViewRootImpl，未启用")
            return
        }
        XposedBridge.hookAllMethods(cls, "setView", object : XC_MethodHook() {
            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                try {
                    (param.args.getOrNull(0) as? View)?.let { hideMinimize(it) }
                } catch (t: Throwable) {
                    XposedBridge.log("[TapDock] 小窗标题栏处理失败: $t")
                }
            }
        })
        XposedBridge.log("[TapDock] 小窗标题栏：已拦在 View 上屏处")
    }

    private fun hideMinimize(root: View) {
        // 状态栏、通知shade、面板这些根先按类名/资源名挡掉，别去遍历它们的整棵树
        if (!isWindowDecor(root)) return
        val button = ID_NAMES.firstNotNullOfOrNull { findById(root, it) }
        if (button == null) {
            XposedBridge.log("[TapDock] 小窗标题栏: ${root.javaClass.simpleName} 里没找到最小化键")
            return
        }
        hidden.removeAll { it.get() == null }
        hidden.add(WeakReference(button))
        button.visibility = View.GONE
        XposedBridge.log("[TapDock] 小窗标题栏: 已藏掉最小化 (${nameOf(button)})")
    }

    /**
     * 每次任务出现/信息变化时重新压一遍。
     *
     * 标题栏的按钮可见性是 Shell 在 `onTaskInfoChanged` 里刷的（最大化、能不能最小化都会改它），
     * 我们的 hook 挂在同一个方法的 after，所以这一下一定排在系统之后。
     */
    internal fun reapply() {
        if (hidden.isEmpty()) return
        hidden.removeAll { it.get() == null }
        hidden.forEach { it.get()?.visibility = View.GONE }
    }

    /** 装饰视图的两种特征：Shell 那个包里的类，或者根自己就叫 caption */
    private fun isWindowDecor(root: View): Boolean =
        root.javaClass.name.contains("windowdecor", true) || nameOf(root) == "caption"

    private fun findById(v: View, name: String): View? {
        if (nameOf(v) == name) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findById(v.getChildAt(i), name)?.let { return it }
            }
        }
        return null
    }

    private fun nameOf(v: View): String? = try {
        v.resources.getResourceEntryName(v.id)
    } catch (_: Throwable) {
        null
    }
}
