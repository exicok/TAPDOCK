package com.lsp102.tapdock.xposed

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.TrafficStats
import android.net.wifi.WifiManager as WifiManagerAlias
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.doOnPreDraw
import com.lsp102.tapdock.CardSpec
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * 点击 Dock 弹出的控制中心：LineageOS 23.2（Android 16）QS 卡片磁贴风格。
 *  - 窗口贴屏幕顶部、水平居中，宽度 = 屏宽 - 左右各 16dp（不再跟着 Dock 摆位）
 *  - 双列网格，每项一个胶囊圆角卡片（图标 + 标签），宽度按权重等分铺满面板
 *  - 开启 = 系统强调色（取 SystemUI 主题的 colorAccent，跟随 ROM 主题色）
 *    关闭 = 中性色卡片（自动适配深/浅色模式）
 *  - 点整张卡片切换；点面板外部或再点 Dock 关闭
 *
 * 代码运行在 SystemUI 进程内，直接使用系统特权 API 控制各项开关；
 * 摄像头 / 麦克风走 Android 12+ 的 SensorPrivacyManager（反射调用）。
 * 图标来自模块 APK 资源（createPackageContext 加载）。
 */
object ControlPanel {

    private const val CORNER_PANEL_DP = 24
    private const val CORNER_TILE_DP = 26

    /** 整屏毛玻璃的模糊半径，面板和 Dock 共用同一层，所以只有一个值 */
    private const val BLUR_DP = 20

    /** 首帧 SurfaceControl 尚未就绪时的有界跨帧补发次数 */
    private const val SURFACE_BLUR_RETRY_FRAMES = 8

    // SensorPrivacyManager 的传感器常量（反射兜底硬编码）
    private const val SENSOR_MICROPHONE = 1
    private const val SENSOR_CAMERA = 2

    /** 面板与屏幕左右边缘各留出的间距 */
    private const val SIDE_MARGIN_DP = 16

    /** 面板距屏幕顶部（状态栏下方）的间距 */
    private const val TOP_MARGIN_DP = 16

    /** macOS 式开/关窗动画：从顶边中心缩放 + 淡入，末尾轻微回弹 */
    private const val OPEN_MS = 320L
    private const val CLOSE_MS = 200L
    private const val ANIM_SCALE = 0.82f
    private const val OPEN_OVERSHOOT = 1.4f

    private var panelView: LinearLayout? = null
    private var panelWm: WindowManager? = null

    /** 整屏毛玻璃层：铺在面板下面，只把 Dock 那块留清晰 */
    private var backdropBlurView: View? = null
    private var backdropBlurWm: WindowManager? = null
    private var backdropBlurAnchor: View? = null

    /** SurfaceControl 模糊已下发（只用于只打一次日志） */
    private var backdropBlurActive = false
    private var blurRetryRunnable: Runnable? = null
    private val main = Handler(Looper.getMainLooper())

    /** startTethering 要求一个 Executor；结果只有日志要用，就地跑即可 */
    private val DIRECT_EXECUTOR = Executor { it.run() }

    /** 面板内的音乐控制卡片引用（无媒体会话时为 null） */
    private var musicRefs: MusicRefs? = null

    /** 各卡片的状态回读，面板开着时每秒一次 */
    private var connectRefresh: List<() -> Unit> = emptyList()

    /** 关窗动画进行中：这段时间不再接受第二次 dismiss，也不让卡片响应点击 */
    private var closing = false

    /** 卡片顺序（App 里拖出来，经 HookDock 的三级配置通道下发） */
    private var cardDefs: List<TileDef> = emptyList()

    /** 多进度显示区：下载按应用各占一行，行数随并发下载数变化 */
    private var progressBox: LinearLayout? = null
    private val progressRows = mutableListOf<ProgressRow>()
    private var progressKeys: List<String> = emptyList()

    /** 状态区：蜂窝信号 / 电量 / WLAN 信号 / 网络上下行 / 当前页面帧率，面板开着时跟着 1 秒节拍刷新 */
    private class StatusRefs(
        val cell: TextView,
        val battery: TextView,
        val wifi: TextView,
        val traffic: TextView,
        val fps: TextView,
    )

    private var statusRefs: StatusRefs? = null

    /** 状态区的 CPU 全核心图（App 里开了才有） */
    private var cpuChart: CpuChartView? = null
    private var cpuLogged = false

    /** TrafficStats 给的是开机累计值，速率要自己按两次采样的间隔差分 */
    private var trafficAt = 0L
    private var trafficTx = 0L
    private var trafficRx = 0L

    /** 蜂窝信号读不到（多半是宿主进程没 READ_PHONE_STATE）就不再每秒撞一次 */
    private var cellProbeDead = false

    private class ProgressRow(
        val key: String,
        val label: TextView,
        val row: LinearLayout,
        val fill: NyanFillView,
        val value: TextView,
        var percent: Int = -1,
    )

    /**
     * 填充用 scaleX 而不是改 layoutParams.width：
     * 后者在 onLayoutChange 里 requestLayout 会被当前布局忽略，只能等下一次刷新（1 秒）才显示；
     * scaleX 是纯绘制属性，建面板时就能算准，和面板同帧出现。
     */
    private fun applyProgressFill(r: ProgressRow) {
        if (r.percent < 0) return
        r.fill.pivotX = 0f
        r.fill.scaleX = r.percent / 100f
    }

    private class MusicRefs(
        val card: LinearLayout,
        val box: LinearLayout,
        val track: FrameLayout,
        val fill: View,
        val playBtn: ImageView,
        val title: TextView,
        val artist: TextView,
        val art: ImageView,
        val posText: TextView,
        val durText: TextView,
    ) {
        var controller: MediaController? = null
    }

    private val musicTick = object : Runnable {
        override fun run() {
            val ctx = panelView?.context
            try {
                refreshMusicCard()
                if (ctx != null) {
                    refreshProgressRows(ctx)
                    refreshStatus(ctx)
                }
                connectRefresh.forEach { it() }
            } catch (t: Throwable) {
                XposedBridge.log("[TapDock] panel tick error: $t")
            }
            if (panelView != null) main.postDelayed(this, 1000)
        }
    }

    // 手电筒状态（TorchCallback 维护）
    private var torchOn = false
    private var torchCameraId: String? = null
    private var torchRegistered = false

    // SensorPrivacyManager 反射缓存
    private var spmInstance: Any? = null
    private var spmGet: java.lang.reflect.Method? = null
    private var spmSet: java.lang.reflect.Method? = null

    // TetheringManager（热点）反射缓存
    private var tmInstance: Any? = null
    private var tmStart: java.lang.reflect.Method? = null
    private var tmStop: java.lang.reflect.Method? = null
    private var tmWifiType = 0

    private class TileDef(
        val spec: CardSpec,
        val isOn: () -> Boolean,
        val setOn: (Boolean) -> Unit,
    )

    /** 一套配色，拖动换序重建网格时整批卡片共用 */
    private class CardStyle(
        val accent: Int,
        val cardBg: Int,
        val chipOff: Int,
        val iconOff: Int,
        val contentOn: Int,
        val contentOff: Int,
    )

    /** 点击 Dock：打开 / 关闭控制中心 */
    fun toggle(anchor: View) {
        if (panelView != null) {
            dismiss()
        } else {
            show(anchor)
        }
    }

    /** 关面板：先播 macOS 式关窗动画（缩回 + 淡出），动画跑完才真正移除窗口 */
    fun dismiss() {
        if (closing) return
        val panel = panelView
        val backdrop = backdropBlurView
        closing = true
        main.removeCallbacks(musicTick)
        connectRefresh = emptyList()
        backdrop?.animate()?.alpha(0f)?.setDuration(CLOSE_MS)?.start()
        if (panel == null) {
            removePanelViews()
            return
        }
        panel.animate()
            .alpha(0f)
            .scaleX(ANIM_SCALE)
            .scaleY(ANIM_SCALE)
            .setDuration(CLOSE_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { removePanelViews() }
            .start()
    }

    private fun removePanelViews() {
        closing = false
        HookDock.setPanelOpen(false)
        cancelBackdropBlurRetry()
        musicRefs = null
        progressBox = null
        progressRows.clear()
        statusRefs = null
        cpuChart = null
        cpuLogged = false
        trafficAt = 0L
        backdropBlurActive = false

        val panel = panelView
        val backdrop = backdropBlurView
        panelView = null
        backdropBlurView = null
        backdropBlurAnchor = null
        try {
            if (panel != null) panelWm?.removeView(panel)
        } catch (_: Throwable) {
        }
        try {
            if (backdrop != null) backdropBlurWm?.removeView(backdrop)
        } catch (_: Throwable) {
        }
        panelWm = null
        backdropBlurWm = null
    }

    private fun show(anchor: View) {
        try {
            val ctx = anchor.context.applicationContext
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val panel = buildPanel(ctx)

            val sideMargin = dp(ctx, SIDE_MARGIN_DP)
            val lp = WindowManager.LayoutParams(
                ctx.resources.displayMetrics.widthPixels - sideMargin * 2,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                // 不加 FLAG_LAYOUT_IN_SCREEN，所以 y 的起点就是状态栏下沿：贴屏幕顶部、又不压住状态栏
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(ctx, TOP_MARGIN_DP)
            }

            panel.setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) dismiss()
                false
            }

            // Add the transparent layer first, then the panel. For equal window types,
            // the later window is above it, so the panel itself is not covered.
            val backdrop = View(ctx).apply {
                isClickable = false
                isFocusable = false
            }
            val backdropLp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }
            wm.addView(backdrop, backdropLp)
            backdropBlurView = backdrop
            backdropBlurWm = wm
            backdropBlurAnchor = anchor

            // 首帧之前先设成"小且透明"，免得面板以最终尺寸闪一帧
            panel.alpha = 0f
            panel.scaleX = ANIM_SCALE
            panel.scaleY = ANIM_SCALE
            wm.addView(panel, lp)
            panelView = panel
            panelWm = wm
            // 面板展开期间 Dock 收成一颗返回图标
            HookDock.setPanelOpen(true)
            panel.doOnPreDraw {
                // 以顶边中心为原点撑开，像 macOS 从标题栏位置展开窗口
                panel.pivotX = panel.width / 2f
                panel.pivotY = 0f
                panel.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(OPEN_MS)
                    .setInterpolator(OvershootInterpolator(OPEN_OVERSHOOT))
                    .start()
            }
            if (Build.VERSION.SDK_INT >= 31) {
                // 系统窗口级实时模糊：首帧绘制之前下发到遮罩窗口的 SurfaceControl，
                // 事务由 RenderThread 随这一帧提交，所以面板第一次可见就是糊的。
                // 刻意不用 FLAG_BLUR_BEHIND 兜底：它要等 WMS 把窗口当作"已绘制"后的下一次
                // relayout 才下发，正是用户看到的 1~3 秒延时。
                backdrop.doOnPreDraw { applyBackdropBlurWithRetry(backdrop) }
                // 面板改尺寸（进度行增减）不影响模糊区，但 Dock 收起/展开会动排除区，
                // 那边由 HookDock 的布局回调来补发。
            }
            main.post(musicTick)
            XposedBridge.log("[TapDock] control panel shown")
        } catch (t: Throwable) {
            removePanelViews()
            XposedBridge.log("[TapDock] control panel show failed: $t")
        }
    }

    // ------------------------------------------------------------------ 面板（Lineage QS 卡片风格）

    private fun buildPanel(ctx: Context): LinearLayout {
        registerTorch(ctx)
        sensorPrivacyManager(ctx)
        tetheringManager(ctx)

        val night = (ctx.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        // 留出透明度，让窗口级背景模糊透出来
        val panelBg = if (night) 0xB3141418.toInt() else 0xB3F1F2F6.toInt()
        val accent = systemAccent(ctx)
        val inactiveBg = if (night) 0xFF2A2B2F.toInt() else 0xFFE2E4EA.toInt()
        val chipOff = if (night) 0xFF3A3B40.toInt() else 0xFFCFD2D9.toInt()
        val iconOff = if (night) 0xFF9A9CA3.toInt() else 0xFF75787F.toInt()
        val contentOn = contrastOn(accent)
        val contentOff = if (night) 0xFFD8D9DD.toInt() else 0xFF2A2C31.toInt()

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(panelBg)
                cornerRadius = dp(ctx, CORNER_PANEL_DP).toFloat()
            }
            setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 6))
            // 进度条上的猫比那几 dp 高的轨道大得多，整条链都要放开裁剪
            clipChildren = false
            clipToPadding = false
        }

        // 音乐控制卡片（无正在播放的媒体会话时自动隐藏）
        addMusicCard(box, night, accent)

        // 8 个开关统一成同一种卡片：双列网格，顺序由 App 里的排序界面决定
        val bySpec = CardSpec.values().associateWith { spec ->
            when (spec) {
                CardSpec.BT -> TileDef(spec, { bluetoothOn() }, { setBluetooth(it) })
                CardSpec.WIFI -> TileDef(spec, { wifiOn(ctx) }, { setWifi(ctx, it) })
                CardSpec.HOTSPOT -> TileDef(spec, { hotspotOn(ctx) }, { setHotspot(it) })
                CardSpec.DATA -> TileDef(spec, { mobileDataOn(ctx) }, { setMobileData(ctx, it) })
                CardSpec.LOCATION -> TileDef(spec, { locationOn(ctx) }, { setLocation(ctx, it) })
                CardSpec.TORCH -> TileDef(spec, { torchOn }, { setTorch(ctx, it) })
                CardSpec.CAMERA -> TileDef(
                    spec, { sensorUsable(SENSOR_CAMERA) }, { setSensorPrivacy(SENSOR_CAMERA, !it) }
                )
                CardSpec.MIC -> TileDef(
                    spec, { sensorUsable(SENSOR_MICROPHONE) },
                    { setSensorPrivacy(SENSOR_MICROPHONE, !it) }
                )
            }
        }
        cardDefs = CardSpec.ordered(HookDock.cardOrder()).map { bySpec.getValue(it) }
        addCardGrid(
            box,
            CardStyle(accent, inactiveBg, chipOff, iconOff, contentOn, contentOff),
        )

        // 状态区：控制区和进度区之间的一条实时读数
        addStatusSection(box, night, contentOff, accent)

        // 进度区：控制区下方的独立区域（下载 / 音乐 / 充电，没有进度的源自动收起）
        addProgressSection(box, night, accent)
        return box
    }

    /** 双列网格：按 cardDefs（App 里拖出来的顺序）铺卡片 */
    private fun addCardGrid(parent: LinearLayout, style: CardStyle) {
        val ctx = parent.context
        val refreshers = mutableListOf<() -> Unit>()

        cardDefs.chunked(2).forEach { rowDefs ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            rowDefs.forEachIndexed { i, def ->
                row.addView(
                    buildCard(ctx, def, style, refreshers),
                    LinearLayout.LayoutParams(0, dp(ctx, 52), 1f).apply {
                        if (i < rowDefs.size - 1) marginEnd = dp(ctx, 8)
                    }
                )
            }
            if (rowDefs.size == 1) {
                row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            }
            parent.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 8) }
            )
        }
        connectRefresh = refreshers
    }

    /**
     * 一张卡片：圆角图标块 + 标题 + 状态副标题。卡体恒为中性色，开关状态只落在图标块上
     * （关闭 = 灰块灰图标，开启 = 强调色块 + 对比色图标）。点卡片切开关，长按跳该开关的设置页。
     */
    private fun buildCard(
        ctx: Context,
        def: TileDef,
        style: CardStyle,
        refreshers: MutableList<() -> Unit>,
    ): LinearLayout {
        val chipSize = dp(ctx, 32)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(ctx, 8), 0, dp(ctx, 6), 0)
            background = GradientDrawable().apply {
                setColor(style.cardBg)
                cornerRadius = dp(ctx, CORNER_TILE_DP).toFloat()
            }
        }
        val icon = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = (chipSize - dp(ctx, 19)) / 2
            setPadding(pad, pad, pad, pad)
            // 必须走模块资源上下文：SystemUI 资源表里没有这些 ID
            ModuleRes.drawable(ctx, def.spec.iconRes)?.let { setImageDrawable(it) }
        }
        card.addView(icon, LinearLayout.LayoutParams(chipSize, chipSize))

        val title = TextView(ctx).apply {
            text = def.spec.label
            textSize = 13f
            maxLines = 1
            setTextColor(style.contentOff)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val sub = TextView(ctx).apply {
            textSize = 11f
            maxLines = 1
            setTextColor(style.contentOff and 0x00FFFFFF or 0xB3000000.toInt())
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(title)
        texts.addView(sub)
        card.addView(
            texts,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(ctx, 8) }
        )

        val refresh = {
            val on = try {
                def.isOn()
            } catch (_: Throwable) {
                false
            }
            icon.background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 11).toFloat()
                setColor(if (on) style.accent else style.chipOff)
            }
            icon.setColorFilter(if (on) style.contentOn else style.iconOff)
            sub.text = if (on) "已开启" else "已关闭"
        }
        card.setOnClickListener {
            if (closing) return@setOnClickListener
            val cur = try {
                def.isOn()
            } catch (_: Throwable) {
                false
            }
            try {
                def.setOn(!cur)
            } catch (t: Throwable) {
                XposedBridge.log("[TapDock] toggle ${def.spec.label} failed: $t")
                val msg = ModuleRes.string(
                    ctx, com.lsp102.tapdock.R.string.toast_toggle_failed
                ) ?: "该开关不可用（权限不足或设备不支持）"
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
            // 热点是异步起的，这里读到的多半还是旧值；每秒一次的 tick 会把它纠正过来
            refresh()
        }
        card.setOnLongClickListener {
            if (closing) return@setOnLongClickListener false
            // 面板自己就是 overlay 窗口，不先收掉它会压在设置页上面
            dismiss()
            if (!def.spec.openSettings(ctx)) {
                XposedBridge.log("[TapDock] no settings page for ${def.spec.label}")
            }
            true
        }
        refresh()
        refreshers += refresh
        return card
    }


    /** 读取 SystemUI 主题的强调色（与 ROM 快捷设置磁贴的颜色一致） */
    private fun systemAccent(ctx: Context): Int {
        return try {
            val ta = ctx.theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorAccent))
            val color = ta.getColor(0, 0xFF1B6EF3.toInt())
            ta.recycle()
            color
        } catch (_: Throwable) {
            0xFF1B6EF3.toInt()
        }
    }

    /** 强调色上该用深色还是浅色前景：本 ROM 的 colorAccent 是浅青色，白字会糊成一片 */
    private fun contrastOn(bg: Int): Int {
        val lum = 0.299 * (bg shr 16 and 0xFF) + 0.587 * (bg shr 8 and 0xFF) +
            0.114 * (bg and 0xFF)
        return if (lum > 150) 0xFF1B1B1F.toInt() else 0xFFFFFFFF.toInt()
    }

    // ------------------------------------------------------------------ 各开关实现（SystemUI 特权）

    private fun wifiOn(ctx: Context): Boolean = wifiManager(ctx).isWifiEnabled

    @Suppress("DEPRECATION")
    private fun setWifi(ctx: Context, on: Boolean) {
        wifiManager(ctx).setWifiEnabled(on)
    }

    private fun wifiManager(ctx: Context): WifiManager =
        ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Suppress("DEPRECATION")
    private fun bluetoothOn(): Boolean =
        BluetoothAdapter.getDefaultAdapter()?.isEnabled == true

    @Suppress("DEPRECATION")
    private fun setBluetooth(on: Boolean) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (on) adapter.enable() else adapter.disable()
    }

    private fun telephony(ctx: Context): TelephonyManager =
        ctx.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private fun mobileDataOn(ctx: Context): Boolean = telephony(ctx).isDataEnabled

    private fun setMobileData(ctx: Context, on: Boolean) {
        telephony(ctx).setDataEnabled(on)
    }

    private fun locationOn(ctx: Context): Boolean {
        val mode = Settings.Secure.getInt(
            ctx.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF
        )
        return mode != Settings.Secure.LOCATION_MODE_OFF
    }

    private fun setLocation(ctx: Context, on: Boolean) {
        Settings.Secure.putInt(
            ctx.contentResolver,
            Settings.Secure.LOCATION_MODE,
            if (on) Settings.Secure.LOCATION_MODE_HIGH_ACCURACY else Settings.Secure.LOCATION_MODE_OFF
        )
    }

    // ------------------------------------------------------------------ 热点（TetheringManager）

    /** 状态读 SoftAp：`WifiManager.isWifiApEnabled()`（本 ROM 上是 @hide，只能反射） */
    private fun hotspotOn(ctx: Context): Boolean =
        wifiManager(ctx).call("isWifiApEnabled") == true

    /**
     * 开关走 SystemUI 自己那颗热点磁贴的同一条路：
     * `TetheringManager.startTethering(TETHERING_WIFI, executor, callback)` /
     * `.stopTethering(TETHERING_WIFI)`。这两个方法在 SDK 里都是 @hide；
     * 本 ROM 的 `tether(String)` / `untether(String)` 已被 unsupportedAfterV 废掉，别用。
     */
    private fun setHotspot(on: Boolean) {
        val tm = tmInstance ?: throw IllegalStateException("TetheringManager unavailable")
        val m = (if (on) tmStart else tmStop)
            ?: throw IllegalStateException("tethering call unavailable")
        if (on) m.invoke(tm, tmWifiType, DIRECT_EXECUTOR, hotspotResultCallback())
        else m.invoke(tm, tmWifiType)
    }

    private fun tetheringManager(ctx: Context) {
        if (tmInstance != null) return
        try {
            val cls = Class.forName("android.net.TetheringManager")
            tmWifiType = cls.getField("TETHERING_WIFI").getInt(null)
            tmInstance = ctx.applicationContext
                // Context.TETHERING_SERVICE 在公开 SDK 里是 @hide，服务名实测为 "tethering"
                .getSystemService("tethering")
            tmStart = cls.methods.firstOrNull {
                it.name == "startTethering" && it.parameterCount == 3 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            tmStop = cls.methods.firstOrNull {
                it.name == "stopTethering" && it.parameterCount == 1
            }
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] TetheringManager unavailable: $t")
        }
    }

    /** StartTetheringCallback 在本 ROM 是接口，用 Proxy 接住结果写日志（失败码只有这里能看到） */
    private fun hotspotResultCallback(): Any? {
        val cls = try {
            Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        } catch (_: Throwable) {
            return null
        }
        return Proxy.newProxyInstance(cls.classLoader, arrayOf(cls)) { _, m, args ->
            when (m.name) {
                "onTetheringStarted" -> {
                    XposedBridge.log("[TapDock] hotspot started")
                    null
                }
                "onTetheringFailed" -> {
                    XposedBridge.log("[TapDock] hotspot start failed: code=${args?.getOrNull(0)}")
                    null
                }
                "equals" -> false
                "hashCode" -> 0
                else -> "HotspotResultCallback"
            }
        }
    }

    // ------------------------------------------------------------------ 手电筒

    private fun registerTorch(ctx: Context) {
        if (torchRegistered) return
        val cm = ctx.applicationContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return
        try {
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(id: String, enabled: Boolean) {
                    if (torchCameraId == null) torchCameraId = id
                    if (id == torchCameraId) torchOn = enabled
                }
            }, Handler(Looper.getMainLooper()))
            torchRegistered = true
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] torch callback failed: $t")
        }
    }

    private fun setTorch(ctx: Context, on: Boolean) {
        val cm = ctx.applicationContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return
        val id = torchCameraId
            ?: try {
                cm.cameraIdList.firstOrNull()
            } catch (_: Throwable) {
                null
            } ?: return
        cm.setTorchMode(id, on)
        torchOn = on
    }

    // ------------------------------------------------------------------ 摄像头 / 麦克风（SensorPrivacyManager）

    /** 磁贴状态 = 隐私开关的反面：隐私关闭 = 可用（开） */
    private fun sensorUsable(sensor: Int): Boolean = !(sensorPrivacyEnabled(sensor) ?: true)

    private fun setSensorPrivacy(sensor: Int, privacyOn: Boolean) {
        val m = spmSet ?: throw IllegalStateException("SensorPrivacyManager unavailable")
        m.invoke(spmInstance, sensor, privacyOn)
    }

    private fun sensorPrivacyEnabled(sensor: Int): Boolean? {
        val instance = spmInstance ?: return null
        val m = spmGet ?: return null
        return try {
            m.invoke(instance, sensor) as Boolean
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] sensor state failed: $t")
            null
        }
    }

    private fun sensorPrivacyManager(ctx: Context) {
        if (spmInstance != null) return
        try {
            val cls = Class.forName("android.hardware.SensorPrivacyManager")
            spmInstance = cls.getMethod("getInstance", Context::class.java).invoke(null, ctx)
            spmGet = cls.methods.firstOrNull {
                it.name == "isSensorPrivacyEnabled" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            spmSet = cls.methods.firstOrNull {
                it.name == "setSensorPrivacy" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] SensorPrivacyManager unavailable: $t")
        }
    }

    private fun cancelBackdropBlurRetry() {
        blurRetryRunnable?.let { retry ->
            backdropBlurView?.removeCallbacks(retry)
        }
        blurRetryRunnable = null
    }

    /**
     * 首帧 preDraw 时 SurfaceControl 可能还未就绪，scheduleApply 期间 surface 也可能重建，
     * 导致 RenderThread 回调直接丢弃事务。连续跨几帧补发，确保首次可见前后稳定带上模糊。
     */
    private fun applyBackdropBlurWithRetry(backdrop: View) {
        cancelBackdropBlurRetry()
        scheduleBackdropBlurRetry(
            backdrop,
            SURFACE_BLUR_RETRY_FRAMES - 1,
            applyBackdropBlur(backdrop),
        )
    }

    private fun scheduleBackdropBlurRetry(
        backdrop: View,
        framesLeft: Int,
        anyRequested: Boolean,
    ) {
        if (framesLeft <= 0 || backdropBlurView !== backdrop) {
            if (!anyRequested) XposedBridge.log("[TapDock] window blur unavailable after retries")
            return
        }
        val retry = object : Runnable {
            override fun run() {
                if (blurRetryRunnable !== this) return
                blurRetryRunnable = null
                if (backdropBlurView !== backdrop) return
                scheduleBackdropBlurRetry(
                    backdrop,
                    framesLeft - 1,
                    applyBackdropBlur(backdrop, logFailure = false) || anyRequested,
                )
            }
        }
        blurRetryRunnable = retry
        backdrop.postOnAnimation(retry)
    }

    /**
     * Dock 几何变了（收起/展开的宽度补间、图标增减改变胶囊宽度）时重算排除区，
     * 否则模糊区按旧位置留一块清晰矩形。面板没开就是空操作。
     */
    fun refreshBackdropBlur() {
        val backdrop = backdropBlurView ?: return
        applyBackdropBlur(backdrop, logFailure = false)
    }

    /**
     * 整屏一层实时毛玻璃，下发到遮罩窗口的 SurfaceControl（SystemUI 自己 BlurUtils.applyBlur
     * 那条路：SyncRtSurfaceTransactionApplier + setBlurRegions，RenderThread 随帧生效）。
     *
     * 面板不再自带一层模糊：两层矩形模糊互相裁切，接缝正好落在面板四个角上，看起来就是
     * "角上的糊和别处不同步"。现在面板和 Dock 一样，只靠自身半透明底色浮在这层毛玻璃上，
     * 材质由同一个半径决定。
     *
     * 只有 Dock 那块保持清晰（图标糊了就不能看），而胶囊是圆角、排除区只能是矩形，
     * 所以四角各补一个方块进模糊区——方块与胶囊重叠的部分是纯色底，糊了看不出来。
     */
    private fun applyBackdropBlur(backdrop: View, logFailure: Boolean = true): Boolean {
        if (Build.VERSION.SDK_INT < 31 || backdropBlurView !== backdrop) return false
        val root = backdrop.rootView ?: return false
        if (root.width <= 0 || root.height <= 0) return false
        return try {
            val viewRootImpl = root.call("getViewRootImpl") ?: return false
            val sc = viewRootImpl.call("getSurfaceControl") as? android.view.SurfaceControl
                ?: return false
            if (!sc.isValid) return false

            val rootLoc = IntArray(2)
            root.getLocationOnScreen(rootLoc)
            val radiusPx = dp(backdrop.context, BLUR_DP)
            val regions = mutableListOf<FloatArray>()

            val anchor = backdropBlurAnchor
            val dock = Rect()
            if (anchor != null && anchor.width > 0 && anchor.height > 0) {
                val anchorLoc = IntArray(2)
                anchor.getLocationOnScreen(anchorLoc)
                val left = anchorLoc[0] - rootLoc[0]
                val top = anchorLoc[1] - rootLoc[1]
                dock.set(left, top, left + anchor.width, top + anchor.height)
                val corner = minOf(
                    dp(backdrop.context, HookDock.DOCK_CORNER_DP),
                    anchor.width / 2,
                    anchor.height / 2,
                )
                if (corner > 0) {
                    listOf(
                        Rect(left, top, left + corner, top + corner),
                        Rect(dock.right - corner, top, dock.right, top + corner),
                        Rect(left, dock.bottom - corner, left + corner, dock.bottom),
                        Rect(dock.right - corner, dock.bottom - corner, dock.right, dock.bottom),
                    ).forEach { regions += blurRegion(radiusPx, it) }
                }
            }

            val visible = Region(0, 0, root.width, root.height)
            if (dock.width() > 0 && dock.height() > 0) {
                visible.op(Region(dock), Region.Op.DIFFERENCE)
            }
            val iterator = RegionIterator(visible)
            val rect = Rect()
            while (iterator.next(rect)) regions += blurRegion(radiusPx, rect)
            val blurRegions = regions.toTypedArray()

            val transactionClass = Class.forName("android.view.SurfaceControl\$Transaction")
            val transaction = transactionClass.getConstructor().newInstance()
            val setBlurRegions = transactionClass.methods.firstOrNull {
                it.name == "setBlurRegions" && it.parameterCount == 2
            } ?: throw NoSuchMethodException("SurfaceControl.Transaction.setBlurRegions")
            setBlurRegions.invoke(transaction, sc, blurRegions)

            val builder = Class.forName(
                "android.view.SyncRtSurfaceTransactionApplier\$SurfaceParams\$Builder"
            ).getConstructor(android.view.SurfaceControl::class.java).newInstance(sc)
            builder.require("withMergeTransaction", transaction)
            builder.require("withBackgroundBlurRadius", radiusPx)
            builder.call("withBackgroundBlurScale", 1f)
            builder.call("withOpaque", false)
            val params = builder.require("build") ?: return false

            val applier = Class.forName("android.view.SyncRtSurfaceTransactionApplier")
                .getConstructor(View::class.java).newInstance(root)
            val arr = java.lang.reflect.Array.newInstance(params.javaClass, 1)
            java.lang.reflect.Array.set(arr, 0, params)
            applier.require("scheduleApply", arr)

            if (!backdropBlurActive) {
                XposedBridge.log("[TapDock] backdrop region blur applied (${regions.size} regions)")
            }
            backdropBlurActive = true
            true
        } catch (t: Throwable) {
            if (logFailure && !backdropBlurActive) {
                XposedBridge.log("[TapDock] backdrop region blur unavailable: $t")
            }
            false
        }
    }

    /**
     * SurfaceFlinger 的模糊区布局（frameworks/native ui/BlurRegion，Android 16 每个区 10 个 float）：
     * 模糊半径、不透明度、left/top/right/bottom，最后是左上/右上/左下/右下四个圆角半径。
     */
    private fun blurRegion(radiusPx: Int, rect: Rect) = floatArrayOf(
        radiusPx.toFloat(),
        1f,
        rect.left.toFloat(),
        rect.top.toFloat(),
        rect.right.toFloat(),
        rect.bottom.toFloat(),
        0f, 0f, 0f, 0f,
    )

    /** @hide API 的签名各版本有出入，按方法名 + 参数个数匹配 */
    private fun Any.call(name: String, vararg args: Any?): Any? =
        javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
            ?.invoke(this, *args)

    /** 只要求方法存在；void 方法 invoke 返回 null 是正常的 */
    private fun Any.require(name: String, vararg args: Any?): Any? {
        val m = javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
            ?: throw NoSuchMethodException("$name with ${args.size} args")
        return m.invoke(this, *args)
    }

    // ------------------------------------------------------------------ 状态区

    /**
     * 控制区与进度区之间的一块实时读数：一行五格等分（蜂窝信号 / 电量 / WLAN 信号 / 上下行速率 /
     * 当前页面帧率），App 里开了 CPU 图再补一行全核心竖柱。
     * 文案压到"标签 + 短值"是因为一格只有 ~48dp，长一点的单位就溢出成省略号了。
     */
    private fun addStatusSection(parent: LinearLayout, night: Boolean, content: Int, accent: Int) {
        val ctx = parent.context
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(if (night) 0xFF26262C.toInt() else 0xFFE4E6EC.toInt())
                cornerRadius = dp(ctx, 18).toFloat()
            }
            setPadding(dp(ctx, 10), dp(ctx, 7), dp(ctx, 10), dp(ctx, 7))
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val refs = List(5) {
            TextView(ctx).apply {
                textSize = 11f
                maxLines = 1
                gravity = Gravity.CENTER
                setTextColor(content)
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        }
        refs.forEach {
            row.addView(
                it,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        box.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        statusRefs = StatusRefs(refs[0], refs[1], refs[2], refs[3], refs[4])
        cpuChart = if (HookDock.cpuChart()) addCpuRow(box, ctx, night, content, accent) else null
        parent.addView(
            box,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 6) }
        )
        if (cpuChart != null) {
            // 重新起一段：不然第一次读数会把"面板关着的这段时间"平均进来
            CpuSource.reset()
        }
        // 建面板时就取一次值，和进度区一样跟着控制中心同帧出现
        refreshStatus(ctx)
        XposedBridge.log("[TapDock] status area: " + refs.joinToString(" | ") { it.text.toString() })
    }

    /** 状态区的 CPU 那一行：标签 + 占满剩余宽度的全核心竖柱，返回画布好每秒喂数据 */
    private fun addCpuRow(
        box: LinearLayout,
        ctx: Context,
        night: Boolean,
        content: Int,
        accent: Int,
    ): CpuChartView {
        val chart = CpuChartView(ctx).apply {
            setColors(if (night) 0x33FFFFFF else 0x1F000000, accent)
        }
        val label = TextView(ctx).apply {
            text = "CPU"
            textSize = 11f
            maxLines = 1
            setTextColor(content and 0x00FFFFFF or 0xB3000000.toInt())
        }
        val cpuRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cpuRow.addView(
            label,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        cpuRow.addView(
            chart,
            LinearLayout.LayoutParams(0, dp(ctx, 16), 1f).apply { marginStart = dp(ctx, 6) }
        )
        box.addView(
            cpuRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 6) }
        )
        return chart
    }

    private fun refreshStatus(ctx: Context) {
        val refs = statusRefs ?: return
        refs.cell.text = "蜂窝 ${cellLevelText(ctx)}"
        refs.battery.text = batteryText(ctx)
        refs.wifi.text = "WLAN ${wifiLevelText(ctx)}"
        refs.traffic.text = trafficText()
        // 一次 tick 只取一次：sample() 会消费掉这一段的帧数窗口
        refs.fps.text = "帧率 ${FpsMonitor.sample()}"
        val view = cpuChart ?: return
        val cores = CpuSource.sample()
        view.setValues(cores)
        if (!cpuLogged && cores.isNotEmpty()) {
            cpuLogged = true
            XposedBridge.log(
                "[TapDock] cpu ${cores.size} cores: " + cores.joinToString(" ") { "${it.toInt()}%" }
            )
        }
    }

    /** 0~4 格，取 @hide 的 SignalStrength.getLevel()；无权限或无服务时留空而不是骗人 */
    private fun cellLevelText(ctx: Context): String {
        if (cellProbeDead) return "--"
        return try {
            val level = telephony(ctx).call("getSignalStrength")?.call("getLevel") as? Int
            when (level) {
                null -> "--"
                0 -> "无服务"
                else -> "$level/4"
            }
        } catch (t: Throwable) {
            cellProbeDead = true
            XposedBridge.log("[TapDock] cell signal probe failed: $t")
            "--"
        }
    }

    /** 电量走 ACTION_BATTERY_CHANGED 的粘性广播：这条通道不要任何权限 */
    private fun batteryText(ctx: Context): String {
        val sticky = try {
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Throwable) {
            null
        } ?: return "电量 --"
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        if (level < 0) return "电量 --"
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return "电量 ${level * 100 / scale}" + if (charging) "% 充电" else "%"
    }

    private fun wifiLevelText(ctx: Context): String {
        val wm = wifiManager(ctx)
        if (!wm.isWifiEnabled) return "关闭"
        // getRssi 不在定位权限的脱敏字段里，SSID 才是；这里只取信号
        val info = try {
            @Suppress("DEPRECATION")
            wm.connectionInfo
        } catch (_: Throwable) {
            null
        }
        val rssi = info?.rssi ?: Int.MIN_VALUE
        if (rssi <= -110) return "未连接"
        return "${WifiManager.calculateSignalLevel(rssi, 5)}/4"
    }

    /** 全网卡累计字节差分出来的瞬时速率 */
    private fun trafficText(): String {
        val tx = TrafficStats.getTotalTxBytes()
        val rx = TrafficStats.getTotalRxBytes()
        val now = SystemClock.elapsedRealtime()
        val first = trafficAt == 0L
        val dt = if (first) 0f else (now - trafficAt) / 1000f
        val down = if (first) 0L else rx - trafficRx
        val up = if (first) 0L else tx - trafficTx
        trafficAt = now
        trafficTx = tx
        trafficRx = rx
        if (dt <= 0f) return "↓ -- ↑ --"
        return "↓ ${fmtRate(down / dt)} ↑ ${fmtRate(up / dt)}"
    }

    private fun fmtRate(bytesPerSec: Float): String {
        val v = if (bytesPerSec < 0f) 0f else bytesPerSec
        return when {
            v >= 1_048_576f -> "%.1fM".format(v / 1_048_576f)
            v >= 1_024f -> "%.0fK".format(v / 1_024f)
            else -> "${v.toInt()}B"
        }
    }

    // ------------------------------------------------------------------ 多进度显示区

    /** 下载行的 key 前缀：一个下载器应用一行 */
    private const val DL_PREFIX = "dl:"

    private fun addProgressSection(parent: LinearLayout, night: Boolean, accent: Int) {
        val ctx = parent.context
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            background = GradientDrawable().apply {
                setColor(if (night) 0xFF26262C.toInt() else 0xFFE4E6EC.toInt())
                cornerRadius = dp(ctx, 18).toFloat()
            }
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 4))
        }
        progressBox = box
        progressRows.clear()
        progressKeys = emptyList()
        parent.addView(
            box,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 6) }
        )
        // 建面板时就同步取一次值：进度区必须和控制中心同帧出现，而不是等第一次刷新
        refreshProgressRows(ctx)
    }

    private fun newProgressRow(ctx: Context, night: Boolean, accent: Int): ProgressRow {
        val textColor = if (night) 0xFFD8D9DD.toInt() else 0xFF3A3C42.toInt()
        val radius = dp(ctx, 3).toFloat()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        val label = TextView(ctx).apply {
            textSize = 12f
            maxLines = 1
            setTextColor(textColor)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(
            label,
            LinearLayout.LayoutParams(dp(ctx, 64), LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        val track = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(if (night) 0x33FFFFFF else 0x1F000000)
                cornerRadius = radius
            }
            clipChildren = false
        }
        row.addView(track, LinearLayout.LayoutParams(0, dp(ctx, 6), 1f))
        val fill = NyanFillView(ctx).apply {
            setSolidColor(accent)
            setCornerRadius(radius)
            setNyan(HookDock.nyanStyle())
            // 面板里行高只有 6dp、行距 7dp，猫刻意比 Dock 小一号，免得压到上下相邻行
            setCat(HookDock.scaledCat(ctx, dp(ctx, 14)))
        }
        track.addView(
            fill,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val value = TextView(ctx).apply {
            textSize = 12f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(textColor)
        }
        row.addView(
            value,
            LinearLayout.LayoutParams(dp(ctx, 40), LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        return ProgressRow("", label, row, fill, value)
    }

    /**
     * 进度区：下载按应用各一行（进度高的在前），再音乐、再充电。
     * 行数和顺序只在集合真的变了时才重排，否则每秒只是改改文字和填充。
     */
    private fun refreshProgressRows(ctx: Context) {
        val box = progressBox ?: return
        val wanted = LinkedHashMap<String, Int>()
        HookDock.downloadEntries(ctx).forEach { wanted[DL_PREFIX + it.first] = it.second }
        // 每个源只取一次：musicProgress / chargeProgress 背后都是 binder 调用
        HookDock.musicProgress(ctx)?.let { wanted["音乐"] = it }
        HookDock.chargeProgress(ctx)?.let { wanted["充电"] = it }
        val keys = wanted.keys.toList()

        if (keys != progressKeys) {
            val night = (ctx.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val accent = systemAccent(ctx)
            val dead = progressRows.filter { it.key !in wanted }
            dead.forEach { box.removeView(it.row) }
            progressRows.removeAll(dead)
            keys.forEach { key ->
                if (progressRows.none { it.key == key }) {
                    val r = newProgressRow(ctx, night, accent)
                    progressRows.add(ProgressRow(key, r.label, r.row, r.fill, r.value))
                }
            }
            box.removeAllViews()
            keys.forEach { key ->
                box.addView(
                    progressRows.first { it.key == key }.row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(ctx, 7) }
                )
            }
            progressKeys = keys
        }

        for (r in progressRows) {
            val pct = wanted[r.key] ?: continue
            r.label.text = r.key.removePrefix(DL_PREFIX)
            r.value.text = "$pct%"
            if (pct != r.percent) {
                r.percent = pct
                applyProgressFill(r)
            }
        }
        box.visibility = if (progressRows.isEmpty()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ 音乐控制卡片

    /** 取正在播放的媒体会话；无播放中的取第一个；完全没有返回 null */
    private fun pickSession(ctx: Context): MediaController? {
        val msm = ctx.applicationContext
            .getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return null
        val list = try {
            msm.getActiveSessions(null)
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] getActiveSessions failed: $t")
            null
        } ?: return null
        return list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull()
    }

    private fun addMusicCard(box: LinearLayout, night: Boolean, accent: Int) {
        val controller = pickSession(box.context) ?: return
        val card = buildMusicCard(box.context, box, controller, night, accent)
        box.addView(
            card,
            0,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(box.context, 10) }
        )
        musicRefs = card.tag as MusicRefs
    }

    private fun buildMusicCard(
        ctx: Context,
        box: LinearLayout,
        controller: MediaController,
        night: Boolean,
        accent: Int,
    ): LinearLayout {
        val cardBg = if (night) 0xFF26262C.toInt() else 0xFFE4E6EC.toInt()
        val titleColor = if (night) 0xFFEDEDED.toInt() else 0xFF1B1B1F.toInt()
        val artistColor = if (night) 0xFF9A9AA5.toInt() else 0xFF5A5C63.toInt()
        val artBg = if (night) 0xFF33343A.toInt() else 0xFFC9CCD4.toInt()

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cardBg)
                cornerRadius = dp(ctx, 18).toFloat()
            }
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
        }

        val art = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 48), dp(ctx, 48))
            background = GradientDrawable().apply {
                setColor(artBg)
                cornerRadius = dp(ctx, 8).toFloat()
            }
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER_CROP
            val d = loadArt(ctx, controller.metadata)
            if (d == null) {
                scaleType = ImageView.ScaleType.CENTER
                ModuleRes.drawable(ctx, com.lsp102.tapdock.R.drawable.ic_music_note)
                    ?.let { setImageDrawable(it) }
                setColorFilter(artistColor)
            } else {
                setImageDrawable(d)
            }
        }

        val meta = controller.metadata
        val title = TextView(ctx).apply {
            text = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: ModuleRes.string(ctx, com.lsp102.tapdock.R.string.music_unknown_title)
                ?: "未知曲目"
            textSize = 14f
            setTextColor(titleColor)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val artist = TextView(ctx).apply {
            text = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: ModuleRes.string(ctx, com.lsp102.tapdock.R.string.music_unknown_artist)
                ?: "未知艺术家"
            textSize = 12f
            setTextColor(artistColor)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(ctx, 10) }
        }
        texts.addView(title)
        texts.addView(artist)

        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(art)
        top.addView(texts)
        card.addView(top)

        val track = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 4)
            ).apply { topMargin = dp(ctx, 12) }
            background = GradientDrawable().apply {
                setColor(0x33FFFFFF)
                cornerRadius = 2f
            }
        }
        val fill = View(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
            background = GradientDrawable().apply {
                setColor(accent)
                cornerRadius = 2f
            }
        }
        track.addView(fill)
        card.addView(track)

        // 进度条下方左右各一个时间：当前播放位置 / 总时长
        val posText = TextView(ctx).apply {
            textSize = 11f
            setTextColor(artistColor)
        }
        val durText = TextView(ctx).apply {
            textSize = 11f
            setTextColor(artistColor)
            gravity = Gravity.END
        }
        val times = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        times.addView(
            posText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        times.addView(
            durText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        card.addView(
            times,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 5) }
        )
        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        posText.text = fmtTime(controller.playbackState?.position ?: 0L)
        durText.text = if (duration > 0L) fmtTime(duration) else "--:--"

        fun ctrl(iconRes: Int, sizeDp: Int, onClick: (MediaController) -> Unit): ImageView {
            return ImageView(ctx).apply {
                // 必须走模块资源上下文：setImageResource 会解析到 SystemUI 的资源表上
                ModuleRes.drawable(ctx, iconRes)?.let { setImageDrawable(it) }
                setColorFilter(titleColor)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
                isClickable = true
                setOnClickListener {
                    musicRefs?.controller?.let(onClick)
                }
            }
        }
        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 6) }
        }
        controls.addView(ctrl(com.lsp102.tapdock.R.drawable.ic_ctrl_prev, 26) {
            it.transportControls.skipToPrevious()
        })
        val play = ctrl(com.lsp102.tapdock.R.drawable.ic_ctrl_pause, 34) { c ->
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
                c.transportControls.pause()
            } else {
                c.transportControls.play()
            }
        }
        play.layoutParams = LinearLayout.LayoutParams(dp(ctx, 34), dp(ctx, 34))
            .apply {
                marginStart = dp(ctx, 28)
                marginEnd = dp(ctx, 28)
            }
        controls.addView(play)
        controls.addView(ctrl(com.lsp102.tapdock.R.drawable.ic_ctrl_next, 26) {
            it.transportControls.skipToNext()
        })
        card.addView(controls)

        card.tag = MusicRefs(card, box, track, fill, play, title, artist, art, posText, durText)
            .apply {
                this.controller = controller
            }
        return card
    }

    private fun refreshMusicCard() {
        val refs = musicRefs ?: return
        val ctx = refs.card.context
        val fresh = pickSession(ctx)
        if (fresh == null) {
            refs.card.visibility = View.GONE
            return
        }
        refs.card.visibility = View.VISIBLE
        if (fresh.sessionToken != refs.controller?.sessionToken) {
            // 正在播放的会话变了：重挂整个音乐卡片（刷新封面与文案）
            refs.box.removeView(refs.card)
            musicRefs = null
            addMusicCard(refs.box, true, 0xFF1B6EF3.toInt())
            return
        }
        val st = refs.controller?.playbackState
        val meta = refs.controller?.metadata
        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val playing = st?.state == PlaybackState.STATE_PLAYING
        val pos = when {
            st == null -> 0L
            playing -> st.position +
                ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed)
                .toLong()
            else -> st.position
        }.coerceIn(0L, duration.coerceAtLeast(1L))
        val pct = if (duration > 0L) ((pos * 100) / duration).toInt().coerceIn(0, 100) else 0
        refs.posText.text = fmtTime(pos)
        refs.durText.text = if (duration > 0L) fmtTime(duration) else "--:--"
        val trackW = refs.track.width
        if (trackW > 0) {
            refs.fill.layoutParams = FrameLayout.LayoutParams(
                (trackW * pct) / 100, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        ModuleRes.drawable(
            ctx,
            if (playing) com.lsp102.tapdock.R.drawable.ic_ctrl_pause
            else com.lsp102.tapdock.R.drawable.ic_ctrl_play
        )?.let { refs.playBtn.setImageDrawable(it) }
    }

    /** 专辑封面：优先位图，其次封面 URI；失败返回 null（用音符占位） */
    private fun loadArt(ctx: Context, meta: MediaMetadata?): android.graphics.drawable.Drawable? {
        meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let {
            return BitmapDrawable(ctx.resources, it)
        }
        meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let {
            return BitmapDrawable(ctx.resources, it)
        }
        val uriStr = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: return null
        return try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            ctx.contentResolver.openInputStream(Uri.parse(uriStr))?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            }?.let { BitmapDrawable(ctx.resources, it) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    /** 毫秒 → m:ss，超过一小时补上小时位 */
    private fun fmtTime(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
