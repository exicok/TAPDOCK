package com.lsp102.tapdock.xposed

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap
import com.lsp102.tapdock.DockConfigProvider

/**
 * 最近任务页：把「全部清除」那颗按钮拉成和任务卡片一样高（桌面进程）。
 *
 * 桌面上实测（这台 1240×2772）：卡片 `task_view_single` 是 868×1940，「全部清除」`clear_all`
 * 只有 244×132。卡片在任务页里是按 scale 缩小画的，所以对齐用的是屏幕上真正看到的那块高度，
 * 不是控件的 measured 高度。
 *
 * 只在切进 Overview 那一帧之后动手：按钮和卡片都是桌面自己的控件，改完 layout 就交给桌面继续管，
 * 不去拦桌面的重排逻辑。
 *
 * **只对竖屏生效，横屏零参与**：这套"按钮 = 卡片高"的对齐和胶囊钉角都是按竖屏几何调的，
 * 横屏下卡片尺寸完全不同，任何写入都会把布局搅乱（实测：按钮改高会把边框挤得下移而
 * 缩略图不跟、胶囊钉角会跟桌面的横屏排布打架）。横屏下 hook 一律直接返回，
 * 最近任务页 100% 交给桌面原版。
 */
object RecentsLayoutHook {

    /** 卡片和按钮的资源名；找不到卡片时退认类名 TaskView */
    private const val CARD_ID = "task_view_single"
    private const val BUTTON_ID = "clear_all"

    /** 已经调过的按钮 → 当前设置的高度，避免每次状态回调都重排一遍 */
    private val done = WeakHashMap<View, Int>()

    /** 卡片 → 胶囊容器（id/icon）缓存；卡片实例跨会话复用，缓存有效 */
    private val chips = WeakHashMap<View, View>()

    /** 开关状态 + 3 秒缓存：桌面进程低频回调里查 provider，几乎总是直接返回 */
    private var recentsFixEnabled = true
    private var queriedAt = 0L

    /** 从 App 的 DockConfigProvider 读一次开关（读不到就按默认开处理） */
    private fun refreshEnabledIfNeeded(context: android.content.Context) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - queriedAt < 3000L) return
        queriedAt = now
        try {
            context.applicationContext.contentResolver
                .query(DockConfigProvider.CONTENT_URI, null, null, null, null)
                ?.use { c ->
                    if (!c.moveToFirst()) return@use
                    val i = c.getColumnIndex("recentsfix")
                    if (i >= 0) recentsFixEnabled = c.getString(i) == "1"
                }
        } catch (_: Throwable) {
        }
    }

    fun hook(classLoader: ClassLoader) {
        val enter = object : XC_MethodHook() {
            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                if (param.args.getOrNull(0)?.toString() != "Overview") return
                val act = param.thisObject as? Activity ?: return
                refreshEnabledIfNeeded(act)
                if (!recentsFixEnabled) return
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
        // 胶囊钉角（竖屏）：桌面自己定位 id/icon 容器，转场 / 切卡时会跑偏
        // （实测被挤成卡片右侧一条几十像素的竖条）。它是卡片的子 view，
        // 每帧用"卡片左上角 - 胶囊当前左上角"的差量把它钉回卡片左上角，
        // 位移收敛后就是零开销。横屏零参与（见类注释）。
        XposedHelpers.findClassIfExists("com.android.quickstep.views.RecentsView", classLoader)?.let { rv ->
            XposedBridge.hookAllMethods(rv, "updateCurveProperties", object : XC_MethodHook() {
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    val rv = param.thisObject as? ViewGroup ?: return
                    if (!isPortrait(rv)) return
                    refreshEnabledIfNeeded(rv.context)
                    if (!recentsFixEnabled) return
                    pinChips(rv)
                }
            })
            XposedBridge.log("[TapDock] 胶囊钉角已启用")
        }
        XposedBridge.log("[TapDock] 全部清除改高已启用")
    }

    /** 每帧把每张卡片的胶囊钉在卡片左上角（仅竖屏会走到这里） */
    private fun pinChips(recentsView: ViewGroup) {
        try {
            for (i in 0 until recentsView.childCount) {
                val card = recentsView.getChildAt(i) ?: continue
                if (!card.isLaidOut) continue
                val chip = chipOf(card) ?: continue
                if (!chip.isLaidOut || chip.visibility == View.GONE) continue
                val b = IntArray(2)
                val c = IntArray(2)
                chip.getLocationInWindow(b)
                card.getLocationInWindow(c)
                val dx = c[0] - b[0]
                val dy = c[1] - b[1]
                if (dx != 0 || dy != 0) {
                    chip.translationX += dx
                    chip.translationY += dy
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** 卡片里的胶囊容器（id/icon），带缓存；找不到不缓存，下帧再试 */
    private fun chipOf(card: View): View? {
        chips[card]?.let { return it }
        val found = findById(card, "icon") ?: return null
        chips[card] = found
        return found
    }

    /**
     * 竖屏才动手。Resources 的 configuration 在这台 ROM 上不跟转屏走
     * （HeadsetCard.isLandscape 踩过同一坑），所以再看 decorView 所在 display 的真实旋转角。
     */
    private fun isPortrait(root: View): Boolean {
        if (root.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) return false
        val rotation = try {
            root.display?.rotation
        } catch (_: Throwable) {
            null
        }
        return rotation == null || rotation == android.view.Surface.ROTATION_0
    }

    private fun applyHeight(root: View) {
        // 横屏零参与：改高不做、还原也不做，横屏的最近任务页完全交给桌面
        if (!isPortrait(root)) return
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
