package com.lsp102.tapdock.xposed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * 进度条填充：默认是单色圆角条（沿用 App 里选的进度色），
 * 彩虹猫模式下换成 nyan 六色彩虹带横向流动。
 *
 * 流动刻意用低帧率自绘（约 14fps）：这条带常驻在状态栏里，满帧重绘不划算。
 */
class NyanFillView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = RectF()
    private val clipPath = Path()
    private var nyan = false
    private var cat: Bitmap? = null
    private var solid = Color.WHITE
    private var corner = 0f
    private var phase = 0f

    private val density = resources.displayMetrics.density
    private val stripe = 5f * density

    /**
     * 进度是用 scaleX 表现的，所以条纹按 scaleX 反算宽度 ——
     * 否则 5% 进度时整条彩虹被压成 1/20，颜色全糊在一起。
     */
    private fun stripeWidth() = stripe / scaleX.coerceAtLeast(0.01f)

    /** 彩虹猫图：叠加在彩虹带前端，由本视图直接画，不受轨道几 dp 高限制 */
    fun setCat(bmp: Bitmap?) {
        cat = bmp
        invalidate()
    }

    fun setNyan(on: Boolean) {
        if (nyan == on) return
        nyan = on
        restartLoop()
        invalidate()
    }

    fun setSolidColor(color: Int) {
        solid = color
        invalidate()
    }

    fun setCornerRadius(radius: Float) {
        corner = radius
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartLoop()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(flow)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changed: View, visibility: Int) {
        super.onVisibilityChanged(changed, visibility)
        restartLoop()
    }

    private fun restartLoop() {
        removeCallbacks(flow)
        if (nyan && isShown) postDelayed(flow, FLOW_MS)
    }

    private val flow = object : Runnable {
        override fun run() {
            if (!nyan || !isShown) return
            val sw = stripeWidth()
            phase = (phase + sw * 0.6f) % (sw * COLORS.size)
            invalidate()
            postDelayed(this, FLOW_MS)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (!nyan) {
            paint.color = solid
            canvas.drawRoundRect(0f, 0f, w, h, corner, corner, paint)
            return
        }
        // Dock 用 layoutParams.width 表示进度（scaleX=1），面板进度条用 scaleX 表示进度，
        // 所以带子真正的可见末端是 w*scaleX；猫和收口都按屏幕位置算，再换算回视图坐标，
        // 否则低进度时猫会被 scaleX 压成一条饼。
        val bmp = cat
        val s = scaleX.coerceAtLeast(0.01f)
        var bandRight = w
        var catX = 0f
        if (bmp != null) {
            // 一律先在"屏幕坐标"里算，再除以 scaleX 换回视图坐标（面板用 scaleX 表示进度）
            val bandEnd = w * s
            // 左下限：绝不许探到带子起点以左，那里紧挨着状态图标
            val catScreenX = (bandEnd - INSET_DP * density - bmp.width).coerceAtLeast(0f)
            catX = catScreenX / s
            // 收口点落在猫体内侧：猫头轮廓右上角是透明的，正好对齐仍会漏出一小截彩虹
            bandRight = (minOf(bandEnd, catScreenX + bmp.width - TAIL_DP * density) / s)
                .coerceIn(0f, w)
        }
        clip[0f, 0f, bandRight] = h
        clipPath.reset()
        clipPath.addRoundRect(clip, corner, corner, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        // 从 -一个周期 开始铺，保证流动时左边缘始终有带子补进来
        val sw = stripeWidth()
        var x = -phase - sw * COLORS.size
        var i = 0
        while (x < w) {
            paint.color = COLORS[((i % COLORS.size) + COLORS.size) % COLORS.size]
            canvas.drawRect(x, 0f, x + sw, h, paint)
            x += sw
            i++
        }
        canvas.restore()
        if (bmp != null) {
            // 叠加在带子上、纵向居中：向上探会被状态栏窗口整块裁掉（实测看不见）
            canvas.save()
            canvas.scale(1f / s, 1f, catX, 0f)   // 反掉 scaleX，猫保持原比例
            canvas.drawBitmap(bmp, catX, (h - bmp.height) / 2f, paint)
            canvas.restore()
        }
    }

    companion object {
        private const val FLOW_MS = 70L
        /** 猫右边缘与带子末端的内缩距离 */
        private const val INSET_DP = 2f

        /** 带子在猫体内侧多收一点，避免从透明角落漏出彩虹 */
        private const val TAIL_DP = 6f
        private val COLORS = intArrayOf(
            0xFFFF0000.toInt(), 0xFFFF9900.toInt(), 0xFFFFFF00.toInt(),
            0xFF00FF00.toInt(), 0xFF0099FF.toInt(), 0xFF6633FF.toInt(),
        )
    }
}
