package com.lsp102.tapdock.xposed

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.AppOpsManager
import android.app.Notification
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Hook 侧 Dock：往任务栏（状态栏 / 12L 任务栏）布局里注入一个黑色胶囊条，
 * 内容左右排布在同一行：
 *   1. 状态图标（仅激活的项显示）：
 *      WiFi(已连接) / 蓝牙(已连接设备) / 定位(实际占用中) / VPN(连接中,钥匙图标) /
 *      摄像头(实际占用中) / 麦克风(实际占用中)
 *      —— WiFi/蓝牙/定位/VPN 的原生图标已从系统状态栏隐藏，改由 Dock 接管显示
 *   2. 进度条：下载 / 音乐 / 充电（同一套渲染代码，按此优先级取用）
 *      摄像头 / 麦克风占用中时整条底色转红（原生隐私胶囊一并隐藏）
 *
 * 点击 Dock 弹出 Lineage QS 风格控制中心（见 ControlPanel）。
 *
 * 配置同步三重保障：
 *   1. App 修改 → HOOK_RELOAD 广播直接携带配置 extra（即时）
 *   2. Hook 侧每 3 秒轮询 App 的 DockConfigProvider（广播丢失也生效）
 *   3. XSharedPreferences 仅作为 SystemUI 重启后初始状态的兜底
 */
object HookDock {

    const val ACTION_RELOAD = "com.lsp102.tapdock.HOOK_RELOAD"
    const val ACTION_ACTIVE = "com.lsp102.tapdock.HOOK_ACTIVE"
    const val EXTRA_SHOW = "show"
    const val EXTRA_ALIGN = "align"
    const val EXTRA_PCOLOR = "pcolor"
    const val EXTRA_DL = "dlpkgs"
    const val EXTRA_ORDER = "card_order"
    const val EXTRA_NYAN = "nyan"
    const val EXTRA_ALBUM = "album"
    const val EXTRA_THUMB = "thumb"
    const val EXTRA_CPU = "cpu"
    const val ALIGN_LEFT = "left"
    const val ALIGN_RIGHT = "right"

    const val MODULE_PKG = "com.lsp102.tapdock"
    private const val TAG_ROOT = "tapdock_dock_root"
    private const val PREFS_FILE = "dock_prefs"

    // 基础外观
    private const val BG_ALPHA_PERCENT = 80
    private const val BG_COLOR_NORMAL = 0x000000
    private const val BG_COLOR_PRIVACY = 0xFFD32F2F.toInt()
    /** ControlPanel 要按这个半径给 Dock 四角补模糊，见 ControlPanel.applyBackdropBlur */
    const val DOCK_CORNER_DP = 12
    private const val MIN_WIDTH_STATUS_DP = 122
    private const val MIN_WIDTH_TASKBAR_DP = 156
    private const val ICON_STATUS_DP = 13
    private const val ICON_TASKBAR_DP = 18
    private const val ICON_GAP_STATUS_DP = 4
    private const val ICON_GAP_TASKBAR_DP = 6
    private const val TRACK_H_STATUS_DP = 4
    private const val TRACK_H_TASKBAR_DP = 6
    private const val POLL_MS = 3000L
    private const val CLOCK_MS = 1000L
    private const val BADGE_COLOR = 0x40FFFFFF

    /** 模糊封面上再压一层半透明黑，保证白色图标和文字仍可读 */
    private const val ALBUM_SCRIM = 0xA6000000.toInt()

    /** Dock 收成返回图标 / 放回原体积的补间时长 */
    private const val DOCK_ANIM_MS = 220L

    /** Material 的 fast-out-slow-in：FastOutSlowInInterpolator 在公开 SDK 里是 @hide，直接用同一条贝塞尔 */
    private val DOCK_INTERPOLATOR = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    // 需要从系统状态栏隐藏、改由 Dock 接管显示的图标槽位
    private val HIDDEN_SLOTS = setOf("wifi", "bluetooth", "location", "vpn")

    /** iconRes 里从这一格起是隐私类（摄像头 / 麦克风）：播放封面时它们也不让位 */
    private const val PRIVACY_ICON_FROM = 4

    // 摄像头/麦克风占用时出现的原生隐私胶囊（aapt2 确认 id 为 privacy_chip / privacy_container，
    // 视图类 OngoingPrivacyChip），同样由 Dock 接管：只在视图层隐藏，
    // 不动 PrivacyItemController 的回调列表，见 PrivacyMonitor 的说明
    private val HIDDEN_PRIVACY_VIEWS = setOf("ongoingprivacychip", "privacy_chip", "privacy_container")

    // 判断"蓝牙已连接"用到的 profile（只用公开常量；GATT 太宽，BLE 后台连接会常年点亮，排除）
    private val BT_PROFILES = intArrayOf(
        BluetoothProfile.HEADSET,
        BluetoothProfile.A2DP,
        BluetoothProfile.HID_DEVICE,
        BluetoothProfile.SAP,
        BluetoothProfile.HEARING_AID,
    )

    private val main = Handler(Looper.getMainLooper())
    private var prefs: XSharedPreferences? = null
    private var receiverRegistered = false
    private var tickerStarted = false

    /** Dock 此刻是否在显示时钟（决定时钟要不要继续跳秒） */
    private var clockVisible = false

    private val CLOCK_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var statusIconsHooked = false
    private var notifHooked = false

    /** 活动通知集合（pkg:id），驱动 Dock 最左侧的数字徽标 */
    private val activeNotifs = HashSet<String>()
    private var notifSeeded = false
    private var lastNotifCount = -1
    private var appContextRef: WeakReference<Context>? = null
    private val bindings = mutableListOf<Binding>()

    /** 最新配置（广播 extra / Provider 轮询写入，优先于 XSharedPreferences） */
    private val overrideShow = AtomicReference<Boolean>(null)
    private val overrideAlignRight = AtomicReference<Boolean>(null)
    private val overrideColor = AtomicReference<Int>(null)
    private val overrideDl = AtomicReference<Set<String>?>(null)

    /** 控制中心卡片顺序（App 里拖出来的；null = 还没收到，退回读 prefs 文件） */
    private val overrideOrder = AtomicReference<List<String>?>(null)

    /** 进度条彩虹猫风格（null = 还没收到，退回读 prefs 文件） */
    private val overrideNyan = AtomicReference<Boolean?>(null)

    /** Dock 背景是否用模糊专辑封面（同上） */
    private val overrideAlbum = AtomicReference<Boolean?>(null)

    /** 进度条左侧的封面小图开关（同上） */
    private val overrideThumb = AtomicReference<Boolean?>(null)

    /** 状态区的 CPU 全核心图开关（同上） */
    private val overrideCpu = AtomicReference<Boolean?>(null)
    private var lastPercent: Int? = null
    private var lastStates: BooleanArray? = null

    /** 上一次是否因为播放封面而让位了状态图标，切换它要重排整条 Dock */
    private var lastArtShown: Boolean? = null
    private var lastColor: Int? = null

    /** 上一次下发的彩虹猫开关状态 */
    private var lastNyan = false
    private var lastPrivacyWarn = false

    /** 下载器通知进度：pkg -> 0..100（NotificationListener 管线） */
    private val downloadMap = HashMap<String, Int>()

    /** pkg -> 应用名，解析一次就缓存（每次刷新查 PackageManager 没必要） */
    private val appLabels = HashMap<String, String>()

    private class DockViews(
        val root: LinearLayout,
        val track: FrameLayout,
        val fill: NyanFillView,
        val icons: List<ImageView>,
        val art: ImageView,
        val clock: TextView,
        val badge: TextView,
        val back: ImageView,
        val pill: GradientDrawable,
        val minWidth: Int,
        val collapsedWidth: Int,
    )

    private class Binding(
        val root: LinearLayout,
        val track: FrameLayout,
        val fill: NyanFillView,
        val icons: List<ImageView>,
        val art: ImageView,
        val clock: TextView,
        val badge: TextView,
        val back: ImageView,
        val pill: GradientDrawable,
        val minWidth: Int,
        val collapsedWidth: Int,
        parent: ViewGroup,
        /** FrameLayout 重力注入时为 true（靠左/靠右通过改重力切换） */
        val isFrame: Boolean,
    ) {
        val parentRef = WeakReference(parent)

        /** 展开状态下的实测宽度，收起动画结束后按它放回去 */
        var naturalWidth = 0

        /** 当前模糊封面背景 + 它对应的曲目标识（换曲才重算一次） */
        var artBg: Drawable? = null
        var artToken: String? = null

        /** 进度条左侧的封面小图位图 + 它对应的曲目标识（存在 Binding.art 这个 ImageView 里） */
        var thumbBmp: Bitmap? = null
        var thumbToken: String? = null

        var anim: ValueAnimator? = null
    }

    private fun loadPrefs(): XSharedPreferences? {
        if (prefs == null) {
            prefs = try {
                XSharedPreferences(MODULE_PKG, PREFS_FILE)
            } catch (t: Throwable) {
                XposedBridge.log("[TapDock] XSharedPreferences failed: $t")
                null
            }
        }
        try {
            prefs?.reload()
        } catch (_: Throwable) {
        }
        return prefs
    }

    private fun alignRight(): Boolean {
        overrideAlignRight.get()?.let { return it }
        return loadPrefs()?.getString("dock_align", ALIGN_RIGHT) != ALIGN_LEFT
    }

    private fun enabled(): Boolean {
        overrideShow.get()?.let { return it }
        return loadPrefs()?.getBoolean("hook_enabled", true) ?: true
    }

    private fun progressColor(): Int {
        overrideColor.get()?.let { return it }
        return loadPrefs()?.getInt("progress_color", 0xFFFFFFFF.toInt()) ?: 0xFFFFFFFF.toInt()
    }

    /** 下载进度跟踪的应用包名集合；null = 全部应用 */
    private fun downloadAllowed(): Set<String>? {
        overrideDl.get()?.let { return it.ifEmpty { null } }
        val raw = loadPrefs()?.getString("download_packages", "") ?: ""
        val set = raw.split('|').filter { it.isNotBlank() }.toSet()
        return set.ifEmpty { null }
    }

    /**
     * 摄像头 / 麦克风正在被占用 → Dock 底色转红警示。
     * 定位不参与：本 ROM 的 PrivacyItemController.locationAvailable 恒为 false，
     * 拿不到"正在被定位"，用开关态又会常年红。
     */
    private fun privacyWarn(): Boolean = PrivacyMonitor.camera || PrivacyMonitor.microphone

    private fun dockBackgroundColor(): Int {
        val base = if (privacyWarn()) BG_COLOR_PRIVACY else BG_COLOR_NORMAL
        return (base and 0x00FFFFFF) or ((BG_ALPHA_PERCENT * 255 / 100) shl 24)
    }

    // ------------------------------------------------------------------ 注入

    fun injectIntoLinearLayout(parent: LinearLayout, compact: Boolean) {
        try {
            if (parent.findViewWithTag<View>(TAG_ROOT) != null) return
            val context = parent.context
            val v = build(context, compact)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER_VERTICAL }
            val index = if (alignRight()) parent.childCount else 0
            parent.addView(v.root, index, lp)
            synchronized(bindings) {
                bindings.add(Binding(v.root, v.track, v.fill, v.icons, v.art, v.clock, v.badge, v.back, v.pill, v.minWidth,
                    v.collapsedWidth, parent, isFrame = false))
            }
            ensureReceiver(context)
            notifyActive(context)
            XposedBridge.log("[TapDock] dock injected (compact=$compact)")
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] inject failed: $t")
        }
    }

    fun injectIntoFrameLayout(parent: ViewGroup, gravity: Int, compact: Boolean) {
        try {
            if (parent.findViewWithTag<View>(TAG_ROOT) != null) return
            val context = parent.context
            val v = build(context, compact)
            val vertical = gravity and Gravity.VERTICAL_GRAVITY_MASK
            val horizontal = if (alignRight()) Gravity.END else Gravity.START
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                horizontal or vertical
            )
            parent.addView(v.root, lp)
            synchronized(bindings) {
                bindings.add(Binding(v.root, v.track, v.fill, v.icons, v.art, v.clock, v.badge, v.back, v.pill, v.minWidth,
                    v.collapsedWidth, parent, isFrame = true))
            }
            ensureReceiver(context)
            notifyActive(context)
            XposedBridge.log("[TapDock] dock injected (frame, compact=$compact)")
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] inject(frame) failed: $t")
        }
    }

    // ------------------------------------------------------------------ 视图

    private fun build(context: Context, compact: Boolean): DockViews {
        val root = LinearLayout(context)
        root.tag = TAG_ROOT
        // 左右排布：状态图标 + 进度条同一行
        root.orientation = LinearLayout.HORIZONTAL
        root.gravity = Gravity.CENTER_VERTICAL
        root.minimumWidth = dp(context, if (compact) MIN_WIDTH_STATUS_DP else MIN_WIDTH_TASKBAR_DP)
        val bg = GradientDrawable().apply {
            setColor(dockBackgroundColor())
            cornerRadius = dp(context, DOCK_CORNER_DP).toFloat()
        }
        root.background = bg
        // 控制中心展开时，遮罩的清晰排除区按 Dock 当前位置算；胶囊一变形就得重算，
        // 否则旧位置会留下一块清晰矩形。
        root.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob ->
            if (l != ol || t != ot || r != or || b != ob) ControlPanel.refreshBackdropBlur()
        }
        // 猫画在彩虹带上方，会越出 Dock 自己的边界
        root.clipChildren = false
        val padH = dp(context, 8)
        val padV = dp(context, if (compact) 2 else 6)
        root.setPadding(padH, padV, padH, padV)

        // 状态图标排最左：仅激活的项显示
        // WiFi/蓝牙/定位/VPN = 接管自系统状态栏；摄像头/麦克风 = 实际占用时
        val iconSize = dp(context, if (compact) ICON_STATUS_DP else ICON_TASKBAR_DP)
        val gap = dp(context, if (compact) ICON_GAP_STATUS_DP else ICON_GAP_TASKBAR_DP)
        val iconRes = intArrayOf(
            com.lsp102.tapdock.R.drawable.ic_tile_wifi,
            com.lsp102.tapdock.R.drawable.ic_tile_bt,
            com.lsp102.tapdock.R.drawable.ic_tile_location,
            com.lsp102.tapdock.R.drawable.ic_tile_vpn,
            com.lsp102.tapdock.R.drawable.ic_tile_camera,
            com.lsp102.tapdock.R.drawable.ic_tile_mic,
        )
        val iconsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icons = iconRes.map { res ->
            val iv = ImageView(context).apply {
                // 必须用模块自己的资源上下文加载（SystemUI 资源表里没有这些 ID）
                ModuleRes.drawable(context, res)?.let { setImageDrawable(it) }
                setColorFilter(0xFFFFFFFF.toInt())
                visibility = View.GONE
            }
            iv.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                if (res != iconRes.last()) marginEnd = gap
            }
            iconsRow.addView(iv)
            iv
        }
        // 控制中心展开时唯一留下的返回图标（平时隐藏，Dock 同时缩到最小宽度）
        val back = ImageView(context).apply {
            visibility = View.GONE
            ModuleRes.drawable(context, com.lsp102.tapdock.R.drawable.ic_dock_back)?.let {
                setImageDrawable(it)
            }
            setColorFilter(0xFFFFFFFF.toInt())
        }
        root.addView(back, LinearLayout.LayoutParams(iconSize, iconSize))

        // 最左侧：通知数圆形徽标，0 条时不占位
        val badgeSize = dp(context, if (compact) 15 else 20)
        val badge = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = if (compact) 9f else 11f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            includeFontPadding = false
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BADGE_COLOR)
            }
        }
        root.addView(
            badge,
            LinearLayout.LayoutParams(badgeSize, badgeSize).apply { marginEnd = gap }
        )
        root.addView(iconsRow)

        // 进度条左侧的封面小图：只有播放中且开关打开才占位，平时整格让给图标 / 时钟
        val art = ImageView(context).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(art, LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = gap })

        // 进度轨道 + 填充（下载/充电共用同一套渲染）：占图标右侧剩余宽度
        val trackH = dp(context, if (compact) TRACK_H_STATUS_DP else TRACK_H_TASKBAR_DP)
        val track = FrameLayout(context).apply {
            // 彩虹猫模式下猫比这条几 dp 高的轨道大得多，得让它溢出显示
            clipChildren = false
            clipToPadding = false
        }
        track.layoutParams = LinearLayout.LayoutParams(0, trackH, 1f)
        track.background = GradientDrawable().apply {
            setColor(0x26FFFFFF)
            cornerRadius = (trackH / 2f)
        }

        val fill = NyanFillView(context).apply {
            setSolidColor(0xFFFFFFFF.toInt())
            setCornerRadius(trackH / 2f)
        }
        fill.layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
        track.addView(fill)

        // 猫骑在彩虹带前端、画在带的上方：轨道只有 trackH 那几 dp 高，塞进去必然被裁
        fill.setCat(scaledCat(context, dp(context, if (compact) 20 else 26)))
        root.addView(track)

        // Dock 空着（既没图标也没进度条）时显示时钟；等宽字体免得秒跳时宽度抖
        val clock = TextView(context).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = if (compact) 11f else 13f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        root.addView(
            clock,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        root.visibility = if (enabled()) View.VISIBLE else View.GONE

        // 全圆角（胶囊形）：布局完成后把圆角设为高度的一半
        root.post {
            // 直接改 pill 自己：背景随时可能被换成封面 BitmapDrawable，不能再靠 as? 转型
            try {
                bg.cornerRadius = (root.height.coerceAtMost(root.width)) / 2f
            } catch (_: Throwable) {
            }
        }

        // 点击 Dock：打开 / 关闭控制中心（面板贴屏幕顶部居中，见 ControlPanel）
        root.isClickable = true
        root.setOnClickListener { ControlPanel.toggle(root) }
        return DockViews(
            root, track, fill, icons, art, clock, badge, back, bg, root.minimumWidth,
            padH * 2 + iconSize
        )
    }

    // ------------------------------------------------------------------ 实时刷新

    private fun ensureReceiver(context: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        val appCtx = context.applicationContext
        appContextRef = WeakReference(appCtx)
        // 摄像头/麦克风：接入 SystemUI 隐私指示器数据源（并隐藏右上角原生图标）
        PrivacyMonitor.start(appCtx.classLoader, appCtx) {
            lastStates = null
            applyContent()
        }
        // WiFi/蓝牙/定位/VPN：从系统状态栏隐藏，改由 Dock 显示
        hideStatusBarIcons(appCtx)
        // 下载进度：监听 SystemUI 通知管线里的下载器通知
        startNotificationWatch(appCtx)
        // 状态图标：接真实事件源，变化当帧刷新（轮询退化为兜底）
        startStateWatchers(appCtx)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent == null) return
                if (intent.hasExtra(EXTRA_SHOW)) {
                    overrideShow.set(intent.getBooleanExtra(EXTRA_SHOW, true))
                }
                if (intent.hasExtra(EXTRA_ALIGN)) {
                    overrideAlignRight.set(intent.getStringExtra(EXTRA_ALIGN) != ALIGN_LEFT)
                }
                if (intent.hasExtra(EXTRA_PCOLOR)) {
                    overrideColor.set(intent.getIntExtra(EXTRA_PCOLOR, 0xFFFFFFFF.toInt()))
                }
                if (intent.hasExtra(EXTRA_DL)) {
                    overrideDl.set(
                        intent.getStringExtra(EXTRA_DL)
                            ?.split('|')?.filter { it.isNotBlank() }?.toSet()
                    )
                }
                if (intent.hasExtra(EXTRA_ORDER)) {
                    overrideOrder.set(
                        intent.getStringExtra(EXTRA_ORDER)
                            ?.split(',')?.filter { it.isNotBlank() }
                    )
                }
                if (intent.hasExtra(EXTRA_NYAN)) {
                    overrideNyan.set(intent.getBooleanExtra(EXTRA_NYAN, false))
                    main.post { applyContent() }
                }
                if (intent.hasExtra(EXTRA_ALBUM)) {
                    overrideAlbum.set(intent.getBooleanExtra(EXTRA_ALBUM, true))
                    main.post { applyContent() }
                }
                if (intent.hasExtra(EXTRA_CPU)) {
                    overrideCpu.set(intent.getBooleanExtra(EXTRA_CPU, false))
                }
                if (intent.hasExtra(EXTRA_THUMB)) {
                    overrideThumb.set(intent.getBooleanExtra(EXTRA_THUMB, true))
                    // 封面一收一放会改变整条宽度，作废状态缓存立刻重排
                    lastStates = null
                    main.post { applyContent() }
                }
                main.post { rebuildAll() }
            }
        }
        val filter = IntentFilter(ACTION_RELOAD)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                appCtx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appCtx.registerReceiver(receiver, filter)
            }
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] register receiver failed: $t")
            receiverRegistered = false
        }
        startTicker()
    }

    // ------------------------------------------------------------------ 状态图标事件源

    private var watchersStarted = false

    /**
     * WiFi/蓝牙/定位/VPN 接系统事件源，状态一改立刻重算图标；
     * 摄像头/麦克风由 [PrivacyMonitor] 直接回调，3 秒轮询只作为丢事件时的兜底。
     */
    private fun startStateWatchers(ctx: Context) {
        if (watchersStarted) return
        watchersStarted = true
        watchNetwork(ctx)
        watchBluetooth(ctx)
        XposedBridge.log("[TapDock] status icon watchers started")
    }

    /** 事件回调可能在 binder 线程，统一回主线程刷视图 */
    private fun refreshIcons() {
        main.post { applyContent() }
    }

    /** WiFi 连接/断开、VPN 建立/断开都会走到这里（transports 之间是或关系） */
    private fun watchNetwork(ctx: Context) {
        val cm = connectivity(ctx) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshIcons()
            override fun onLost(network: Network) = refreshIcons()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                refreshIcons()
        }
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .build()
            cm.registerNetworkCallback(request, callback)
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] network watcher failed: $t")
        }
    }

    private fun watchBluetooth(ctx: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED,
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_ACL_DISCONNECTED,
                    -> refreshIcons()
                }
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // 仅监听系统受保护广播，NOT_EXPORTED 不给第三方应用伪造的机会
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver, filter)
            }
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] bluetooth watcher failed: $t")
        }
    }

    private fun startTicker() {
        if (tickerStarted) return
        tickerStarted = true
        main.post(ticker)
        main.post(clockTick)
    }

    /** 时钟单独 1 秒一跳：配置轮询是 3 秒，不能拿它当时钟节拍 */
    private val clockTick = object : Runnable {
        override fun run() {
            if (clockVisible) {
                updateClock(synchronized(bindings) { bindings.toList() }, true)
            }
            main.postDelayed(this, CLOCK_MS)
        }
    }

    private fun updateClock(snapshot: List<Binding>, show: Boolean) {
        clockVisible = show
        if (!show) {
            snapshot.forEach { it.clock.visibility = View.GONE }
            return
        }
        val text = CLOCK_FORMAT.format(Date())
        snapshot.forEach {
            it.clock.visibility = View.VISIBLE
            it.clock.text = text
        }
    }

    /** 定时兜底：轮询 Provider 配置 + 刷新状态图标与进度 */
    private val ticker = object : Runnable {
        override fun run() {
            try {
                fetchRemoteState()
                applyContent()
            } catch (t: Throwable) {
                XposedBridge.log("[TapDock] tick error: $t")
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    /** 从 App 的 ContentProvider 拉取配置（App 进程不在时静默跳过） */
    private fun fetchRemoteState() {
        val ctx = appContextRef?.get() ?: return
        try {
            val uri = Uri.parse("content://$MODULE_PKG.config/state")
            ctx.contentResolver.query(uri, null, null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val si = c.getColumnIndex("show")
                        val ai = c.getColumnIndex("align")
                        val ci = c.getColumnIndex("pcolor")
                        val mi = c.getColumnIndex("dl_pkgs")
                        val oi = c.getColumnIndex("card_order")
                        val ni = c.getColumnIndex("nyan")
                        if (si >= 0) overrideShow.set(c.getInt(si) == 1)
                        if (ni >= 0) overrideNyan.set(c.getInt(ni) == 1)
                        val ai2 = c.getColumnIndex("album")
                        if (ai2 >= 0) overrideAlbum.set(c.getInt(ai2) == 1)
                        val ti = c.getColumnIndex("thumb")
                        if (ti >= 0) overrideThumb.set(c.getInt(ti) == 1)
                        val cpi = c.getColumnIndex("cpu")
                        if (cpi >= 0) overrideCpu.set(c.getInt(cpi) == 1)
                        if (ai >= 0) overrideAlignRight.set(c.getString(ai) != ALIGN_LEFT)
                        if (ci >= 0) overrideColor.set(c.getInt(ci))
                        if (oi >= 0) {
                            overrideOrder.set(
                                c.getString(oi)?.split(',')?.filter { it.isNotBlank() }
                            )
                        }
                        if (mi >= 0) {
                            overrideDl.set(
                                c.getString(mi)
                                    ?.split('|')?.filter { it.isNotBlank() }?.toSet()
                            )
                        }
                    }
                }
        } catch (_: Throwable) {
            // App 未安装/进程不可用：保持当前状态
        }
    }

    /** 刷新状态图标 + 进度填充 */
    private fun applyContent() {
        val snapshot = synchronized(bindings) { bindings.toList() }
        if (snapshot.isEmpty()) return
        val context = snapshot[0].root.context
        // 视图层兜底：周期性把系统状态栏里被接管的图标视图隐藏掉
        sweepHiddenIcons(snapshot[0].root.rootView)

        // 只在展开/收起那一次切换上补间体积
        val transition = lastPanelOpen != panelOpen
        lastPanelOpen = panelOpen

        if (panelOpen) {
            // 控制中心展开中：Dock 收成一颗返回图标（点整条 Dock 仍然关面板）
            for (b in snapshot) {
                b.back.visibility = View.VISIBLE
                b.badge.visibility = View.GONE
                b.art.visibility = View.GONE
                b.track.visibility = View.GONE
                b.icons.forEach { it.visibility = View.GONE }
                b.root.minimumWidth = 0
            }
            // 必须走 updateClock 把 clockVisible 一起落下：只置 GONE 的话
            // 下一秒的 clockTick 会照旧把时间点亮
            updateClock(snapshot, false)
            if (transition) snapshot.forEach { animateDockWidth(it, it.collapsedWidth) }
            return
        }
        for (b in snapshot) {
            b.root.minimumWidth = b.minWidth
            // 返回图标只在控制中心展开时存在，这里每帧定死，不靠切换那一帧
            b.back.visibility = View.GONE
            // 记一次展开态的实测宽度，收起动画结束时按它放回去
            if (!transition && b.root.width > 0) b.naturalWidth = b.root.width
        }

        // musicState 一次取回进度、封面和曲目标识，图标要不要让位也看它（别为一个 tick 打两次 binder）
        val music = musicState(context)
        val thumbArt = if (albumThumb() && music.token != null) music.art else null
        val artShown = thumbArt != null

        // 状态图标：仅激活的项显示；播放中封面小图接管左侧，此时整批图标让位
        val states = statusStates(context)
        val warn = privacyWarn()
        val notifCount = synchronized(activeNotifs) { activeNotifs.size }
        val changed = lastStates == null || !lastStates.contentEquals(states) ||
            warn != lastPrivacyWarn || notifCount != lastNotifCount || artShown != lastArtShown
        if (changed) {
            val anyIcon = anyIconShown(states, artShown) || notifCount > 0
            val badgeText = if (notifCount > 99) "99+" else notifCount.toString()
            for (b in snapshot) {
                b.badge.visibility = if (notifCount > 0) View.VISIBLE else View.GONE
                b.badge.text = badgeText
                b.icons.forEachIndexed { i, iv ->
                    iv.visibility = if (showsIcon(states, artShown, i)) View.VISIBLE else View.GONE
                }
                // 图标与进度条之间的间距只在有内容时保留
                val tlp = b.track.layoutParams as? LinearLayout.LayoutParams
                if (tlp != null) {
                    tlp.marginStart = if (anyIcon) dp(b.root.context, 6) else 0
                    b.track.layoutParams = tlp
                }
            }
            lastStates = states
            lastArtShown = artShown
            lastPrivacyWarn = warn
            lastNotifCount = notifCount
            // 隐私占用时整条 Dock 转红（复用同一个 GradientDrawable，保住胶囊圆角）
            val bg = dockBackgroundColor()
            for (b in snapshot) b.pill.setColor(bg)
        }

        if (transition) {
            // 面板收起：把体积补间回展开前记下的宽度
            snapshot.forEach {
                animateDockWidth(it, if (it.naturalWidth > 0) it.naturalWidth else it.minWidth)
            }
        }

        // 进度（下载器通知 > 音乐播放 > 充电）
        val percent = downloadPercent()
            ?: music.percent
            ?: ChargeProgressSource.snapshot(context)
        // 图标、徽标、封面和进度条都没东西可显示时，Dock 空出来显示时钟
        updateClock(
            snapshot,
            !artShown && !states.any { it } && notifCount == 0 && percent == null
        )
        applyBackdrop(snapshot, music)
        applyThumb(snapshot, thumbArt, music.token)
        val color = progressColor()
        val colorChanged = color != lastColor
        val nyan = nyanStyle()
        val nyanChanged = nyan != lastNyan
        if (percent == lastPercent && !changed && !colorChanged && !nyanChanged) return
        lastPercent = percent
        if (colorChanged) {
            lastColor = color
            snapshot.forEach { it.fill.setSolidColor(color) }
        }
        if (nyanChanged) {
            lastNyan = nyan
            XposedBridge.log("[TapDock] nyan progress style = $nyan")
            snapshot.forEach { it.fill.setNyan(nyan) }
        }
        for (b in snapshot) {
            if (percent == null) {
                b.track.visibility = View.GONE
                continue
            }
            b.track.visibility = View.VISIBLE
            val trackW = b.track.width
            if (trackW > 0) {
                val lp = b.fill.layoutParams
                lp.width = ((trackW * percent) / 100).coerceAtLeast(dp(b.fill.context, 3))
                b.fill.layoutParams = lp
            }
        }
    }

    /** 音乐播放进度：MediaSession API（SystemUI 持有 MEDIA_CONTENT_CONTROL），对所有音乐应用生效 */
    /** 一次 getActiveSessions 同时拿进度、封面和曲目标识，别为一个 tick 打两次 binder */
    private class MusicState(val percent: Int?, val art: Bitmap?, val token: String?)

    private fun musicState(context: Context): MusicState = try {
        val msm = context.applicationContext
            .getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val controllers: List<MediaController> = msm?.getActiveSessions(null) ?: emptyList()
        var percent: Int? = null
        var art: Bitmap? = null
        var token: String? = null
        for (controller in controllers) {
            val state = controller.playbackState ?: continue
            if (state.state != PlaybackState.STATE_PLAYING) continue
            val meta = controller.metadata
            val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            if (duration > 0L) {
                val position = state.position.coerceIn(0L, duration)
                percent = ((position * 100) / duration).toInt().coerceIn(0, 100)
            }
            art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            token = controller.packageName + "|" + meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            break
        }
        MusicState(percent, art, token)
    } catch (t: Throwable) {
        XposedBridge.log("[TapDock] music state failed: $t")
        MusicState(null, null, null)
    }

    private fun musicPercent(context: Context): Int? = musicState(context).percent

    /**
     * 封面 → 缩到 10x10 再双线性放大 = 廉价模糊。
     * 不用 RenderScript（API 31 已移除），也不用 RenderEffect（会糊到整棵视图树，图标跟着糊）。
     * 两端按胶囊圆角裁掉，再压一层半透明黑保证白色图标可读。
     */
    private fun blurredArt(art: Bitmap, w: Int, h: Int): Bitmap? = try {
        if (w <= 0 || h <= 0) null
        else {
            val tiny = Bitmap.createScaledBitmap(art, 10, 10, true)
            val big = Bitmap.createScaledBitmap(tiny, w, h, true)
            tiny.recycle()
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val path = Path().apply {
                addRoundRect(
                    RectF(0f, 0f, w.toFloat(), h.toFloat()),
                    h / 2f, h / 2f, Path.Direction.CW
                )
            }
            c.clipPath(path)
            c.drawBitmap(big, 0f, 0f, null)
            big.recycle()
            c.drawPaint(Paint().apply { color = ALBUM_SCRIM })
            out
        }
    } catch (t: Throwable) {
        XposedBridge.log("[TapDock] album blur failed: $t")
        null
    }

    /**
     * Dock 背景优先级：隐私告警红 > 播放中的模糊封面 > 纯色胶囊。
     * 背景用 FILL 的 BitmapDrawable，所以收起 / 放大的宽度动画期间它会跟着缩放，不露旧尺寸。
     */
    private fun applyBackdrop(snapshot: List<Binding>, music: MusicState) {
        val art = if (privacyWarn() || !albumArtBg()) null else music.art
        val token = music.token
        for (b in snapshot) {
            if (art == null || token == null) {
                if (b.artToken != null || b.artBg != null) {
                    b.artBg = null
                    b.artToken = null
                    b.root.background = b.pill
                }
                continue
            }
            if (b.artToken == token && b.artBg != null) {
                b.root.background = b.artBg
                continue
            }
            val bmp = blurredArt(art, b.root.width, b.root.height)
            if (bmp == null) continue
            b.artBg = BitmapDrawable(b.root.resources, bmp).apply { gravity = Gravity.FILL }
            b.artToken = token
            b.root.background = b.artBg
            XposedBridge.log("[TapDock] dock album backdrop <- $token")
        }
    }

    /**
     * 进度条左侧的封面小图：`art == null`（没播放 / 开关关掉 / 没元数据）就整格让位给状态图标。
     * 和"背景模糊封面"是两个独立开关，互不影响。
     */
    private fun applyThumb(snapshot: List<Binding>, art: Bitmap?, token: String?) {
        for (b in snapshot) {
            if (art == null || token == null) {
                b.art.visibility = View.GONE
                continue
            }
            if (b.thumbToken != token) {
                b.thumbBmp = squareArt(art, b.art.layoutParams.width, dp(b.art.context, 4).toFloat())
                b.thumbToken = token
                XposedBridge.log("[TapDock] dock album thumb <- $token")
            }
            val bmp = b.thumbBmp
            if (bmp == null) {
                b.art.visibility = View.GONE
                continue
            }
            b.art.setImageBitmap(bmp)
            b.art.visibility = View.VISIBLE
        }
    }

    /**
     * 封面 → 中间取方 → 缩到目标尺寸 → 圆角裁。
     * 源位图可能和中间产物是同一个对象（正方形封面时 createBitmap 会原样返回），
     * 所以回收前都要比一次引用，别把 metadata 里的位图给 recycle 掉。
     */
    private fun squareArt(art: Bitmap, size: Int, radius: Float): Bitmap? = try {
        if (size <= 0) null
        else {
            val side = minOf(art.width, art.height)
            val cropped = Bitmap.createBitmap(
                art, (art.width - side) / 2, (art.height - side) / 2, side, side
            )
            val scaled =
                if (side == size) cropped else Bitmap.createScaledBitmap(cropped, size, size, true)
            if (scaled !== cropped && cropped !== art) cropped.recycle()
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val path = Path().apply {
                addRoundRect(
                    RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, Path.Direction.CW
                )
            }
            c.clipPath(path)
            c.drawBitmap(scaled, 0f, 0f, null)
            if (scaled !== art) scaled.recycle()
            out
        }
    } catch (t: Throwable) {
        XposedBridge.log("[TapDock] album thumb failed: $t")
        null
    }

    /** Dock 只有一条细轨道，所以取所有下载器里的最高进度 */
    private fun downloadPercent(): Int? = synchronized(downloadMap) {
        downloadMap.values.maxOrNull()
    }

    /** 多项下载进度：每个下载器应用一行，按进度从高到低排 */
    internal fun downloadEntries(context: Context): List<Pair<String, Int>> =
        synchronized(downloadMap) { downloadMap.toList() }
            .sortedByDescending { it.second }
            .map { appLabel(context, it.first) to it.second }

    private fun appLabel(context: Context, pkg: String): String = appLabels.getOrPut(pkg) {
        try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Throwable) {
            pkg
        }
    }

    // 控制中心的"多进度显示区"要同时看到三个进度源，这里开三个只读入口
    internal fun downloadProgress(): Int? = downloadPercent()

    internal fun musicProgress(ctx: Context): Int? = musicPercent(ctx)

    internal fun chargeProgress(ctx: Context): Int? = ChargeProgressSource.snapshot(ctx)

    /** 播放音乐时 Dock 背景是否用模糊封面（App 里的开关） */
    internal fun albumArtBg(): Boolean =
        overrideAlbum.get() ?: (loadPrefs()?.getBoolean("album_art_bg", true) ?: true)

    /** 进度条左侧是否显示封面小图（App 里的开关） */
    private fun albumThumb(): Boolean =
        overrideThumb.get() ?: (loadPrefs()?.getBoolean("album_thumb", true) ?: true)

    /** 状态区是否显示 CPU 全核心占用图（App 里的开关，默认关） */
    internal fun cpuChart(): Boolean =
        overrideCpu.get() ?: (loadPrefs()?.getBoolean("cpu_chart", false) ?: false)

    /** 进度条是否走彩虹猫风格（App 里的开关） */
    internal fun nyanStyle(): Boolean =
        overrideNyan.get() ?: (loadPrefs()?.getBoolean("nyan_style", false) ?: false)

    /** 控制中心卡片顺序：App 里拖出来的，走广播 / Provider / prefs 三级通道 */
    internal fun cardOrder(): List<String> {
        overrideOrder.get()?.let { return it }
        val raw = loadPrefs()?.getString("card_order", "") ?: ""
        return raw.split(',').filter { it.isNotBlank() }
    }

    /** 监听 SystemUI 通知管线：从带进度条的常驻通知中提取下载进度 */
    private fun startNotificationWatch(ctx: Context) {
        if (notifHooked) return
        notifHooked = true
        try {
            val cls = Class.forName(
                "com.android.systemui.statusbar.NotificationListener", false, ctx.classLoader
            )
            XposedBridge.hookAllMethods(cls, "onNotificationPosted", object : XC_MethodHook() {
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    val sbn = param.args?.firstOrNull() as? StatusBarNotification ?: return
                    seedFrom(param.thisObject)
                    if (!isGroupSummary(sbn)) {
                        synchronized(activeNotifs) { activeNotifs.add(keyOf(sbn)) }
                    }
                    handleDownloadNotification(sbn)
                }
            })
            XposedBridge.hookAllMethods(cls, "onNotificationRemoved", object : XC_MethodHook() {
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    val sbn = param.args?.firstOrNull() as? StatusBarNotification ?: return
                    synchronized(activeNotifs) { activeNotifs.remove(keyOf(sbn)) }
                    synchronized(downloadMap) { downloadMap.remove(sbn.packageName) }
                    main.post {
                        lastPercent = null
                        applyContent()
                    }
                }
            })
            // 监听器每次接上都要重新取一次全量（SystemUI 重启 / 重新绑定监听）
            XposedBridge.hookAllMethods(cls, "onListenerConnected", object : XC_MethodHook() {
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    notifSeeded = false
                    seedFrom(param.thisObject)
                    main.post { applyContent() }
                }
            })
            XposedBridge.log("[TapDock] notification download watch started")
        } catch (t: Throwable) {
            notifHooked = false
            XposedBridge.log("[TapDock] notification watch failed: $t")
        }
    }

    private fun handleDownloadNotification(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val extras = n.extras
        val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX)
        val prog = extras.getInt(Notification.EXTRA_PROGRESS)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        val allowed = downloadAllowed()
        val pkg = sbn.packageName
        synchronized(downloadMap) {
            if (ongoing && !indeterminate && max > 0 && prog in 0..max &&
                (allowed == null || pkg in allowed)
            ) {
                downloadMap[pkg] = prog * 100 / max
            } else {
                downloadMap.remove(pkg)
            }
        }
        main.post {
            lastPercent = null
            applyContent()
        }
    }

    private fun keyOf(sbn: StatusBarNotification) = "${sbn.packageName}:${sbn.id}"

    /** 分组的汇总条目不单独计数：一个分组只算它下面真实的那几条 */
    private fun isGroupSummary(sbn: StatusBarNotification): Boolean = try {
        (sbn.notification?.flags?.and(Notification.FLAG_GROUP_SUMMARY) ?: 0) != 0
    } catch (_: Throwable) {
        false
    }

    /**
     * 第一次拿到监听器实例时，用它自己的 activeNotifications 补一次全量 ——
     * 只靠 posted/removed 增量会漏掉"钩子装好之前就已经挂着"的那些通知。
     */
    private fun seedFrom(listener: Any?) {
        if (notifSeeded || listener == null) return
        notifSeeded = true
        try {
            val arr = listener.javaClass.getMethod("getActiveNotifications")
                .invoke(listener) as? Array<*> ?: return
            val keys = arr.filterIsInstance<StatusBarNotification>()
                .filterNot { isGroupSummary(it) }
                .map { keyOf(it) }
            synchronized(activeNotifs) {
                activeNotifs.clear()
                activeNotifs.addAll(keys)
            }
            XposedBridge.log("[TapDock] notification count seeded: ${keys.size}")
            main.post { applyContent() }
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] notification seed failed: $t")
        }
    }

    /** 控制中心此刻是否展开（展开时 Dock 收成一颗返回图标） */
    private var panelOpen = false

    /** 上一次 applyContent 时的展开态，用来认出"就是这一次切换" */
    private var lastPanelOpen: Boolean? = null

    /**
     * Dock 体积缩放：把 root 宽度从当前值补间到 to，收尾交回 WRAP_CONTENT，
     * 之后宽度重新跟着内容走。
     */
    private fun animateDockWidth(b: Binding, to: Int) {
        b.anim?.cancel()
        b.anim = null
        val root = b.root
        val lp = root.layoutParams ?: return
        val from = root.width
        val original = lp.width
        if (from <= 0 || to <= 0 || from == to) return
        val a = ValueAnimator.ofInt(from, to)
        a.duration = DOCK_ANIM_MS
        a.interpolator = DOCK_INTERPOLATOR
        a.addUpdateListener {
            val p = root.layoutParams ?: return@addUpdateListener
            p.width = it.animatedValue as Int
            root.layoutParams = p
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(an: Animator) {
                if (b.anim !== an) return
                b.anim = null
                val p = root.layoutParams ?: return
                p.width = original
                root.layoutParams = p
            }
        })
        b.anim = a
        a.start()
    }

    /**
     * 由 ControlPanel 在同进程内调用。收起时要作废进度/图标缓存，
     * 否则展开期间没走过 applyContent，状态没变的那部分就永远刷不回来。
     */
    fun setPanelOpen(open: Boolean) {
        if (panelOpen == open) return
        panelOpen = open
        if (!open) {
            lastStates = null
            lastPercent = null
        }
        main.post { applyContent() }
    }

    private fun rebuildAll() {
        val show = enabled()
        val right = alignRight()
        val snapshot: List<Binding>
        synchronized(bindings) { snapshot = bindings.toList() }
        for (b in snapshot) {
            val parent = b.parentRef.get() ?: continue
            if (!parent.isAttachedToWindow) continue
            b.root.visibility = if (show) View.VISIBLE else View.GONE
            if (b.isFrame) {
                val lp = b.root.layoutParams as? FrameLayout.LayoutParams ?: continue
                val vertical = lp.gravity and Gravity.VERTICAL_GRAVITY_MASK
                val horizontal = if (right) Gravity.END else Gravity.START
                if (lp.gravity != horizontal or vertical) {
                    lp.gravity = horizontal or vertical
                    b.root.layoutParams = lp
                    b.root.requestLayout()
                }
                continue
            }
            if (b.root.parent === parent) parent.removeView(b.root)
            val index = if (right) parent.childCount else 0
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER_VERTICAL }
            parent.addView(b.root, index.coerceIn(0, parent.childCount), lp)
        }
        lastStates = null
        lastPercent = null // 触发下一次 tick 重新套用
    }

    private fun notifyActive(context: Context) {
        try {
            context.applicationContext.sendBroadcast(
                Intent(ACTION_ACTIVE).setPackage(MODULE_PKG)
            )
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ 状态源（SystemUI 特权）

    /**
     * 该不该显示第 index 个状态图标：播放中的封面小图挤掉连接类图标
     * （WLAN / 蓝牙 / 定位 / VPN），但摄像头 / 麦克风永远让不得 —— 原生隐私胶囊已被
     * 本模块一并隐藏，Dock 再藏就等于没有任何占用提示。
     */
    private fun showsIcon(states: BooleanArray, artShown: Boolean, index: Int): Boolean =
        states[index] && (!artShown || index >= PRIVACY_ICON_FROM)

    private fun anyIconShown(states: BooleanArray, artShown: Boolean): Boolean =
        states.indices.any { showsIcon(states, artShown, it) }

    /** 顺序与图标一致，仅返回 true 的项显示 */
    private fun statusStates(context: Context): BooleanArray = booleanArrayOf(
        wifiConnected(context),
        bluetoothConnected(),
        PrivacyMonitor.location,
        vpnActive(context),
        PrivacyMonitor.camera,
        PrivacyMonitor.microphone,
    )

    private fun connectivity(ctx: Context): ConnectivityManager? =
        ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private fun wifiConnected(ctx: Context): Boolean = try {
        val cm = connectivity(ctx) ?: return false
        cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (_: Throwable) {
        false
    }

    private fun vpnActive(ctx: Context): Boolean = try {
        val cm = connectivity(ctx) ?: return false
        cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * 蓝牙"已连接"= 任一音频/输入/网络 profile 处于非断开态。
     * 只看 adapter 开关会让图标常年挂着，所以这里不查 isEnabled。
     */
    @Suppress("DEPRECATION")
    private fun bluetoothConnected(): Boolean = try {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        adapter.isEnabled && BT_PROFILES.any {
            adapter.getProfileConnectionState(it) != BluetoothProfile.STATE_DISCONNECTED
        }
    } catch (_: Throwable) {
        false
    }

    // ------------------------------------------------------------------ 隐藏系统状态栏图标（改为 Dock 显示）

    // 视图层兜底扫描：StatusBarIconView 类与槽位读取方法（反射缓存）
    private var sweepCls: Class<*>? = null
    private var sweepGetSlot: java.lang.reflect.Method? = null
    private var sweepSlotField: java.lang.reflect.Field? = null
    private var sweepSlotResolved = false

    private fun hideStatusBarIcons(ctx: Context) {
        if (statusIconsHooked) return
        statusIconsHooked = true

        // 1) Lineage 原生状态栏图标黑名单（ROM 自身支持，新旧图标管线均识别）
        try {
            android.provider.Settings.System.putString(
                ctx.contentResolver,
                "status_bar_icon_blacklist",
                HIDDEN_SLOTS.joinToString(",")
            )
            XposedBridge.log("[TapDock] lineage icon blacklist applied")
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] lineage blacklist failed: $t")
        }

        try {
            val cl = ctx.classLoader
            // 2) 新旧两套图标控制器都按槽位拦截：
            //    Android 14+ 走 com.android.systemui.statusbar.phone.ui 包，
            //    旧实现在 com.android.systemui.statusbar.phone 包
            val implNames = listOf(
                "com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl",
                "com.android.systemui.statusbar.phone.ui.StatusIconDispatcher",
                "com.android.systemui.statusbar.phone.StatusBarIconControllerImpl",
            )
            val suppress = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    val tag = param.args?.firstOrNull() as? String ?: return
                    if (tag.lowercase() in HIDDEN_SLOTS) param.result = null
                }
            }
            implNames.forEach { name ->
                try {
                    val cls = Class.forName(name, false, cl)
                    // wifi/bluetooth/location/vpn 图标全部经由 setIcon/setExternalIcon 设置
                    XposedBridge.hookAllMethods(cls, "setIcon", suppress)
                    XposedBridge.hookAllMethods(cls, "setExternalIcon", suppress)
                    // 兜底：清除已设置的图标
                    XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            val impl = param.thisObject
                            main.post { removeSlots(impl, cls) }
                        }
                    })
                    XposedBridge.log("[TapDock] icon controller hooked: $name")
                } catch (_: Throwable) {
                    // 该实现类在当前版本不存在，忽略
                }
            }
            // 3) 视图层兜底扫描用：StatusBarIconView 类
            sweepCls = try {
                Class.forName("com.android.systemui.statusbar.StatusBarIconView", false, cl)
            } catch (_: Throwable) {
                null
            }
            XposedBridge.log("[TapDock] status bar icons (wifi/bt/location/vpn) hidden")
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] hide status bar icons failed: $t")
        }
    }

    private fun removeSlots(impl: Any, cls: Class<*>) {
        val rm = cls.methods.firstOrNull {
            it.name == "removeIcon" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java
        }
        HIDDEN_SLOTS.forEach { slot ->
            try {
                rm?.invoke(impl, slot)
            } catch (_: Throwable) {
            }
        }
    }

    /** 视图层兜底：遍历状态栏视图树，隐藏槽位匹配的图标视图（含新管线自定义视图） */
    private fun sweepHiddenIcons(rootView: View) {
        val cls = sweepCls ?: return
        try {
            // 只扫状态栏子树（PhoneStatusBarView），避免波及下拉 QS 磁贴
            var cur: View = rootView
            var barRoot: View = rootView
            while (cur is ViewGroup) {
                if (cur.javaClass.simpleName == "PhoneStatusBarView") barRoot = cur
                val p = cur.parent
                if (p !is View) break
                cur = p
            }
            sweepView(barRoot, cls)
        } catch (_: Throwable) {
        }
    }

    private fun sweepView(view: View, iconCls: Class<*>) {
        if (isHiddenStatusView(view, iconCls) && view.visibility != View.GONE) {
            view.visibility = View.GONE
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) sweepView(view.getChildAt(i), iconCls)
        }
    }

    private fun isHiddenStatusView(view: View, iconCls: Class<*>): Boolean {
        // 1) 传统 StatusBarIconView：按槽位（getSlot / mSlot 字段）
        if (iconCls.isInstance(view) && isHiddenSlot(view)) return true
        // 2) 新管线自定义指示视图：类名含槽位名（如 statusbar.pipeline.wifi.ui.WifiView）
        val className = view.javaClass.name.lowercase()
        if (HIDDEN_SLOTS.any { className.contains(it) }) return true
        // 3) 布局声明的视图：id 资源名含槽位名（如 @id/wifi）
        if (view.id != View.NO_ID) {
            val idName = try {
                view.context.resources.getResourceEntryName(view.id)
            } catch (_: Throwable) {
                null
            }
            if (idName != null &&
                (HIDDEN_SLOTS.any { idName.lowercase().contains(it) } ||
                    idName.lowercase() in HIDDEN_PRIVACY_VIEWS)
            ) return true
        }
        // 4) 原生隐私 chip（摄像头/麦克风占用时出现）
        if (view.javaClass.simpleName.lowercase() in HIDDEN_PRIVACY_VIEWS) return true
        return false
    }

    private fun isHiddenSlot(view: View): Boolean {
        if (!sweepSlotResolved) {
            sweepSlotResolved = true
            val cls = view.javaClass
            sweepGetSlot = try {
                cls.getMethod("getSlot")
            } catch (_: Throwable) {
                null
            }
            sweepSlotField = try {
                cls.getDeclaredField("mSlot").apply { isAccessible = true }
            } catch (_: Throwable) {
                null
            }
        }
        sweepGetSlot?.let {
            return try {
                val s = it.invoke(view) as? String
                s?.lowercase() in HIDDEN_SLOTS
            } catch (_: Throwable) {
                false
            }
        }
        sweepSlotField?.let {
            return try {
                val s = it.get(view) as? String
                s?.lowercase() in HIDDEN_SLOTS
            } catch (_: Throwable) {
                false
            }
        }
        val tag = view.tag as? String
        return tag?.lowercase() in HIDDEN_SLOTS
    }

    /**
     * 猫图：先裁掉四周透明边（素材里猫只占画布中间一块，不裁就会比看起来小一圈），
     * 再按最近邻缩放到指定高度并保持宽高比 —— 像素画用双线性会糊成一团。
     */
    internal fun scaledCat(context: Context, heightPx: Int): Bitmap? = try {
        val res = ModuleRes.context(context)?.resources
        val src = res?.let { BitmapFactory.decodeResource(it, com.lsp102.tapdock.R.drawable.nyan_cat) }
        src?.let {
            val tight = cropToContent(it)
            val w = (heightPx * tight.width / tight.height.toFloat()).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(tight, w, heightPx, false)
        }
    } catch (t: Throwable) {
        XposedBridge.log("[TapDock] cat load failed: $t")
        null
    }

    private fun cropToContent(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (px[row + x] ushr 24 != 0) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < left || bottom < top) return src
        return Bitmap.createBitmap(src, left, top, right - left + 1, bottom - top + 1)
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
