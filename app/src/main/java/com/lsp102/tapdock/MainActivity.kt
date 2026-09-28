package com.lsp102.tapdock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.DataOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: DockPrefs

    private lateinit var tvHookStatus: TextView
    private lateinit var swHookMode: Switch
    private lateinit var swNyan: Switch
    private lateinit var swAlbum: Switch
    private lateinit var swThumb: Switch
    private lateinit var swCpu: Switch
    private lateinit var rgAlign: RadioGroup
    private lateinit var llPcolors: LinearLayout
    private lateinit var tvMediaSummary: TextView

    private val pcolorViews = mutableListOf<View>()

    private val hookActiveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            showHookActive()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = DockPrefs(this)
        setContentView(R.layout.activity_main)

        tvHookStatus = findViewById(R.id.tv_hook_status)
        swHookMode = findViewById(R.id.sw_hook_mode)
        swNyan = findViewById(R.id.sw_nyan)
        swAlbum = findViewById(R.id.sw_album)
        swThumb = findViewById(R.id.sw_thumb)
        swCpu = findViewById(R.id.sw_cpu)
        rgAlign = findViewById(R.id.rg_align)
        llPcolors = findViewById(R.id.ll_pcolors)
        tvMediaSummary = findViewById(R.id.tv_dl_summary)

        swHookMode.isChecked = prefs.hookEnabled
        swHookMode.setOnCheckedChangeListener { _, checked ->
            prefs.hookEnabled = checked
            syncToHook()
        }

        swNyan.isChecked = prefs.nyanStyle
        swNyan.setOnCheckedChangeListener { _, checked ->
            prefs.nyanStyle = checked
            syncToHook()
        }

        swAlbum.isChecked = prefs.albumArtBg
        swAlbum.setOnCheckedChangeListener { _, checked ->
            prefs.albumArtBg = checked
            syncToHook()
        }

        swThumb.isChecked = prefs.albumThumb
        swThumb.setOnCheckedChangeListener { _, checked ->
            prefs.albumThumb = checked
            syncToHook()
        }

        swCpu.isChecked = prefs.cpuChart
        swCpu.setOnCheckedChangeListener { _, checked ->
            prefs.cpuChart = checked
            syncToHook()
        }

        rgAlign.check(
            if (prefs.dockAlign == DockPrefs.ALIGN_LEFT) R.id.rb_align_left
            else R.id.rb_align_right
        )
        rgAlign.setOnCheckedChangeListener { _, checkedId ->
            prefs.dockAlign =
                if (checkedId == R.id.rb_align_left) DockPrefs.ALIGN_LEFT else DockPrefs.ALIGN_RIGHT
            syncToHook()
        }

        findViewById<Button>(R.id.btn_dl_pick).setOnClickListener {
            startActivity(Intent(this, DownloadPickerActivity::class.java))
        }

        findViewById<Button>(R.id.btn_card_pick).setOnClickListener {
            startActivity(Intent(this, CardOrderActivity::class.java))
        }

        findViewById<Button>(R.id.btn_restart_systemui).setOnClickListener {
            restartSystemUI()
        }

        buildProgressSwatches()
    }

    override fun onResume() {
        super.onResume()
        if (prefs.hookEnabled) {
            tvHookStatus.text = getString(R.string.main_hook_inactive)
        } else {
            tvHookStatus.text = getString(R.string.main_hook_off)
        }
        refreshDlSummary()
        refreshProgressSwatches()
        ContextCompat.registerReceiver(
            this,
            hookActiveReceiver,
            IntentFilter(HookSync.HookActions.ACTIVE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(hookActiveReceiver)
        } catch (_: Exception) {
        }
        HookSync.notify(
            this, prefs.hookEnabled, prefs.dockAlign, prefs.progressColor,
            prefs.downloadPackages, prefs.cardOrder, prefs.nyanStyle, prefs.albumArtBg,
            prefs.albumThumb, prefs.cpuChart,
        )
    }

    private fun showHookActive() {
        tvHookStatus.text = getString(R.string.main_hook_active)
        tvHookStatus.setTextColor(ContextCompat.getColor(this, R.color.brand))
    }

    private fun syncToHook() {
        HookSync.notify(
            this, prefs.hookEnabled, prefs.dockAlign, prefs.progressColor,
            prefs.downloadPackages, prefs.cardOrder, prefs.nyanStyle, prefs.albumArtBg,
            prefs.albumThumb, prefs.cpuChart,
        )
    }

    private fun refreshDlSummary() {
        val n = prefs.downloadPackages.size
        tvMediaSummary.text =
            if (n == 0) getString(R.string.dl_summary_all)
            else getString(R.string.dl_summary_fmt, n)
    }

    // ------------------------------------------------------------------ 进度条颜色

    private fun buildProgressSwatches() {
        llPcolors.removeAllViews()
        pcolorViews.clear()
        PROGRESS_COLORS.forEachIndexed { index, color ->
            val v = View(this)
            val size = (36 * resources.displayMetrics.density).toInt()
            val params = LinearLayout.LayoutParams(size, size)
            if (index > 0) params.marginStart = (10 * resources.displayMetrics.density).toInt()
            v.layoutParams = params
            v.background = swatchDrawable(color, selected = false)
            v.setOnClickListener {
                prefs.progressColor = color
                refreshProgressSwatches()
                syncToHook()
            }
            llPcolors.addView(v)
            pcolorViews.add(v)
        }
    }

    private fun refreshProgressSwatches() {
        PROGRESS_COLORS.forEachIndexed { index, color ->
            val selected = (prefs.progressColor and 0x00FFFFFF) == (color and 0x00FFFFFF)
            pcolorViews.getOrNull(index)?.background = swatchDrawable(color, selected)
        }
    }

    private fun swatchDrawable(color: Int, selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(
                ((if (selected) 4 else 2) * resources.displayMetrics.density).toInt(),
                if (selected) ContextCompat.getColor(this@MainActivity, R.color.brand)
                else Color.parseColor("#55888888")
            )
        }
    }

    // ------------------------------------------------------------------ 重启 SystemUI

    /**
     * 重启 SystemUI 使模块启用/禁用立即生效。
     * LSPosed 环境本身具备 root，这里通过 su 杀掉 SystemUI 进程，系统会自动拉起。
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
                    val exit = proc.waitFor()
                    if (exit == 0) {
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
        // 进度条可选颜色（白色为默认）
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
