package com.lsp102.tapdock.xposed

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import de.robv.android.xposed.XposedBridge
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 前台游戏时贴在屏幕左上角的一条竖向悬浮把手。
 *
 * 竖着压在屏幕左缘：默认大半条在屏幕外，只露出右缘一条窄边。向右滑（或点一下）
 * 打开 [GameSidebar] 侧边栏；再点空白处收起。只在 SystemUI 进程里挂，由 HookDock 的
 * 3 秒 ticker 驱动：GameInfoSource 判定前台是游戏 → 挂上，切走 / 熄屏 / 关开关 → 摘掉。
 *
 * 把手占着左缘、又要横向滑动，所以显示期间会临时把左右边缘的侧滑返回区域收成 0
 * （Settings.Secure 的 back_gesture_inset_scale_*），离开游戏后恢复原值。
 */
object GameBubble {

    private const val PREFS = "tapdock_game_bubble"

    /** 收起时露在屏幕里的宽度（dp）：只有这么一条能被看到、摸到 */
    private const val PEEK_DP = 16

    /** 横向再收 15px：把手与可见窄条一起变细 */
    private const val WIDTH_TRIM_PX = 15

    /** 把手距屏幕顶端的距离（像素）：从左上角往下挪，避开状态栏/挖孔区域 */
    private const val TOP_OFFSET_PX = 200

    /** 向右滑出这么多（dp）就算"打开侧边栏"，不用等松手 */
    private const val SWIPE_OPEN_DP = 20

    /** 长条长边 = 厚度 × 这个比例 */
    private const val LENGTH_FACTOR = 2.6f

    /** 全黑底 */
    private const val BG_COLOR = 0xFF000000.toInt()

    /** 系统边缘返回手势区宽度：设成 0 就是该侧不再响应返回手势 */
    private const val BACK_LEFT = "back_gesture_inset_scale_left"
    private const val BACK_RIGHT = "back_gesture_inset_scale_right"
    private const val PREF_BACK_LEFT = "back_left_saved"
    private const val PREF_BACK_RIGHT = "back_right_saved"

    /** 原来根本没有这个设置项时的占位值；恢复时写 null 把它删掉 */
    private const val NULL_VALUE = "\u0000none"

    private var wm: WindowManager? = null
    private var view: ImageView? = null
    private var lp: WindowManager.LayoutParams? = null

    /** 当前挂着的档位直径；-1 = 还没挂过 */
    private var sizeDp = -1

    /** 窗口实际像素：厚度 = 档位直径，长度 = 厚度 × 长条比例 */
    private var wPx = 0
    private var hPx = 0

    /** 收起时的可见宽度、贴左缘收起时的 x */
    private var peekPx = 0
    private var parkedPos = 0

    // 触摸过程
    private var downRawX = 0f
    private var downRawY = 0f
    private var moved = false
    private var triggered = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 把手显示期间的 1 秒安全拍：切到别的应用后，3 秒 tick 太慢，
     * 这里尽快把前台状态刷新成"非游戏"，立刻恢复边缘返回并收起把手。
     */
    private val foregroundCheck = object : Runnable {
        override fun run() {
            val v = view ?: return
            val ctx = v.context.applicationContext
            GameInfoSource.checkForeground(ctx)
            if (!GameInfoSource.gameForeground) {
                GameSidebar.dismiss()
                restoreBackGesture(ctx)
                hide()
                return
            }
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    /**
     * 由 HookDock 每拍调用：want = 该不该显示，sizeDp = 长条厚度。
     * 尺寸没变就不重建窗口；横竖屏换了只按新屏幕重算收起位置。
     */
    fun sync(context: Context, want: Boolean, sizeDp: Int) {
        if (context.packageName != "com.android.systemui") return
        if (!want) {
            GameSidebar.dismiss()
            restoreBackGesture(context)
            hide()
            return
        }
        val current = view
        if (current != null && lp != null && this.sizeDp == sizeDp) {
            blockBackGesture(context)
            if (current.visibility != View.VISIBLE) current.visibility = View.VISIBLE
            settle(context)
            return
        }
        hide()
        show(context, sizeDp)
    }

    /** 摘掉悬浮把手；没挂着时是空操作 */
    fun hide() {
        mainHandler.removeCallbacks(foregroundCheck)
        val v = view ?: return
        view = null
        lp = null
        try {
            wm?.removeView(v)
        } catch (_: Throwable) {
        }
        XposedBridge.log("[TapDock] game bubble hidden")
    }

    private fun show(context: Context, sizeDp: Int) {
        val density = context.resources.displayMetrics.density
        val base = (sizeDp * density).roundToInt().coerceAtLeast(1)
        val w = (base - WIDTH_TRIM_PX).coerceAtLeast(1)
        // 高度按原档位算，只缩左右宽度，不跟着变短
        val h = (base * LENGTH_FACTOR).roundToInt()
        peekPx = (dp(context, PEEK_DP) - WIDTH_TRIM_PX).coerceIn(1, (w / 2).coerceAtLeast(1))
        parkedPos = peekPx - w
        val params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = parkedPos
            y = TOP_OFFSET_PX
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        val v = build(context, w, h)
        try {
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            manager.addView(v, params)
            wm = manager
            view = v
            lp = params
            this.sizeDp = sizeDp
            wPx = w
            hPx = h
            blockBackGesture(context)
            mainHandler.removeCallbacks(foregroundCheck)
            mainHandler.postDelayed(foregroundCheck, 1_000L)
            XposedBridge.log("[TapDock] game bubble shown ${w}x${h} at (${params.x},${params.y})")
        } catch (t: Throwable) {
            restoreBackGesture(context)
            XposedBridge.log("[TapDock] game bubble failed: $t")
        }
    }

    private fun build(context: Context, w: Int, h: Int): ImageView {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val trigger = dp(context, SWIPE_OPEN_DP)
        val iv = ImageView(context).apply {
            // 纯黑长条：不留图标、不留描边
            background = GradientDrawable().apply {
                // 竖长条：矩形 + 厚度一半的圆角，两端是完整的半圆
                shape = GradientDrawable.RECTANGLE
                setColor(BG_COLOR)
                cornerRadius = w / 2f
            }
            isClickable = true
            contentDescription = "游戏悬浮条"
        }
        // 点一下 / 右滑：打开侧边栏（点空白处收起）
        iv.setOnClickListener { GameSidebar.toggle(iv.context) }
        iv.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    moved = false
                    triggered = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!moved && hypot(dx, dy) > slop) moved = true
                    // 右滑到阈值就开，不等松手；只开一次
                    if (!triggered && dx > trigger) {
                        triggered = true
                        v.performClick()
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) v.performClick()
                    true
                }

                else -> true
            }
        }
        return iv
    }

    /** 横竖屏切换后屏幕宽度变了：收起位置跟着重算 */
    private fun settle(context: Context) {
        val p = lp ?: return
        peekPx = (dp(context, PEEK_DP) - WIDTH_TRIM_PX).coerceIn(1, (wPx / 2).coerceAtLeast(1))
        parkedPos = peekPx - wPx
        if (p.x == parkedPos && p.y == TOP_OFFSET_PX) return
        p.x = parkedPos
        p.y = TOP_OFFSET_PX
        try {
            wm?.updateViewLayout(view ?: return, p)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ 游戏期间关掉边缘返回

    /**
     * 只收左边缘的返回手势区：把手就在左缘、要横向滑动，别的侧滑返回不会和它冲突。
     * 右边缘保持系统原样，切到其他应用时也不会被这条设置牵连。
     */
    private fun blockBackGesture(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        blockOne(context, sp, BACK_LEFT, PREF_BACK_LEFT)
    }

    private fun blockOne(
        context: Context,
        sp: SharedPreferences,
        key: String,
        savedKey: String,
    ) {
        if (sp.contains(savedKey)) return
        val current = try {
            Settings.Secure.getString(context.contentResolver, key)
        } catch (_: Throwable) {
            null
        }
        if (current == "0" || current == "0.0") {
            // 上一轮异常退出没留下原值，当前已经是 0：按系统默认 1.0 记一份，
            // 离开游戏时一定恢复，避免 0 残留导致所有应用都不能侧滑返回
            sp.edit().putString(savedKey, "1.0").apply()
            return
        }
        sp.edit().putString(savedKey, current ?: NULL_VALUE).apply()
        try {
            Settings.Secure.putString(context.contentResolver, key, "0")
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] block back gesture failed: $t")
        }
    }

    private fun restoreBackGesture(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        restoreOne(context, sp, BACK_LEFT, PREF_BACK_LEFT)
        restoreOne(context, sp, BACK_RIGHT, PREF_BACK_RIGHT)
    }

    private fun restoreOne(
        context: Context,
        sp: SharedPreferences,
        key: String,
        savedKey: String,
    ) {
        val saved = sp.getString(savedKey, null) ?: return
        try {
            Settings.Secure.putString(
                context.contentResolver,
                key,
                if (saved == NULL_VALUE) null else saved,
            )
            sp.edit().remove(savedKey).apply()
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] restore back gesture failed: $t")
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}
