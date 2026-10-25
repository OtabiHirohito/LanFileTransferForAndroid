package com.lanfiletransfer

import android.content.Context
import android.net.Uri

/** 画面の入力内容の保存(合言葉は保存しない)。 */
class AppSettings(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var lastMode: String
        get() = prefs.getString(KEY_MODE, MODE_SEND) ?: MODE_SEND
        set(value) = prefs.edit().putString(KEY_MODE, value).apply()

    var lastHost: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var lastPort: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var saveTreeUri: String?
        get() = prefs.getString(KEY_SAVE_TREE, null)
        set(value) = prefs.edit().putString(KEY_SAVE_TREE, value).apply()

    fun rememberSaveTree(uri: Uri?) {
        val resolver = context.applicationContext.contentResolver
        val previous = saveTreeUri
        if (previous != null && previous != uri?.toString()) {
            runCatching {
                resolver.releasePersistableUriPermission(
                    Uri.parse(previous),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        saveTreeUri = uri?.toString()
    }

    companion object {
        const val MODE_SEND = "send"
        const val MODE_RECEIVE = "receive"
        const val DEFAULT_PORT = 5000

        private const val PREFS_NAME = "lan_file_transfer"
        private const val KEY_MODE = "mode"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_SAVE_TREE = "save_tree"
    }
}
