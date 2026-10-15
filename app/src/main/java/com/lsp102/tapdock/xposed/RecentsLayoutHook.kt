package com.lsp102.tapdock.xposed

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap

/**
 * 最近任务页：把「全部清除」那颗按钮拉成和任务卡片一样高（桌面进程）。
 *
 * 桌面上实测（这台 1240×2772）：卡片 `task_view_single` 是 868×1940，「全部清除」`clear_all`
 * 只有 244×132。卡片在任务页里是按 scale 缩小画的，所以对齐用的是屏幕上真正看到的那块高度，
 * 不是控件的 measured 高度。
 *
 * 只在切进 Overview 那一帧之后动手：按钮和卡片都是桌面自己的控件，改完 layout 就交给桌面继续管，
 * 不去拦桌面的重排逻辑。
 */
object RecentsLayoutHook {

    /** 卡片和按钮的资源名；找不到卡片时退认类名 TaskView */
    private const val CARD_ID = "task_view_single"
    private const val BUTTON_ID = "clear_all"

    /** 已经调过的按钮，避免每次状态回调都重排一遍 */
    private val done = WeakHashMap<View, Int>()

    fun hook(classLoader: ClassLoader) {
        val enter = object : XC_MethodHook() {
            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                if (param.args.getOrNull(0)?.toString() != "Overview") return
                val act = param.thisObject as? Activity ?: return
                val root = act.window.decorView
                // 进 Overview 时卡片还在做缩放动画，量早了拿到的是动画中间值
                root.postDelayed({ applyHeight(root) }, 260)
                root.postDelayed({ applyHeight(root) }, 700)
            }
        }
        for (name in listOf(
            "com.android.launcher3.Launcher",
            "com.android.launcher3.uioverrides.QuickstepLauncher",
        )) {
            val cls = XposedHelpers.findClassIfExists(name, classLoader) ?: continue
            for (method in listOf("onStateSetStart", "onStateSetEnd")) {
                XposedBridge.hookAllMethods(cls, method, enter)
            }
        }
        XposedBridge.log("[TapDock] 全部清除改高已启用")
    }

    private fun applyHeight(root: View) {
        val card = findById(root, CARD_ID) ?: findByClass(root, "TaskView") ?: return
        val btn = findById(root, BUTTON_ID) ?: return
        // 卡片这一屏上画出来多高
        val target = (card.height * card.scaleY).toInt()
        if (target <= 0) return
        if (done[btn] == target) return
        done[btn] = target
        // 按钮比它的父容器高，父容器默认会把超出部分裁掉，一路放开到根
        unclip(btn)
        btn.layoutParams.height = target
        btn.requestLayout()
        // 高度一改，父容器给它的落点也会跟着挪，量完再把它顶到卡片上沿
        btn.post { align(btn, card) }
        XposedBridge.log(
            "[TapDock] 全部清除：132 → $target（卡片 ${card.height}×缩放 ${card.scaleY}）"
        )
    }

    /**
     * 把按钮的顶边对到卡片的顶边。
     * getLocationInWindow 里的坐标已经含上了当前的 translationY，所以差值直接加上去就收敛。
     */
    private fun align(btn: View, card: View) {
        val b = IntArray(2)
        val c = IntArray(2)
        btn.getLocationInWindow(b)
        card.getLocationInWindow(c)
        val dy = c[1] - b[1]
        if (kotlin.math.abs(dy) > 1) btn.translationY += dy
    }

    private fun unclip(v: View) {
        var p = v.parent
        while (p is View) {
            if (p is ViewGroup) {
                p.clipChildren = false
                p.clipToPadding = false
            }
            p = p.parent
        }
    }

    private fun findById(v: View, name: String): View? {
        if (entryName(v) == name) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) findById(v.getChildAt(i), name)?.let { return it }
        }
        return null
    }

    private fun findByClass(v: View, simpleName: String): View? {
        if (v.javaClass.simpleName == simpleName) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) findByClass(v.getChildAt(i), simpleName)?.let { return it }
        }
        return null
    }

    private fun entryName(v: View): String? = try {
        v.resources.getResourceEntryName(v.id)
    } catch (_: Throwable) {
        null
    }
}
