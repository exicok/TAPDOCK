package com.lsp102.tapdock.xposed

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.Settings
import android.telephony.TelephonyManager
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
import de.robv.android.xposed.XposedHelpers
import java.io.FileDescriptor
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
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
    private const val CARD_BG = 0x14FFFFFF
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

    // 一帧快照，refresh 里各读一次，行动作只读字段
    private var battery: BatteryInfo? = null
    private var wifi: WifiSnapshot? = null
    private var cell: CellSnapshot? = null
    private var mem: MemSnapshot? = null
    private var storage: StorageSnapshot? = null

    private val clockFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("yyyy年M月d日 EEEE", Locale.getDefault())

    private val main = Handler(Looper.getMainLooper())

    private class BatteryInfo(
        val percent: Int,
        val status: Int,
        val plugged: Int,
        val tempC: Float,
        val voltageMv: Int,
        val health: Int,
    )

    private class WifiSnapshot(
        val enabled: Boolean,
        val connected: Boolean,
        val ssid: String,
        val rssi: Int,
        val level: Int,
    )

    private class CellSnapshot(
        val simReady: Boolean,
        val dataOn: Boolean,
        val operator: String,
        val typeName: String,
        val level: Int,
        val dbm: Int,
    )

    private class MemSnapshot(val used: Long, val total: Long, val avail: Long, val low: Boolean)
    private class StorageSnapshot(val used: Long, val total: Long, val avail: Long)

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

    private fun header(ctx: Context): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val chip = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(accentAlpha(0x33))
                cornerRadius = dp(ctx, 12).toFloat()
            }
        }
        val icon = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(
                ModuleRes.drawable(ctx, com.lsp102.tapdock.R.drawable.ic_tile_sys)
            )
        }
        chip.addView(
            icon,
            FrameLayout.LayoutParams(dp(ctx, 26), dp(ctx, 26), Gravity.CENTER)
        )
        row.addView(chip, LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(
            TextView(ctx).apply {
                text = "设备状态"
                setTextColor(TEXT_MAIN)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
            },
            matchWrap()
        )
        col.addView(
            TextView(ctx).apply {
                text = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
                setTextColor(TEXT_DIM)
                textSize = 10.5f
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            matchWrap()
        )
        row.addView(
            col,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(ctx, 12)
            }
        )
        row.addView(
            TextView(ctx).apply {
                text = "×"
                setTextColor(TEXT_DIM)
                textSize = 20f
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { dismiss() }
            },
            LinearLayout.LayoutParams(dp(ctx, 32), dp(ctx, 32))
        )
        return row
    }

    private fun statusContent(ctx: Context, parent: LinearLayout) {
        rows.clear()

        sectionTitle(ctx, parent, "游戏")
        addRow(ctx, parent, com.lsp102.tapdock.R.drawable.ic_tile_game, "帧率 / 刷新率") { v, sub, _ ->
            // 帧率跟图形 API 走同一条采集帧（GameInfoCollector 的 pkg|api|宽x高|帧率）。
            // Unity 游戏不走 HWUI，dumpsys gfxinfo 的Total frames 恒为 0；这台 ROM 的
            // SurfaceFlinger --latency 也只回刷新周期、不给帧时间戳，所以实测 FPS 拿不到时
            // 先读系统里游戏 SurfaceView 的 requestedFrameRate（ANativeWindow_setFrameRate 声明的
            // 目标帧率），再退到屏幕刷新率，副标题都标明数据来路。
            val info = GameInfoSource.peek(ctx)
            val api = GraphicsApiSource.currentGraphicsApi()?.label ?: info?.api
            val fps = info?.fps?.takeIf { it.isNotBlank() }
            val sysFps = if (fps == null) systemFrameRate(ctx) else 0f
            val hz = displayRefreshRate(ctx)
            when {
                fps != null -> {
                    v.text = "$fps fps"
                    sub.text = if (api != null) "$api · 图像 API 采集" else "图像 API 采集"
                }
                sysFps > 0f -> {
                    v.text = String.format(Locale.US, "%.0f fps", sysFps)
                    sub.text = if (api != null) "$api · 系统请求帧率" else "系统请求帧率"
                }
                hz > 0f -> {
                    v.text = String.format(Locale.US, "%.0f Hz", hz)
                    sub.text = if (api != null) {
                        "$api · 系统未提供实测 FPS"
                    } else {
                        "系统未提供实测 FPS"
                    }
                }
                else -> {
                    v.text = "--"
                    sub.text = "等待图形 API 数据"
                }
            }
        }

        sectionTitle(ctx, parent, "电池")
        addRow(ctx, parent, com.lsp102.tapdock.R.drawable.ic_status_battery, "电池电量", true) { v, sub, bar ->
            val b = battery
            if (b == null) {
                v.text = "--"
                sub.text = "电池信息不可用"
                bar?.fraction = 0f
            } else {
                v.text = "${b.percent}%"
                sub.text = "${chargeState(b)} · ${plugName(b.plugged)}"
                bar?.fraction = b.percent / 100f
                bar?.color = when {
                    b.percent <= 15 -> BAR_CRIT
                    b.percent <= 30 -> BAR_WARN
                    else -> accent
                }
            }
        }
        addRow(ctx, parent, com.lsp102.tapdock.R.drawable.ic_status_charge, "充电状态") { v, sub, _ ->
            val b = battery
            if (b == null) {
                v.text = "--"
                sub.text = "电池信息不可用"
            } else {
                v.text = if (b.plugged != 0) "${chargeState(b)} · ${plugName(b.plugged)}" else chargeState(b)
                sub.text = String.format(
                    Locale.US, "电压 %.2f V · 健康%s", b.voltageMv / 1000f, healthName(b.health)
                )
            }
        }
        addRow(ctx, parent, com.lsp102.tapdock.R.drawable.ic_status_temp, "电池温度") { v, sub, _ ->
            val b = battery
            if (b == null) {
                v.text = "--"
                sub.text = "温度传感器不可用"
            } else {
                v.text = String.format(Locale.US, "%.1f °C", b.tempC)
                sub.text = "电池实时温度"
            }
        }

        sectionTitle(ctx, parent, "网络")
        addRow(ctx, parent, com.lsp102.tapdock.R.drawable.ic_tile_wifi, "WiFi") { v, sub, _ ->
            val w = wifi
            when {
                w == null -> {
                    v.text = "--"
                    sub.text = "WiFi 信息不可用"
                }
                !w.enabled -> {
                    v.text = "已关闭"
                    sub.text = "WiFi 开关已关闭"
                }
                !w.connected -> {
                    v.text = "已开启"
                    sub.text = "未连接到网络"
                }
                else -> {
                    v.text = "已连接"
                    sub.text = buildString {
                        if (w.ssid.isNotEmpty()) append(w.ssid).append(" · ")
                        if (w.rssi > -100) append("${w.rssi} dBm · ")
                        append("信号 ${w.level}/4")
                    }
                }
            }
        }
        addRow(ctx, parent, com.lsp102.tapdock.R.drawable.ic_tile_data, "移动网络") { v, sub, _ ->
            val c = cell
            if (c == null) {
                v.text = "--"
                sub.text = "移动网络信息不可用"
            } else if (!c.simReady) {
                v.text = "未插卡"
                sub.text = "SIM 卡未就绪"
            } else {
                val op = c.operator.takeIf { it.isNotBlank() } ?: "未知运营商"
                v.text = if (c.dataOn) "${c.typeName} · $op" else "数据已关闭"
                sub.text = if (c.level >= 0) {
                    buildString {
                        append("信号 ${c.level}/4")
                        if (c.dbm != Int.MIN_VALUE) append(" · ${c.dbm} dBm")
                        append(" · $op")
                    }
                } else {
                    "当前信号不可用"
                }
            }
        }

        sectionTitle(ctx, parent, "显示")
        addRow(ctx, parent, com.lsp102.tapdock.R.drawable.ic_status_brightness, "屏幕亮度") { v, sub, _ ->
            val mode = runCatching {
                Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
            }.getOrDefault(0)
            val raw = runCatching {
                Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 0)
            }.getOrDefault(0)
            v.text = "${(raw * 100 / 255).coerceIn(0, 100)}%"
            sub.text = if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) "自动亮度" else "手动亮度"
        }

        sectionTitle(ctx, parent, "存储与内存")
        addRow(ctx, parent, com.lsp102.tapdock.R.drawable.ic_status_storage, "内部存储", true) { v, sub, bar ->
            val s = storage
            if (s == null || s.total <= 0) {
                v.text = "--"
                sub.text = "存储信息不可用"
                bar?.fraction = 0f
            } else {
                v.text = "${bytes(s.used)} / ${bytes(s.total)}"
                sub.text = "可用 ${bytes(s.avail)} · 已用 ${s.used * 100 / s.total}%"
                bar?.fraction = s.used.toFloat() / s.total
                bar?.color = accent
            }
        }
        addRow(ctx, parent, com.lsp102.tapdock.R.drawable.ic_status_memory, "运行内存", true) { v, sub, bar ->
            val m = mem
            if (m == null || m.total <= 0) {
                v.text = "--"
                sub.text = "内存信息不可用"
                bar?.fraction = 0f
            } else {
                v.text = "${bytes(m.used)} / ${bytes(m.total)}"
                sub.text = buildString {
                    append("可用 ${bytes(m.avail)} · 已用 ${m.used * 100 / m.total}%")
                    if (m.low) append(" · 内存紧张")
                }
                bar?.fraction = m.used.toFloat() / m.total
                bar?.color = when {
                    m.used.toFloat() / m.total > 0.9f -> BAR_CRIT
                    m.used.toFloat() / m.total > 0.8f -> BAR_WARN
                    else -> accent
                }
            }
        }

        sectionTitle(ctx, parent, "系统")
        addRow(ctx, parent, com.lsp102.tapdock.R.drawable.ic_status_clock, "系统时间") { v, sub, _ ->
            val now = Date()
            v.text = clockFmt.format(now)
            sub.text = dateFmt.format(now)
        }

        sectionTitle(ctx, parent, "连接")
        addRow(ctx, parent, com.lsp102.tapdock.R.drawable.ic_tile_bt, "蓝牙") { v, sub, _ ->
            @Suppress("DEPRECATION")
            val adapter = BluetoothAdapter.getDefaultAdapter()
            val on = adapter?.isEnabled == true
            v.text = when {
                adapter == null -> "不支持"
                on -> "已开启"
                else -> "已关闭"
            }
            sub.text = if (on) {
                val paired = runCatching { adapter?.bondedDevices?.size ?: -1 }.getOrNull() ?: -1
                if (paired > 0) "已配对 $paired 台设备" else "未配对设备"
            } else {
                "蓝牙适配器"
            }
        }
        addRow(ctx, parent, com.lsp102.tapdock.R.drawable.ic_tile_location, "定位服务") { v, sub, _ ->
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val on = runCatching { lm?.isLocationEnabled == true }.getOrDefault(false)
            v.text = if (on) "已开启" else "已关闭"
            @Suppress("DEPRECATION")
            val mode = runCatching {
                Settings.Secure.getInt(
                    ctx.contentResolver,
                    Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_OFF,
                )
            }.getOrDefault(Settings.Secure.LOCATION_MODE_OFF)
            sub.text = when (mode) {
                3 -> "高精度定位"
                2 -> "省电定位"
                1 -> "仅设备定位"
                else -> "定位已关闭"
            }
        }

        parent.addView(
            TextView(ctx).apply {
                text = "每秒自动刷新 · 点空白处关闭"
                setTextColor(TEXT_DIM)
                textSize = 10.5f
                includeFontPadding = false
                setPadding(dp(ctx, 2), dp(ctx, 10), 0, dp(ctx, 6))
            },
            matchWrap()
        )
    }

    private fun sectionTitle(ctx: Context, parent: LinearLayout, text: String) {
        parent.addView(
            TextView(ctx).apply {
                this.text = text
                setTextColor(TEXT_DIM)
                textSize = 12f
                includeFontPadding = false
                setPadding(dp(ctx, 2), 0, 0, dp(ctx, 8))
            },
            matchWrap().apply { topMargin = dp(ctx, 14) }
        )
    }

    /**
     * 一行状态：图标 + 标题 + 副标题 + 右侧读数；[withBar] 时底部再带一条进度条。
     * [update] 在每秒刷新时被调用。
     */
    private fun addRow(
        ctx: Context,
        parent: LinearLayout,
        iconRes: Int,
        title: String,
        withBar: Boolean = false,
        update: (TextView, TextView, BarView?) -> Unit,
    ) {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 10), dp(ctx, 9), dp(ctx, 10), dp(ctx, 9))
            background = GradientDrawable().apply {
                setColor(CARD_BG)
                cornerRadius = dp(ctx, 12).toFloat()
            }
        }
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val chip = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(0x0DFFFFFF)
                cornerRadius = dp(ctx, 10).toFloat()
            }
        }
        val icon = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(ModuleRes.drawable(ctx, iconRes))
            setColorFilter(TEXT_MAIN)
        }
        chip.addView(
            icon,
            FrameLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22), Gravity.CENTER)
        )
        line.addView(chip, LinearLayout.LayoutParams(dp(ctx, 34), dp(ctx, 34)))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val titleView = TextView(ctx).apply {
            text = title
            setTextColor(TEXT_MAIN)
            textSize = 13f
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val subView = TextView(ctx).apply {
            setTextColor(TEXT_DIM)
            textSize = 10.5f
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(titleView, matchWrap())
        col.addView(subView, matchWrap())
        line.addView(
            col,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(ctx, 10)
            }
        )
        val valueView = TextView(ctx).apply {
            setTextColor(TEXT_MAIN)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END
            maxWidth = dp(ctx, 150)
        }
        line.addView(
            valueView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(ctx, 8) }
        )
        card.addView(line, matchWrap())
        val bar = if (withBar) {
            BarView(ctx, accent).also {
                card.addView(
                    it,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 4)).apply {
                        topMargin = dp(ctx, 8)
                    }
                )
            }
        } else {
            null
        }
        parent.addView(card, matchWrap().apply { bottomMargin = dp(ctx, 8) })
        rows.add { update(valueView, subView, bar) }
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
        battery = readBattery(ctx)
        wifi = readWifi(ctx)
        cell = readCell(ctx)
        mem = readMemory(ctx)
        storage = readStorage()
        rows.forEach { it() }
    }

    private fun readBattery(ctx: Context): BatteryInfo? {
        val intent = try {
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Throwable) {
            null
        } ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return BatteryInfo(
            percent = (level * 100 / scale).coerceIn(0, 100),
            status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f,
            voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
            health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN),
        )
    }

    private fun readWifi(ctx: Context): WifiSnapshot? {
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        @Suppress("DEPRECATION")
        val enabled = runCatching { wm.isWifiEnabled }.getOrDefault(false)
        @Suppress("DEPRECATION")
        val ci = runCatching { wm.connectionInfo }.getOrNull()
        val ssid = ci?.ssid?.trim('"')
            ?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" && it != "0x" }
            ?: ""
        val rssi = ci?.rssi ?: Int.MIN_VALUE
        // 网络名可能因权限被抹掉，连接与否交给网络能力判断更可靠
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val connected = runCatching {
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }.getOrDefault(false)
        val level = if (rssi == Int.MIN_VALUE) -1 else signalLevel(rssi)
        return WifiSnapshot(enabled, connected, ssid, rssi, level)
    }

    private fun readCell(ctx: Context): CellSnapshot? {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        val simReady = runCatching {
            tm.simState == TelephonyManager.SIM_STATE_READY
        }.getOrDefault(false)
        @Suppress("DEPRECATION")
        val dataOn = runCatching { tm.isDataEnabled }.getOrDefault(false)
        val operator = runCatching { tm.networkOperatorName ?: "" }.getOrDefault("")
        @Suppress("DEPRECATION")
        val type = runCatching { tm.dataNetworkType }
            .getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        val ss = runCatching { tm.signalStrength }.getOrNull()
        val level = runCatching { ss?.level ?: -1 }.getOrDefault(-1)
        // dBm 在公开 SDK 里读不到（getDbm 被藏了），反射调一下，失败就当未知
        val dbm = runCatching {
            (ss?.javaClass?.getMethod("getDbm")?.invoke(ss) as? Int) ?: Int.MIN_VALUE
        }.getOrDefault(Int.MIN_VALUE)
        return CellSnapshot(simReady, dataOn, operator, networkTypeName(type), level, dbm)
    }

    private fun readMemory(ctx: Context): MemSnapshot? {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val mi = ActivityManager.MemoryInfo()
        return runCatching {
            am.getMemoryInfo(mi)
            MemSnapshot(mi.totalMem - mi.availMem, mi.totalMem, mi.availMem, mi.lowMemory)
        }.getOrNull()
    }

    private fun readStorage(): StorageSnapshot? = runCatching {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val total = stat.blockCountLong * stat.blockSizeLong
        val avail = stat.availableBlocksLong * stat.blockSizeLong
        StorageSnapshot(total - avail, total, avail)
    }.getOrNull()

    // ------------------------------------------------------------------ 格式化

    private fun chargeState(b: BatteryInfo): String = when (b.status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
        BatteryManager.BATTERY_STATUS_FULL -> "已充满"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
        else -> "未知"
    }

    private fun plugName(plugged: Int): String = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_AC -> "交流电"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线充电"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "底座"
        else -> "未连接充电器"
    }

    private fun healthName(health: Int): String = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
        BatteryManager.BATTERY_HEALTH_DEAD -> "损坏"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "电压过高"
        BatteryManager.BATTERY_HEALTH_COLD -> "温度过低"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "故障"
        else -> "未知"
    }

    private fun networkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_TD_SCDMA,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_IDEN,
        -> "3G"

        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_GSM,
        -> "2G"

        else -> "未知"
    }

    private fun signalLevel(dbm: Int): Int = when {
        dbm >= -50 -> 4
        dbm >= -60 -> 3
        dbm >= -70 -> 2
        dbm >= -80 -> 1
        else -> 0
    }

    private fun bytes(value: Long): String {
        val gb = value / 1073741824.0
        return if (gb >= 1.0) {
            String.format(Locale.US, "%.1f GB", gb)
        } else {
            String.format(Locale.US, "%.0f MB", value / 1048576.0)
        }
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
        appCtx = null
        showing = false
        closing = false
        XposedBridge.log("[TapDock] status sidebar hidden")
    }

    /** 屏幕当前刷新率：Unity 游戏没有可读的实测 FPS 时用这个兜底，副标题会注明 */
    private fun displayRefreshRate(ctx: Context): Float = try {
        // SystemUI 的应用上下文不一定带 Display，getDisplay() 可能为空甚至抛异常，
        // 再退到 WindowManager.defaultDisplay 取默认屏
        val fromContext = if (Build.VERSION.SDK_INT >= 30) {
            runCatching { ctx.display }.getOrNull()
        } else {
            null
        }
        @Suppress("DEPRECATION")
        val fallback = (ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.defaultDisplay
        (fromContext ?: fallback)?.refreshRate ?: 0f
    } catch (_: Throwable) {
        0f
    }

    // ------------------------------------------------------------------ 系统侧帧率

    /** SF dump 里游戏层那一行：`... requestedFrameRate: {30.00 Hz ...}` */
    private val REQUESTED_RATE = Regex("requestedFrameRate: \\{\\s*([0-9.]+)\\s*Hz")

    private var sfPkg = ""
    private var sfRate = 0f
    private var sfAt = 0L
    private var sfBusy = false

    /**
     * 从系统侧读游戏声明的目标帧率，取不到返回 0。
     *
     * 这台 ROM 关掉了逐层帧时间戳：`dumpsys SurfaceFlinger --latency` 对游戏层、状态栏、
     * 壁纸、甚至随手编的层名都只回一行刷新周期，没有任何帧时间戳；FrameTimeline 服务也不存在。
     * 所以"实测 FPS"在系统侧根本不存在。系统里唯一跟游戏帧率有关的就是 SurfaceView 层的
     * `requestedFrameRate` —— 游戏通过 ANativeWindow_setFrameRate 声明的目标帧率
     * （实测终末地是 30Hz）。SF 全量 dump 约 80KB，4 秒刷一次，只在主线程之外读。
     */
    private fun systemFrameRate(ctx: Context): Float {
        val pkg = GameInfoSource.foregroundPackage ?: return 0f
        val uid = try {
            ctx.packageManager.getApplicationInfo(pkg, 0).uid
        } catch (_: Throwable) {
            -1
        }
        val now = System.currentTimeMillis()
        if (!sfBusy && (pkg != sfPkg || now - sfAt > 4_000L)) {
            sfBusy = true
            sfPkg = pkg
            Thread {
                val rate = readRequestedRate(pkg, uid)
                main.post {
                    sfBusy = false
                    sfRate = rate
                    sfAt = System.currentTimeMillis()
                }
            }.apply { isDaemon = true }.start()
        }
        return if (pkg == sfPkg) sfRate else 0f
    }

    /**
     * 在 SF dump 里找该游戏 SurfaceView（BLAST）层，读它声明的帧率。
     *
     * SF 的层级树会把超长的层名截成 `com.hypergryph.e[...].u8.sdk.U8UnityContext`，
     * 所以按完整包名匹配不成立；改按 uid 认（同一行末尾就是 `pid=... uid=...`），
     * uid 拿不到时再退回包名前缀。
     */
    private fun readRequestedRate(pkg: String, uid: Int): Float {
        val text = dumpSurfaceFlinger() ?: return 0f
        val prefix = "SurfaceView[" + pkg.take(12)
        for (line in text.lineSequence()) {
            if (!line.contains("requestedFrameRate") || !line.contains("(BLAST)")) continue
            if (uid >= 0) {
                if (!line.contains("uid=$uid")) continue
            } else if (!line.contains(prefix)) {
                continue
            }
            val rate = REQUESTED_RATE.find(line)?.groupValues?.get(1)?.toFloatOrNull() ?: continue
            if (rate >= 1f) {
                android.util.Log.i("TapDockFps", "system frame rate for $pkg = $rate")
                return rate
            }
        }
        android.util.Log.w("TapDockFps", "no requestedFrameRate line for $pkg uid=$uid (dump ${text.length} chars)")
        return 0f
    }

    /**
     * 直接问 SurfaceFlinger 要状态。SystemUI 的 uid 实测持有 DUMP 和
     * ACCESS_SURFACE_FLINGER，不用 root；binder 走不通再退回 exec。
     */
    private fun dumpSurfaceFlinger(): String? {
        binderDump()?.takeIf { it.isNotBlank() }?.let { return it }
        return execDumpsys()
    }

    private fun binderDump(): String? = try {
        val sm = XposedHelpers.findClass("android.os.ServiceManager", null)
        val binder = XposedHelpers.callStaticMethod(sm, "getService", "SurfaceFlinger") as? IBinder
        if (binder == null) {
            android.util.Log.w("TapDockFps", "SurfaceFlinger binder null")
            null
        } else {
            val pipe = ParcelFileDescriptor.createPipe()
            val read = pipe[0]
            val write = pipe[1]
            Thread {
                try {
                    binder.javaClass
                        .getMethod(
                            "dump", FileDescriptor::class.java, Array<String>::class.java
                        )
                        .apply { isAccessible = true }
                        .invoke(binder, write.fileDescriptor, emptyArray<String>())
                } catch (t: Throwable) {
                    android.util.Log.w("TapDockFps", "binder dump call failed: $t")
                } finally {
                    runCatching { write.close() }
                }
            }.apply { isDaemon = true }.start()
            // 正常 80KB 一秒内写完；万一 SF 卡住就关掉两端让读退出，别把 sfBusy 永久锁住
            val watchdog = Thread {
                try {
                    Thread.sleep(3_000L)
                } catch (_: Throwable) {
                }
                runCatching { write.close() }
                runCatching { read.close() }
            }.apply { isDaemon = true }
            watchdog.start()
            val text = StringBuilder()
            val input = FileInputStream(read.fileDescriptor)
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    text.append(String(buf, 0, n, Charsets.UTF_8))
                }
            } catch (t: Throwable) {
                android.util.Log.w("TapDockFps", "binder pipe read failed: $t")
            } finally {
                runCatching { input.close() }
                runCatching { read.close() }
                runCatching { write.close() }
                watchdog.interrupt()
            }
            val s = text.toString()
            android.util.Log.i("TapDockFps", "binder dump ${s.length} chars")
            s
        }
    } catch (t: Throwable) {
        android.util.Log.w("TapDockFps", "binder dump failed: $t")
        null
    }

    /** binder 不通时的兜底：SystemUI 的 uid 有 DUMP，但 exec 可能被 SELinux 域名挡 */
    private fun execDumpsys(): String? = try {
        val p = Runtime.getRuntime().exec(arrayOf("dumpsys", "SurfaceFlinger"))
        val text = p.inputStream.bufferedReader().use { it.readText() }
        runCatching { p.errorStream.close() }
        runCatching { p.waitFor() }
        android.util.Log.i("TapDockFps", "exec dump ${text.length} chars")
        text
    } catch (t: Throwable) {
        android.util.Log.w("TapDockFps", "exec dump failed: $t")
        null
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
