package com.lsp102.tapdock.xposed

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * 横向占用条：一个值一条横条，条长 = 百分比。CPU 全核心传 N 个值（左边带序号），
 * GPU / 内存传 1 个值。只画不测，数据每秒由 `SysStatSource` / 内存那路喂进来。
 */
class UsageBarsView(context: Context) : View(context) {

    /** 每行左边画核心序号（全核心图用），单值图不用 */
    var showIndex = false

    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gap = 2f * resources.displayMetrics.density
    private val radius = 1.5f * resources.displayMetrics.density
    private var values: FloatArray = FloatArray(0)

    fun setColors(trackColor: Int, barColor: Int, textColor: Int) {
        track.color = trackColor
        bar.color = barColor
        label.color = textColor
        invalidate()
    }

    /** 传进来的数组直接持有，不复制：调用方每秒新建一个，画完这帧就没用了 */
    fun setValues(v: FloatArray) {
        values = v
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val rows = values.size
        if (rows == 0 || width == 0 || height == 0) return
        val rowH = (height - gap * (rows - 1)) / rows
        if (rowH <= 0f) return
        label.textSize = rowH.coerceAtMost(10f * resources.displayMetrics.density)
        // 序号占的宽度按最大那个数（rows-1）算，不然右边沿会随位数抖
        val labelW = if (showIndex) label.measureText((rows - 1).toString()) + gap else 0f
        val usable = width - labelW
        if (usable <= 0f) return
        var y = 0f
        for (i in 0 until rows) {
            val bottom = y + rowH
            canvas.drawRoundRect(labelW, y, width.toFloat(), bottom, radius, radius, track)
            val w = usable * (values[i].coerceIn(0f, 100f) / 100f)
            if (w > radius) {
                canvas.drawRoundRect(labelW, y, labelW + w, bottom, radius, radius, bar)
            }
            if (showIndex) {
                // 基线按行高居中，includeFontPadding 关掉后这个偏移够用
                canvas.drawText(i.toString(), 0f, y + rowH / 2f - (label.descent() + label.ascent()) / 2f, label)
            }
            y = bottom + gap
        }
    }
}
