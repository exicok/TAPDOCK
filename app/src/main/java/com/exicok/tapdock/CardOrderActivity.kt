package com.lsp102.tapdock

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * 卡片页：上面是面板里会出现的格子（长按拖动换位，双列），下面是系统控制块目录
 * （SystemUI 回报的内置磁贴 + App 自己枚举到的第三方 TileService），勾选后作为代理卡片加入。
 *
 * 两类内容共用一个 RecyclerView：标题行和目录行占满两列、不参与拖动，
 * 只有格子行能被 ItemTouchHelper 抓住。
 */
class CardOrderActivity : AppCompatActivity() {

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class Card(val info: CardInfo) : Row()
        data class Toggle(val entry: SysEntry) : Row()
    }

    private lateinit var prefs: DockPrefs
    private lateinit var rv: RecyclerView
    private lateinit var adapter: RowAdapter

    /** 面板里的卡片顺序（原生 id 与 `sys:<spec>` 混排），落盘到 DockPrefs.cardOrder */
    private val order = mutableListOf<String>()
    private var catalog: List<SysEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = DockPrefs(this)
        setContentView(R.layout.activity_card_order)
        title = getString(R.string.card_order_title)

        order.addAll(prefs.cardOrder)
        buildCatalog()

        rv = findViewById(R.id.rv_cards)
        adapter = RowAdapter()
        val lm = GridLayoutManager(this, SPAN)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) =
                if (adapter.getItemViewType(position) == TYPE_CARD) 1 else SPAN
        }
        rv.layoutManager = lm
        rv.adapter = adapter
        rebuildRows()

        attachDragHelper()

        findViewById<Button>(R.id.btn_reset).setOnClickListener {
            order.clear()
            order.addAll(CardSpec.values().map { it.id })
            rebuildRows()
            adapter.notifyDataSetChanged()
            save()
        }

        findViewById<Button>(R.id.btn_sys_refresh).setOnClickListener {
            HookSync.requestCatalog(this)
            buildCatalog()
            rebuildRows()
            adapter.notifyDataSetChanged()
            Toast.makeText(this, catalogSummary(), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // SystemUI 可能在我们离开这页期间回报了新目录
        buildCatalog()
        rebuildRows()
        adapter.notifyDataSetChanged()
    }

    /** 目录 = SystemUI 回报的部分（内置磁贴 / 设备控制块）+ App 本地枚举到的第三方磁贴 */
    private fun buildCatalog() {
        val local = SysTilesLocal.customEntries(packageManager)
        val localSpecs = local.mapTo(HashSet()) { it.spec }
        catalog = (prefs.sysCatalog.filter { it.spec !in localSpecs } + local).distinctBy { it.spec }
    }

    private fun catalogSummary(): String = getString(
        R.string.catalog_summary_fmt,
        catalog.size,
        catalog.count { it.kind == SysCatalog.KIND_STOCK },
    )

    private fun save() {
        prefs.cardOrder = order.toList()
        HookSync.notify(this, prefs)
    }

    private fun rebuildRows() {
        rows.clear()
        rows.add(Row.Header(getString(R.string.card_order_section_cards)))
        Cards.resolve(order, catalog).forEach { rows.add(Row.Card(it)) }

        var listedCards = false
        for (kind in intArrayOf(SysCatalog.KIND_STOCK, SysCatalog.KIND_CUSTOM, SysCatalog.KIND_CONTROL)) {
            val entries = catalog.filter { it.kind == kind }
            if (entries.isEmpty()) continue
            rows.add(Row.Header(SysCatalog.kindLabel(kind)))
            entries.forEach { rows.add(Row.Toggle(it)) }
            if (kind == SysCatalog.KIND_STOCK) listedCards = true
        }
        if (!listedCards && catalog.isEmpty()) {
            rows.add(Row.Header(getString(R.string.catalog_empty)))
        }
    }

    private val rows = mutableListOf<Row>()

    private fun toggleEntry(entry: SysEntry) {
        if (!order.remove(entry.id)) order.add(entry.id)
        rebuildRows()
        adapter.notifyDataSetChanged()
        save()
    }

    private fun attachDragHelper() {
        val directions = ItemTouchHelper.UP or ItemTouchHelper.DOWN or
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = // 只有格子行可拖；四个方向都要，否则双列网格换不了左右列
                if (viewHolder.itemViewType == TYPE_CARD) {
                    makeMovementFlags(directions, 0)
                } else {
                    makeMovementFlags(0, 0)
                }

            override fun onMove(
                recyclerView: RecyclerView,
                from: RecyclerView.ViewHolder,
                to: RecyclerView.ViewHolder,
            ): Boolean {
                val a = from.bindingAdapterPosition
                val b = to.bindingAdapterPosition
                if (a == RecyclerView.NO_POSITION || b == RecyclerView.NO_POSITION) return false
                if (rows[a] !is Row.Card || rows[b] !is Row.Card) return false
                rows.add(b, rows.removeAt(a))
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

            // 松手即落盘：拖动只改顺序，不增减成员，所以不用重建行表
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                viewHolder.itemView.alpha = 1f
                super.clearView(recyclerView, viewHolder)
                order.clear()
                rows.filterIsInstance<Row.Card>().forEach { order.add(it.info.id) }
                save()
            }
        }).attachToRecyclerView(rv)
    }

    private inner class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Header -> TYPE_HEADER
            is Row.Card -> TYPE_CARD
            is Row.Toggle -> TYPE_TOGGLE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val layout = when (viewType) {
                TYPE_CARD -> R.layout.item_card_order
                TYPE_TOGGLE -> R.layout.item_row_toggle
                else -> R.layout.item_row_header
            }
            val v = inflater.inflate(layout, parent, false)
            return when (viewType) {
                TYPE_CARD -> CardHolder(v)
                TYPE_TOGGLE -> ToggleHolder(v)
                else -> HeaderHolder(v)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).title.text = row.title
                is Row.Card -> {
                    holder as CardHolder
                    holder.label.text = row.info.label
                    holder.icon.setImageResource(row.info.iconRes)
                }
                is Row.Toggle -> {
                    holder as ToggleHolder
                    holder.bind(row.entry, row.entry.id in order)
                }
            }
        }
    }

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tv_header)
    }

    private class CardHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.iv_icon)
        val label: TextView = view.findViewById(R.id.tv_label)
    }

    private inner class ToggleHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.iv_icon)
        val label: TextView = view.findViewById(R.id.tv_label)
        val state: TextView = view.findViewById(R.id.tv_state)
        val check: CheckBox = view.findViewById(R.id.cb_entry)

        fun bind(entry: SysEntry, added: Boolean) {
            icon.setImageResource(R.drawable.ic_tile_sys)
            label.text = entry.label
            state.text = getString(
                if (added) R.string.catalog_state_added else R.string.catalog_state_add
            )
            check.isChecked = added
            itemView.setOnClickListener { toggleEntry(entry) }
        }
    }

    companion object {
        private const val SPAN = 2
        private const val TYPE_HEADER = 0
        private const val TYPE_CARD = 1
        private const val TYPE_TOGGLE = 2
    }
}
