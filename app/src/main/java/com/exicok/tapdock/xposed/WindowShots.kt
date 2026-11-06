package com.lsp102.tapdock.xposed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.lsp102.tapdock.HookSync
import de.robv.android.xposed.XposedBridge

/**
 * 小窗卡片上那张窗口画面。
 *
 * 采集在模块 App 里跑（root 截一屏再按矩形裁，见 WindowShotCollector）：SystemUI 这边 exec su
 * 不保证放行。回推走广播（同 SYSSTAT_STATE 那条路）：这台 ROM 的 prefs 跨不了进程。
 *
 * 只在面板开着、并且确有小窗口时发请求：一次 root 截屏几百毫秒，没人看的时候不截。
 * 换面时旧的那张继续挂着，直到新的解出来 —— 卡片闪成空白比慢半拍难看。
 */
object WindowShots {

    /** 请求间隔：画面只用来认"哪个窗口是哪个"，两秒一帧够了，再快是白烧一次 su 往返 */
    private const val ASK_MS = 2_000L

    /** 一帧最多解几张图：和 App 侧的 MAX_WINDOWS 对齐，多出来的一律不解 */
    private const val MAX_SHOTS = 5

    private var askedAt = 0L

    /** 上一次收到的整帧原文，同样的内容不必再比对一遍 */
    private var lastRaw = ""

    /**
     * 任务 id → (Base64, 解好的图)。
     * 存着 Base64 是为了比对：内容没变的窗口不必再解一次，面板每秒都在读这里。
     * 换掉的旧图不主动 recycle —— ImageView 可能还攥着它，回收了下一次绘制就崩。
     */
    private val shots = HashMap<Int, Pair<String, Bitmap>>()

    /** [crops] 每项是 `任务id:左,上,宽,高`（屏幕像素）；没有小窗就别问 */
    fun request(context: Context, crops: List<String>) {
        val live = crops.filter { it.isNotBlank() }
        if (live.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - askedAt < ASK_MS) return
        askedAt = now
        HookSync.queryShots(context, live)
    }

    /** App 广播回来的一帧：`任务id:Base64JPEG;…`（广播回调在 main，和面板读取同一条线程） */
    fun ingest(raw: String) {
        if (raw.isEmpty() || raw == lastRaw) return
        lastRaw = raw
        val fresh = HashSet<Int>()
        raw.split(';').take(MAX_SHOTS).forEach { part ->
            val sep = part.indexOf(':')
            if (sep <= 0) return@forEach
            val id = part.substring(0, sep).toIntOrNull() ?: return@forEach
            val b64 = part.substring(sep + 1)
            fresh.add(id)
            if (shots[id]?.first == b64) return@forEach
            val bmp = decode(b64)
            if (bmp != null) shots[id] = b64 to bmp
        }
        // 这一帧没有的窗口把缓存丢掉：卡片本来也要跟着窗口一起消失
        shots.keys.retainAll(fresh)
    }

    fun bitmap(taskId: Int): Bitmap? = shots[taskId]?.second

    /** 面板关掉时清一次：下次打开从空画面开始，不拿上一轮的旧窗口冒充 */
    fun clear() {
        shots.clear()
        lastRaw = ""
        askedAt = 0L
    }

    private fun decode(b64: String): Bitmap? = try {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (t: Throwable) {
        XposedBridge.log("[TapDock] 小窗画面解码失败: $t")
        null
    }
}
