package com.lsp102.tapdock.xposed

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import de.robv.android.xposed.XposedBridge
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 游戏侧的设备状态侧边栏：从屏幕左缘滑出的一整列，按分组展示手机实时状态。
 * 由 [GameBubble] 的把手右滑 / 点按打开，点空白处或 × 收起；每秒刷新一次数据。
 */
object GameSidebar {

    private const val WIDTH_DP = 300
    private const val OPEN_MS = 200L
    private const val CLOSE_MS = 160L
    private const val TICK_MS = 1_000L

    private const val PANEL_BG = 0xFF000000.toInt()

    /** 侧边栏那张"系统负载"大卡片：比单行卡再深一点，把三张小卡托起来 */
    private const val BIG_CARD_BG = 0x0FFFFFFF

    /** 三张小卡片的底色：比大卡片略亮，视觉上才浮在上面 */
    private const val MINI_CARD_BG = 0x1FFFFFFF

    /** GPU / 内存小卡片的标题色，和 CPU 那张区分开 */
    private const val GPU_ACCENT = 0xFFB388FF.toInt()
    private const val MEM_ACCENT = 0xFF4DD0E1.toInt()
    private const val TEXT_MAIN = 0xFFFFFFFF.toInt()
    private const val TEXT_DIM = 0xFFA7AAB2.toInt()
    private const val ACCENT_FALLBACK = 0xFF4F8CFF.toInt()
    private const val BAR_WARN = 0xFFFFB300.toInt()
    private const val BAR_CRIT = 0xFFE53935.toInt()

    private var wm: WindowManager? = null
    private var panel: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var backdrop: View? = null
    private var slideAnim: ValueAnimator? = null

    private var showing = false
    private var closing = false
    private var widthPx = 0
    private var appCtx: Context? = null
    private var accent = ACCENT_FALLBACK

    /** 每行一个刷新动作，每秒整批跑一遍 */
    private val rows = mutableListOf<() -> Unit>()

    /** 最近一帧系统负载（CPU / GPU / 内存），每秒由 [refresh] 更新 */
    private var stat = SysStatSource.Frame(FloatArray(0), null, null)

    /** 当前选中的媒体控制器；null = 没有正在播放的会话 */
    private var media: MediaController? = null

    private val main = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            if (!showing) return
            val ctx = appCtx ?: return
            refresh(ctx)
            main.postDelayed(this, TICK_MS)
        }
    }

    fun toggle(context: Context) {
        if (showing || closing) dismiss() else show(context)
    }

    /** 游戏退出 / 关开关时由 GameBubble 调用；没开着就是空操作 */
    fun dismiss() {
        if (!showing || closing) return
        closing = true
        main.removeCallbacks(tick)
        if (panel == null || lp == null || wm == null) {
            cleanup()
            return
        }
        slide(-widthPx, CLOSE_MS) { cleanup() }
    }

    private fun show(context: Context) {
        val ctx = context.applicationContext
        appCtx = ctx
        accent = accentColor(ctx)
        // 这里刻意不调 SysStatSource.reset()：关着期间读到的那一帧要留着，
        // 再次打开时先把上次的数值显示出来，新帧到了再就地覆盖，不出现"--"空屏。
        // 进程被重启过（内存里已经没有）时，再从磁盘把最后一帧读回来顶上。
        if (stat.cores.isEmpty() && stat.gpu == null && stat.mem == null) {
            SysStatSource.restore(ctx)?.let { stat = it }
        }
        val metrics = ctx.resources.displayMetrics
        widthPx = min(dp(ctx, WIDTH_DP), (metrics.widthPixels * 0.42f).roundToInt())
            .coerceAtLeast(dp(ctx, 200))
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager
        val dim = View(ctx).apply {
            setBackgroundColor(0x33000000)
            setOnClickListener { dismiss() }
        }
        val dimLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        val v = build(ctx)
        val p = WindowManager.LayoutParams(
            widthPx,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = -widthPx
            y = 0
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        try {
            manager.addView(dim, dimLp)
            backdrop = dim
            manager.addView(v, p)
            panel = v
            lp = p
            showing = true
            closing = false
            refresh(ctx)
            slide(0, OPEN_MS) {}
            main.removeCallbacks(tick)
            main.postDelayed(tick, TICK_MS)
            XposedBridge.log("[TapDock] status sidebar shown width=$widthPx")
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] status sidebar failed: $t")
            cleanup()
        }
    }

    // ------------------------------------------------------------------ 视图

    private fun build(ctx: Context): LinearLayout {
        val r = dp(ctx, 18).toFloat()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(PANEL_BG)
                // 只把右侧两个角做成圆角，贴着左屏缘才像从侧边滑出来
                cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            }
            setPadding(dp(ctx, 18), dp(ctx, 24), dp(ctx, 14), dp(ctx, 14))
            isClickable = true
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    dismiss()
                    true
                } else {
                    false
                }
            }
        }
        root.addView(header(ctx), matchWrap())
        // 横屏高度只有 450dp 左右，内容必然超长：头部固定，下方整块可滚
        val scroll = ScrollView(ctx).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        statusContent(ctx, content)
        scroll.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        )
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(ctx, 14)
            }
        )
        return root
    }

    /**
     * 顶栏：只留一个关闭按钮，没有任何图标和文字。
     *
     * 原来这里是「设备状态」+ 机型两行、左侧还有一个系统图标；这些都在面板里重复了
     * 使用者已经知道的信息，删掉之后整块面板从顶部就开始是数据。
     */
    private fun header(ctx: Context): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        row.addView(
            TextView(ctx).apply {
                text = "×"
                setTextColor(TEXT_DIM)
                textSize = 20f
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { dismiss() }
            },
            LinearLayout.LayoutParams(dp(ctx, 32), dp(ctx, 32)).apply {
                gravity = Gravity.END
            }
        )
        return row
    }

    /**
     * 侧边栏内容：一张大卡片，里面三张小卡片 —— CPU / GPU / 内存。
     *
     * 三张都是**整机视角**的系统数据，跟当前在跑哪个游戏无关：
     * 打游戏时想知道的是"这台机器累不累"，不是"这个游戏占了多少"。
     * 数据走 [SysStatSource] 那一帧（root 采集：SystemUI 域读不到 /proc/stat 和 kgsl 节点）。
     *
     * 布局：一张大卡片，里面直接是三张等宽小卡片横排 —— 不写标题，也不标采样状态，
     * 打开就是数据本身。
     * 侧边栏宽 300dp，去掉内边距后每张约 84dp，够放一个大数字和两行小字。
     */
    private fun statusContent(ctx: Context, parent: LinearLayout) {
        rows.clear()

        val big = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
            background = GradientDrawable().apply {
                setColor(BIG_CARD_BG)
                cornerRadius = dp(ctx, 16).toFloat()
            }
        }

        // 三张小卡片横排
        val trio = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        val cpuCard = miniCard(ctx, trio, "CPU", accent, first = true)
        val gpuCard = miniCard(ctx, trio, "GPU", GPU_ACCENT, first = false)
        val memCard = miniCard(ctx, trio, "内存", MEM_ACCENT, first = false)
        big.addView(trio, matchWrap())
        parent.addView(big, matchWrap())

        // ---- 顶部叠加开关
        hudToggle(ctx, parent)

        // ---- 性能模式
        perfSection(ctx, parent)

        // ---- 通知
        notifSection(ctx, parent)

        // ---- 音乐控制区
        musicSection(ctx, parent)

        rows.add {
            val f = stat
            // 有数值就照常显示（哪怕是上一次的）：要的是"打开就能看到数"，
            // 不是每开一次就从 -- 重新等

            // CPU：全核平均，副标题给出核数与最忙的那一核
            val avg = f.cpuAvg
            cpuCard.value.text = if (avg == null) "--" else String.format(Locale.US, "%.0f%%", avg)
            cpuCard.sub.text = if (avg == null) {
                "无数据"
            } else {
                val peak = f.cpuPeak ?: 0f
                "${f.cores.size} 核 · 峰 ${String.format(Locale.US, "%.0f%%", peak)}"
            }
            cpuCard.bar.fraction = (avg ?: 0f) / 100f
            cpuCard.bar.color = loadColor(avg)

            // GPU：占用 + 当前 / 最高频率
            val gpuPct = f.gpu
            gpuCard.value.text = if (gpuPct == null) "--" else "$gpuPct%"
            gpuCard.sub.text = when {
                gpuPct == null && f.gpuClock == null -> "无数据"
                f.gpuClock != null -> {
                    // maxMhz 在采集端已经折成 MHz（max_gpuclk 原始值是 900000000 这种 Hz），
                    // 这里直接用，不能再除 1000 —— 整数除法会得到 0，显示成 "285/0 MHz"
                    val c = f.gpuClock
                    when {
                        c.curMhz > 0 && c.maxMhz > 0 -> "${c.curMhz}/${c.maxMhz} MHz"
                        c.curMhz > 0 -> "${c.curMhz} MHz"
                        else -> "频率不可读"
                    }
                }
                else -> "GPU 占用"
            }
            gpuCard.bar.fraction = (gpuPct ?: 0) / 100f
            gpuCard.bar.color = loadColor(gpuPct?.toFloat())

            // 内存：已用 / 总量（MemTotal − MemAvailable，和系统设置页口径一致）
            val m = f.mem
            memCard.value.text = if (m == null) "--" else SysStatSource.fmt(m.usedKb)
            memCard.sub.text = if (m == null) {
                "无数据"
            } else {
                val pct = (m.fraction * 100).toInt().coerceIn(0, 100)
                "共 ${SysStatSource.fmt(m.totalKb)} · $pct%"
            }
            memCard.bar.fraction = m?.fraction ?: 0f
            memCard.bar.color = loadColor((m?.fraction ?: 0f) * 100f)

        }
    }

    /**
     * "游戏顶部叠加"开关。
     *
     * 用一颗胶囊而不是系统 Switch：侧边栏是纯代码搭的深色面板，Switch 的
     * Material 配色在这里会显得突兀，胶囊也更容易在窄条里放得下。
     *
     * 点击后三件事同时发生：本进程立刻生效、写回 App 偏好、窗口按新值同步。
     */
    private fun hudToggle(ctx: Context, parent: LinearLayout) {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            background = GradientDrawable().apply {
                setColor(BIG_CARD_BG)
                cornerRadius = dp(ctx, 16).toFloat()
            }
            isClickable = true
        }
        val label = TextView(ctx).apply {
            text = "游戏顶部叠加"
            setTextColor(TEXT_MAIN)
            textSize = 12f
            includeFontPadding = false
        }
        row.addView(
            label,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        // 胶囊：开着是主题色描边 + 实心点，关着是灰底
        val knob = TextView(ctx).apply {
            text = ""
            setPadding(0, 0, 0, 0)
        }
        val pill = FrameLayout(ctx).apply {
            addView(knob, FrameLayout.LayoutParams(dp(ctx, 16), dp(ctx, 16), Gravity.CENTER))
        }
        row.addView(pill, LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 22)))

        // 当前状态 → 视觉
        fun paint(on: Boolean) {
            pill.background = GradientDrawable().apply {
                setColor(if (on) accentAlpha(0x66) else 0x1FFFFFFF)
                cornerRadius = dp(ctx, 11).toFloat()
            }
            val lp = knob.layoutParams as? FrameLayout.LayoutParams
            lp?.gravity = (if (on) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            if (lp != null) {
                lp.marginEnd = dp(ctx, 3)
                lp.marginStart = dp(ctx, 3)
                knob.layoutParams = lp
            }
            knob.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (on) accent else 0xFF6B6B6B.toInt())
            }
        }
        paint(HookDock.gameHudOn())

        row.setOnClickListener {
            val next = !HookDock.gameHudOn()
            HookDock.setGameHud(ctx, next)
            paint(next)
        }
        parent.addView(row, matchWrap().apply { topMargin = dp(ctx, 10) })
    }

    /**
     * 性能模式区：四档胶囊横排 —— 省电 / 均衡 / 性能 / 极速。
     *
     * 点档位三件事一起做：本进程 override、写回 App 偏好、root 切调频策略。
     * 调频节点因机而异，写不动就跳过（日志可查），但选中的档位始终高亮并记住。
     */
    private fun perfSection(ctx: Context, parent: LinearLayout) {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 11), dp(ctx, 12), dp(ctx, 12))
            background = GradientDrawable().apply {
                setColor(BIG_CARD_BG)
                cornerRadius = dp(ctx, 16).toFloat()
            }
        }
        val title = TextView(ctx).apply {
            text = "性能模式"
            setTextColor(TEXT_MAIN)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
        card.addView(title, matchWrap())

        // 两行两列的四档：窄条里横排四颗会太挤，2×2 更稳
        val levels = PerformanceMode.all()
        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isBaselineAligned = false
        }
        val chips = mutableMapOf<PerformanceMode.Level, TextView>()
        for (rowIdx in levels.indices step 2) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            for (colIdx in rowIdx until min(rowIdx + 2, levels.size)) {
                val lv = levels[colIdx]
                val chip = TextView(ctx).apply {
                    text = lv.label
                    textSize = 11f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    isClickable = true
                }
                chips[lv] = chip
                row.addView(
                    chip,
                    LinearLayout.LayoutParams(0, dp(ctx, 32), 1f).apply {
                        if (colIdx > rowIdx) marginStart = dp(ctx, 6)
                    }
                )
            }
            grid.addView(row, matchWrap().apply { topMargin = if (rowIdx == 0) dp(ctx, 8) else dp(ctx, 6) })
        }
        card.addView(grid, matchWrap())

        // 副标题提示当前档位的说明，也告知调频可能不支持
        val hint = TextView(ctx).apply {
            textSize = 9f
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(hint, matchWrap().apply { topMargin = dp(ctx, 8) })

        fun paintSelected() {
            val cur = PerformanceMode.fromId(HookDock.perfModeId())
            chips.forEach { (lv, chip) ->
                val on = lv == cur
                chip.background = GradientDrawable().apply {
                    setColor(if (on) accentAlpha(0x66) else MINI_CARD_BG)
                    cornerRadius = dp(ctx, 10).toFloat()
                    if (on) setStroke(dp(ctx, 1), accent) else setStroke(0, accent)
                }
                chip.setTextColor(if (on) TEXT_MAIN else TEXT_DIM)
            }
            hint.text = "${cur.label} · ${cur.desc}（调频需 root，部分机型节点不同可能只部分生效）"
            hint.setTextColor(TEXT_DIM)
        }
        paintSelected()

        chips.forEach { (lv, chip) ->
            chip.setOnClickListener {
                if (lv.id == HookDock.perfModeId()) return@setOnClickListener
                HookDock.setPerfMode(ctx, lv.id)
                paintSelected()
            }
        }

        parent.addView(card, matchWrap().apply { topMargin = dp(ctx, 10) })
    }

    /**
     * 通知区块：把当前挂着的通知列成竖排小卡。
     *
     * 数据来自 [NotifCenter]（跟 Dock 徽标同一条通知管线，只是那边只数个数）。
     * 最多铺 [NOTIF_MAX_ROWS] 条，再多折叠成一行"还有 N 条"，免得侧边栏被通知撑到滚不到底。
     * 常驻通知排在后面并加一个"常驻"标记 —— 它们不会自己消失，混在消息里容易误读。
     */
    private fun notifSection(ctx: Context, parent: LinearLayout) {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        parent.addView(box, matchWrap().apply { topMargin = dp(ctx, 10) })

        // 只在内容变化时重建：通知列表逐秒刷会闪，而且没必要
        var builtVersion = -1
        rows.add {
            if (NotifCenter.version == builtVersion) return@add
            builtVersion = NotifCenter.version
            box.removeAllViews()
            val list = NotifCenter.list()
            if (list.isEmpty()) {
                box.visibility = View.GONE
                return@add
            }
            box.visibility = View.VISIBLE
            val shown = list.take(NOTIF_MAX_ROWS)
            shown.forEach { box.addView(notifRow(ctx, it), matchWrap().apply { bottomMargin = dp(ctx, 6) }) }
            val rest = list.size - shown.size
            if (rest > 0) {
                box.addView(
                    TextView(ctx).apply {
                        text = "还有 $rest 条"
                        setTextColor(TEXT_DIM)
                        textSize = 10f
                        includeFontPadding = false
                        setPadding(dp(ctx, 4), dp(ctx, 1), 0, 0)
                    },
                    matchWrap()
                )
            }
        }
    }

    /** 一条通知：应用图标 + 标题 + 正文 + 时间 */
    private fun notifRow(ctx: Context, n: NotifCenter.Item): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 10), dp(ctx, 9), dp(ctx, 10), dp(ctx, 9))
            background = GradientDrawable().apply {
                setColor(MINI_CARD_BG)
                cornerRadius = dp(ctx, 12).toFloat()
            }
        }
        val icon = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(n.icon ?: n.small)
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20)))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val head = TextView(ctx).apply {
            // 应用名 + 可选"常驻"标记，比只写标题更能分辨来源
            text = if (n.ongoing) "${n.appLabel} · 常驻" else n.appLabel
            setTextColor(TEXT_DIM)
            textSize = 9f
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val title = TextView(ctx).apply {
            text = n.title
            setTextColor(TEXT_MAIN)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val body = TextView(ctx).apply {
            text = n.text
            setTextColor(TEXT_DIM)
            textSize = 10f
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            visibility = if (n.text.isEmpty()) View.GONE else View.VISIBLE
        }
        col.addView(head, matchWrap())
        col.addView(title, matchWrap())
        col.addView(body, matchWrap())
        card.addView(
            col,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(ctx, 9) }
        )
        card.addView(
            TextView(ctx).apply {
                text = n.time
                setTextColor(TEXT_DIM)
                textSize = 9f
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(ctx, 6) }
        )
        return card
    }

    /** 通知列表最多直接铺几条 */
    private const val NOTIF_MAX_ROWS = 4

    /**
     * 音乐控制区：一张卡片，曲名 + 艺人 + 进度 + 上一首 / 播放暂停 / 下一首。
     *
     * 会话来源是 MediaSessionManager.getActiveSessions —— SystemUI 持有 MEDIA_CONTENT_CONTROL，
     * 所以不需要 App 自己去连任何播放器。多个会话同时活跃时取"正在播放"的那一个；
     * 都在暂停就取第一个，至少能让人从这里起播。
     *
     * 没有会话时整块收起（GONE），不留一个空框占位置。
     */
    private fun musicSection(ctx: Context, parent: LinearLayout) {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 11), dp(ctx, 12), dp(ctx, 11))
            background = GradientDrawable().apply {
                setColor(BIG_CARD_BG)
                cornerRadius = dp(ctx, 16).toFloat()
            }
            visibility = View.GONE
        }

        val title = TextView(ctx).apply {
            text = "未在播放"
            setTextColor(TEXT_MAIN)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val artist = TextView(ctx).apply {
            text = ""
            setTextColor(TEXT_DIM)
            textSize = 10.5f
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(title, matchWrap())
        card.addView(artist, matchWrap().apply { topMargin = dp(ctx, 1) })

        val progress = BarView(ctx, accent)
        card.addView(
            progress,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 3))
                .apply { topMargin = dp(ctx, 9) }
        )
        val time = TextView(ctx).apply {
            text = "--:-- / --:--"
            setTextColor(TEXT_DIM)
            textSize = 9.5f
            includeFontPadding = false
        }
        card.addView(time, matchWrap().apply { topMargin = dp(ctx, 3) })

        // 三个按钮：等宽平分，图标居中
        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
        }
        val prev = controlButton(ctx, com.lsp102.tapdock.R.drawable.ic_ctrl_prev) {
            media?.transportControls?.skipToPrevious()
        }
        val play = controlButton(ctx, com.lsp102.tapdock.R.drawable.ic_ctrl_play) {
            val c = media ?: return@controlButton
            val st = c.playbackState?.state
            if (st == PlaybackState.STATE_PLAYING) c.transportControls.pause()
            else c.transportControls.play()
        }
        val next = controlButton(ctx, com.lsp102.tapdock.R.drawable.ic_ctrl_next) {
            media?.transportControls?.skipToNext()
        }
        controls.addView(prev, LinearLayout.LayoutParams(0, dp(ctx, 34), 1f))
        controls.addView(play, LinearLayout.LayoutParams(0, dp(ctx, 34), 1f))
        controls.addView(next, LinearLayout.LayoutParams(0, dp(ctx, 34), 1f))
        card.addView(controls, matchWrap().apply { topMargin = dp(ctx, 7) })

        parent.addView(card, matchWrap().apply { topMargin = dp(ctx, 10) })

        rows.add {
            val c = pickMediaController(ctx)
            media = c
            if (c == null) {
                card.visibility = View.GONE
                return@add
            }
            card.visibility = View.VISIBLE
            val meta = c.metadata
            title.text = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: "未知曲目"
            artist.text = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)
                ?: ""
            val st = c.playbackState
            val playing = st?.state == PlaybackState.STATE_PLAYING
            play.setImageDrawable(
                ModuleRes.drawable(
                    ctx,
                    if (playing) com.lsp102.tapdock.R.drawable.ic_ctrl_pause
                    else com.lsp102.tapdock.R.drawable.ic_ctrl_play,
                )
            )
            val pos = st?.position ?: 0L
            val dur = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            progress.fraction = if (dur > 0L) (pos.toFloat() / dur) else 0f
            progress.color = accent
            time.text = "${clockOf(pos)} / ${if (dur > 0L) clockOf(dur) else "--:--"}"
        }
    }

    /** 一个圆角方形图标按钮 */
    private fun controlButton(ctx: Context, iconRes: Int, onClick: () -> Unit): ImageView {
        val pad = dp(ctx, 7)
        return ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(ModuleRes.drawable(ctx, iconRes))
            setColorFilter(TEXT_MAIN)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(MINI_CARD_BG)
                cornerRadius = dp(ctx, 10).toFloat()
            }
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    /** 毫秒 → m:ss */
    private fun clockOf(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    /**
     * 挑一个要显示的媒体会话。
     *
     * 优先"正在播放"的那个（可能后台还有别的会话挂着）；都在暂停就取第一个已知的，
     * 这样用户至少能从侧边栏把它起播。完全没有会话就返回 null，音乐区整块收起。
     */
    private fun pickMediaController(ctx: Context): MediaController? = try {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val list = msm?.getActiveSessions(null).orEmpty()
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.playbackState != null }
    } catch (_: Throwable) {
        null
    }

    /** 一张小卡片的可刷新部件 */
    private class MiniCard(
        val root: LinearLayout,
        val value: TextView,
        val sub: TextView,
        val bar: BarView,
    )

    /** 三张小卡片之一：标题 + 大数字 + 副标题 + 底部细条，等宽平分 [parent] */
    private fun miniCard(
        ctx: Context,
        parent: LinearLayout,
        title: String,
        tint: Int,
        first: Boolean,
    ): MiniCard {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 9), dp(ctx, 9), dp(ctx, 9), dp(ctx, 9))
            background = GradientDrawable().apply {
                setColor(MINI_CARD_BG)
                cornerRadius = dp(ctx, 12).toFloat()
            }
        }
        val label = TextView(ctx).apply {
            text = title
            setTextColor(tint)
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
        val value = TextView(ctx).apply {
            text = "--"
            setTextColor(TEXT_MAIN)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val sub = TextView(ctx).apply {
            text = "无数据"
            setTextColor(TEXT_DIM)
            textSize = 9f
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(label, matchWrap())
        card.addView(value, matchWrap().apply { topMargin = dp(ctx, 2) })
        card.addView(sub, matchWrap().apply { topMargin = dp(ctx, 1) })
        val bar = BarView(ctx, tint)
        card.addView(
            bar,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 3))
                .apply { topMargin = dp(ctx, 7) }
        )
        parent.addView(
            card,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (!first) marginStart = dp(ctx, 6)
            }
        )
        return MiniCard(card, value, sub, bar)
    }

    /** 占用率配色：<60% 主题色，<85% 琥珀，再高就红 */
    private fun loadColor(percent: Float?): Int = when {
        percent == null -> accent
        percent >= 85f -> BAR_CRIT
        percent >= 60f -> BAR_WARN
        else -> accent
    }

    /** 细进度条：一次绘制轨道和填充，避免每秒改布局宽度 */
    private class BarView(context: Context, fillColor: Int) : View(context) {
        var fraction = 0f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }

        var color = fillColor
            set(value) {
                field = value
                invalidate()
            }

        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }

        init {
            setWillNotDraw(false)
        }

        override fun onDraw(canvas: Canvas) {
            fillPaint.color = color
            val h = height.toFloat()
            val r = h / 2f
            canvas.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, trackPaint)
            if (fraction > 0f) {
                canvas.drawRoundRect(0f, 0f, width * fraction, h, r, r, fillPaint)
            }
        }
    }

    // ------------------------------------------------------------------ 数据快照

    private fun refresh(ctx: Context) {
        // 系统负载：CPU / GPU / 内存走 root 采集的那一帧（SystemUI 域读不到这些节点）。
        // 不再按进程读游戏本身的数据 —— 这张卡片展示的是整机视角。
        stat = SysStatSource.poll(ctx)
        rows.forEach { it() }
    }

    // ------------------------------------------------------------------ 动画 / 清理 / 工具

    private fun slide(to: Int, duration: Long, end: () -> Unit) {
        val old = slideAnim
        slideAnim = null
        try {
            old?.cancel()
        } catch (_: Throwable) {
        }
        val v = panel ?: return end()
        val p = lp ?: return end()
        val manager = wm ?: return end()
        val from = p.x
        if (from == to) {
            end()
            return
        }
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            addUpdateListener {
                p.x = (from + (to - from) * it.animatedFraction).roundToInt()
                try {
                    manager.updateViewLayout(v, p)
                } catch (_: Throwable) {
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (slideAnim === animation) {
                        slideAnim = null
                        end()
                    }
                }
            })
        }
        slideAnim = a
        a.start()
    }

    private fun cleanup() {
        main.removeCallbacks(tick)
        slideAnim = null
        panel?.let {
            try {
                wm?.removeView(it)
            } catch (_: Throwable) {
            }
        }
        backdrop?.let {
            try {
                wm?.removeView(it)
            } catch (_: Throwable) {
            }
        }
        panel = null
        lp = null
        backdrop = null
        rows.clear()
        media = null
        appCtx = null
        showing = false
        closing = false
        XposedBridge.log("[TapDock] status sidebar hidden")
    }

    private fun accentColor(ctx: Context): Int = try {
        val ta = ctx.theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorAccent))
        val color = ta.getColor(0, ACCENT_FALLBACK)
        ta.recycle()
        color
    } catch (_: Throwable) {
        ACCENT_FALLBACK
    }

    private fun accentAlpha(alpha: Int): Int = (accent and 0x00FFFFFF) or (alpha shl 24)

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private fun matchWrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )
}
