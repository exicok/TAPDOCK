package com.lsp102.tapdock.xposed

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * 全核心占用图：一格一条竖柱，柱高 = 这个核这一段的占用百分比。
 * 只画不测，数据由 `CpuSource.sample()` 每秒喂进来；竖柱而不是曲线是因为核心数是固定的，
 * 一眼能看出是哪一核在忙。
 */
class CpuChartView(context: Context) : View(context) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gap = 2f * resources.displayMetrics.density
    private val radius = 1.5f * resources.displayMetrics.density
    private var values: FloatArray = FloatArray(0)

    fun setColors(trackColor: Int, barColor: Int) {
        track.color = trackColor
        bar.color = barColor
        invalidate()
    }

    /** 传进来的数组直接持有，不复制：调用方每秒新建一个，画完这帧就没用了 */
    fun setValues(v: FloatArray) {
        values = v
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val n = values.size
        if (n == 0 || width == 0 || height == 0) return
        val w = (width - gap * (n - 1)) / n
        if (w <= 0f) return
        val bottom = height.toFloat()
        var x = 0f
        for (i in 0 until n) {
            val right = x + w
            canvas.drawRoundRect(x, 0f, right, bottom, radius, radius, track)
            val h = bottom * (values[i].coerceIn(0f, 100f) / 100f)
            // 空载的核只留轨道，不然一排 0 高的柱子看着像没画出来
            if (h > radius) canvas.drawRoundRect(x, bottom - h, right, bottom, radius, radius, bar)
            x = right + gap
        }
    }
}
