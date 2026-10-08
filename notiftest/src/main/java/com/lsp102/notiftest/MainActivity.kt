package com.lsp102.notiftest

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TapDock 通知卡片的手动测试台。
 *
 * 之所以要有这个 App：`cmd notification post` 的渠道重要度固定在 3，够不到"会弹横幅"
 * 那一档，而反射读重要度在 SystemUI 里又被隐藏 API 拦了——只能由真 App 建真渠道来喂。
 */
class MainActivity : Activity() {

    private val nm: NotificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private val main = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private lateinit var logView: TextView
    private lateinit var bodyInput: EditText
    private lateinit var permRow: TextView
    private var scroller: ScrollView? = null
    private val levelBtns = ArrayList<Button>()
    private val live = ArrayList<Int>()

    private var level = NotificationManager.IMPORTANCE_HIGH
    private var seq = 1000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureChannels()
        setContentView(buildUi())
        log("就绪。TapDock 首页「通知横幅由 Dock 接管」打开时，只有 HIGH 档会出卡片")
        refreshPerm()
        // savedInstanceState 非空 = 系统恢复旧任务，带回来的是栈里存的任务意图（上次那个用例），
        // 不是谁新发的请求，别重放
        handleIntent(intent, savedInstanceState == null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, true)
    }

    /** 除了手点，还能从命令行直接触发：am start -n 本活动 --es fire high */
    private fun handleIntent(intent: Intent?, allowFire: Boolean) {
        intent ?: return
        intent.getIntExtra(EXTRA_FROM, -1).takeIf { it > 0 }?.let { log("从通知 #$it 点开") }
        val case = intent.getStringExtra(EXTRA_FIRE) ?: return
        intent.removeExtra(EXTRA_FIRE)
        if (!allowFire) {
            log("忽略恢复任务时带回的旧用例：$case")
            return
        }
        dispatch(case)
    }

    private fun dispatch(case: String) {
        when (case.lowercase(Locale.US)) {
            "min" -> { level = NotificationManager.IMPORTANCE_MIN; refreshLevels(); fireCurrent() }
            "low" -> { level = NotificationManager.IMPORTANCE_LOW; refreshLevels(); fireCurrent() }
            "default" -> { level = NotificationManager.IMPORTANCE_DEFAULT; refreshLevels(); fireCurrent() }
            "high" -> { level = NotificationManager.IMPORTANCE_HIGH; refreshLevels(); fireCurrent() }
            "queue" -> fireQueue()
            "update" -> fireSameId()
            "big" -> fireBig()
            "group" -> fireGroup()
            "progress" -> fireProgress()
            "cooldown" -> fireCooldown()
            "cancel" -> cancelAll()
            else -> log("未知用例：$case")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPerm()
    }

    // ---------------------------------------------------------------- 渠道

    private fun ensureChannels() {
        for (lv in LEVELS) {
            val ch = NotificationChannel(chId(lv), "测试 ${name(lv)}", lv).apply {
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                description = "TapDock 通知卡片测试：${name(lv)}"
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun chId(lv: Int) = "tapdock_test_$lv"

    private fun name(lv: Int) = when (lv) {
        NotificationManager.IMPORTANCE_MIN -> "MIN"
        NotificationManager.IMPORTANCE_LOW -> "LOW"
        NotificationManager.IMPORTANCE_DEFAULT -> "DEFAULT"
        else -> "HIGH"
    }

    // ---------------------------------------------------------------- 发通知

    private fun nextId() = ++seq

    private fun bodyText(): String {
        val custom = bodyInput.text?.toString()?.trim()
        if (!custom.isNullOrEmpty()) return custom
        return "编号 #$seq · ${timeFmt.format(Date())} · 点卡片回到本页面"
    }

    private fun post(
        id: Int,
        lv: Int,
        title: String,
        text: String,
        big: Boolean = false,
        ongoing: Boolean = false,
        group: String? = null,
        summary: Boolean = false,
        progress: Int = -1,
    ) {
        val pi = PendingIntent.getActivity(
            this, id,
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_FROM, id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(this, chId(lv))
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setColor(ACCENT)
        if (big) b.setStyle(Notification.BigTextStyle().bigText(text + LONG_TAIL))
        if (group != null) {
            b.setGroup(group)
            if (summary) b.setGroupSummary(true)
        }
        if (ongoing) b.setOngoing(true)
        if (progress >= 0) b.setProgress(100, progress, false)
        nm.notify(id, b.build())
        if (!live.contains(id)) live.add(id)
        log("notify #$id ${name(lv)} · $title")
    }

    private fun cancelAll() {
        live.forEach { nm.cancel(it) }
        log("撤回 ${live.size} 条")
        live.clear()
    }

    // ---------------------------------------------------------------- 用例

    private fun fireCurrent() = post(nextId(), level, "一条 ${name(level)} 通知", bodyText())

    /** 队列：卡片一屏一条，后来的直接把前一条挤掉，不攒队列 */
    private fun fireQueue() {
        cancelAll()
        for (i in 0 until 5) {
            val n = i + 1
            main.postDelayed({
                val id = nextId()
                post(id, NotificationManager.IMPORTANCE_HIGH, "队列第 $n 条", bodyText())
            }, i * 600L)
        }
        log("连发 5 条，间隔 600ms：第一条滑出后，后面每条都只换卡片文字，最后停在第 5 条")
    }

    /** 同 id 复投：卡片就地换字，不重放动画 */
    private fun fireSameId() {
        val id = nextId()
        post(id, NotificationManager.IMPORTANCE_HIGH, "就地更新 1/4", bodyText())
        for (i in 1 until 4) {
            main.postDelayed({
                post(id, NotificationManager.IMPORTANCE_HIGH, "就地更新 ${i + 1}/4", bodyText())
            }, i * 1400L)
        }
        log("同一条 #$id 改 4 次：卡片只出现一次，文字逐次变")
    }

    /**
     * 限流：同一渠道在冷却窗口内只弹一次卡片。
     * 卡片停留 5 秒，所以第二条（7 秒后）已经不在"就地刷新"那条路径上了，
     * 只有冷却判断能拦下它。把本渠道配进模块的限流名单才能看到效果。
     */
    private fun fireCooldown() {
        post(nextId(), NotificationManager.IMPORTANCE_HIGH, "限流 1（该弹）", bodyText())
        main.postDelayed({
            post(nextId(), NotificationManager.IMPORTANCE_HIGH, "限流 2（不该弹）", bodyText())
        }, 7000L)
        main.postDelayed({
            post(nextId(), NotificationManager.IMPORTANCE_HIGH, "限流 3（窗口过后该弹）", bodyText())
        }, 18000L)
        log("三条间隔 7s/11s：首条弹，第二条压掉，第三条看冷却窗口是否已过")
    }

    private fun fireBig() {
        cancelAll()
        val id = nextId()
        post(id, NotificationManager.IMPORTANCE_HIGH, "长文本 BigTextStyle", bodyText(), big = true)
    }

    /** summary 壳不该单独出卡片，两条子通知该出 */
    private fun fireGroup() {
        cancelAll()
        val g = "tapdock_grp_${nextId()}"
        val sum = nextId()
        post(sum, NotificationManager.IMPORTANCE_HIGH, "分组 summary（不该弹）", "这只是壳", group = g, summary = true)
        post(nextId(), NotificationManager.IMPORTANCE_HIGH, "子通知 A", bodyText(), group = g)
        main.postDelayed({
            post(nextId(), NotificationManager.IMPORTANCE_HIGH, "子通知 B", bodyText(), group = g)
        }, 700L)
        log("1 条 summary + 2 条子通知：只有 A、B 弹卡片")
    }

    /** 后台进度条：LOW + ongoing 连投，接管后不该刷屏 */
    private fun fireProgress() {
        val id = nextId()
        var step = 0
        fun tick() {
            val p = step * 10
            post(id, NotificationManager.IMPORTANCE_LOW, "正在下载 $p%", "LOW + ongoing，不该出卡片", ongoing = true, progress = p)
            if (step < 10) {
                step++
                main.postDelayed({ tick() }, 350L)
            }
        }
        tick()
        log("进度条 #$id 从 0 走到 100：整个过程不该出现卡片")
    }

    // ---------------------------------------------------------------- 权限

    private fun permMissing(): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    private fun refreshPerm() {
        if (!::permRow.isInitialized) return
        if (permMissing()) {
            permRow.text = "还没拿到通知权限，发出去也没人显示"
            permRow.visibility = View.VISIBLE
        } else {
            permRow.visibility = View.GONE
        }
    }

    private fun askPerm() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        log("权限结果：${grantResults.firstOrNull()}（0=同意）")
        refreshPerm()
    }

    // ---------------------------------------------------------------- 界面

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        root.addView(text("通知卡片测试台", 20f, Typeface.BOLD, Color.BLACK))
        root.addView(
            text(
                "给 Dock 的横幅接管喂通知：HIGH 档才会被接管成卡片，" +
                    "其余档位应当安静地留在通知栏里。",
                13f,
                Typeface.NORMAL,
                Color.DKGRAY,
            ).apply { setPadding(0, dp(6), 0, dp(12)) }
        )

        root.addView(
            text(
                "命令行触发：adb shell am start -n com.lsp102.notiftest/.MainActivity " +
                    "--es fire high|queue|update|big|group|progress|cooldown|cancel",
                11f,
                Typeface.NORMAL,
                Color.parseColor("#FF1769AA"),
            ).apply { setPadding(0, 0, 0, dp(10)) }
        )

        permRow = text("", 13f, Typeface.BOLD, Color.parseColor("#FFB30000")).apply {
            visibility = View.GONE
            setOnClickListener { askPerm() }
        }
        root.addView(permRow)
        root.addView(rowOf {
            addView(btn("请求通知权限") { askPerm() })
            addView(btn("系统通知设置") {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            })
        })

        val lv = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in LEVELS) {
            val b = btn(name(i)) {
                level = i
                refreshLevels()
                log("当前重要度 = ${name(i)}")
            }
            levelBtns.add(b)
            lv.addView(b)
        }
        root.addView(label("渠道重要度（决定该不该弹卡片）"))
        root.addView(HorizontalScrollView(this).apply { addView(lv) })
        refreshLevels()

        root.addView(label("正文（留空则自动编号）"))
        bodyInput = EditText(this).apply {
            hint = "自定义正文"
            maxLines = 2
            setTextSize(14f)
            layoutParams = lp()
        }
        root.addView(bodyInput)

        root.addView(label("用例"))
        listOf(
            Pair("① 发一条当前重要度") { fireCurrent() },
            Pair("② 连发 5 条 · 只看最新") { fireQueue() },
            Pair("③ 同一条改 4 次 · 测就地换字") { fireSameId() },
            Pair("④ 长文本 BigTextStyle") { fireBig() },
            Pair("⑤ 分组 summary + 2 子条") { fireGroup() },
            Pair("⑥ 后台进度条（不该刷屏）") { fireProgress() },
            Pair("⑦ 撤回全部（卡片应立刻收起）") { cancelAll() },
            Pair("⑧ 冷却窗口内连发（只弹第一条）") { fireCooldown() },
        ).forEach { (t, run) -> root.addView(btn(t) { run() }) }

        root.addView(label("日志"))
        logView = text("", 11f, Typeface.NORMAL, Color.DKGRAY).apply {
            typeface = Typeface.MONOSPACE
            layoutParams = lp()
            setBackgroundColor(Color.parseColor("#FFF3F3F3"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(logView)

        return ScrollView(this).also { scroller = it }.apply { addView(root) }
    }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(6) }

    private fun rowOf(block: LinearLayout.() -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = lp()
        block()
    }

    private fun label(s: String) = text(s, 12f, Typeface.BOLD, Color.parseColor("#FF666666"))
        .apply { setPadding(0, dp(14), 0, dp(4)) }

    private fun text(s: String, size: Float, tf: Int, color: Int) = TextView(this).apply {
        text = s
        textSize = size
        setTypeface(null, tf)
        setTextColor(color)
    }

    private fun btn(label: String, block: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setOnClickListener { block() }
    }

    private fun refreshLevels() {
        LEVELS.forEachIndexed { i, lv ->
            val on = lv == level
            levelBtns[i].setBackgroundColor(if (on) ACCENT else Color.parseColor("#FFE8E8E8"))
            levelBtns[i].setTextColor(if (on) Color.WHITE else Color.BLACK)
        }
    }

    private fun log(s: String) {
        if (!::logView.isInitialized) return
        logView.append("${timeFmt.format(Date())}  $s\n")
        scroller?.post { scroller?.fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        private const val EXTRA_FROM = "from_id"
        private const val EXTRA_FIRE = "fire"
        private val ACCENT = Color.parseColor("#FF1769AA")
        private val LEVELS = intArrayOf(
            NotificationManager.IMPORTANCE_MIN,
            NotificationManager.IMPORTANCE_LOW,
            NotificationManager.IMPORTANCE_DEFAULT,
            NotificationManager.IMPORTANCE_HIGH,
        )
        private const val LONG_TAIL =
            "\n第二行：卡片正文最多两行，超出按尾部省略。\n" +
                "第三行：再长也只会截断，不会把卡片顶高——这是接管卡片和原生横幅的差别。"
    }
}
