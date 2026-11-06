package com.lsp102.tapdock.xposed

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.HandlerThread
import de.robv.android.xposed.XposedBridge
import java.io.File

/**
 * 热点：开没开 + 连了几台。
 *
 * 开没开用 `WifiManager.isWifiApEnabled()`（@hide，反射可得，控制面板那颗热点磁贴就是这么读的），
 * 每帧读一次不贵。
 *
 * 台数只有 TetheringService 自己数得准：它才是每个下游客户端的持有者。这里走 `dumpsys tethering`
 * 的 "Client Information" 段落数 `client:` 条数——SystemUI 持有 DUMP 权限，这条路是通的。
 * 起进程要几十毫秒，不能挂在主线程的绘制路径上，所以单独一条后台线程采，绘制侧只读缓存。
 * 采不到就是 -1，Dock 上只画图标不画数字，不硬凑一个假数。
 */
object HotspotSource {

    private const val POLL_MS = 5000L

    /** 连接中的下游设备数；-1 = 这一轮没读到 */
    @Volatile
    var clients = -1
        private set

    private var ctx: Context? = null
    private var handler: Handler? = null
    private var started = false
    private var lastLog = ""

    /** 起后台采样：注入时就调用，之后绘制侧只读 [clients] */
    fun start(context: Context) {
        if (started) return
        started = true
        ctx = context.applicationContext
        val thread = HandlerThread("tapdock-hotspot").apply { start() }
        handler = Handler(thread.looper).apply { postDelayed(poll, POLL_MS) }
    }

    /** 热点是否开着：读不到一律按没开处理，不给 Dock 添一颗点不亮的死图标 */
    fun enabled(context: Context): Boolean = try {
        val wm = context.applicationContext.getSystemService("wifi") ?: return false
        val m = wm.javaClass.methods.firstOrNull {
            it.name == "isWifiApEnabled" && it.parameterCount == 0
        } ?: return false
        m.invoke(wm) == true
    } catch (_: Throwable) {
        false
    }

    private val poll = object : Runnable {
        override fun run() {
            val context = ctx ?: return
            val (count, how) = probe(context)
            clients = count
            if ("$count/$how" != lastLog) {
                lastLog = "$count/$how"
                XposedBridge.log("[TapDock] hotspot clients = $count via $how")
            }
            handler?.postDelayed(this, POLL_MS)
        }
    }

    private fun probe(context: Context): Pair<Int, String> {
        if (!enabled(context)) return -1 to "off"
        val n = dumpCount()
        return if (n >= 0) n to "dumpsys" else arpCount(context) to "arp"
    }

    /** dumpsys 拿不到（无权限 / 拦了 exec / 段落改名）时返回 -1，由 arpCount 接手 */
    private fun dumpCount(): Int {
        val text = try {
            val p = Runtime.getRuntime().exec(arrayOf("dumpsys", "tethering"))
            val out = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            p.destroy()
            out
        } catch (_: Throwable) {
            return -1
        }
        val lines = text.split('\n')
        val head = lines.indexOfFirst { it.trim() == "Client Information:" }
        if (head < 0) return -1
        val indent = lines[head].length - lines[head].trimStart().length
        var n = 0
        var i = head + 1
        while (i < lines.size) {
            val l = lines[i]
            // 段落里的客户端可能全挤在一行（每个 IpServer 一段），按 `client:` 出现次数数最稳
            if (l.isBlank() || l.length - l.trimStart().length <= indent) break
            n += l.split("client:").size - 1
            i++
        }
        return n
    }

    /**
     * 近似兜底：数 ARP 表里不在自己上行网卡上的完整条目。
     * 热点与上行共用一张网卡（ap/sta 并发）时会读偏，所以只在 dumpsys 那条路走不通时才用它。
     */
    private fun arpCount(context: Context): Int = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val active = cm?.activeNetwork
        val up = if (active == null) null else cm.getLinkProperties(active)?.interfaceName
        File("/proc/net/arp").useLines { lines ->
            lines.drop(1).count { l ->
                val cols = l.trim().split(Regex("\\s+"))
                cols.size >= 6 && cols[3].contains(':') && cols[5] != up && cols[5] != "lo"
            }
        }
    } catch (_: Throwable) {
        -1
    }
}
