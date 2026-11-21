package com.lsp102.tapdock

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从 GitHub Releases 检查更新：只读公开接口，不带 token，不缓存到磁盘以外的地方。
 *
 * 三条路依次退：
 * 1. `/releases/latest`：已发布的最新正式版，正常情况一次请求就够；
 * 2. `/releases` 列表：latest 返回 404（只有预发布）时按 draft / prerelease 过滤后取第一个；
 * 3. `releases.atom`：上面两个被限流（未鉴权每小时 60 次）或代理拦掉 api.github.com 时兜底，
 *    只能拿到 tag、标题和说明，拿不到 APK 资产链接。
 *
 * 三条都拿不到才算失败；仓库一个正式 Release 都没有则报 [Result.NoRelease]，
 * 这和「网络挂了」必须分开，否则界面会把空仓库说成检查失败。
 */
object UpdateChecker {

    const val REPO = "exicok/TAPDOCK"
    const val REPO_URL = "https://github.com/$REPO"
    const val RELEASES_URL = "$REPO_URL/releases"

    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val API_LIST = "https://api.github.com/repos/$REPO/releases?per_page=10"
    private const val ATOM = "$REPO_URL/releases.atom"

    private const val TIMEOUT_MS = 8000

    /** 一个已发布的正式版。notes 是去掉 HTML 后的纯文本，长度已截断 */
    data class Release(
        val tag: String,
        val name: String,
        val notes: String,
        val pageUrl: String,
        /** Release 里附带的 .apk 资产；atom 兜底时为 null */
        val apkUrl: String?,
    )

    sealed class Result {
        data class Found(val release: Release) : Result()
        /** 仓库还没有任何正式 Release */
        object NoRelease : Result()
        data class Failed(val message: String) : Result()
    }

    // ------------------------------------------------------------------ 版本比较

    /**
     * 把 `v1.2.3` / `1.2.3-beta.1` / `1.2.3+7` 拆成数字段。
     * 只看 '-' 和 '+' 之前的部分：预发布后缀和构建号不参与比较，
     * 否则 `1.2.3-beta` 会被算成比 `1.2.3` 新。
     */
    fun numbersOf(version: String): List<Int> {
        val core = version.trim()
            .removePrefix("v").removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
            .trim()
        if (core.isEmpty()) return emptyList()
        return core.split('.').map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
    }

    /** remote 是否比 local 新：逐段比数字，缺的段按 0 算（1.0 与 1.0.0 等价） */
    fun isNewer(remote: String, local: String): Boolean {
        val r = numbersOf(remote)
        val l = numbersOf(local)
        if (r.isEmpty()) return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** 显示用：版本号补上 v 前缀；不是数字开头的 tag（如 parent-5.5.1）原样返回 */
    fun tagLabel(version: String): String {
        val t = version.trim()
        return when {
            t.isEmpty() -> ""
            t.startsWith("v", true) -> t
            t.first().isDigit() -> "v$t"
            else -> t
        }
    }

    // ------------------------------------------------------------------ 取数据

    fun fetch(): Result {
        // 1. 正式版直取
        val latest = http(API_LATEST)
        if (latest != null && latest.code == 200) {
            val obj = runCatching { JSONObject(latest.body) }.getOrNull()
            if (obj != null) parse(obj)?.let { return Result.Found(it) }
        }

        // 2. 列表：404 说明只有预发布或仍在草稿，列表能给准确答案
        val list = http(API_LIST)
        if (list != null && list.code == 200) {
            val arr = runCatching { JSONArray(list.body) }.getOrNull()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    parse(o)?.let { return Result.Found(it) }
                }
                return Result.NoRelease
            }
        }

        // 3. atom 兜底：接口被限流时仍能读到 tag 和说明
        val atom = http(ATOM)
        if (atom != null && atom.code == 200) {
            parseAtom(atom.body)?.let { return Result.Found(it) }
            return Result.NoRelease
        }

        // 三条都失败：把最具体的那条原因报上去，别一律说成「网络不可用」
        val reasons = listOfNotNull(latest?.error, list?.error, atom?.error).filter { it.isNotBlank() }
        return Result.Failed(
            when {
                reasons.isNotEmpty() -> reasons.first()
                else -> "GitHub 接口暂时不可用（可能被限流），稍后再试"
            }
        )
    }

    private class Response(val code: Int, val body: String, val error: String? = null)

    private fun http(url: String): Response? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "TapDock-UpdateChecker")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            Response(code, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        // 域名解析失败最常见（DNS / 断网 / 广告拦截），单独记下来，界面别只说「网络不可用」
        val hint = if (e is java.net.UnknownHostException) "域名解析失败（DNS）" else e.javaClass.simpleName
        Response(0, "", hint)
    }

    private fun parse(o: JSONObject): Release? {
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val tag = o.optString("tag_name").trim()
        if (tag.isEmpty()) return null
        return Release(
            tag = tag,
            name = o.optString("name").trim().ifBlank { tag },
            notes = plain(o.optString("body")),
            pageUrl = o.optString("html_url").trim().ifBlank { RELEASES_URL },
            apkUrl = apkOf(o.optJSONArray("assets")),
        )
    }

    /** 第一个 .apk 资产；一个 Release 挂多个包时按上传顺序取第一个 */
    private fun apkOf(assets: JSONArray?): String? {
        if (assets == null) return null
        for (i in 0 until assets.length()) {
            val url = assets.optJSONObject(i)?.optString("browser_download_url")?.trim().orEmpty()
            if (url.endsWith(".apk", true)) return url
        }
        return null
    }

    private val ENTRY = Regex("(?s)<entry>(.*?)</entry>")
    private val TAG_LINK = Regex("href=\"([^\"]*/releases/tag/[^\"]+)\"")
    private val TITLE = Regex("(?s)<title[^>]*>(.*?)</title>")
    private val CONTENT = Regex("(?s)<content[^>]*>(.*?)</content>")

    private fun parseAtom(xml: String): Release? {
        val entry = ENTRY.find(xml)?.groupValues?.get(1) ?: return null
        val href = TAG_LINK.find(entry)?.groupValues?.get(1) ?: return null
        val tag = href.substringAfter("/releases/tag/").trim()
        if (tag.isEmpty()) return null
        val title = TITLE.find(entry)?.groupValues?.get(1)?.let { plain(it) }.orEmpty()
        val notes = CONTENT.find(entry)?.groupValues?.get(1)?.let { plain(it) }.orEmpty()
        return Release(
            tag = tag,
            name = title.ifBlank { tag },
            notes = notes,
            pageUrl = href,
            apkUrl = null,
        )
    }

    // ------------------------------------------------------------------ 文本清理

    /** Release 说明是 Markdown / HTML 混排，弹窗里只要纯文本：去标签、解实体、压空行 */
    fun plain(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            // 块级标签一律折成换行，否则 <h2>标题</h2><ul>… 会粘成一行
            .replace(Regex("(?i)</(p|h[1-6]|div|ul|ol|tr|blockquote)>"), "\n")
            .replace(Regex("(?i)<li[^>]*>"), "· ")
            .replace(Regex("(?i)</li>"), "\n")
            .replace(Regex("(?is)<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        s = s.lines().joinToString("\n") { it.trimEnd() }
        s = s.replace(Regex("\n{3,}"), "\n\n").trim()
        return if (s.length > 1600) s.take(1600).trimEnd() + "…" else s
    }
}
