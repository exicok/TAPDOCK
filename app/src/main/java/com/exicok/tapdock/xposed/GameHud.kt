package com.lsp102.tapdock.xposed

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XposedBridge
import java.util.Locale

/**
 * 游戏画面的顶部状态条：CPU / GPU / 内存三个读数横排贴在屏幕顶端居中。
 *
 * 只在"前台是游戏"时挂，由 HookDock 的 tick 驱动（和 [GameBubble] 同一拍）。
 * 数据同样是 [SysStatSource] 那一帧，所以和侧边栏、控制中心看到的是同一组数字。
 *
 * 为什么单独一个窗口而不是塞进游戏里：host 进程是 SystemUI，只有 overlay 能画到别人之上。
 * 用 FLAG_NOT_TOUCHABLE，绝不拦触摸 —— 打游戏时误触的代价太大。
 *
 * 竖屏 / 横屏都不隐藏（这跟耳机那张卡不同：HUD 是用户主动开的，横屏打游戏时才是它最有用的场合）。
 */
object GameHud {

    /** 距离屏幕顶端的位置（dp）：让开状态栏，又不要压住刘海挖孔区 */
    private const val TOP_DP = 26

    /** 三个数字之间的间隔 */
    private const val GAP_DP = 10

    private const val PAD_H_DP = 12
    private const val PAD_V_DP = 5
    private const val TEXT_SP = 10.5f
    private const val BG = 0xB3000000.toInt()
    private const val TEXT_MAIN = 0xFFEDEDED.toInt()
    private const val WARN = 0xFFFFB300.toInt()
    private const val CRIT = 0xFFFF5252.toInt()

    private val main = Handler(Looper.getMainLooper())

    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var cpuView: TextView? = null
    private var gpuView: TextView? = null
    private var memView: TextView? = null

    /** 当前挂着的可见性，避免每拍重复 addView / removeView */
    private var showing = false

    /** 最近一次写进 TextView 的文本，内容没变就不 setText（避免每拍触发重绘） */
    private var lastCpu = ""
    private var lastGpu = ""
    private var lastMem = ""

    /**
     * 由 HookDock 每拍调用：[want] = 该不该显示，[enabled] 已在调用方与"前台是游戏"合并过。
     * 尺寸 / 横竖屏都不重建窗口，只改位置。
     */
    fun sync(context: Context, want: Boolean) {
        if (context.packageName != "com.android.systemui") return
        if (!want) {
            hide()
            return
        }
        if (root == null) {
            show(context)
        } else {
            settle(context)
        }
        update(context)
    }

    fun hide() {
        if (!showing && root == null) return
        val v = root ?: return
        root = null
        cpuView = null
        gpuView = null
        memView = null
        lp = null
        showing = false
        lastCpu = ""
        lastGpu = ""
        lastMem = ""
        try {
            wm?.removeView(v)
        } catch (_: Throwable) {
        }
        XposedBridge.log("[TapDock] game hud hidden")
    }

    private fun show(context: Context) {
        val ctx = context.applicationContext
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不抢焦点、不吃触摸：打游戏时这块区域必须能点透
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(ctx, TOP_DP)
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        val v = build(ctx)
        try {
            val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            manager.addView(v, params)
            wm = manager
            root = v
            lp = params
            showing = true
            XposedBridge.log("[TapDock] game hud shown")
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] game hud failed: $t")
        }
    }

    private fun build(ctx: Context): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, PAD_H_DP), dp(ctx, PAD_V_DP), dp(ctx, PAD_H_DP), dp(ctx, PAD_V_DP))
            background = GradientDrawable().apply {
                setColor(BG)
                cornerRadius = dp(ctx, 10).toFloat()
            }
        }
        val gap = dp(ctx, GAP_DP)
        val cpu = label(ctx, "CPU --").also {
            row.addView(it, wrap())
            cpuView = it
        }
        row.addView(spacer(ctx, gap))
        val gpu = label(ctx, "GPU --").also {
            row.addView(it, wrap())
            gpuView = it
        }
        row.addView(spacer(ctx, gap))
        val mem = label(ctx, "内存 --").also {
            row.addView(it, wrap())
            memView = it
        }
        return row
    }

    private fun label(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(TEXT_MAIN)
        textSize = TEXT_SP
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        includeFontPadding = false
        maxLines = 1
    }

    private fun spacer(ctx: Context, w: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(w, 1)
    }

    private fun wrap() = LinearLayout.LayoutParams(-2, -2)

    /** 刷新三个读数；内容没变就不动，省掉每拍的重绘 */
    private fun update(ctx: Context) {
        val f = SysStatSource.poll(ctx.applicationContext)

        val cpuText = f.cpuAvg?.let { String.format(Locale.US, "CPU %.0f%%", it) } ?: "CPU --"
        if (cpuText != lastCpu) {
            lastCpu = cpuText
            cpuView?.text = cpuText
            cpuView?.setTextColor(levelColor(f.cpuAvg))
        }

        val gpuText = f.gpu?.let { "GPU $it%" } ?: "GPU --"
        if (gpuText != lastGpu) {
            lastGpu = gpuText
            gpuView?.text = gpuText
            gpuView?.setTextColor(levelColor(f.gpu?.toFloat()))
        }

        val memText = f.mem?.let { m ->
            val pct = (m.fraction * 100).toInt().coerceIn(0, 100)
            "内存 $pct%"
        } ?: "内存 --"
        if (memText != lastMem) {
            lastMem = memText
            memView?.text = memText
            memView?.setTextColor(levelColor(f.mem?.let { it.fraction * 100f }))
        }
    }

    /** 与侧边栏同一套配色：<60 正常，<85 琥珀，再高红 */
    private fun levelColor(percent: Float?): Int = when {
        percent == null -> TEXT_MAIN
        percent >= 85f -> CRIT
        percent >= 60f -> WARN
        else -> TEXT_MAIN
    }

    /** 分辨率 / 方向变了以后把窗口重新贴回顶部居中 */
    private fun settle(ctx: Context) {
        val p = lp ?: return
        val y = dp(ctx, TOP_DP)
        if (p.y == y) return
        p.y = y
        try {
            wm?.updateViewLayout(root ?: return, p)
        } catch (_: Throwable) {
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
