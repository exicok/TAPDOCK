package com.lsp102.tapdock

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 强制竖屏的应用名单：勾上的应用一律被 system_server 拦成竖屏，
 * 应用自己声明的横屏 / 传感器方向都会被改写。
 *
 * 名单制而不是全局强制：这台机器上游戏 / 视频必须横屏，全局会把它们全折进去；
 * 想强制谁就勾谁，不勾的完全不受影响。布局沿用下载器选择器（activity_media_picker），
 * 选中集合换成 [DockPrefs.forcePortraitApps]。
 */
class ForcePortraitPickerActivity : AppCompatActivity() {

    private lateinit var prefs: DockPrefs

    private val apps = mutableListOf<AppItem>()
    private val selected = mutableSetOf<String>()
    private lateinit var adapter: PickerAdapter
    private lateinit var tvCount: TextView
    private lateinit var btnAll: Button

    private data class AppItem(
        val label: String,
        val packageName: String,
        val icon: android.graphics.drawable.Drawable,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = DockPrefs(this)
        setContentView(R.layout.activity_media_picker)

        tvCount = findViewById(R.id.tv_count)
        btnAll = findViewById(R.id.btn_all)
        adapter = PickerAdapter()
        val rv = findViewById<RecyclerView>(R.id.rv_apps)
        rv.layoutManager = GridLayoutManager(this, 4)
        rv.adapter = adapter

        selected.addAll(prefs.forcePortraitApps)

        findViewById<Button>(R.id.btn_done).setOnClickListener {
            prefs.forcePortraitApps = selected.toList()
            // 立刻下发；system_server 那边 3 秒缓存的懒查询下一拍就拿到新名单
            syncToHook()
            finish()
        }

        btnAll.setOnClickListener {
            if (apps.isNotEmpty() && selected.size >= apps.size) {
                // 全选状态下再点 = 清空；名单清空后即使开关开着也不会有应用被强制
                selected.clear()
            } else {
                selected.clear()
                apps.forEach { selected.add(it.packageName) }
            }
            refreshAll()
        }

        Thread {
            val list = loadApps()
            runOnUiThread {
                apps.clear()
                apps.addAll(list)
                adapter.notifyDataSetChanged()
                findViewById<TextView>(R.id.tv_empty).visibility =
                    if (apps.isEmpty()) View.VISIBLE else View.GONE
                refreshAll()
            }
        }.start()
    }

    private fun loadApps(): List<AppItem> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            pm.queryIntentActivities(intent, 0)
                .orEmpty()
                .mapNotNull { ri ->
                    val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == packageName) return@mapNotNull null
                    AppItem(
                        ri.loadLabel(pm)?.toString() ?: pkg,
                        pkg,
                        ri.loadIcon(pm),
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun syncToHook() {
        HookSync.notify(this, prefs)
    }

    private fun refreshAll() {
        adapter.notifyDataSetChanged()
        tvCount.text = getString(R.string.picker_count_fmt, selected.size)
        btnAll.text =
            if (apps.isNotEmpty() && selected.size >= apps.size) getString(R.string.picker_none)
            else getString(R.string.picker_all)
    }

    private inner class PickerAdapter : RecyclerView.Adapter<PickerAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_media_picker, parent, false)
            return Holder(v)
        }

        override fun getItemCount(): Int = apps.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = apps[position]
            holder.ivIcon.setImageDrawable(app.icon)
            holder.tvLabel.text = app.label
            val checked = app.packageName in selected
            holder.ivCheck.visibility = if (checked) View.VISIBLE else View.GONE
            holder.itemView.alpha = if (checked) 1f else 0.7f
            holder.itemView.setOnClickListener {
                if (app.packageName in selected) {
                    selected.remove(app.packageName)
                } else {
                    selected.add(app.packageName)
                }
                refreshAll()
            }
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val ivIcon: ImageView = v.findViewById(R.id.iv_icon)
            val ivCheck: ImageView = v.findViewById(R.id.iv_check)
            val tvLabel: TextView = v.findViewById(R.id.tv_label)
        }
    }
}
