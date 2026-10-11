package com.lsp102.tapdock.xposed

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.AppOpsManager
import android.app.Notification
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
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
    const val EXTRA_BADGE = "badge"
    const val EXTRA_OTP = "otp"
    const val EXTRA_PKG = "pkgcode"
    const val EXTRA_NCARD = "ncard"
    const val EXTRA_MAXW = "maxw"
    const val EXTRA_API_BADGE = "api_badge"
    const val EXTRA_API_BADGE_UNKNOWN = "api_badge_unknown"
    const val ALIGN_LEFT = "left"
    const val ALIGN_RIGHT = "right"

    const val MODULE_PKG = "com.lsp102.tapdock"
    private const val TAG_ROOT = "tapdock_dock_root"
    private const val PREFS_FILE = "dock_prefs"

    // 基础外观
    private const val BG_ALPHA_PERCENT = 80
    private const val BG_COLOR_NORMAL = 0x000000
    private const val BG_COLOR_PRIVACY = 0xFFD32F2F.toInt()
    /**
     * 低电量告警底色：琥珀 + 红，都取"白图标压上去还读得清"的深档，
     * 不用亮黄（#FFEB3B 那一档配白色图标等于糊成一片）。
     */
    private const val BG_COLOR_BATT_WARN = 0xFFF9A825.toInt()
    private const val BG_COLOR_BATT_CRIT = 0xFFD32F2F.toInt()
    /** 电量档位：≤ 20% 黄，≤ 10% 红；-1 = 还没读到过电池广播，不着色 */
    private const val BATT_WARN_LEVEL = 20
    private const val BATT_CRIT_LEVEL = 10
    /** 底色换档的补间时长：比宽度动画长一点，颜色跳变才看得出是"换了一档" */
    private const val BG_FADE_MS = 320L
    /** ControlPanel 要按这个半径给 Dock 四角补模糊，见 ControlPanel.applyBackdropBlur */
    const val DOCK_CORNER_DP = 12
    private const val MIN_WIDTH_STATUS_DP = 122
    private const val MIN_WIDTH_TASKBAR_DP = 156
    private const val DEFAULT_MAXW_DP = 220
    private const val ICON_STATUS_DP = 13
    private const val ICON_TASKBAR_DP = 18
    private const val ICON_GAP_STATUS_DP = 4
    private const val ICON_GAP_TASKBAR_DP = 6
    private const val TRACK_H_STATUS_DP = 4
    private const val TRACK_H_TASKBAR_DP = 6

    /** 占用摄像头 / 麦克风的应用图标最多画几颗：再多 Dock 就成图标抽屉了 */
    private const val PRIV_ICONS = 2

    /** 图标达到这个颗数就把进度条整条让开：胶囊再挤下去谁都没了 */
    private const val TRACK_OFF_ICONS = 4
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
    private val HIDDEN_SLOTS = setOf("wifi", "bluetooth", "location", "vpn", "hotspot")

    /** 热点连接设备数那颗小字（挂在 iconsRow 里，靠 tag 找，不进 DockViews） */
    private const val TAG_HS = "tapdock_hotspot_count"

    /** iconRes 里从这一格起是隐私类（摄像头 / 麦克风）：播放封面时它们也不让位 */
    private const val PRIVACY_ICON_FROM = 4

    /** iconRes 的第 0 格 */
    private const val WIFI_ICON = 0

    /** iconRes 的末位：热点；连上的台数就写在这颗图标右边 */
    private const val HOTSPOT_ICON = 8

    /** 热点之前的一格：前台游戏、且图形元数据已经采到才亮（采集失败就不亮，不空有个图标） */
    private const val GAME_ICON = 7

    /** WiFi 按信号强度分格画：第 0 格是完整扇形，用于连不上/读不到信号时兜底 */
    private val WIFI_LEVEL_ICONS = intArrayOf(
        com.lsp102.tapdock.R.drawable.ic_tile_wifi,
        com.lsp102.tapdock.R.drawable.ic_dock_wifi_1,
        com.lsp102.tapdock.R.drawable.ic_dock_wifi_2,
        com.lsp102.tapdock.R.drawable.ic_dock_wifi_3,
        com.lsp102.tapdock.R.drawable.ic_dock_wifi_4,
    )

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

    /** Dock 左侧通知数徽标开关（同上） */
    private val overrideBadge = AtomicReference<Boolean?>(null)

    /** 短信验证码显示开关（同上） */
    private val overrideOtp = AtomicReference<Boolean?>(null)

    /** 快递取件码提示开关（同上） */
    private val overridePkg = AtomicReference<Boolean?>(null)

    /** 通知横幅由 Dock 接管开关（同上） */
    private val overrideNcard = AtomicReference<Boolean?>(null)

    /** 上一次下发给系统的"接管原生横幅"状态，只有它变了才去写全局设置 */
    private var lastHeadsUpTakeover: Boolean? = null

    /** 上一次画在 Dock 上的验证码，用来判断这一帧要不要重排 */
    private var lastOtp = ""

    /** 上一次画在 Dock 上的热点连接数，同上 */
    private var lastHs = ""

    /** 上一次 WiFi 图标用的第几格（WIFI_LEVEL_ICONS 下标），换格才重画 */
    private var lastWifiLevel = -1

    /** 上一次回报给 App 预览的 Dock 内容签名，内容没变不重复发广播 */
    private var lastMirrorSig = ""

    /** Dock 最大宽度（dp，0 = 不限）（App 里的档位，走同一套三级通道） */
    private val overrideMaxW = AtomicReference<Int>(null)

    /** App 侧 root 采到的游戏信息（`pkg|api|宽x高|帧率|时间戳|进程号`），随 Provider 的 query 回来 */
    private val overrideGame = AtomicReference<String>(null)

    /** 游戏 tile 换成图形 API 图标的开关（同上，走三级通道） */
    private val overrideApiBadge = AtomicReference<Boolean?>(null)

    /** 认不出 API 时是否也换成"未知"图标 */
    private val overrideApiBadgeUnknown = AtomicReference<Boolean?>(null)

    /** App 侧攒的图形 API 历史（`pkg|api|时间戳;…`），只给面板的"上次"那行用 */
    private val overrideApiHistory = AtomicReference<String>(null)

    /** 上一次画在游戏 tile 上的 API 结论（包名 + 类型），换结论才 setImageDrawable */
    private var lastGameApi = ""
    private var lastPercent: Int? = null
    private var lastStates: BooleanArray? = null

    /** 上一次是否因为播放封面而让位了状态图标，切换它要重排整条 Dock */
    private var lastArtShown: Boolean? = null
    private var lastColor: Int? = null

    /** 上一次下发的彩虹猫开关状态 */
    private var lastNyan = false
    private var lastPrivacyWarn = false

    /** 底色：[lastBg] 是这一档要到的颜色，[bgShown] 是补间当前真实画出来的颜色 */
    private var lastBg = 0
    private var bgShown = 0
    private var bgAnim: ValueAnimator? = null

    /** 下载器通知进度：pkg -> 0..100（NotificationListener 管线） */
    private val downloadMap = HashMap<String, Int>()

    /** pkg -> 应用名，解析一次就缓存（每次刷新查 PackageManager 没必要） */
    private val appLabels = HashMap<String, String>()

    /** pkg -> 应用图标，同上；图标 Drawable 按 constantState 现取一份，不和多颗图标共用实例 */
    private val appIcons = HashMap<String, Drawable?>()

    /** 上一次画在 Dock 上的占用应用，用来判断这一帧要不要重排 */
    private var lastPrivPkgs: List<String> = emptyList()

    private class DockViews(
        val root: LinearLayout,
        val track: FrameLayout,
        val fill: NyanFillView,
        val icons: List<ImageView>,
        /** 占用摄像头 / 麦克风的应用图标位 */
        val priv: List<ImageView>,
        val art: ImageView,
        val clock: TextView,
        val badge: TextView,
        val otp: TextView,
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
        val priv: List<ImageView>,
        val art: ImageView,
        val clock: TextView,
        val badge: TextView,
        val otp: TextView,
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

    /** 通知横幅由 Dock 接管：开 = 关掉系统原生横幅，改画自家卡片 */
    private fun notifCard(): Boolean =
        overrideNcard.get() ?: (loadPrefs()?.getBoolean("notif_card", true) ?: true)

    /**
     * 把"接管横幅"这个决定落到系统全局设置上。
     * 只在我们自己的配置真的变了时写：用户进系统设置手动改回去，不该每帧跟他抢。
     */
    private fun syncHeadsUpSetting(context: Context) {
        val on = notifCard()
        if (lastHeadsUpTakeover == on) return
        lastHeadsUpTakeover = on
        NotificationCard.applyHeadsUpSetting(context, on)
        if (!on) NotificationCard.shutdown()
    }

    /**
     * 摄像头 / 麦克风正在被占用 → Dock 底色转红警示。
     * 定位不参与：本 ROM 的 PrivacyItemController.locationAvailable 恒为 false，
     * 拿不到"正在被定位"，用开关态又会常年红。
     */
    private fun privacyWarn(): Boolean = PrivacyMonitor.camera || PrivacyMonitor.microphone

    /** 电量百分比，-1 = 还没读到过；由 watchBattery 的粘性广播喂 */
    @Volatile
    private var batteryLevel = -1

    /**
     * Dock 底色分档：隐私告警红 > 低电红（≤10%）> 低电黄（≤20%）> 常态黑。
     *
     * 只看电量，不看是否在充电：插着电掉到 8% 也是真的只剩 8%，
     * 而且"充电中就不上色"会让这根条在充满之前一直看不出来。
     */
    private fun dockBaseColor(): Int = when {
        privacyWarn() -> BG_COLOR_PRIVACY
        batteryLevel in 0..BATT_CRIT_LEVEL -> BG_COLOR_BATT_CRIT
        batteryLevel in 0..BATT_WARN_LEVEL -> BG_COLOR_BATT_WARN
        else -> BG_COLOR_NORMAL
    }

    private fun dockBackgroundColor(): Int = withAlpha(dockBaseColor())

    private fun withAlpha(color: Int): Int =
        (color and 0x00FFFFFF) or ((BG_ALPHA_PERCENT * 255 / 100) shl 24)

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
                bindings.add(Binding(v.root, v.track, v.fill, v.icons, v.priv, v.art, v.clock, v.badge, v.otp, v.back, v.pill, v.minWidth,
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
                bindings.add(Binding(v.root, v.track, v.fill, v.icons, v.priv, v.art, v.clock, v.badge, v.otp, v.back, v.pill, v.minWidth,
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
            if (l != ol || t != ot || r != or || b != ob) {
                ControlPanel.refreshBackdropBlur()
            }
        }
        // 猫画在彩虹带上方，会越出 Dock 自己的边界
        root.clipChildren = false
        val padH = dp(context, 8)
        val padV = dp(context, if (compact) 2 else 6)
        root.setPadding(padH, padV, padH, padV)

        // 状态图标排最左：仅激活的项显示
        // WiFi/蓝牙/定位/VPN = 接管自系统状态栏；摄像头/麦克风 = 实际占用时
        // 再往后 = 未读短信里的快递取件码、前台游戏、热点（都只亮图标，详情进面板才给看）
        val iconSize = dp(context, if (compact) ICON_STATUS_DP else ICON_TASKBAR_DP)
        val gap = dp(context, if (compact) ICON_GAP_STATUS_DP else ICON_GAP_TASKBAR_DP)
        val iconRes = intArrayOf(
            com.lsp102.tapdock.R.drawable.ic_tile_wifi,
            com.lsp102.tapdock.R.drawable.ic_tile_bt,
            com.lsp102.tapdock.R.drawable.ic_tile_location,
            com.lsp102.tapdock.R.drawable.ic_tile_vpn,
            com.lsp102.tapdock.R.drawable.ic_tile_camera,
            com.lsp102.tapdock.R.drawable.ic_tile_mic,
            com.lsp102.tapdock.R.drawable.ic_row_package,
            com.lsp102.tapdock.R.drawable.ic_tile_game,
            com.lsp102.tapdock.R.drawable.ic_tile_hotspot,
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
        // 热点连接设备数：紧贴图标串的一颗小数字，热点没开 / 数读不到都不占位。
        // 挂在 iconsRow 里、按 tag 找，就不必为它改 DockViews/Binding 的构造参数串
        val hs = TextView(context).apply {
            tag = TAG_HS
            setTextColor(0xFFFFFFFF.toInt())
            textSize = if (compact) 9f else 11f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            includeFontPadding = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = gap / 2 }
        }
        iconsRow.addView(hs)
        // 占用摄像头 / 麦克风的应用图标：跟在隐私图标后面，画的是那个应用自己的图标
        val priv = (0 until PRIV_ICONS).map {
            ImageView(context).apply {
                visibility = View.GONE
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    marginEnd = gap
                }
            }.also { iconsRow.addView(it) }
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

        // 短信验证码：等宽数字 + 半透明底，点一下复制到剪贴板。平时不占位
        val otp = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = if (compact) 10f else 12f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            includeFontPadding = false
            setPadding(gap, gap / 2, gap, gap / 2)
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(0x40FFFFFF)
                cornerRadius = dp(context, 6).toFloat()
            }
            setOnClickListener { copyOtp(context) }
        }
        root.addView(otp, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = gap })
        root.addView(iconsRow)

        // Dock 空着（既没图标也没进度条）时显示时钟；只挂两颗图标的稀疏状态下，
        // 右边那点空白也让给时间。等宽字体免得秒跳时宽度抖。
        // 排在图标之后、进度条之前：彩虹猫那颗头会越出轨道右沿，压在末位的时间上
        val clock = TextView(context).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = if (compact) 11f else 13f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        root.addView(
            clock,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        )

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
            root, track, fill, icons, priv, art, clock, badge, otp, back, bg, root.minimumWidth,
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
        // WiFi/蓝牙/定位/VPN/热点：从系统状态栏隐藏，改由 Dock 显示
        hideStatusBarIcons(appCtx)
        // 热点连接台数要起进程采，几十毫秒不能占绘制线程；开关状态本身每帧现读
        HotspotSource.start(appCtx)
        // 下载进度：监听 SystemUI 通知管线里的下载器通知
        startNotificationWatch(appCtx)
        // 横幅接管：开机 / SystemUI 重启后按当前配置下一次全局开关
        syncHeadsUpSetting(appCtx)
        // 状态图标：接真实事件源，变化当帧刷新（轮询退化为兜底）
        startStateWatchers(appCtx)
        // 系统控制块：SystemUI 进程当引擎（建 / 点磁贴），桌面进程只缓存广播来的快照
        SystemTiles.attach(appCtx, appCtx.packageName == "com.android.systemui")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent == null) return
                if (intent.action == com.lsp102.tapdock.HookSync.HookActions.DOCK_QUERY) {
                    // App 首页要画预览：作废签名，让下一次 applyContent 必然回报一次
                    lastMirrorSig = ""
                    main.post { applyContent() }
                    return
                }
                if (intent.action == com.lsp102.tapdock.HookSync.HookActions.SYSSTAT_STATE) {
                    intent.getStringExtra(com.lsp102.tapdock.HookSync.HookActions.EXTRA_SYSSTAT)
                        ?.let { SysStatSource.ingest(it) }
                    return
                }
                // 小窗卡片的画面：App 用 root 截一屏、按窗口矩形裁回来
                if (intent.action == com.lsp102.tapdock.HookSync.HookActions.SHOT_STATE) {
                    intent.getStringExtra(com.lsp102.tapdock.HookSync.HookActions.EXTRA_SHOTS)
                        ?.let { WindowShots.ingest(it) }
                    return
                }
                // 取件码测试注入：不等 3 秒轮询，当帧就把图标挂上 / 摘下
                if (intent.action == com.lsp102.tapdock.HookSync.HookActions.PKGCODE_STATE) {
                    PackageCodeSource.ingest(
                        intent.getStringExtra(com.lsp102.tapdock.HookSync.HookActions.EXTRA_PKGCODE)
                            ?: ""
                    )
                    main.post { applyContent() }
                    return
                }
                // 系统控制块的三种广播直接交给 SystemTiles
                if (intent.action == SystemTiles.ACTION_CLICK ||
                    intent.action == SystemTiles.ACTION_REFRESH ||
                    intent.action == SystemTiles.ACTION_SNAPSHOT
                ) {
                    SystemTiles.handleIntent(intent, appCtx)
                    return
                }
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
                    overrideAlbum.set(intent.getBooleanExtra(EXTRA_ALBUM, false))
                    main.post { applyContent() }
                }
                if (intent.hasExtra(EXTRA_CPU)) {
                    overrideCpu.set(intent.getBooleanExtra(EXTRA_CPU, false))
                }
                if (intent.hasExtra(EXTRA_BADGE)) {
                    overrideBadge.set(intent.getBooleanExtra(EXTRA_BADGE, true))
                    main.post { applyContent() }
                }
                if (intent.hasExtra(EXTRA_OTP)) {
                    overrideOtp.set(intent.getBooleanExtra(EXTRA_OTP, true))
                    main.post { applyContent() }
                }
                if (intent.hasExtra(EXTRA_PKG)) {
                    overridePkg.set(intent.getBooleanExtra(EXTRA_PKG, true))
                    main.post { applyContent() }
                }
                if (intent.hasExtra(EXTRA_NCARD)) {
                    overrideNcard.set(intent.getBooleanExtra(EXTRA_NCARD, true))
                    syncHeadsUpSetting(appCtx)
                }
                if (intent.hasExtra(EXTRA_MAXW)) {
                    overrideMaxW.set(intent.getIntExtra(EXTRA_MAXW, DEFAULT_MAXW_DP))
                    main.post { applyContent() }
                }
                if (intent.hasExtra(EXTRA_API_BADGE)) {
                    overrideApiBadge.set(intent.getBooleanExtra(EXTRA_API_BADGE, true))
                    // 一关就把检测模块整块停用：会话状态清掉，tile 立刻退回手柄
                    syncApiDetector()
                    main.post { applyContent() }
                }
                if (intent.hasExtra(EXTRA_API_BADGE_UNKNOWN)) {
                    overrideApiBadgeUnknown.set(
                        intent.getBooleanExtra(EXTRA_API_BADGE_UNKNOWN, false)
                    )
                    main.post { applyContent() }
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
        val filter = IntentFilter(ACTION_RELOAD).apply {
            addAction(SystemTiles.ACTION_CLICK)
            addAction(SystemTiles.ACTION_REFRESH)
            addAction(SystemTiles.ACTION_SNAPSHOT)
            addAction(com.lsp102.tapdock.HookSync.HookActions.DOCK_QUERY)
            addAction(com.lsp102.tapdock.HookSync.HookActions.SYSSTAT_STATE)
            addAction(com.lsp102.tapdock.HookSync.HookActions.SHOT_STATE)
            addAction(com.lsp102.tapdock.HookSync.HookActions.PKGCODE_STATE)
        }
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
        watchBattery(ctx)
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

    /**
     * 电量：粘性广播先读一次打底，之后每有变化再刷一次 Dock。
     * 不监听就只能等 3 秒兜底轮询，掉到 10% 那一刻的变色会滞后到下一拍。
     */
    private fun watchBattery(ctx: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent ?: return
                applyBatteryIntent(intent)
                refreshIcons()
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        try {
            // ACTION_BATTERY_CHANGED 是受保护的系统广播，NOT_EXPORTED 挡住第三方伪造
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver, filter)
            }
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] battery watcher failed: $t")
        }
    }

    private fun applyBatteryIntent(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) batteryLevel = (level * 100 / scale).coerceIn(0, 100)
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
                // 游戏元数据的采集不能只挂在面板上：面板收着的时候 Dock 那颗游戏图标也要亮
                appContextRef?.get()?.let { GameInfoSource.refresh(it) }
                applyContent()
                // 引擎侧顺便刷新面板在用的系统磁贴（宿主侧这行是空操作）
                SystemTiles.pushStates(cardOrder())
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
                        val bi = c.getColumnIndex("badge")
                        if (bi >= 0) overrideBadge.set(c.getInt(bi) == 1)
                        val otpi = c.getColumnIndex("otp")
                        if (otpi >= 0) overrideOtp.set(c.getInt(otpi) == 1)
                        val poi = c.getColumnIndex("pkgon")
                        if (poi >= 0) overridePkg.set(c.getInt(poi) == 1)
                        val nci = c.getColumnIndex("ncard")
                        if (nci >= 0) overrideNcard.set(c.getInt(nci) == 1)
                        val pci = c.getColumnIndex("pkgcode")
                        if (pci >= 0) PackageCodeSource.ingest(c.getString(pci) ?: "")
                        val gii = c.getColumnIndex("game")
                        if (gii >= 0) overrideGame.set(c.getString(gii) ?: "")
                        val abi = c.getColumnIndex("apibadge")
                        if (abi >= 0) overrideApiBadge.set(c.getInt(abi) == 1)
                        val abui = c.getColumnIndex("apibunk")
                        if (abui >= 0) overrideApiBadgeUnknown.set(c.getInt(abui) == 1)
                        val ahi = c.getColumnIndex("apihist")
                        if (ahi >= 0) overrideApiHistory.set(c.getString(ahi) ?: "")
                        val mwi = c.getColumnIndex("maxw")
                        if (mwi >= 0) overrideMaxW.set(c.getInt(mwi))
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
        // 配置里"接管横幅"变了就顺手下发（内部按上一次状态判重，不会每 3 秒写一次设置）
        syncHeadsUpSetting(ctx)
        // 图形 API：把这一拍的帧和历史喂给检测器（它自己不读通道，UI 只管问它要结论）
        syncApiDetector()
        GraphicsApiSource.observeGraphicsApi(gameInfoRaw(), apiHistoryRaw())
    }

    /** 检测模块跟着"图形 API 标识"那颗开关走：关掉 = 停用，不留会话状态 */
    private fun syncApiDetector() {
        val on = apiBadge()
        GraphicsApiSource.enabled = on
        if (!on) GraphicsApiSource.stopObserving()
    }

    /** 刷新状态图标 + 进度填充 */
    internal fun applyContent() {
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
                b.otp.visibility = View.GONE
                b.root.findViewWithTag<TextView>(TAG_HS)?.visibility = View.GONE
                b.art.visibility = View.GONE
                b.track.visibility = View.GONE
                b.icons.forEach { it.visibility = View.GONE }
                b.priv.forEach { it.visibility = View.GONE }
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
            clampDockWidth(b, context)
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
        // WiFi 按信号强度分格。格数没变就不动图：setImageDrawable 每次都会重绘整条 Dock
        val wifiLevel = if (states[WIFI_ICON]) wifiIconLevel(context) else lastWifiLevel
        if (wifiLevel != lastWifiLevel) {
            lastWifiLevel = wifiLevel
            XposedBridge.log("[TapDock] wifi icon level = $wifiLevel")
            val res = WIFI_LEVEL_ICONS[wifiLevel]
            snapshot.forEach { b ->
                ModuleRes.drawable(context, res)?.let { b.icons[WIFI_ICON].setImageDrawable(it) }
            }
        }
        // 游戏 tile 按这一局实测到的图形 API 换图形。结论没变就不重设图，理由同上（WiFi 分格）
        val gameApi = GraphicsApiSource.currentGraphicsApi()
        val gameSig = if (apiBadge()) "${gameApi?.packageName}:${gameApi?.apiType}" else "off"
        if (gameSig != lastGameApi) {
            lastGameApi = gameSig
            XposedBridge.log("[TapDock] game tile api = ${gameApi?.label ?: "无"}")
            val res = gameTileRes(gameApi)
            snapshot.forEach { b ->
                ModuleRes.drawable(context, res)?.let { b.icons[GAME_ICON].setImageDrawable(it) }
            }
        }
        val warn = privacyWarn()
        // 徽标一关，通知数就完全不参与 Dock 排版：不占位，也不压掉空档时的时钟
        val notifCount =
            if (notifBadge()) synchronized(activeNotifs) { activeNotifs.size } else 0
        val otpText = if (showOtp()) OtpSource.peek()?.text ?: "" else ""
        // 热点那格亮了才写台数：采样最多滞后 5 秒，图标灭了数字得跟着灭，不留尾巴
        val hsCount = HotspotSource.clients
        val hsText = if (!states[HOTSPOT_ICON] || hsCount < 0) "" else hsCount.toString()
        val privPkgs = privacyUsers()
        // 底色换档（隐私 / 低电）也是一次内容变化：不并进 changed 的话只有别的项变了才跟着刷
        val bg = dockBackgroundColor()
        val bgTinted = bg != withAlpha(BG_COLOR_NORMAL)
        val changed = lastStates == null || !lastStates.contentEquals(states) ||
            warn != lastPrivacyWarn || notifCount != lastNotifCount || artShown != lastArtShown ||
            otpText != lastOtp || privPkgs != lastPrivPkgs || hsText != lastHs || bg != lastBg
        if (changed) {
            val anyIcon = anyIconShown(states, artShown) || privPkgs.isNotEmpty() ||
                notifCount > 0 || otpText.isNotEmpty()
            val badgeText = if (notifCount > 99) "99+" else notifCount.toString()
            for (b in snapshot) {
                b.badge.visibility = if (notifCount > 0) View.VISIBLE else View.GONE
                b.badge.text = badgeText
                b.otp.visibility = if (otpText.isEmpty()) View.GONE else View.VISIBLE
                b.otp.text = otpText
                b.root.findViewWithTag<TextView>(TAG_HS)?.apply {
                    visibility = if (hsText.isEmpty()) View.GONE else View.VISIBLE
                    text = hsText
                }
                b.icons.forEachIndexed { i, iv ->
                    iv.visibility = if (showsIcon(states, artShown, i)) View.VISIBLE else View.GONE
                }
                b.priv.forEachIndexed { i, iv -> applyPrivIcon(iv, privPkgs.getOrNull(i)) }
                // 图标与进度条之间的间距只在有内容时保留
                val tlp = b.track.layoutParams as? LinearLayout.LayoutParams
                if (tlp != null) {
                    tlp.marginStart = if (anyIcon) dp(b.root.context, 6) else 0
                    b.track.layoutParams = tlp
                }
            }
            lastStates = states
            lastArtShown = artShown
            lastPrivPkgs = privPkgs
            lastPrivacyWarn = warn
            lastNotifCount = notifCount
            lastOtp = otpText
            lastHs = hsText
            // 隐私占用转红 / 低电转黄转红：都补间过去，别从黑硬跳到警告色
            animateDockBackground(snapshot, bg)
            lastBg = bg
        }

        if (transition) {
            // 面板收起：把体积补间回展开前记下的宽度
            snapshot.forEach {
                animateDockWidth(it, if (it.naturalWidth > 0) it.naturalWidth else it.minWidth)
            }
        }

        // 进度（下载器通知 > 音乐播放 > 充电）
        // 充电满格不再占进度条：100% 是一条没有信息量的满带，让位给时钟
        val percent = downloadPercent()
            ?: music.percent
            ?: ChargeProgressSource.snapshot(context)?.takeIf { it < 100 }
        val shownIcons = states.indices.count { showsIcon(states, artShown, it) } + privPkgs.size
        // Dock 宽度有限：验证码要占位、图标挤到四颗以上，进度条整条让开
        val trackPercent =
            if (otpText.isEmpty() && shownIcons < TRACK_OFF_ICONS) percent else null
        // 时钟只在进度条不占位时出现：两者都要吃图标右边那点剩余宽度，同时上会互相挤
        updateClock(
            snapshot,
            trackPercent == null && !artShown && otpText.isEmpty() && shownIcons <= 2
        )
        applyBackdrop(snapshot, music, bgTinted)
        applyThumb(snapshot, thumbArt, music.token)
        val color = progressColor()
        val colorChanged = color != lastColor
        val nyan = nyanStyle()
        val nyanChanged = nyan != lastNyan
        // 回报一份"此刻 Dock 上到底有什么"给 App 首页的预览；进度条让位时也别报进度
        pushDockMirror(context, states, notifCount, otpText, trackPercent, nyan, color, artShown)
        if (trackPercent == lastPercent && !changed && !colorChanged && !nyanChanged) return
        lastPercent = trackPercent
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
            if (trackPercent == null) {
                b.track.visibility = View.GONE
                continue
            }
            b.track.visibility = View.VISIBLE
            val trackW = b.track.width
            if (trackW > 0) {
                val lp = b.fill.layoutParams
                lp.width = ((trackW * trackPercent) / 100).coerceAtLeast(dp(b.fill.context, 3))
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
     * Dock 背景优先级：告警底色（隐私红 / 低电黄红）> 播放中的模糊封面 > 纯色胶囊。
     * 背景用 FILL 的 BitmapDrawable，所以收起 / 放大的宽度动画期间它会跟着缩放，不露旧尺寸。
     *
     * [tinted] = 当前底色是告警色：这时封面让位，不然糊一层专辑图就把"没电了"看不出来了。
     */
    private fun applyBackdrop(snapshot: List<Binding>, music: MusicState, tinted: Boolean) {
        val art = if (tinted || privacyWarn() || !albumArtBg()) null else music.art
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

    internal fun appLabel(context: Context, pkg: String): String = appLabels.getOrPut(pkg) {
        try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Throwable) {
            pkg
        }
    }

    /** 正在占用摄像头 / 麦克风的应用：两边都用同一个应用时只画一颗 */
    private fun privacyUsers(): List<String> =
        (PrivacyMonitor.cameraPackages + PrivacyMonitor.microphonePackages)
            .distinct().take(PRIV_ICONS)

    /** 一颗占用应用图标位：换应用才取一次图标，取不到就不占位 */
    private fun applyPrivIcon(iv: ImageView, pkg: String?) {
        if (pkg == null) {
            iv.visibility = View.GONE
            iv.tag = null
            return
        }
        if (iv.tag != pkg) {
            iv.tag = pkg
            val icon = appIcon(iv.context, pkg)
            // 状态栏和任务栏两颗图标位共用一个 Drawable 会抢 callback，各取一份
            iv.setImageDrawable(icon?.constantState?.newDrawable(iv.resources, null) ?: icon)
        }
        iv.visibility = if (iv.drawable == null) View.GONE else View.VISIBLE
    }

    internal fun appIcon(context: Context, pkg: String): Drawable? = appIcons.getOrPut(pkg) {
        try {
            context.packageManager.getApplicationIcon(pkg)
        } catch (_: Throwable) {
            null
        }
    }

    // 控制中心的"多进度显示区"要同时看到三个进度源，这里开三个只读入口
    internal fun downloadProgress(): Int? = downloadPercent()

    internal fun musicProgress(ctx: Context): Int? = musicPercent(ctx)

    internal fun chargeProgress(ctx: Context): Int? = ChargeProgressSource.snapshot(ctx)

    /** 播放音乐时 Dock 背景是否用模糊封面（App 里的开关） */
    internal fun albumArtBg(): Boolean =
        overrideAlbum.get() ?: (loadPrefs()?.getBoolean("album_art_bg", false) ?: false)

    /** 进度条左侧是否显示封面小图（App 里的开关） */
    private fun albumThumb(): Boolean =
        overrideThumb.get() ?: (loadPrefs()?.getBoolean("album_thumb", true) ?: true)

    /** 状态区的资源占用图（CPU 全核心 / GPU / 内存 / zram）是否显示（App 里的开关，默认关） */
    internal fun cpuChart(): Boolean =
        overrideCpu.get() ?: (loadPrefs()?.getBoolean("cpu_chart", false) ?: false)

    /** Dock 左侧的通知数徽标（App 里的开关，默认开） */
    private fun notifBadge(): Boolean =
        overrideBadge.get() ?: (loadPrefs()?.getBoolean("notif_badge", true) ?: true)

    /** 短信验证码是否显示在 Dock 上（App 里的开关，默认开） */
    internal fun showOtp(): Boolean =
        overrideOtp.get() ?: (loadPrefs()?.getBoolean("show_otp", true) ?: true)

    /** 快递取件码提示的开关（App 里的开关，默认开） */
    private fun packageCodeOn(): Boolean =
        overridePkg.get() ?: (loadPrefs()?.getBoolean("pkg_code_enabled", true) ?: true)

    /**
     * 当前该提示的取件码：Dock 只按它决定图标亮不亮，
     * 码本身和短信内容只在展开的面板里出现。
     */
    internal fun packageCode(): com.lsp102.tapdock.PackageCodeCollector.Hit? {
        if (!packageCodeOn()) return null
        return PackageCodeSource.current()
    }

    /** 只留 10 秒，3 秒一轮的配置轮询兜不住这么短的窗口，到点单独刷一帧撤下 */
    private val otpExpire = Runnable { applyContent() }

    private fun scheduleOtpExpire() {
        main.removeCallbacks(otpExpire)
        main.postDelayed(otpExpire, OtpSource.TTL_MS)
    }

    /**
     * App 侧 root 采集回来的游戏信息：`pkg|api|宽x高|帧率|时间戳|进程号`，空 = 还没采到。
     *
     * 只认 Provider 那一拍：模块 App 的偏好文件在这台机器上从不落盘（见 DockPrefs.gameInfo），
     * XSharedPreferences 读到的永远是空串。
     */
    internal fun gameInfoRaw(): String = overrideGame.get() ?: ""

    /** App 侧攒下来的图形 API 历史：`pkg|api|时间戳;…`，空 = 一条都没有 */
    internal fun apiHistoryRaw(): String = overrideApiHistory.get() ?: ""

    /** 游戏 tile 换成图形 API 图标（App 里的开关，默认开） */
    internal fun apiBadge(): Boolean =
        overrideApiBadge.get() ?: (loadPrefs()?.getBoolean("api_badge", true) ?: true)

    /** 认不出 API 时是否也画"未知"图形（App 里的开关，默认关） */
    internal fun apiBadgeUnknown(): Boolean =
        overrideApiBadgeUnknown.get()
            ?: (loadPrefs()?.getBoolean("api_badge_unknown", false) ?: false)

    /**
     * 游戏 tile 这一格画哪张图。
     *
     * 开关关着、或这一局还没有结论 → 手柄；认不出 API 默认也留手柄 —— "未知"本身不是信息，
     * 画一颗圈会让人以为检测到了什么，想要的人在设置里单独打开。
     */
    private fun gameTileRes(info: GraphicsApiInfo?): Int {
        val res = when (info?.apiType) {
            GraphicsApiType.VULKAN -> com.lsp102.tapdock.R.drawable.ic_tile_api_vulkan
            GraphicsApiType.OPENGL_ES -> com.lsp102.tapdock.R.drawable.ic_tile_api_gles
            GraphicsApiType.ANGLE -> com.lsp102.tapdock.R.drawable.ic_tile_api_angle
            GraphicsApiType.OTHER -> com.lsp102.tapdock.R.drawable.ic_tile_api_other
            GraphicsApiType.UNKNOWN ->
                if (apiBadgeUnknown()) com.lsp102.tapdock.R.drawable.ic_tile_api_unknown else 0
            null -> 0
        }
        return if (res == 0) com.lsp102.tapdock.R.drawable.ic_tile_game else res
    }

    /** 点验证码：复制到剪贴板，省得在状态栏和短信之间来回抄 */
    private fun copyOtp(context: Context) {
        val code = OtpSource.peek() ?: return
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("code", code.text))
            Toast.makeText(context, "验证码已复制：${code.text}", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] copy otp failed: $t")
        }
    }

    /**
     * 把 Dock 此刻真实渲染的内容回报给模块 App（首页顶部预览用）。
     * 图标位取的是"这一帧真的画出来了"的那些，不是控制器认为该亮的；内容没变就不发。
     */
    private fun pushDockMirror(
        context: Context,
        states: BooleanArray,
        badge: Int,
        otp: String,
        percent: Int?,
        nyan: Boolean,
        color: Int,
        thumb: Boolean,
    ) {
        var bits = 0
        states.forEachIndexed { i, on -> if (on && showsIcon(states, thumb, i)) bits = bits or (1 shl i) }
        val pct = percent ?: -1
        val sig = "$badge|$otp|$bits|$pct|$nyan|${color and 0xFFFFFF}|$thumb"
        if (sig == lastMirrorSig) return
        lastMirrorSig = sig
        val A = com.lsp102.tapdock.HookSync.HookActions
        val intent = Intent(A.DOCK_STATE)
            .setPackage(MODULE_PKG)
            .putExtra(A.EXTRA_M_BADGE, badge)
            .putExtra(A.EXTRA_M_OTP, otp)
            .putExtra(A.EXTRA_M_ICONS, bits)
            .putExtra(A.EXTRA_M_PERCENT, pct)
            .putExtra(A.EXTRA_M_NYAN, nyan)
            .putExtra(A.EXTRA_M_COLOR, color)
            .putExtra(A.EXTRA_M_THUMB, thumb)
        try {
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] mirror broadcast failed: $t")
        }
    }

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
                    // 横幅接管：原生横幅已经被整批关掉，够格弹的那几条改画成 Dock 下方的卡片。
                    // 双参重载会带 RankingMap（系统算好的重要度），单参调用时 args[1] 取不到就是 null
                    if (!isGroupSummary(sbn) && notifCard()) {
                        val ranking = param.args?.getOrNull(1) as? NotificationListenerService.RankingMap
                        appContextRef?.get()?.let { NotificationCard.onPosted(it, sbn, ranking) }
                    }
                    // 验证码走同一条管线：不读短信数据库，只看已经弹出来的通知
                    val before = OtpSource.peek()?.text
                    OtpSource.onPosted(sbn)
                    if (OtpSource.peek()?.text != before) {
                        scheduleOtpExpire()
                        refreshIcons()
                    }
                }
            })
            XposedBridge.hookAllMethods(cls, "onNotificationRemoved", object : XC_MethodHook() {
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    val sbn = param.args?.firstOrNull() as? StatusBarNotification ?: return
                    synchronized(activeNotifs) { activeNotifs.remove(keyOf(sbn)) }
                    synchronized(downloadMap) { downloadMap.remove(sbn.packageName) }
                    // 验证码不在这里清：短信应用常常两三秒就把通知 cancel 掉，
                    // 码得在 Dock 上撑满 OtpSource.TTL_MS 那 10 秒才够抄
                    // 通知被撤掉时卡片跟着收，免得留下一张已经不存在的通知
                    NotificationCard.onRemoved(keyOf(sbn))
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
     * Dock 底色换档：ARGB 补间，复用同一颗 GradientDrawable（保住胶囊圆角）。
     *
     * 从"还没画过"的那一版直接补间会让 Dock 出现时先透明一下，所以第一拍不上动画，
     * 之后每次换档才走补间 —— 掉到 20% / 10% 那两下是要看得出来的渐变。
     */
    private fun animateDockBackground(snapshot: List<Binding>, to: Int) {
        val from = bgShown
        if (from == to) return
        bgAnim?.cancel()
        bgAnim = null
        if (from == 0 || snapshot.isEmpty()) {
            bgShown = to
            snapshot.forEach { it.pill.setColor(to) }
            return
        }
        val a = ValueAnimator.ofObject(ArgbEvaluator(), from, to)
        a.duration = BG_FADE_MS
        a.interpolator = DOCK_INTERPOLATOR
        a.addUpdateListener {
            val c = it.animatedValue as Int
            bgShown = c
            snapshot.forEach { b -> b.pill.setColor(c) }
        }
        bgAnim = a
        a.start()
    }

    /** App 里的宽度上限档位（dp，0 = 不限） */
    private fun dockMaxDp(): Int =
        overrideMaxW.get()
            ?: (loadPrefs()?.getInt("dock_max_dp", DEFAULT_MAXW_DP) ?: DEFAULT_MAXW_DP)

    /**
     * 内容自然宽度超过上限时把 root 钉在上限，否则交回 WRAP_CONTENT。
     * 用 UNSPECIFIED 量一次拿到的是"不算带权重子 view 拉伸"的自然宽度 —— 就是这条 pill 的实际宽度；
     * 钉住之后多出来的收缩由进度条和时钟这两个 weight=1 的孩子吸收，图标 / 徽标 / 验证码不缩。
     * 补间动画期间不插手，否则会和 animateDockWidth 抢 width。
     */
    private fun clampDockWidth(b: Binding, context: Context) {
        val lp = b.root.layoutParams ?: return
        val maxDp = dockMaxDp()
        if (maxDp <= 0) {
            if (lp.width != ViewGroup.LayoutParams.WRAP_CONTENT && b.anim == null) {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                b.root.layoutParams = lp
            }
            return
        }
        val cap = dp(context, maxDp).coerceAtLeast(b.minWidth)
        b.root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val want = if (b.root.measuredWidth > cap) cap else ViewGroup.LayoutParams.WRAP_CONTENT
        if (lp.width != want && b.anim == null) {
            lp.width = want
            b.root.layoutParams = lp
        }
    }

    /**
     * 由 ControlPanel 在同进程内调用。收起时要作废进度/图标缓存，
     * 否则展开期间没走过 applyContent，状态没变的那部分就永远刷不回来。
     */
    fun setPanelOpen(open: Boolean) {
        if (panelOpen == open) return
        panelOpen = open
        if (open) {
            // 面板要占住屏幕顶部，通知卡片先让开
            NotificationCard.onPanelOpen()
        }
        if (!open) {
            // 面板收起：队列里憋着的下一条补上
            NotificationCard.onPanelClosed()
            lastStates = null
            lastPercent = null
        }
        main.post { applyContent() }
    }

    /** 控制中心此刻是否展开（通知卡片用它决定要不要憋着） */
    internal fun panelOpenNow(): Boolean = panelOpen

    /** Dock 在屏幕上的矩形 [left, top, right, bottom]；还没注入或还没量出来返回 null */
    internal fun dockScreenRect(): IntArray? {
        val root = synchronized(bindings) { bindings.firstOrNull() }?.root ?: return null
        if (root.width <= 0 || root.height <= 0) return null
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        return intArrayOf(loc[0], loc[1], loc[0] + root.width, loc[1] + root.height)
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
        packageCode() != null,
        GameInfoSource.dockLive,
        HotspotSource.enabled(context),
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

    /**
     * WiFi 图标该画第几格（WIFI_LEVEL_ICONS 下标）。
     * 关着 / 没关联 / 读不到都回落到 0 = 完整扇形，不让 Dock 上出现空白格。
     */
    @Suppress("DEPRECATION")
    private fun wifiIconLevel(ctx: Context): Int {
        val wm = try {
            ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        } catch (_: Throwable) {
            null
        } ?: return 0
        if (!wm.isWifiEnabled) return 0
        // rssi 不在定位脱敏的字段里（SSID 才是），这里只取信号
        val rssi = try {
            wm.connectionInfo?.rssi ?: Int.MIN_VALUE
        } catch (_: Throwable) {
            return 0
        }
        if (rssi <= -110) return 0
        // 4 格档位给 0..3，图标从第 1 格起画，第 0 格留给兜底
        return WifiManager.calculateSignalLevel(rssi, WIFI_LEVEL_ICONS.size - 1) + 1
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
            XposedBridge.log("[TapDock] status bar icons (wifi/bt/location/vpn/hotspot) hidden")
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
