package com.lsp102.tapdock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * 控制中心小窗卡片要显示窗口画面：用 root 截一屏，把每个小窗口那块裁出来、压小、Base64 回推 SystemUI。
 *
 * 为什么在 App 侧截：截屏要 READ_FRAME_BUFFER 那一级的能力，SystemUI 虽然是 platform_app 域但
 * `exec su` 在这台机上不保证放行（同 GameInfoCollector / SysStatCollector），而 root 一条 screencap 就有。
 * 整屏只截一次，几个窗口共用同一帧 —— 三个小窗不等于三次截屏。
 *
 * 裁剪交给 [BitmapRegionDecoder]：它只解矩形那一段，不用把 1240×2772 整帧解码进内存。
 * 缩略图刻意做小（宽 160 像素、JPEG 60、RGB_565）：这张图只用来认"哪个窗口是哪个"，
 * 一帧几十 KB，广播那条 binder 通道（1MB 缓冲）才装得下几个窗口。
 */
object WindowShotCollector {

    private const val TAG = "TapDockShot"

    /** 缩略图宽度（像素）；高度按窗口自身比例算 */
    private const val THUMB_W = 160

    private const val JPEG_QUALITY = 60

    /** 一帧最多带几个窗口：再多就超广播缓冲了 */
    private const val MAX_WINDOWS = 5

    /** 一帧 Base64 的总量上限，超了后面的窗口这一拍就不带 */
    private const val MAX_FRAME_CHARS = 220_000

    private const val SCRIPT = "screencap -p"

    /** 一个裁剪请求：任务 id + 它在屏幕上的矩形 */
    private class Req(val id: Int, val x: Int, val y: Int, val w: Int, val h: Int)

    /**
     * [crops] 是 `任务id:左,上,宽,高` 用分号串起来的列表（屏幕像素）。
     * 返回 true 表示这一帧确实推回去了。
     */
    @Suppress("DEPRECATION")
    fun collect(context: Context, crops: String): Boolean {
        val reqs = crops.split(';').mapNotNull(::parse).distinctBy { it.id }.take(MAX_WINDOWS)
        if (reqs.isEmpty()) return false
        val png = runSu() ?: return false
        val dec = try {
            BitmapRegionDecoder.newInstance(png, 0, png.size)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "not a PNG (${png.size}B): $t")
            null
        } ?: return false
        val frame = StringBuilder()
        try {
            val iw = dec.width
            val ih = dec.height
            reqs.forEach { r ->
                val rect = Rect(
                    r.x.coerceIn(0, iw - 1),
                    r.y.coerceIn(0, ih - 1),
                    (r.x + r.w).coerceIn(1, iw),
                    (r.y + r.h).coerceIn(1, ih),
                )
                if (rect.width() < 16 || rect.height() < 16) {
                    android.util.Log.w(TAG, "crop #${r.id} 落在屏外：$rect of ${iw}x$ih")
                    return@forEach
                }
                val opts = BitmapFactory.Options().apply {
                    // 先按 2 的幂粗降采样（PNG 解码时边解边缩，比解完再缩便宜得多），再精确缩到 THUMB_W
                    inSampleSize = highestPow2(rect.width() / THUMB_W)
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val part = try {
                    decode(dec, rect, opts)?.let { jpeg(it) }
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "decode #${r.id} failed: $t")
                    null
                } ?: return@forEach
                // 装不下就这一帧到此为止：宁可后面的窗口这拍没画面，也别把 binder 缓冲撑爆
                if (frame.length + part.length > MAX_FRAME_CHARS) return@forEach
                if (frame.isNotEmpty()) frame.append(';')
                frame.append(r.id).append(':').append(part)
            }
        } finally {
            dec.recycle()
        }
        if (frame.isEmpty()) return false
        HookSync.pushShots(context, frame.toString())
        android.util.Log.i(TAG, "pushed ${reqs.size} windows, ${frame.length} chars")
        return true
    }

    private fun parse(spec: String): Req? = try {
        val (idPart, rect) = spec.split(':')
        val v = rect.split(',').map { it.trim().toInt() }
        if (v.size != 4 || v[2] < 16 || v[3] < 16) null else Req(idPart.toInt(), v[0], v[1], v[2], v[3])
    } catch (_: Throwable) {
        null
    }

    /** 解出矩形并按 2 的幂粗降采样；解完立刻把超采样那张收掉，只留缩好的 */
    private fun decode(dec: BitmapRegionDecoder, rect: Rect, opts: BitmapFactory.Options): Bitmap? {
        val big = dec.decodeRegion(rect, opts) ?: return null
        if (big.width <= THUMB_W) return big
        val small = Bitmap.createScaledBitmap(
            big, THUMB_W, (big.height.toLong() * THUMB_W / big.width).toInt().coerceAtLeast(1), true
        )
        if (small !== big) big.recycle()
        return small
    }

    private fun jpeg(bmp: Bitmap): String? = try {
        val out = ByteArrayOutputStream(24 * 1024)
        val ok = bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bmp.recycle()
        if (!ok) null else Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (t: Throwable) {
        android.util.Log.w(TAG, "jpeg failed: $t")
        bmp.recycle()
        null
    }

    private fun highestPow2(n: Int): Int = if (n < 2) 1 else Integer.highestOneBit(n)

    /** screencap 的 PNG 直接从 stdout 读：走文件要跨 SELinux 域，读管道没这个问题 */
    private fun runSu(): ByteArray? = try {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", SCRIPT))
        val bytes = proc.inputStream.use { it.readBytes() }
        proc.waitFor()
        if (bytes.size < 1024) {
            android.util.Log.w(TAG, "screencap too small: ${bytes.size}B")
            null
        } else {
            bytes
        }
    } catch (t: Throwable) {
        android.util.Log.w(TAG, "su screencap failed: $t")
        null
    }
}
