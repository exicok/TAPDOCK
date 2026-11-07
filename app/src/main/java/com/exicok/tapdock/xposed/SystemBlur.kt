package com.lsp102.tapdock.xposed

import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.SurfaceControl
import android.view.View

/**
 * SystemUI 自带的窗口级背景模糊（BlurUtils 那条路：SyncRtSurfaceTransactionApplier +
 * setBlurRegions）。控制中心的整屏毛玻璃和游戏侧边栏的局部模糊共用这一份，
 * 免得两边各写一套反射、SurfaceFlinger 的数组格式再各踩一次坑。
 */
object SystemBlur {

    /**
     * 把 [regions] 圈出的部分在 [target] 所在窗口身后糊掉。
     * regions 传空数组、radiusPx 传 0 就是撤销之前的模糊。
     */
    fun apply(target: View, regions: Array<FloatArray>, radiusPx: Int): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val root = target.rootView ?: return false
        val viewRootImpl = root.call("getViewRootImpl") ?: return false
        val sc = viewRootImpl.call("getSurfaceControl") as? SurfaceControl ?: return false
        if (!sc.isValid) return false

        val transactionClass = Class.forName("android.view.SurfaceControl\$Transaction")
        val transaction = transactionClass.getConstructor().newInstance()
        val setBlurRegions = transactionClass.methods.firstOrNull {
            it.name == "setBlurRegions" && it.parameterCount == 2
        } ?: throw NoSuchMethodException("SurfaceControl.Transaction.setBlurRegions")
        setBlurRegions.invoke(transaction, sc, regions)

        val builder = Class.forName(
            "android.view.SyncRtSurfaceTransactionApplier\$SurfaceParams\$Builder"
        ).getConstructor(SurfaceControl::class.java).newInstance(sc)
        builder.require("withMergeTransaction", transaction)
        builder.require("withBackgroundBlurRadius", radiusPx)
        builder.call("withBackgroundBlurScale", 1f)
        builder.call("withOpaque", false)
        val params = builder.require("build") ?: return false

        val applier = Class.forName("android.view.SyncRtSurfaceTransactionApplier")
            .getConstructor(View::class.java).newInstance(root)
        val arr = java.lang.reflect.Array.newInstance(params.javaClass, 1)
        java.lang.reflect.Array.set(arr, 0, params)
        applier.require("scheduleApply", arr)
        return true
    }

    /**
     * SurfaceFlinger 的模糊区数组各版本长度不同：Android 16 及以前每个区 10 个 float，
     * Android 17 起 14 个（四角各带 x/y 两个半径）。长度给短了 native 不抛可捕获的异常，
     * 而是直接 abort 掉整个 SystemUI，所以优先让平台的 BlurRegion 自己生成数组；
     * 只有确认是老版本（有 RoundedRectBlurRegion 之前的 10-float 格式）才手搓。
     */
    fun region(radiusPx: Int, rect: Rect): FloatArray? =
        platformRegion(radiusPx, rect) ?: legacyRegion(radiusPx, rect)

    private fun platformRegion(radiusPx: Int, rect: Rect): FloatArray? {
        val ctor = blurRegionCtor ?: return null
        val toArray = blurRegionToArray ?: return null
        return try {
            val region = ctor.newInstance(
                RectF(
                    rect.left.toFloat(), rect.top.toFloat(),
                    rect.right.toFloat(), rect.bottom.toFloat()
                ),
                FloatArray(8),
                1f, radiusPx.toFloat()
            )
            toArray.invoke(region, 0, 0, 1f, 1f) as? FloatArray
        } catch (_: Throwable) {
            null
        }
    }

    /** 模糊半径、不透明度、left/top/right/bottom，最后是四角的圆角半径 */
    private fun legacyRegion(radiusPx: Int, rect: Rect): FloatArray? {
        if (Build.VERSION.SDK_INT >= 37) return null
        return floatArrayOf(
            radiusPx.toFloat(),
            1f,
            rect.left.toFloat(),
            rect.top.toFloat(),
            rect.right.toFloat(),
            rect.bottom.toFloat(),
            0f, 0f, 0f, 0f,
        )
    }

    private val blurRegionCtor by lazy(LazyThreadSafetyMode.NONE) {
        try {
            Class.forName("android.view.RoundedRectBlurRegion").getConstructor(
                RectF::class.java, FloatArray::class.java,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType
            )
        } catch (_: Throwable) {
            null
        }
    }

    private val blurRegionToArray by lazy(LazyThreadSafetyMode.NONE) {
        try {
            Class.forName("android.view.RoundedRectBlurRegion").methods
                .firstOrNull { it.name == "toFloatArray" && it.parameterCount == 4 }
        } catch (_: Throwable) {
            null
        }
    }

    /** @hide API 的签名各版本有出入，按方法名 + 参数个数匹配 */
    private fun Any.call(name: String, vararg args: Any?): Any? =
        javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
            ?.invoke(this, *args)

    /** 只要求方法存在；void 方法 invoke 返回 null 是正常的 */
    private fun Any.require(name: String, vararg args: Any?): Any? {
        val m = javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
            ?: throw NoSuchMethodException("$name with ${args.size} args")
        return m.invoke(this, *args)
    }
}
