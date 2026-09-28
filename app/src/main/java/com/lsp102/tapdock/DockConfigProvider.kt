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
                )
            )
        }
    }

    override fun getType(uri: Uri): String =
        "vnd.android.cursor.item/vnd.com.lsp102.tapdock.config"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY = "com.lsp102.tapdock.config"
        const val PATH_STATE = "state"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_STATE")
        val COLUMNS = arrayOf(
            "show", "align", "pcolor", "dl_pkgs", "card_order", "nyan", "album", "thumb", "cpu",
        )
    }
}
