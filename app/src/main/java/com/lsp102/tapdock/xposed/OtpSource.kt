package com.lsp102.tapdock.xposed

import android.app.Notification
import android.service.notification.StatusBarNotification
import de.robv.android.xposed.XposedBridge

/**
 * 短信验证码：从已经在监听的通知管线里取（不申请 READ_SMS，也不读短信数据库）。
 *
 * 两条判据：
 *  - Android 14+ 的 `Notification.isOtp`（短信应用会把验证码标成 OTP 类通知），命中就直接认；
 *  - 否则要求正文里同时出现验证码类关键词和一串 4–8 位数字，两个条件缺一个都不显示 ——
 *    宁可漏，也不要把银行余额里的数字当验证码挂在状态栏上。
 *
 * 只保留最新一条，超时或对应通知被划走就清掉。
 */
object OtpSource {

    class Code(val text: String, val pkg: String, val key: String, val at: Long)

    /** 验证码在 Dock 上的停留时间（HookDock 按它安排到点撤下，改这里就行） */
    internal const val TTL_MS = 10_000L

    private val DIGITS = Regex("(?<!\\d)(\\d{4,8})(?!\\d)")

    private val KEYWORDS = listOf(
        "验证码", "校验码", "动态码", "短信口令", "一次性密码", "otp", "code", "verification", "captcha",
    )

    private var current: Code? = null

    fun onPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val code = extract(n) ?: return
        current = Code(code, sbn.packageName, keyOf(sbn), System.currentTimeMillis())
        XposedBridge.log("[TapDock] otp captured from ${sbn.packageName}")
    }

    /** 通知被划走 / 被应用撤掉时一起清掉，不留一个已经作废的码在屏幕上 */
    fun onRemoved(key: String) {
        if (current?.key == key) current = null
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

    fun clear() {
        current = null
    }

    private fun extract(n: Notification): String? {
        val extras = n.extras ?: return null
        val text = StringBuilder()
        (extras.getCharSequence(Notification.EXTRA_TITLE))?.let { text.append(it).append(' ') }
        extras.getCharSequenceArrayList(Notification.EXTRA_TEXT_LINES)?.forEach {
            text.append(it).append(' ')
        }
        (extras.getCharSequence(Notification.EXTRA_TEXT))?.let { text.append(it) }
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
        return DIGITS.find(body)?.groupValues?.getOrNull(1)
    }

    private fun keyOf(sbn: StatusBarNotification) = "${sbn.packageName}:${sbn.id}"
}
