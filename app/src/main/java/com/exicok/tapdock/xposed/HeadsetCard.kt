package com.lsp102.tapdock.xposed

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import de.robv.android.xposed.XposedBridge

/**
 * 蓝牙耳机连上时，在屏幕底部浮出的一张提示卡（照 iPhone 那颗连接胶囊的样子做）。
 *
 * 只在 SystemUI 进程里挂：耳机接上 / 断开都从系统广播读，用不着 App 参与。
 * 竖屏才出现 —— 横屏（看视频、打游戏）时整块不弹；正在显示时转成横屏也立刻收起，
 * 见 [dismissForLandscape]。
 *
 * 位置贴屏幕底部居中，底部留 [BOTTOM_MARGIN_DP] 避开手势条 / 任务栏；
 * 弹出 = 从下往上滑一小段 + 淡入，收起原路退回去。
 * 停留 [SHOW_MS] 后自动收，点一下也收。
 */
object HeadsetCard {

    /** 卡片停留时长：够看清设备名，又不至于一直挡着 */
    private const val SHOW_MS = 4000L

    /** 滑入 / 滑出时长 */
    private const val ANIM_MS = 260L

    /**
     * ACL 连上后等这么久再用 profile 状态复核。
     * A2DP 要等 SDP 谈完才变 CONNECTED，广播到得比 profile 早就等于没查，200ms 是实测够用的档。
     */
    private const val CONFIRM_DELAY_MS = 200L

    /** 复核最多再等几拍（200ms × 3 = 600ms，够 A2DP 完成 SDP 协商） */
    private const val CONFIRM_TRIES = 3

    /** 同一批插拔事件的合并窗口：系统会为一次插入连报好几个设备类型 */
    private const val EVENT_DEBOUNCE_MS = 800L

    /** 启动时补弹的延时：等 SystemUI 自己的窗口层就绪，否则 overlay 挂得太早会被吞掉 */
    private const val PLUG_IN_DELAY_MS = 2500L

    /** 判定"耳机"用的 profile：音乐（A2DP）+ 通话（HFP） */
    private val AUDIO_PROFILES = intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)

    /**
     * 有线耳机的输出设备类型。
     *
     * 3.5mm 走 WIRED_HEADSET（带麦）/ WIRED_HEADPHONES（不带麦）；
     * USB-C 走 USB_HEADSET / USB_DEVICE —— 实测这台机器插 TANCHJIM 时
     * AudioManager 报的是 0x4000000，也就是 AUDIO_DEVICE_OUT_USB_HEADSET。
     */
    private val WIRED_TYPES = intArrayOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
    )

    /**
     * 电量广播与它的 extra 在公开 SDK 里都是 @hide（`ACTION_BATTERY_LEVEL`
     * / `EXTRA_BATTERY_LEVEL`），所以直接写字面量。这条是系统受保护广播，
     * 只有 SystemUI 这类进程能收到，普通应用发不出来。
     */
    private const val ACTION_BATTERY_LEVEL =
        "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    private const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

    /** 卡片底边距屏幕底部的距离（dp）：让开手势条 / 三键导航栏 */
    private const val BOTTOM_MARGIN_DP = 72

    /** 距屏幕左右边缘的留白（dp） */
    private const val SIDE_MARGIN_DP = 24

    /** 卡片最大宽度（dp）：平板 / 折叠屏展开时别拉成一整条 */
    private const val MAX_WIDTH_DP = 320

    private const val ICON_DP = 22
    private const val PAD_H_DP = 16
    private const val PAD_V_DP = 12

    /** 滑动距离：卡片自身高度的这一比例，短一点才像"浮出来"而不是"飞进来" */
    private const val SLIDE_RATIO = 0.35f

    private const val CARD_BG = 0xF01A1A1A.toInt()
    private const val TITLE_COLOR = 0xFFFFFFFF.toInt()
    private const val SUB_COLOR = 0xCCFFFFFF.toInt()

    private val main = Handler(Looper.getMainLooper())
    private val interp = PathInterpolator(0.2f, 0f, 0f, 1f)

    private var wm: WindowManager? = null
    private var card: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var titleView: TextView? = null
    private var subView: TextView? = null
    private var anim: ValueAnimator? = null
    private var closing = false

    /** 当前卡上显示的设备名；同一台重连就不重新播动画 */
    private var shownName = ""

    /** 电量百分比，-1 = 还没读到 */
    private var battery = -1

    private var started = false

    /** 宿主 context，退出时还要用它摘回调 */
    private var appCtx: Context? = null

    /** AudioManager 回调是否注册成功；失败时只靠蓝牙广播兜底 */
    private var audioCallbackOn = false

    /** 上一次弹卡的时间：一次插拔系统会连报几个设备，用它压成一次 */
    private var lastShownAt = 0L

    private val expire = Runnable { hide() }

    /** 转屏回调：横屏时这张卡不该留在屏幕上 */
    private val orientationWatcher = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                main.post { dismissForLandscape() }
            }
        }

        override fun onLowMemory() {}
    }

    // ------------------------------------------------------------------ 入口

    /**
     * 由 HookDock 在注入时调一次。只在 SystemUI 进程生效。
     *
     * 三条信号源分工：
     *  1. [AudioDeviceCallback]：主力。系统按"音频输出设备"这一层上报，
     *     3.5mm、USB-C、蓝牙耳机都在里面，插拔 / 连断都走同一套回调 —— 这是有线耳机能被看到的关键。
     *  2. 蓝牙广播：只用来补设备名和电量（AudioDeviceInfo 的名字是"USB-Audio - XXX"这种串，
     *     蓝牙那条能拿到更友好的 productName）。
     *  3. 转屏回调：横屏时把卡收掉。
     */
    fun start(context: Context) {
        val ctx = context.applicationContext
        if (ctx.packageName != "com.android.systemui") return
        if (started) return
        started = true
        appCtx = ctx
        try {
            ctx.registerComponentCallbacks(orientationWatcher)
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] headset orientation watcher failed: $t")
        }
        // 主力通道：音频设备插拔。failed 时退回纯广播（至少蓝牙还能用）
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.registerAudioDeviceCallback(deviceCallback, main)
            audioCallbackOn = true
            XposedBridge.log("[TapDock] headset audio device watcher started")
            // 注册前就已经插着的耳机不会再来一次 added 回调，这里补看一眼当前状态。
            // 放在启动时做，重启 SystemUI 之后也能补上那张已经错过的卡。
            val present = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type in WIRED_TYPES || it.isBluetoothAudio() }
            if (present != null) {
                val name = wiredName(present)
                XposedBridge.log("[TapDock] headset already present: $name")
                main.postDelayed({ showByName(name) }, PLUG_IN_DELAY_MS)
            }
        } catch (t: Throwable) {
            audioCallbackOn = false
            XposedBridge.log("[TapDock] headset audio watcher failed: $t")
        }
        registerBluetooth(ctx)
    }

    /** 蓝牙广播：ACL 连断 + 电量。用来补设备名，也作为音频回调不可用时的兜底 */
    private fun registerBluetooth(ctx: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val i = intent ?: return
                when (i.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> onConnected(i)
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> onDisconnected(i)
                    ACTION_BATTERY_LEVEL -> onBattery(i)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            // 系统自己发的电量广播，第三方读不到，SystemUI 这条通道是通的
            addAction(ACTION_BATTERY_LEVEL)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver, filter)
            }
            XposedBridge.log("[TapDock] headset card watcher started")
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] headset card watcher failed: $t")
        }
    }

    /**
     * 音频输出设备变化：这就是有线 / USB / 蓝牙耳机统一的入口。
     *
     * 只看"新增的设备"：一次插拔系统会连报好几个类型（sink + source，或 USB device + headset），
     * 只要其中有一个是耳机类就弹一张，[lastShownAt] 负责把同一批事件压成一次。
     */
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val dev = addedDevices.firstOrNull { it.isSink && it.type in WIRED_TYPES }
                ?: addedDevices.firstOrNull { it.isSink && it.isBluetoothAudio() }
                ?: return
            if (!HookDock.headsetCardOn()) return
            // 同一批插拔事件：800ms 内只认一次。lastShownAt 在 showByName 真正
            // 上屏时才记 —— 被横屏 / 面板 / 息屏压掉的弹卡不消耗这个窗口，
            // 紧跟着的另一台设备还能正常弹
            if (System.currentTimeMillis() - lastShownAt < EVENT_DEBOUNCE_MS) return
            val name = wiredName(dev)
            // 有线 / USB 耳机不报电量，清掉上一台留下的百分比
            battery = -1
            main.post { showByName(name) }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val gone = removedDevices.firstOrNull {
                it.isSink && (it.type in WIRED_TYPES || it.isBluetoothAudio())
            } ?: return
            val name = wiredName(gone)
            main.post {
                // 还插着别的耳机就不撤（比如 USB-C 转 3.5 会同时报两个类型）
                if (anyHeadsetStillConnected()) return@post
                if (card != null && (shownName == name || shownName.isEmpty())) hide()
            }
        }
    }

    /** 现在还有没有耳机类输出设备接着 */
    private fun anyHeadsetStillConnected(): Boolean = try {
        val ctx = appCtx ?: return false
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type in WIRED_TYPES || it.isBluetoothAudio()
        }
    } catch (_: Throwable) {
        false
    }

    private fun AudioDeviceInfo.isBluetoothAudio(): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    /**
     * 有线耳机的显示名。
     *
     * 系统给的是 `USB-Audio - TANCHJIM BUNNY DSP` 这种串，去掉前缀更像人话；
     * 3.5mm 没有 productName，直接写"有线耳机"。
     */
    private fun wiredName(dev: AudioDeviceInfo): String {
        val raw = try {
            dev.productName?.toString()?.trim()
        } catch (_: Throwable) {
            null
        }
        if (raw.isNullOrEmpty()) {
            return when (dev.type) {
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 耳机"
                else -> "有线耳机"
            }
        }
        // "USB-Audio - TANCHJIM BUNNY DSP" → "TANCHJIM BUNNY DSP"
        val cleaned = raw.substringAfterLast(" - ", raw).trim()
        return cleaned.ifEmpty { raw }
    }

    /** 控制中心展开：底部这张卡让位，它不该浮在整屏面板上 */
    fun onPanelOpen() {
        main.post {
            closing = false
            anim?.cancel()
            anim = null
            main.removeCallbacks(expire)
            removeCard()
        }
    }

    /**
     * 由 HookDock 的 3 秒 tick 兜底调用：万一 SystemUI 这边没收到转屏回调
     * （个别 ROM 不往 application context 派发），横屏时也得把卡收掉。
     */
    fun guardOrientation(ctx: Context) {
        if (card == null) return
        if (isLandscape(ctx)) dismissForLandscape()
    }

    /** 开关关掉：把可能正挂着的卡收干净 */
    fun shutdown() {
        main.post {
            closing = false
            anim?.cancel()
            anim = null
            main.removeCallbacks(expire)
            removeCard()
        }
    }

    /**
     * 横屏收起：iPhone 那种底部胶囊只在竖屏出现，横屏（全屏视频 / 游戏）不占画面。
     * 这里不走滑出动画 —— 转屏那一帧窗口尺寸已经变了，直接摘掉最干净。
     */
    fun dismissForLandscape() {
        if (card == null) return
        closing = false
        anim?.cancel()
        anim = null
        main.removeCallbacks(expire)
        removeCard()
        XposedBridge.log("[TapDock] headset card hidden (landscape)")
    }

    // ------------------------------------------------------------------ 蓝牙事件

    /**
     * ACL 刚连上时 profile 往往还没变成 CONNECTED（A2DP 要等 SDP 谈完），
     * 所以先按设备类认一次耳机，再隔一小会儿用 profile 状态复核：
     * 还没连上就再等一拍，最多 [CONFIRM_TRIES] 次。设备类已经确认是耳机时，
     * profile 一直读不到也照常提示（ROM 收权限的情况）；设备类也读不到则不弹。
     */
    private fun onConnected(intent: Intent) {
        val device = deviceOf(intent) ?: return
        // 音频回调可用时，弹卡这件事统一交给它：ACL 连上到 A2DP 就绪之间会先报一次音频设备，
        // 两边都弹就会出现一张卡闪两下
        if (audioCallbackOn) return
        // false = 明确不是音频设备，直接不提示；null = 读不到设备类（缺权限），
        // 这时必须靠 profile 复核确认，确认不了宁可不弹
        val known = looksLikeAudio(device)
        if (known == false) return
        val name = deviceName(device)
        battery = batteryOf(intent)
        confirmThen(device, name, 0, known == true)
    }

    /**
     * 等 profile 变成已连接再上屏。
     *
     * [trusted] = 设备类已经说明这是耳机，此时即使 profile 一直读不到（ROM 收权限）
     * 也照常提示；反之设备类读不到，就必须等到 profile 确认，否则不弹。
     */
    private fun confirmThen(
        device: BluetoothDevice,
        name: String,
        attempt: Int,
        trusted: Boolean,
    ) {
        main.postDelayed({
            if (!HookDock.headsetCardOn()) return@postDelayed
            val ready = profileHas(device) || audioProfileOn()
            if (!ready) {
                if (attempt < CONFIRM_TRIES) {
                    confirmThen(device, name, attempt + 1, trusted)
                } else if (trusted) {
                    showByName(name)
                }
                return@postDelayed
            }
            showByName(name)
        }, CONFIRM_DELAY_MS)
    }

    private fun onDisconnected(intent: Intent) {
        if (audioCallbackOn) return
        val device = deviceOf(intent) ?: return
        if (looksLikeAudio(device) == false) return
        // 走到这里已经断开：别的耳机还连着就不撤这张卡
        if (stillAudioConnected()) return
        val name = deviceName(device)
        main.post {
            if (card != null && (shownName == name || shownName.isEmpty())) hide()
        }
    }

    /** 这台设备本身在某个音频 profile 的已连接列表里（有的 ROM 会收掉 getConnectedDevices） */
    private fun profileHas(device: BluetoothDevice): Boolean =
        audioProfiles().any { list -> list.any { it.address == device.address } }

    /** 还有没有耳机类的 profile 连着（断开一台时判断"要不要撤卡"） */
    private fun stillAudioConnected(): Boolean {
        val lists = audioProfiles()
        // 一条列表都没读到 = 这台机器不给看，只能退回全局 profile 状态
        if (lists.isEmpty()) return audioProfileOn()
        return lists.any { it.isNotEmpty() } || audioProfileOn()
    }

    /** 各音频 profile 的已连接设备列表；读不到的 profile 直接不出现在结果里 */
    @Suppress("UNCHECKED_CAST")
    private fun audioProfiles(): List<List<BluetoothDevice>> {
        val adapter = adapter() ?: return emptyList()
        val m = adapter.javaClass.methods.firstOrNull {
            it.name == "getConnectedDevices" && it.parameterCount == 1
        } ?: return emptyList()
        val out = ArrayList<List<BluetoothDevice>>(AUDIO_PROFILES.size)
        for (p in AUDIO_PROFILES) {
            val list = try {
                m.invoke(adapter, p) as? List<BluetoothDevice>
            } catch (_: Throwable) {
                null
            }
            if (list != null) out.add(list)
        }
        return out
    }

    private fun onBattery(intent: Intent) {
        val device = deviceOf(intent) ?: return
        val pct = batteryOf(intent)
        if (pct < 0) return
        // 设备名是跨进程读，先在当前线程取好，别带进主线程的绘制路径
        val name = deviceName(device)
        main.post {
            battery = pct
            // 卡还在显示同一台才就地改字，否则只留作下次弹出的初值
            if (card != null && name == shownName) {
                subView?.text = subtitle(shownName)
            }
        }
    }

    private fun deviceOf(intent: Intent): android.bluetooth.BluetoothDevice? =
        try {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<android.bluetooth.BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        } catch (_: Throwable) {
            null
        }

    private fun batteryOf(intent: Intent): Int = try {
        intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
    } catch (_: Throwable) {
        -1
    }

    /**
     * 连上的是不是"耳机"：A2DP / HFP 任一 profile 处于已连接态就算。
     *
     * 只看 ACL 会把手表、键盘、车机全算进来，那不是这张卡想提示的东西；
     * 读不到 profile 状态（权限被收）时退回蓝牙类里的音频大类判断。
     */
    private fun audioProfileOn(): Boolean {
        val adapter = adapter() ?: return false
        val profiles = intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
        for (p in profiles) {
            try {
                if (adapter.getProfileConnectionState(p) == BluetoothProfile.STATE_CONNECTED) {
                    return true
                }
            } catch (_: Throwable) {
            }
        }
        return false
    }

    /** profile 查询失败时的兜底：设备类里带音频服务 */
    private fun looksLikeAudio(device: android.bluetooth.BluetoothDevice): Boolean = try {
        val cls = device.bluetoothClass ?: return false
        cls.hasService(BluetoothClass.Service.AUDIO) || cls.hasService(BluetoothClass.Service.RENDER)
    } catch (_: Throwable) {
        false
    }

    private fun deviceName(device: android.bluetooth.BluetoothDevice): String {
        val n = try {
            device.name
        } catch (_: Throwable) {
            null
        }
        return n?.takeIf { it.isNotBlank() } ?: "蓝牙耳机"
    }

    private fun adapter(): BluetoothAdapter? = try {
        BluetoothAdapter.getDefaultAdapter()
    } catch (_: Throwable) {
        null
    }

    // ------------------------------------------------------------------ 上屏

    /** 统一上屏入口：有线、USB、蓝牙都只带一个设备名过来 */
    private fun showByName(name: String) {
        val ctx = appCtx ?: return
        if (!HookDock.headsetCardOn()) return
        // 横屏（全屏视频 / 游戏）不弹；控制中心正展开时也先不弹，免得浮在面板上
        if (isLandscape(ctx)) return
        if (HookDock.panelOpenNow()) return
        if (!screenOn(ctx)) return

        // 到这一步才算真要上屏，防抖时间戳记在这里
        lastShownAt = System.currentTimeMillis()

        // 上一张还在收尾动画：先把它立刻摘干净再挂新的。否则旧卡的
        // onAnimationEnd 会晚于新卡 addView 到来，两个窗口的引用会搅在一起
        if (closing) {
            anim?.cancel()
            anim = null
        }

        // 已经挂着一张：换台设备就换字 + 重新计时，不重放动画
        if (card != null && !closing) {
            if (shownName != name) {
                shownName = name
                titleView?.text = TITLE_TEXT
                subView?.text = subtitle(name)
            } else {
                subView?.text = subtitle(name)
            }
            restartTimer()
            return
        }

        shownName = name
        val metrics = ctx.resources.displayMetrics
        val margin = dp(ctx, SIDE_MARGIN_DP)
        val width = minOf(metrics.widthPixels - margin * 2, dp(ctx, MAX_WIDTH_DP))
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不抢焦点；要能点一下收掉，所以不加 NOT_TOUCHABLE
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(ctx, BOTTOM_MARGIN_DP)
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        val v = build(ctx)
        v.alpha = 0f
        try {
            val w = wm ?: (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { wm = it }
            w.addView(v, params)
            wm = w
            card = v
            lp = params
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] headset card failed: $t")
            return
        }
        // 等首帧量好高度再起滑入：刚 addView 时 height 还是 0，位移会算成 0
        v.doOnPreDraw {
            val slide = (it.height * SLIDE_RATIO).toInt().coerceAtLeast(dp(ctx, 8))
            slideIn(it, params, dp(ctx, BOTTOM_MARGIN_DP) - slide, dp(ctx, BOTTOM_MARGIN_DP))
        }
        restartTimer()
        XposedBridge.log("[TapDock] headset card: $name")
    }

    private fun build(ctx: Context): LinearLayout {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, PAD_H_DP), dp(ctx, PAD_V_DP), dp(ctx, PAD_H_DP), dp(ctx, PAD_V_DP))
            isClickable = true
            clipToOutline = false
            background = GradientDrawable().apply {
                setColor(CARD_BG)
                // 胶囊：半径给足，两端自然是半圆（高度会随字号变化，所以用大值）
                cornerRadius = dp(ctx, 26).toFloat()
            }
            setOnClickListener { hide() }
        }
        val icon = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(TITLE_COLOR)
            ModuleRes.drawable(ctx, com.lsp102.tapdock.R.drawable.ic_headset_notify)?.let {
                setImageDrawable(it)
            }
        }
        root.addView(icon, LinearLayout.LayoutParams(dp(ctx, ICON_DP), dp(ctx, ICON_DP)))

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(ctx).apply {
            setTextColor(TITLE_COLOR)
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            text = TITLE_TEXT
        }
        val sub = TextView(ctx).apply {
            setTextColor(SUB_COLOR)
            textSize = 11f
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            text = subtitle(shownName)
        }
        col.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ),
        )
        col.addView(
            sub,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 2) },
        )
        root.addView(
            col,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(ctx, 10)
            },
        )
        titleView = title
        subView = sub
        return root
    }

    /** 卡片主标题：只说"连上了"，设备名放副标题，两台设备连着也不会有歧义 */
    private const val TITLE_TEXT = "耳机已连接"

    private fun subtitle(name: String): String =
        if (battery in 0..100) "$name · 电量 $battery%" else name

    // ------------------------------------------------------------------ 动画

    private fun slideIn(v: View, p: WindowManager.LayoutParams, fromY: Int, toY: Int) {
        anim?.cancel()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = ANIM_MS
        a.interpolator = interp
        a.addUpdateListener {
            val f = it.animatedFraction
            p.y = (fromY + (toY - fromY) * f).toInt()
            v.alpha = f.coerceAtMost(1f)
            try {
                wm?.updateViewLayout(v, p)
            } catch (_: Throwable) {
            }
        }
        a.start()
        anim = a
    }

    private fun hide() {
        if (card == null || closing) return
        main.removeCallbacks(expire)
        closing = true
        val v = card ?: return
        val p = lp ?: return
        val fromY = p.y
        val ctx = v.context
        // Gravity.BOTTOM 下 y 是"离底边多远"，值越小越靠下 —— 收起要往下走，所以是减
        val toY = (fromY - (v.height * SLIDE_RATIO).toInt().coerceAtLeast(dp(ctx, 8)))
            .coerceAtLeast(0)
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = ANIM_MS
        a.interpolator = interp
        a.addUpdateListener {
            val f = it.animatedFraction
            p.y = (fromY + (toY - fromY) * f).toInt()
            v.alpha = (1f - f).coerceAtLeast(0f)
            try {
                wm?.updateViewLayout(v, p)
            } catch (_: Throwable) {
            }
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                closing = false
                // 只摘 hide 开始时捕获的这一张：收尾期间 showByName 可能已经
                // 挂了新卡，removeCard() 读共享字段会把新卡一起带走（窗口泄漏）
                removeCard(v)
            }
        })
        a.start()
        anim = a
    }

    /**
     * 摘掉 [v] 指向的那张卡；不传就是当前卡。
     * 共享引用只在摘的确实是当前卡时才清 —— hide 动画收尾期间 showByName
     * 可能已经挂了新卡，别把新卡的引用一起抹掉。
     */
    private fun removeCard(v: View? = null) {
        val target = v ?: card ?: return
        val current = card === target
        try {
            wm?.removeView(target)
        } catch (_: Throwable) {
        }
        if (!current) return
        card = null
        titleView = null
        subView = null
        lp = null
        shownName = ""
    }

    private fun restartTimer() {
        main.removeCallbacks(expire)
        main.postDelayed(expire, SHOW_MS)
    }

    // ------------------------------------------------------------------ 杂项

    /**
     * 是否横屏。先看 Resources 的 orientation；个别 ROM 在 SystemUI 进程里这份配置不跟着转，
     * 所以再拿真实显示尺寸比一次（宽 > 高就是横屏）。
     */
    private fun isLandscape(ctx: Context): Boolean {
        if (ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            return true
        }
        return try {
            val dm = ctx.resources.displayMetrics
            dm.widthPixels > dm.heightPixels
        } catch (_: Throwable) {
            false
        }
    }

    private fun screenOn(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isInteractive ?: true
    } catch (_: Throwable) {
        true
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
