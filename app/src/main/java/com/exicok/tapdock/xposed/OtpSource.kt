package com.lsp102.tapdock.xposed

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import de.robv.android.xposed.XposedBridge

/**
 * 短信验证码：从已经在监听的通知管线里取（不申请 READ_SMS，也不读短信数据库）。
 *
 * 认码两条判据：
 *  - Android 14+ 的 `Notification.isOtp`（短信应用会把验证码标成 OTP 类通知），命中就直接认；
 *  - 否则要求正文里同时出现验证码类关键词和一串 4–8 位数字，两个条件缺一个都不显示 ——
 *    宁可漏，也不要把银行余额里的数字当验证码挂在状态栏上。
 *
 * 只认 10 秒内的新消息：消息发出时间（`notification.when`，应用没写就退回通知投递时间）
 * 距今超过 [TTL_MS] 的一律不认 —— 监听器重新绑定时系统会把还挂着的旧通知补报一遍，
 * 不挡掉就会把几小时前的旧码当成新的挂上 Dock。
 *
 * 同一号码多条只显示最新一条：同一号码（同一会话）的多条消息会整串拼进同一条通知里，
 * 取"第一处数字"拿到的多半是旧码。有结构化消息（EXTRA_MESSAGES，每条自带发出时间）
 * 就按发出时间从新到旧挑，没有就取整段正文里的最后一处命中（越靠后越新）；
 * 晚到的旧码也不许顶掉已经挂着的新码。
 *
 * 只保留最新一条，从抓到起满 [TTL_MS] 就撤。
 *
 * 通知被划走 / 被短信应用自己撤掉都不提前清：这类应用常常在弹出来两三秒后就 cancel，
 * 而码本身几分钟内都有效，屏幕上按 10 秒走完才有机会抄完。
 */
object OtpSource {

    /** [at] = 上屏时间，[sentAt] = 消息发出时间（用来挡晚到的旧码） */
    class Code(val text: String, val at: Long, val sentAt: Long)

    /** 验证码在 Dock 上的停留时间，同时是"发出距今多久还算新"的上限（HookDock 按它安排到点撤下） */
    internal const val TTL_MS = 10_000L

    private val DIGITS = Regex("(?<!\\d)(\\d{4,8})(?!\\d)")

    private val KEYWORDS = listOf(
        "验证码", "校验码", "动态码", "短信口令", "一次性密码", "otp", "code", "verification", "captcha",
    )

    /** EXTRA_MESSAGES 里每个消息 Bundle 的键（MessagingStyle 的 @hide 常量值） */
    private const val KEY_TEXT = "text"
    private const val KEY_TIME = "time"

    private var current: Code? = null

    fun onPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        // 消息发出时间（when 是 Kotlin 关键字，取值要加反引号）；有的应用不写，退回通知的投递时间
        val sentAt = if (n.`when` > 0) n.`when` else sbn.postTime
        // 过期的（监听重连补报的旧通知、几小时前的旧码）直接不认
        if (System.currentTimeMillis() - sentAt > TTL_MS) return
        val code = extract(n) ?: return
        // 已经挂着一条更新的：晚到的旧码不覆盖它
        current?.let { if (it.sentAt > sentAt) return }
        current = Code(code, System.currentTimeMillis(), sentAt)
        XposedBridge.log("[TapDock] otp captured from ${sbn.packageName}")
    }

    /** 过期自动清空 */
    fun peek(now: Long = System.currentTimeMillis()): Code? {
        val c = current ?: return null
        if (now - c.at > TTL_MS) {
            current = null
            return null
        }
        return c
    }

    private fun extract(n: Notification): String? {
        val extras = n.extras ?: return null
        val text = StringBuilder()
        (extras.getCharSequence(Notification.EXTRA_TITLE))?.let { text.append(it).append(' ') }
        extras.getCharSequenceArrayList(Notification.EXTRA_TEXT_LINES)?.forEach {
            text.append(it).append(' ')
        }
        extras.getCharSequence(Notification.EXTRA_TEXT)?.let { text.append(it) }
        val body = text.toString()
        // isOtp 是 Android 14 才加的公开方法，编译期 SDK 未必有存根，运行时反射拿最稳
        val isOtp = try {
            Notification::class.java.getMethod("isOtp").invoke(n) as? Boolean ?: false
        } catch (_: Throwable) {
            false
        }
        if (!isOtp) {
            val lower = body.lowercase()
            if (KEYWORDS.none { lower.contains(it) }) return null
        }
        // 同一会话的多条消息拼在同一段里：先按结构化消息挑最新那条，兜底才在整段里取最后一处
        newestCode(extras, isOtp)?.let { return it }
        return DIGITS.findAll(body).lastOrNull()?.groupValues?.getOrNull(1)
    }

    /**
     * MessagingStyle 的结构化消息（`EXTRA_MESSAGES`）：里面每条消息是一个 Bundle，
     * `text` = 正文、`time` = 发出时间（平台 @hide 键值，24 起没变过）。
     *
     * 按发出时间挑**最新那条**通过「关键词 + 码型」的消息 —— 同一号码（同一会话）
     * 发来的多条里旧码永远排不上；time 相同（同一秒连发）时取数组里靠后的，
     * 平台按时间顺序追加，靠后就是更新的。
     *
     * 没有 EXTRA_MESSAGES 的应用返回 null，交给调用方在整段正文里兜底。
     */
    private fun newestCode(extras: Bundle, isOtp: Boolean): String? {
        val raw = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        var bestAt = Long.MIN_VALUE
        var best: String? = null
        for (element in raw) {
            val message = element as? Bundle ?: continue
            val text = message.getCharSequence(KEY_TEXT)?.toString() ?: continue
            if (!isOtp && KEYWORDS.none { text.lowercase().contains(it) }) continue
            val code = DIGITS.find(text)?.groupValues?.getOrNull(1) ?: continue
            val at = message.getLong(KEY_TIME)
            if (at >= bestAt) {
                bestAt = at
                best = code
            }
        }
        return best
    }
}
