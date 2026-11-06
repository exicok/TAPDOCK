package com.lsp102.tapdock.xposed

import android.os.SystemClock
import android.view.Choreographer
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicLong

/**
 * 当前页面帧率：数宿主进程（桌面 = 主页 / 抽屉 / 最近任务，SystemUI = 状态栏与面板）
 * 每一轮 `Choreographer#doFrame`。
 *
 * 静止画面不排帧，所以读数会是 0 —— 这里要的是"这一秒真画了多少帧"，不是屏幕刷新率；
 * 想要刷新率应该读 `Display.getRefreshRate()`，那是另一个数。
 */
object FpsMonitor {

    private val frames = AtomicLong(0)
    private var windowStart = 0L
    private var lastFps = 0
    private var hooked = false

    /** doFrame 是私有方法，Xposed 按方法名挂即可；一个进程只挂一次 */
    fun hook() {
        if (hooked) return
        try {
            XposedBridge.hookAllMethods(
                Choreographer::class.java, "doFrame",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        frames.incrementAndGet()
                    }
                }
            )
            hooked = true
            XposedBridge.log("[TapDock] fps counter attached")
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] fps counter hook failed: $t")
        }
    }

    /**
     * 面板打开时重开窗口：丢掉关着期间攒下的帧数和上一拍的计时。
     * 不重置的话第一拍报的是"整段关闭时间的平均帧率"，和后面每秒那一拍根本不是一个数。
     */
    fun openWindow() {
        frames.set(0)
        windowStart = SystemClock.elapsedRealtime()
    }

    /**
     * 取一次瞬时帧率：距上次的帧数按实际间隔折算成每秒。
     * 只在面板开着时被节拍调用，窗口不是精确 1000ms，所以必须用真实间隔。
     */
    fun sample(): Int {
        val now = SystemClock.elapsedRealtime()
        val drawn = frames.getAndSet(0)
        val start = windowStart
        windowStart = now
        if (start == 0L) return 0
        val dt = now - start
        if (dt <= 0L) return lastFps
        lastFps = ((drawn * 1000L) / dt).toInt()
        return lastFps
    }
}
