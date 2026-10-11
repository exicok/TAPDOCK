package com.lsp102.tapdock

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat

/**
 * 快递取件码：只从短信数据库的【未读收件箱】里找。
 *
 * 刻意不做兜底：正文里出现一串孤立数字不算数，必须同时命中取件类关键词和
 * "取件码 / 凭码"后面那一段码型（三段码或 4~12 位字母数字）。宁可漏掉一条，
 * 也不把运单号、金额或营销短信里的数字挂到状态栏上。
 *
 * 命中即认为"短信里存在"，用户读过短信（read=1）或者在面板上点了"已取"就消失。
 */
object PackageCodeCollector {

    /** 一条取件码：`id|code|label|date|summary` 单行传输，字段里不允许出现 | 和换行 */
    class Hit(val id: Long, val code: String, val label: String, val date: Long, val summary: String)

    private const val TAG = "TapDockPkgCode"

    /** 未读短信最多翻这么多条，够覆盖几天的取件通知了 */
    private const val SCAN_ROWS = 40

    /** Provider 每 3 秒就被 Hook 侧查一次，别每次都真去读库 */
    private const val RESCAN_MS = 60_000L

    /** 测试注入的存活时间：到期自己消失，不用谁来收尾 */
    private const val TEST_TTL_MS = 5 * 60_000L

    private val PROJECTION = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
    )

    /** 只认未读：读过了就说明用户已经知道这条，Dock 没必要一直挂着 */
    private const val SELECTION = "${Telephony.Sms.READ} = 0"

    private val CODE_AFTER = Regex(
        "取件码[^0-9A-Za-z]{0,4}([0-9A-Za-z][0-9A-Za-z\\-–—_.]{1,15})"
    )

    /** 驿站三段码：`8-3-1024`，没有"取件码"字样时靠这个形状 */
    private val TRIPLE = Regex("(?<![0-9A-Za-z-])(\\d{1,2}-\\d{1,2}-\\d{3,6})(?![0-9-])")

    private val BRACKET = Regex("【([^】]{2,12})】")

    private val KEYWORDS = listOf(
        "取件", "驿站", "快递柜", "代收", "包裹", "快件", "投递", "自提", "到件", "存件", "柜机",
    )

    /** 营销 / 物流状态短信：有码型数字也不是让去取件 */
    private val REJECT = listOf("退订", "回t", "回复t", "优惠券", "折扣", "已发货", "派送中", "运输中")

    @Volatile
    private var lastScanAt = 0L

    /**
     * Hook 侧查询配置时顺带调用：距上次扫描不足 [RESCAN_MS] 就直接回缓存，
     * 强制扫描只在授权变化 / 手动清除后来一次。
     */
    fun current(context: Context, force: Boolean = false): String {
        val prefs = DockPrefs(context)
        testValue(prefs)?.let { return it }
        if (force || System.currentTimeMillis() - lastScanAt >= RESCAN_MS) {
            lastScanAt = System.currentTimeMillis()
            prefs.packageCode = scan(context, prefs.packageCodeDismissed)
        }
        return prefs.packageCode
    }

    /**
     * 测试注入一条"短信"：正文走真解析链路（关键词 + 码型 + 反例过滤），
     * 所以它既能验 Dock / 面板的显示，也能验解析本身。解析不出就是清空。
     *
     * @return 注入后该显示的码；null = 这条正文按规则不该命中
     */
    fun setTest(context: Context, body: String): String? {
        val prefs = DockPrefs(context)
        val code = parse(body)
        if (code == null) {
            clearTest(context)
            android.util.Log.i(TAG, "test body matched nothing: ${body.take(40)}")
            return null
        }
        val at = System.currentTimeMillis()
        prefs.packageCodeTest = encode(Hit(at, code, label(body, ""), at, flat(body)))
        prefs.packageCodeTestUntil = at + TEST_TTL_MS
        android.util.Log.i(TAG, "test injected: $code / ${label(body, "")}")
        return code
    }

    /** 清掉注入值，恢复真实扫描 */
    fun clearTest(context: Context) = clearPrefs(DockPrefs(context))

    /** 注入值还在有效期内就直用；过期顺手抹掉，下一拍回到真实扫描 */
    private fun testValue(prefs: DockPrefs): String? {
        val raw = prefs.packageCodeTest
        if (raw.isBlank()) return null
        if (System.currentTimeMillis() > prefs.packageCodeTestUntil) {
            clearPrefs(prefs)
            return null
        }
        return raw
    }

    private fun clearPrefs(prefs: DockPrefs) {
        prefs.packageCodeTest = ""
        prefs.packageCodeTestUntil = 0L
    }

    /** 面板上点了"已取"：把这条 _id 记进黑名单，缓存立刻作废 */
    fun dismiss(context: Context, id: Long) {
        val prefs = DockPrefs(context)
        val blocked = prefs.packageCodeDismissed.split('|').filter { it.isNotBlank() }.toMutableList()
        val key = id.toString()
        if (key !in blocked) {
            // 只留最近 200 个：短信库里的 _id 单调递增，旧的早就读过了
            blocked.add(key)
            prefs.packageCodeDismissed = blocked.takeLast(200).joinToString("|")
        }
        // 点"已取"就是这条用例的收尾，注入值跟着一起清
        clearPrefs(prefs)
        prefs.packageCode = ""
        current(context, force = true)
    }

    fun permissionGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun scan(context: Context, blockedRaw: String): String {
        if (!permissionGranted(context)) return ""
        return try {
            val blocked = blockedRaw.split('|').mapNotNull { it.toLongOrNull() }.toSet()
            val cursor = context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                PROJECTION,
                SELECTION,
                null,
                "${Telephony.Sms.DATE} DESC",
            ) ?: return ""
            var hit: Hit? = null
            cursor.use { c ->
                val idCol = c.getColumnIndex(Telephony.Sms._ID)
                val addrCol = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyCol = c.getColumnIndex(Telephony.Sms.BODY)
                val dateCol = c.getColumnIndex(Telephony.Sms.DATE)
                var rows = 0
                while (hit == null && rows < SCAN_ROWS && c.moveToNext()) {
                    rows++
                    val id = if (idCol >= 0) c.getLong(idCol) else -1L
                    if (id in blocked) continue
                    val body = if (bodyCol >= 0) c.getString(bodyCol) ?: "" else ""
                    val parsed = parse(body) ?: continue
                    hit = Hit(
                        id,
                        parsed,
                        label(body, if (addrCol >= 0) c.getString(addrCol) ?: "" else ""),
                        if (dateCol >= 0) c.getLong(dateCol) else System.currentTimeMillis(),
                        flat(body),
                    )
                }
            }
            hit?.let { encode(it) } ?: ""
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "sms scan failed: $t")
            ""
        }
    }

    /** 命中返回码型本身，没命中返回 null */
    private fun parse(body: String): String? {
        if (body.isBlank()) return null
        val lower = body.lowercase()
        if (REJECT.any { lower.contains(it) }) return null
        if (KEYWORDS.none { body.contains(it) }) return null
        CODE_AFTER.find(body)?.groupValues?.getOrNull(1)?.let { return it }
        return TRIPLE.find(body)?.groupValues?.getOrNull(1)
    }

    /** 发件方标签：优先短信正文里的【签名】，那是"丰巢 / 菜鸟驿站"这类可读名称 */
    private fun label(body: String, address: String): String {
        BRACKET.find(body)?.groupValues?.getOrNull(1)?.let { return it }
        return address.ifBlank { "短信" }
    }

    /** 单行化 + 截断：跨进程只走一根字符串，换行和 | 都会把字段结构冲垮 */
    private fun flat(text: String): String =
        text.replace(Regex("\\s+"), " ").trim().take(140)

    private fun encode(h: Hit) =
        "${h.id}|${h.code}|${flat(h.label)}|${h.date}|${flat(h.summary)}"

    /** 面板侧回读用的解码；字段数不对就当没有 */
    fun decode(raw: String): Hit? {
        val p = raw.split('|')
        if (p.size < 4) return null
        val id = p[0].toLongOrNull() ?: return null
        val code = p[1]
        if (code.isBlank()) return null
        return Hit(id, code, p[2], p[3].toLongOrNull() ?: 0L, p.getOrElse(4) { "" })
    }
}
