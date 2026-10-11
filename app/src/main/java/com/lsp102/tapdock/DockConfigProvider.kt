package com.lsp102.tapdock

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * 向 Hook 侧（SystemUI / 桌面进程）暴露当前配置。
 * Hook 侧通过 contentResolver 定时轮询此 Provider，
 * 即使 LSPosed 的 XSharedPreferences 共享失效、广播丢失，设置也必然生效。
 */
class DockConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val context = context ?: return null
        val prefs = DockPrefs(context)
        return MatrixCursor(COLUMNS).apply {
            addRow(
                arrayOf(
                    if (prefs.hookEnabled) 1 else 0,
                    prefs.dockAlign,
                    prefs.progressColor,
                    prefs.downloadPackages.joinToString("|"),
                    prefs.cardOrder.joinToString(","),
                    if (prefs.nyanStyle) 1 else 0,
                    if (prefs.albumArtBg) 1 else 0,
                    if (prefs.albumThumb) 1 else 0,
                    if (prefs.cpuChart) 1 else 0,
                    if (prefs.notifBadge) 1 else 0,
                    if (prefs.showOtp) 1 else 0,
                    if (prefs.packageCodeEnabled) 1 else 0,
                    PackageCodeCollector.current(context),
                    if (prefs.notifCard) 1 else 0,
                    prefs.dockMaxDp,
                    prefs.gameInfo,
                    if (prefs.apiBadge) 1 else 0,
                    if (prefs.apiBadgeUnknown) 1 else 0,
                    prefs.apiHistory,
                )
            )
        }
    }

    override fun getType(uri: Uri): String =
        "vnd.android.cursor.item/vnd.com.lsp102.tapdock.config"

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val context = context ?: return null
        // 反方向通道：SystemUI 侧枚举到系统控制块目录后写回来（App 进程不在也会被拉起）
        values?.getAsString(COLUMN_CATALOG)?.let { raw ->
            val entries = SysCatalog.decode(raw)
            DockPrefs(context).sysCatalog = entries
            android.util.Log.i(TAG, "catalog stored: ${entries.size} blocks")
            return Uri.parse("content://$AUTHORITY/$PATH_STATE")
        }
        // 面板上点了"已取"：把这条短信 _id 记进黑名单，取件码当场消失
        values?.getAsString(COLUMN_PKG_DISMISSED)?.toLongOrNull()?.let { id ->
            PackageCodeCollector.dismiss(context, id)
            return Uri.parse("content://$AUTHORITY/$PATH_STATE")
        }
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = if (insert(uri, values) != null) 1 else 0

    companion object {
        const val AUTHORITY = "com.lsp102.tapdock.config"
        const val PATH_STATE = "state"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_STATE")

        /** SystemUI → App 的目录回传列 */
        const val COLUMN_CATALOG = "catalog"

        /** SystemUI → App：这条取件码已经取过了（值是短信 _id） */
        const val COLUMN_PKG_DISMISSED = "pkg_dismissed"

        private const val TAG = "TapDock"

        val COLUMNS = arrayOf(
            "show", "align", "pcolor", "dl_pkgs", "card_order", "nyan", "album", "thumb", "cpu",
            "badge", "otp", "pkgon", "pkgcode", "ncard", "maxw", "game",
            "apibadge", "apibunk", "apihist",
        )
    }
}
