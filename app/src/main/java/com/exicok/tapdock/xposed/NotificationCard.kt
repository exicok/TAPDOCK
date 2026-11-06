package com.lsp102.tapdock.xposed

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dock 接管的通知横幅。
 *
 * 系统原生 heads-up 整批关掉（写全局设置 heads_up_notifications_enabled=0），
 * 改由这里画一张卡片，位置就在原来的横幅区域：贴着 Dock 下沿。
 *
 * 出现 = 把卡片上正对 Dock 的那一点当缩放原点，从 Dock 那条边原地展开到通知区；收起原路缩回。
 * 锚点全程钉在 Dock 上，所以看着确实是"从 Dock 里推出来、再推回去"，不是横着飞过来。
 * 窗口是按内容裁边的，光靠 translation/scale 会被窗口自己剪掉，
 * 所以整段位移是逐帧 updateViewLayout 挪窗口坐标做出来的。
 *
 * 一次只显示一张：新通知到了就直接把卡片内容换成最新的那条（不重放动画），不排队攒着；
 * 点一下打开应用并收起，超时也收起。
 *
 * 少数聒噪源（系统流量警告）另有一小时一次的限流，见 [HOURLY_SOURCES]。
 */
object NotificationCard {

    /** 卡片停留时长，和系统横幅一个量级 */
    private const val SHOW_MS = 5000L

    /** 展开/缩回时长。系统动画缩放 0.5 时实际约 180ms，再短就看不出是从 Dock 推出来的了 */
    private const val ANIM_MS = 360L
    /** 只接管刚到的：超过这个时长还弹出来就是历史重放，不是新通知 */
    private const val FRESH_MS = 5000L
    /** 限流源的间隔：一小时最多弹一次 */
    private const val COOLDOWN_MS = 60 * 60 * 1000L

    /**
     * 需要限流的来源，格式 包名/渠道。
     *
     * android/NETWORK_ALERTS = 系统自己的"流量用量警告"（NetworkPolicyManager，id=34）。
     * 它的重要度是 HIGH，所以原本每次涨流量都会弹；改成一小时一次，
     * 同一小时内再推（用量继续涨、每日重置）只在通知栏里躺着，不出卡片。
     */
    private val HOURLY_SOURCES = setOf("android/NETWORK_ALERTS")
    private const val SIDE_MARGIN_DP = 12
    private const val GAP_DP = 6
    private const val ICON_DP = 22
    private const val PAD_DP = 12

    /** 起始尺寸：约等于 Dock 那颗胶囊的高度占比，看着就是从 Dock 里长出来的 */
    private const val START_SCALE = 0.25f
    private const val CARD_BG = 0xE6101010.toInt()

    /** 系统全局开关：0 = 关掉原生横幅 */
    private const val HEADS_UP_KEY = "heads_up_notifications_enabled"

    private val main = Handler(Looper.getMainLooper())
    private val interp = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private class Item(
        val key: String,
        val title: String,
        val text: String,
        val time: String,
        val icon: Drawable?,
        val open: PendingIntent?,
    )

    private var wm: WindowManager? = null
    private var card: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var titleView: TextView? = null
    private var bodyView: TextView? = null
    private var iconView: ImageView? = null
    private var timeView: TextView? = null
    private var current: Item? = null
    private var anim: ValueAnimator? = null
    private var closing = false
    private var anchoredBottom = false
    /** Dock 那条边上的锚点：卡片从这里长出来，也缩回这里 */
    private var originX = 0
    private var originY = 0
    private var endX = 0
    private var endY = 0
    private var gapPx = 0
    private var pivotX = 0f
    private var pivotY = 0f
    /** 面板展开或卡片正在缩回时憋着的下一条；一有位置就上，后来的会把它挤掉 */
    private var pending: Item? = null
    /** 限流源上次弹卡片的时间，SystemUI 重启即清零（重启后那条会走历史重放被拦，不会补弹） */
    private val lastShown = HashMap<String, Long>()

    private val expire = Runnable { hide() }

    // ------------------------------------------------------------------ 入口

    /**
     * 开或关"由 Dock 接管横幅"。开 = 关掉系统原生横幅，关 = 恢复。
     * 只在配置变化和启动时下发，不去每帧纠正：用户自己进系统设置改回来时不该跟他抢。
     */
    fun applyHeadsUpSetting(ctx: Context, takeover: Boolean) {
        val want = if (takeover) 0 else 1
        try {
            val cur = Settings.Global.getInt(ctx.contentResolver, HEADS_UP_KEY, 1)
            if (cur == want) return
            Settings.Global.putInt(ctx.contentResolver, HEADS_UP_KEY, want)
            XposedBridge.log("[TapDock] heads-up native banners = $want")
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] heads-up setting failed: $t")
        }
    }

    /** 通知到达（SystemUI 通知管线，主线程回调） */
    fun onPosted(
        ctx: Context,
        sbn: StatusBarNotification,
        ranking: NotificationListenerService.RankingMap?,
    ) {
        appContext = ctx
        val n = sbn.notification ?: return
        if (!screenOn(ctx)) return
        if (!interruptive(sbn, n, ranking)) return
        // 监听器重连时系统会把还活着的通知整批重投一遍（重启 SystemUI、模块更新都会），
        // 那是历史不是新消息，只认刚到的
        val age = System.currentTimeMillis() - sbn.postTime
        if (age > FRESH_MS) {
            XposedBridge.log("[TapDock] 历史通知不重放：${sbn.packageName} age=${age}ms")
            return
        }
        val source = "${sbn.packageName}/${n.channelId}"
        if (throttled(source)) {
            XposedBridge.log("[TapDock] 一小时内不重复弹：$source")
            return
        }
        val body = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: n.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val head = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: n.extras?.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString() ?: ""
        val title = head.ifEmpty { HookDock.appLabel(ctx, sbn.packageName) }
        if (title.isEmpty() && body.isEmpty()) return
        val item = Item(
            key = "${sbn.packageName}:${sbn.id}",
            title = title,
            text = body,
            time = timeFmt.format(Date(sbn.postTime)),
            icon = iconOf(ctx, sbn),
            open = n.contentIntent,
        )
        main.post { enqueue(ctx, item) }
    }

    /**
     * 限流源在 COOLDOWN_MS 内弹过就 return true，放行时顺手把这次记上。
     *
     * 记账时机在放行那一刻、不在上屏之后：卡片可能被控制中心挡住延后出现，也可能压根没人看，
     * 但用户要的是"一小时最多打扰一次"，从被接受那刻起算最稳。
     */
    private fun throttled(source: String): Boolean {
        if (source !in HOURLY_SOURCES) return false
        val now = System.currentTimeMillis()
        if (now - (lastShown[source] ?: 0L) < COOLDOWN_MS) return true
        lastShown[source] = now
        return false
    }

    /**
     * 够不够格弹横幅听系统的：双参 onNotificationPosted 带进来的 RankingMap 里，
     * 这条的重要度 >= HIGH 才是原生横幅会放行的那种（后台进度条、静默通知都刷不了屏）。
     *
     * 为什么不用 Notification.getChannelImportance：它是 @hide，反射在这台机器上被隐藏 API
     * 挡掉了；NotificationListenerService.getRankingMap() 也不是公开 API。只有回调送的这份现成数据。
     */
    private fun interruptive(
        sbn: StatusBarNotification,
        n: Notification,
        ranking: NotificationListenerService.RankingMap?,
    ): Boolean {
        val data = ranking?.let { map ->
            try {
                val out = NotificationListenerService.Ranking()
                if (map.getRanking(sbn.key, out)) out else null
            } catch (_: Throwable) {
                null
            }
        }
        // 宿主只覆盖了单参重载、拿不到 RankingMap 时，退回 O 之前的 priority 判断
        return if (data != null) data.importance >= NotificationManager.IMPORTANCE_HIGH
        else n.priority >= Notification.PRIORITY_HIGH
    }

    /** 通知被撤（划掉 / 应用自己取消）：正在显示的那张立刻收 */
    fun onRemoved(key: String) {
        main.post {
            if (current?.key == key) {
                hide()
            } else if (pending?.key == key) {
                pending = null
            }
        }
    }

    /** 控制中心展开：卡片让位，新来的先憋着 */
    fun onPanelOpen() {
        main.post {
            if (card != null) dropCard()
        }
    }

    /** 控制中心收起：把憋着的那条放出来 */
    fun onPanelClosed() {
        main.post { appContext?.let { pump(it) } }
    }

    /** 开关关掉：恢复原生横幅前要先把自家卡片收干净 */
    fun shutdown() {
        main.post {
            pending = null
            current = null
            anim?.cancel()
            anim = null
            closing = false
            main.removeCallbacks(expire)
            removeCard()
        }
    }

    // ------------------------------------------------------------------ 上屏

    private fun enqueue(ctx: Context, item: Item) {
        val v = card
        if (v != null && !closing) {
            // 卡片正显示：新通知直接把内容换掉，不重放动画——一屏就一张，最新那条说话
            bind(item)
            restartTimer()
            XposedBridge.log("[TapDock] 卡片就地刷新：${item.title}")
            return
        }
        pending = item
        pump(ctx)
    }

    private fun pump(ctx: Context) {
        if (card != null || closing || HookDock.panelOpenNow()) return
        val item = pending ?: return
        pending = null
        show(ctx, item)
    }

    // ------------------------------------------------------------------ 显示

    private fun show(ctx: Context, item: Item) {
        val metrics = ctx.resources.displayMetrics
        val margin = dp(ctx, SIDE_MARGIN_DP)
        gapPx = dp(ctx, GAP_DP)
        val width = metrics.widthPixels - margin * 2
        val dock = HookDock.dockScreenRect()
        // Dock 在上半屏（状态栏形态）卡片从它下沿往下长；在下半屏（12L 任务栏）往上长
        anchoredBottom = dock != null && dock[1] > metrics.heightPixels / 2
        // 锚点取 Dock 靠卡片这侧边的中点：出现绕它展开、收起缩回它，才像从 Dock 里推出来
        originX = if (dock == null) margin + width / 2 else (dock[0] + dock[2]) / 2
        originY = when {
            dock == null -> margin
            anchoredBottom -> dock[1]
            else -> dock[3]
        }
        endX = margin
        endY = when {
            dock == null -> margin
            anchoredBottom -> margin // 往上长得知道自身高度，留到 doOnPreDraw 再定
            else -> dock[3] + gapPx
        }

        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 焦点不给（不抢输入），但要可点：所以不能加 FLAG_NOT_TOUCHABLE。
            // 带 IN_SCREEN 是为了让 x/y 按整屏算，锚点才能落在状态栏里的 Dock 上
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // 一律按左上角定位，缩放 + 位移的换算才只有一套公式
            gravity = Gravity.TOP or Gravity.START
            x = originX
            y = originY
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        val v = build(ctx, item)
        v.alpha = 0f
        try {
            val w = wm ?: (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { wm = it }
            w.addView(v, params)
            wm = w
            card = v
            lp = params
            current = item
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] notification card failed: $t")
            current = null
            pending = null
            return
        }
        v.doOnPreDraw {
            // 卡片上正对锚点的那个局部坐标就是缩放原点：位移和放大叠加后，锚点全程钉在 Dock 上
            pivotX = (originX - endX).toFloat().coerceIn(0f, it.width.toFloat())
            pivotY = if (anchoredBottom) it.height.toFloat() else 0f
            it.pivotX = pivotX
            it.pivotY = pivotY
            if (anchoredBottom && dock != null) endY = dock[1] - gapPx - it.height
            place(it, params, 0f)
            runAnim(reverse = false) {}
        }
        restartTimer()
        XposedBridge.log(
            "[TapDock] notification card: ${item.title} 从 Dock($originX,$originY) 展开到 ($endX,$endY)"
        )
    }

    /** 进度 f（0=缩在 Dock 上，1=停在通知区）摆一帧：锚点平移 + 从小放大 */
    private fun place(v: View, p: WindowManager.LayoutParams, f: Float) {
        val s = START_SCALE + (1f - START_SCALE) * f
        val ax = originX + ((endX + pivotX) - originX) * f
        val ay = originY + ((endY + pivotY) - originY) * f
        p.x = (ax - pivotX * s).toInt()
        p.y = (ay - pivotY * s).toInt()
        v.scaleX = s
        v.scaleY = s
        // 透明度走在前面：一出发就该看见，否则前半段缩太小等于凭空闪一下
        v.alpha = (f * 2f).coerceAtMost(1f)
        try {
            wm?.updateViewLayout(v, p)
        } catch (_: Throwable) {
        }
    }

    private fun build(ctx: Context, item: Item): LinearLayout {
        val pad = dp(ctx, PAD_DP)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            isClickable = true
            clipToOutline = false
            background = GradientDrawable().apply {
                setColor(CARD_BG)
                cornerRadius = dp(ctx, 16).toFloat()
            }
        }
        val icon = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(icon, LinearLayout.LayoutParams(dp(ctx, ICON_DP), dp(ctx, ICON_DP)))

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        val title = TextView(ctx).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val body = TextView(ctx).apply {
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 12f
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(
            title,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        col.addView(
            body,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        root.addView(
            col,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(ctx, 10)
            },
        )
        val time = TextView(ctx).apply {
            setTextColor(0x80FFFFFF.toInt())
            textSize = 11f
            includeFontPadding = false
        }
        root.addView(
            time,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(ctx, 8) },
        )
        iconView = icon
        titleView = title
        bodyView = body
        timeView = time
        // 点击取"此刻显示的那条"：内容会被新通知就地换掉，捕获旧 item 会开错应用
        root.setOnClickListener {
            current?.let { openApp(it) }
            hide()
        }
        bind(item)
        return root
    }

    /** 换掉卡片身上显示的那条通知：只动内容不重放动画，也不改窗口位置 */
    private fun bind(item: Item) {
        val iv = iconView ?: return
        val tv = titleView ?: return
        val bv = bodyView ?: return
        val rv = timeView ?: return
        iv.setImageDrawable(item.icon)
        iv.visibility = if (item.icon == null) View.GONE else View.VISIBLE
        tv.text = item.title
        bv.text = item.text
        bv.visibility = if (item.text.isEmpty()) View.GONE else View.VISIBLE
        rv.text = item.time
        current = item
    }

    // ------------------------------------------------------------------ 收起

    private fun hide() {
        if (card == null || closing) return
        main.removeCallbacks(expire)
        closing = true
        runAnim(reverse = true) {
            closing = false
            removeCard()
            appContext?.let { pump(it) }
        }
    }

    /** 直接把窗口摘掉，不播动画（面板展开抢屏时用） */
    private fun dropCard() {
        main.removeCallbacks(expire)
        anim?.cancel()
        anim = null
        closing = false
        removeCard()
    }

    private fun removeCard() {
        val v = card
        card = null
        titleView = null
        bodyView = null
        iconView = null
        timeView = null
        current = null
        lp = null
        try {
            if (v != null) wm?.removeView(v)
        } catch (_: Throwable) {
        }
    }

    private fun restartTimer() {
        main.removeCallbacks(expire)
        main.postDelayed(expire, SHOW_MS)
    }

    /**
     * 位移 + 缩放 + 淡入淡出：f=0 在 Dock 那一头（小、透明），f=1 在横幅位（实际尺寸）。
     * reverse=true 就是收起。
     */
    private fun runAnim(reverse: Boolean, onEnd: () -> Unit) {
        val v = card ?: return
        val p = lp ?: return
        anim?.cancel()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = ANIM_MS
        a.interpolator = interp
        a.addUpdateListener {
            place(v, p, if (reverse) 1f - it.animatedFraction else it.animatedFraction)
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                onEnd()
            }
        })
        a.start()
        anim = a
    }

    // ------------------------------------------------------------------ 杂项

    private fun openApp(item: Item) {
        val pi = item.open ?: return
        try {
            pi.send()
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] notification intent failed: $t")
        }
    }

    private fun iconOf(ctx: Context, sbn: StatusBarNotification): Drawable? {
        val large = try {
            // Notification.largeIcon 这个公开字段是 Bitmap，取 Icon 必须显式调 getter
            sbn.notification?.getLargeIcon()?.loadDrawable(ctx)
        } catch (_: Throwable) {
            null
        }
        return large ?: HookDock.appIcon(ctx, sbn.packageName)
    }

    private fun screenOn(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isInteractive ?: true
    } catch (_: Throwable) {
        true
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    /** 收起一张后接着放下一张，得有个上下文；由 onPosted 带进来 */
    private var appContext: Context? = null
}
