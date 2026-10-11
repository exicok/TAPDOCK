package com.lsp102.tapdock

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import java.io.DataOutputStream

/**
 * 设置首页：顶栏 + 两个子页入口 + 分组列表行 + 右下 FAB + 底部三分类导航。
 * 列表行按当前分类在代码里现建（十几行结构相同，写死在 XML 里太长也不好复用）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: DockPrefs
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView

    /** 预览卡片的小标题，注入状态写在这里 */
    private var previewLabel: TextView? = null

    /** Hook 回报过一次 ACTIVE 就为真，进程内不再回退 */
    private var injected = false

    /** 只在「Dock」分类下存在，回来时刷新文案 */
    private var tvDlSub: TextView? = null

    private val pcolorViews = mutableListOf<View>()
    private var previewCard: View? = null
    private var tab = TAB_DOCK

    /** 未读短信里的取件码要先拿到 READ_SMS */
    private val smsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) rescanPackageCode() else renderTab()
        }

    private val hookActiveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                HookSync.HookActions.ACTIVE -> showHookActive()
                HookSync.HookActions.DOCK_STATE -> {
                    DockMirror.last = DockMirror.from(intent)
                    // 能收到内容回报就说明 Hook 在 SystemUI 里活着：ACTIVE 是注入那一刻发的，
                    // App 后打开就赶不上，不能只靠它判断
                    injected = true
                    previewCard?.let { bindPreview(it) }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = DockPrefs(this)
        setContentView(R.layout.activity_main)

        content = findViewById(R.id.ll_content)
        scroll = findViewById(R.id.scroll_content)

        findViewById<View>(R.id.btn_card_pick).setOnClickListener {
            startActivity(Intent(this, CardOrderActivity::class.java))
        }
        findViewById<View>(R.id.btn_dl_pick).setOnClickListener {
            startActivity(Intent(this, DownloadPickerActivity::class.java))
        }
        findViewById<View>(R.id.fab_restart).setOnClickListener { restartSystemUI() }

        findViewById<BottomNavigationView>(R.id.bottom_nav).setOnItemSelectedListener { item ->
            tab = when (item.itemId) {
                R.id.tab_panel -> TAB_PANEL
                else -> TAB_DOCK
            }
            renderTab()
            scroll.post { scroll.fullScroll(View.FOCUS_UP) }
            true
        }

        renderTab()
    }

    override fun onResume() {
        super.onResume()
        renderTab()
        ContextCompat.registerReceiver(
            this,
            hookActiveReceiver,
            IntentFilter(HookSync.HookActions.ACTIVE).apply {
                addAction(HookSync.HookActions.DOCK_STATE)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        // 让 SystemUI 回报一次 Dock 现在画的是什么，预览才有真内容
        HookSync.queryDockState(this)
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(hookActiveReceiver)
        } catch (_: Exception) {
        }
        HookSync.notify(this, prefs)
    }

    private fun showHookActive() {
        injected = true
        previewLabel?.text = previewTitle()
    }

    private fun syncToHook() {
        HookSync.notify(this, prefs)
    }

    private fun hookStatusText(): String = when {
        injected -> getString(R.string.main_hook_active)
        !prefs.hookEnabled -> getString(R.string.main_hook_off)
        else -> getString(R.string.main_hook_inactive)
    }

    /** 预览卡片的小标题顺带报注入状态：模块页删了，这个信息得有地方看 */
    private fun previewTitle(): String =
        getString(R.string.preview_dock_title) + " · " + hookStatusText()

    // ------------------------------------------------------------------ 两个分类

    private fun renderTab() {
        content.removeAllViews()
        pcolorViews.clear()
        previewLabel = null
        tvDlSub = null
        previewCard = null
        when (tab) {
            TAB_PANEL -> panelTab()
            else -> dockTab()
        }
    }

    private fun dockTab() {
        previewRow()
        sectionHeader(R.drawable.ic_nav_dock, R.string.sec_dock_body)
        switchRow(R.drawable.ic_nav_dock, R.string.main_hook_switch, R.string.sub_hook_switch, prefs.hookEnabled) {
            prefs.hookEnabled = it
            syncToHook()
        }
        switchRow(R.drawable.ic_row_badge, R.string.opt_notif_badge, R.string.sub_badge, prefs.notifBadge) {
            prefs.notifBadge = it
            syncToHook()
        }
        switchRow(R.drawable.ic_row_notif, R.string.opt_notif_card, R.string.sub_notif_card, prefs.notifCard) {
            prefs.notifCard = it
            syncToHook()
        }
        switchRow(R.drawable.ic_row_otp, R.string.opt_otp, R.string.sub_otp, prefs.showOtp) {
            prefs.showOtp = it
            syncToHook()
        }
        switchRow(R.drawable.ic_row_package, R.string.opt_pkg, R.string.sub_pkg, prefs.packageCodeEnabled) {
            prefs.packageCodeEnabled = it
            syncToHook()
            if (it) {
                if (!PackageCodeCollector.permissionGranted(this)) {
                    smsPermission.launch(Manifest.permission.READ_SMS)
                } else {
                    rescanPackageCode()
                }
            }
        }
        pkgPermRow()
        alignRow()
        maxWidthRow()
        switchRow(
            R.drawable.ic_tile_api_vulkan, R.string.opt_api_badge, R.string.sub_api_badge,
            prefs.apiBadge,
        ) {
            prefs.apiBadge = it
            syncToHook()
        }
        switchRow(
            R.drawable.ic_tile_api_unknown, R.string.opt_api_unknown, R.string.sub_api_unknown,
            prefs.apiBadgeUnknown,
        ) {
            prefs.apiBadgeUnknown = it
            syncToHook()
        }
        switchRow(
            R.drawable.ic_tile_api_other, R.string.opt_api_history, R.string.sub_api_history,
            prefs.apiHistoryEnabled,
        ) {
            prefs.apiHistoryEnabled = it
            // 这条开关的意思就是"别留底"，关掉时把已经攒下的清掉
            if (!it) prefs.apiHistory = ""
            syncToHook()
        }
        sectionHeader(R.drawable.ic_row_color, R.string.sec_progress)
        colorRow()
        switchRow(R.drawable.ic_music_note, R.string.opt_album_bg, R.string.sub_album, prefs.albumArtBg) {
            prefs.albumArtBg = it
            syncToHook()
        }
        switchRow(R.drawable.ic_row_thumb, R.string.opt_album_thumb, R.string.sub_thumb, prefs.albumThumb) {
            prefs.albumThumb = it
            syncToHook()
        }
        switchRow(R.drawable.ic_row_nyan, R.string.opt_nyan, R.string.sub_nyan, prefs.nyanStyle) {
            prefs.nyanStyle = it
            syncToHook()
        }
        downloadRow()
        footer()
    }

    private fun panelTab() {
        sectionHeader(R.drawable.ic_nav_panel, R.string.sec_cards)
        actionRow(R.drawable.ic_nav_panel, R.string.opt_card_order, R.string.sub_cards) {
            startActivity(Intent(this, CardOrderActivity::class.java))
        }
        sectionHeader(R.drawable.ic_row_chart, R.string.sec_status)
        switchRow(R.drawable.ic_row_chart, R.string.opt_cpu_chart, R.string.sub_chart, prefs.cpuChart) {
            prefs.cpuChart = it
            syncToHook()
        }
    }

    /** 页尾说明：模块页删掉后，启用说明和用法提示留在这里 */
    private fun footer() {
        val hint = TextView(this).apply {
            text = getString(R.string.main_hint) + "\n\n" + getString(R.string.main_hook_desc)
            setTextAppearance(R.style.Text_Desc)
            setPadding(dp(4), dp(14), dp(4), dp(4))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        content.addView(hint, matchWrap())
    }

    // ------------------------------------------------------------------ 行构造

    /** 分区标题：小图标 + 标题 + 一条把区域切开的横线 */
    private fun sectionHeader(iconRes: Int, titleRes: Int) {
        val v = layoutInflater.inflate(R.layout.item_section_header, content, false)
        v.findViewById<ImageView>(R.id.iv_sec_icon).setImageResource(iconRes)
        v.findViewById<TextView>(R.id.tv_sec_title).setText(titleRes)
        content.addView(v, matchWrap())
    }

    /** 顶部预览：一条迷你 Dock，画的是 SystemUI 回报的真实内容 */
    private fun previewRow() {
        val card = layoutInflater.inflate(R.layout.item_dock_preview, content, false)
        previewCard = card
        bindPreview(card)
        content.addView(card, matchWrap())
    }

    private fun bindPreview(card: View) {
        val pill = card.findViewById<LinearLayout>(R.id.pill)
        val hint = card.findViewById<TextView>(R.id.tv_preview_hint)
        val label = card.findViewById<TextView>(R.id.tv_preview_label)
        label.text = previewTitle()
        previewLabel = label
        val st = DockMirror.last
        pill.removeAllViews()
        if (st == null) {
            hint.visibility = View.VISIBLE
            hint.setText(R.string.preview_dock_waiting)
            return
        }
        val gap = dp(4)
        if (st.badge > 0) {
            pill.addView(
                TextView(this).apply {
                    text = if (st.badge > 99) "99+" else st.badge.toString()
                    textSize = 9f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    typeface = Typeface.MONOSPACE
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0x40FFFFFF)
                    }
                },
                LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = gap }
            )
        }
        st.icons().forEach { res ->
            pill.addView(
                ImageView(this).apply {
                    setImageResource(res)
                    setColorFilter(Color.WHITE)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = gap }
            )
        }
        if (st.otp.isNotEmpty()) {
            pill.addView(
                TextView(this).apply {
                    text = st.otp
                    textSize = 10f
                    setTextColor(Color.WHITE)
                    includeFontPadding = false
                    typeface = Typeface.MONOSPACE
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = gap }
            )
        }
        if (st.percent >= 0) addMiniTrack(pill, st)
        hint.visibility = if (pill.childCount == 0) View.VISIBLE else View.GONE
        hint.setText(R.string.preview_dock_idle)
    }

    /** 迷你进度条：轨道占剩余宽度，填充按回报的百分比画；彩虹猫模式用六色彩带 */
    private fun addMiniTrack(pill: LinearLayout, st: DockMirror) {
        val h = dp(5)
        val track = FrameLayout(this)
        track.background = GradientDrawable().apply {
            setColor(0x26FFFFFF)
            cornerRadius = h / 2f
        }
        val fill = View(this)
        fill.background = GradientDrawable().apply {
            cornerRadius = h / 2f
            if (st.nyan) {
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                colors = intArrayOf(
                    0xFFFF5252.toInt(), 0xFFFFD54F.toInt(), 0xFF66BB6A.toInt(),
                    0xFF42A5F5.toInt(), 0xFFAB47BC.toInt(), 0xFFFF8A65.toInt(),
                )
            } else {
                setColor(if (st.color == -1) Color.WHITE else st.color)
            }
        }
        track.addView(
            fill,
            FrameLayout.LayoutParams(dp(24), FrameLayout.LayoutParams.MATCH_PARENT)
        )
        pill.addView(
            track,
            LinearLayout.LayoutParams(0, h, 1f).apply { marginStart = dp(2) }
        )
        track.post {
            val w = track.width
            if (w > 0) {
                val lp = fill.layoutParams
                lp.width = (w * st.percent / 100f).toInt().coerceAtLeast(dp(3))
                fill.layoutParams = lp
            }
        }
    }

    /** 一行：图标块 + 标题 + 辅助文本；返回行根视图，调用方再取里面的子 view */
    private fun row(iconRes: Int, title: String, sub: String): View {
        val v = layoutInflater.inflate(R.layout.item_setting_row, content, false)
        v.findViewById<ImageView>(R.id.iv_icon).setImageResource(iconRes)
        v.findViewById<TextView>(R.id.tv_title).text = title
        v.findViewById<TextView>(R.id.tv_sub).text = sub
        content.addView(v, matchWrap())
        return v
    }

    private fun switchRow(iconRes: Int, titleRes: Int, subRes: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        val v = row(iconRes, getString(titleRes), getString(subRes))
        val sw = v.findViewById<Switch>(R.id.sw_row)
        sw.visibility = View.VISIBLE
        sw.isChecked = initial
        v.setOnClickListener {
            val now = !sw.isChecked
            sw.isChecked = now
            onChange(now)
        }
    }

    /** 子页入口：右侧箭头，整行可点 */
    private fun actionRow(iconRes: Int, titleRes: Int, subRes: Int, onClick: () -> Unit) {
        val v = row(iconRes, getString(titleRes), getString(subRes))
        v.findViewById<View>(R.id.iv_chevron).visibility = View.VISIBLE
        v.setOnClickListener { onClick() }
    }

    private fun downloadRow() {
        val v = row(
            R.drawable.ic_row_download,
            getString(R.string.opt_download_apps),
            dlSummary(),
        )
        v.findViewById<View>(R.id.iv_chevron).visibility = View.VISIBLE
        tvDlSub = v.findViewById(R.id.tv_sub)
        v.setOnClickListener { startActivity(Intent(this, DownloadPickerActivity::class.java)) }
    }

    private fun dlSummary(): String {
        val n = prefs.downloadPackages.size
        return if (n == 0) getString(R.string.sub_download_all)
        else getString(R.string.sub_download_fmt, n)
    }

    /** 取件码的授权态 + 当前扫到的码；没授权时这一行就是入口 */
    private fun pkgPermRow() {
        if (!prefs.packageCodeEnabled) return
        val granted = PackageCodeCollector.permissionGranted(this)
        val hit = if (granted) PackageCodeCollector.decode(prefs.packageCode) else null
        val sub = when {
            !granted -> getString(R.string.sub_pkg_denied)
            hit != null -> getString(R.string.sub_pkg_found, hit.code, hit.label)
            else -> getString(R.string.sub_pkg_none)
        }
        val v = row(R.drawable.ic_row_package, getString(R.string.pkg_perm), sub)
        v.findViewById<View>(R.id.iv_chevron).visibility = View.VISIBLE
        v.setOnClickListener {
            if (granted) rescanPackageCode() else smsPermission.launch(Manifest.permission.READ_SMS)
        }
    }

    /** 读短信库是跨进程查询，别压在 UI 线程上 */
    private fun rescanPackageCode() {
        val app = applicationContext
        Thread {
            PackageCodeCollector.current(app, force = true)
            runOnUiThread { if (!isFinishing && !isDestroyed) renderTab() }
        }.start()
    }

    /** 位置：同一行里两个胶囊，选中的那个染色 */
    private fun alignRow() {
        val v = row(R.drawable.ic_row_align, getString(R.string.opt_align), getString(R.string.sub_align))
        val extra = v.findViewById<LinearLayout>(R.id.ll_extra)
        extra.visibility = View.VISIBLE
        extra.addView(
            segButton(getString(R.string.align_left), prefs.dockAlign == DockPrefs.ALIGN_LEFT) {
                setAlign(DockPrefs.ALIGN_LEFT)
            }
        )
        extra.addView(
            segButton(getString(R.string.align_right), prefs.dockAlign == DockPrefs.ALIGN_RIGHT) {
                setAlign(DockPrefs.ALIGN_RIGHT)
            }
        )
    }

    private fun setAlign(value: String) {
        if (prefs.dockAlign == value) return
        prefs.dockAlign = value
        renderTab()
        syncToHook()
    }

    /** 宽度上限四档；0 = 不限 */
    private fun maxWidthRow() {
        val v = row(
            R.drawable.ic_row_width,
            getString(R.string.opt_dock_max),
            getString(R.string.sub_dock_max),
        )
        val extra = v.findViewById<LinearLayout>(R.id.ll_extra)
        extra.visibility = View.VISIBLE
        MAX_WIDTHS.forEachIndexed { i, dp ->
            extra.addView(
                segButton(getString(MAX_LABELS[i]), prefs.dockMaxDp == dp) {
                    if (prefs.dockMaxDp == dp) return@segButton
                    prefs.dockMaxDp = dp
                    renderTab()
                    syncToHook()
                }
            )
        }
    }

    /** 进度条颜色：色点直接铺在行内第二行 */
    private fun colorRow() {
        val v = row(R.drawable.ic_row_color, getString(R.string.opt_progress_color), getString(R.string.sub_color))
        val extra = v.findViewById<LinearLayout>(R.id.ll_extra)
        extra.visibility = View.VISIBLE
        val size = dp(24)
        PROGRESS_COLORS.forEachIndexed { index, color ->
            val dot = View(this)
            val lp = LinearLayout.LayoutParams(size, size)
            if (index > 0) lp.marginStart = dp(6)
            dot.layoutParams = lp
            dot.background = swatchDrawable(color, isColorSelected(index))
            dot.setOnClickListener {
                prefs.progressColor = color
                renderTab()
                syncToHook()
            }
            extra.addView(dot)
            pcolorViews.add(dot)
        }
    }

    private fun isColorSelected(index: Int): Boolean {
        val color = PROGRESS_COLORS.getOrNull(index) ?: return false
        return (prefs.progressColor and 0x00FFFFFF) == (color and 0x00FFFFFF)
    }

    private fun swatchDrawable(color: Int, selected: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(
            dp(if (selected) 3 else 1),
            if (selected) attrColor(com.google.android.material.R.attr.colorPrimary)
            else Color.parseColor("#55888888"),
        )
    }

    private fun segButton(text: String, selected: Boolean, onClick: () -> Unit): MaterialButton {
        val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        b.text = text
        b.isAllCaps = false
        b.insetTop = 0
        b.insetBottom = 0
        b.minimumHeight = dp(34)
        b.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) }
        if (selected) {
            b.backgroundTintList = ColorStateList.valueOf(
                attrColor(com.google.android.material.R.attr.colorSecondaryContainer)
            )
            b.strokeWidth = 0
            b.setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
        } else {
            b.setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        b.setOnClickListener { onClick() }
        return b
    }

    private fun attrColor(attr: Int): Int {
        val ta = theme.obtainStyledAttributes(intArrayOf(attr))
        try {
            return ta.getColor(0, Color.GRAY)
        } finally {
            ta.recycle()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

    // ------------------------------------------------------------------ 重启 SystemUI

    /**
     * 重启 SystemUI 使模块启用 / 禁用立即生效。LSPosed 环境本身具备 root，
     * 这里通过 su 杀掉 SystemUI 进程，系统会自动拉起。
     */
    private fun restartSystemUI() {
        val commands = listOf(
            "kill \$(pidof com.android.systemui)",
            "killall com.android.systemui",
        )
        Thread {
            var ok = false
            for (cmd in commands) {
                try {
                    val proc = Runtime.getRuntime().exec("su")
                    val out = DataOutputStream(proc.outputStream)
                    out.writeBytes("$cmd\n")
                    out.flush()
                    out.writeBytes("exit\n")
                    out.flush()
                    if (proc.waitFor() == 0) {
                        ok = true
                        break
                    }
                } catch (_: Exception) {
                }
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    getString(if (ok) R.string.restart_ok else R.string.restart_fail),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    companion object {
        private const val TAB_DOCK = 0
        private const val TAB_PANEL = 1

        /** Dock 最大宽度档位（dp，0 = 不限） */
        private val MAX_WIDTHS = intArrayOf(160, 220, 280, 0)
        private val MAX_LABELS = intArrayOf(
            R.string.max_short, R.string.max_std, R.string.max_long, R.string.max_none,
        )

        /** 进度条可选颜色（白色为默认） */
        private val PROGRESS_COLORS = intArrayOf(
            0xFFFFFFFF.toInt(),
            0xFF3DDC84.toInt(),
            0xFF4F8CFF.toInt(),
            0xFFFF5252.toInt(),
            0xFFFFD54F.toInt(),
            0xFF40C4C4.toInt(),
            0xFFE040FB.toInt(),
            0xFF9E9E9E.toInt(),
        )
    }
}

