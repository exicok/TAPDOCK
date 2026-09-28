package com.lsp102.tapdock

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * 控制中心卡片的排序界面：双列网格，长按卡片拖动换位。
 * 顺序写进 DockPrefs.cardOrder，保存的同时用广播直推 Hook 侧（Provider 轮询兜底）。
 */
class CardOrderActivity : AppCompatActivity() {

    private lateinit var prefs: DockPrefs
    private val items = CardSpec.ordered(emptyList()).toMutableList()
    private lateinit var adapter: CardAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = DockPrefs(this)
        setContentView(R.layout.activity_card_order)
        title = getString(R.string.card_order_title)

        items.clear()
        items.addAll(CardSpec.ordered(prefs.cardOrder))

        val rv = findViewById<RecyclerView>(R.id.rv_cards)
        adapter = CardAdapter()
        rv.layoutManager = GridLayoutManager(this, 2)
        rv.adapter = adapter

        ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = makeMovementFlags(
                // 四个方向都要：只给上下的话，双列网格换不了左右列
                ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                    ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
                0,
            )

            override fun onMove(
                recyclerView: RecyclerView,
                from: RecyclerView.ViewHolder,
                to: RecyclerView.ViewHolder,
            ): Boolean {
                val a = from.bindingAdapterPosition
                val b = to.bindingAdapterPosition
                if (a == RecyclerView.NO_POSITION || b == RecyclerView.NO_POSITION) return false
                items.add(b, items.removeAt(a))
                recyclerView.adapter?.notifyItemMoved(a, b)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.alpha = 0.6f
                }
            }

            // 松手即落盘，不必等退出界面
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                viewHolder.itemView.alpha = 1f
                super.clearView(recyclerView, viewHolder)
                save()
            }
        }).attachToRecyclerView(rv)

        findViewById<Button>(R.id.btn_reset).setOnClickListener {
            items.clear()
            items.addAll(CardSpec.values())
            adapter.notifyDataSetChanged()
            save()
        }
    }

    private fun save() {
        prefs.cardOrder = items.map { it.id }
        HookSync.notify(
            this, prefs.hookEnabled, prefs.dockAlign, prefs.progressColor,
            prefs.downloadPackages, prefs.cardOrder, prefs.nyanStyle, prefs.albumArtBg,
            prefs.albumThumb, prefs.cpuChart,
        )
    }

    private inner class CardAdapter : RecyclerView.Adapter<CardHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = CardHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_card_order, parent, false)
        )

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            val spec = items[position]
            holder.label.text = spec.label
            holder.icon.setImageResource(spec.iconRes)
        }
    }

    private class CardHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.iv_icon)
        val label: TextView = view.findViewById(R.id.tv_label)
    }
}
