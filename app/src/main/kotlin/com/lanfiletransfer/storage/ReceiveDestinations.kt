package com.lanfiletransfer.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.lanfiletransfer.protocol.ReceiveSink

/**
 * フォルダを選んでいないときの保存先。
 *
 * Android 10 以降は MediaStore 経由で端末の「ダウンロード」に保存する(権限不要)。
 * Android 9 以前は WRITE_EXTERNAL_STORAGE があれば同じ場所、
 * なければ従来のアプリ専用フォルダに保存する。
 */
object ReceiveDestinations {

    const val FOLDER_NAME = "LanFileTransfer"

    fun label(context: Context): String =
        if (hasPublicDownloadsAccess(context)) {
            "ダウンロード / $FOLDER_NAME"
        } else {
            "アプリ専用フォルダ"
        }

    fun create(context: Context): ReceiveSink =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStoreDownloadsSink(context)
        } else if (hasPublicDownloadsAccess(context)) {
            LegacyDownloadsSink()
        } else {
            AppDirSink(context)
        }

    /** Android 10 以降は権限不要。Android 9 以前は書き込み権限が必要。 */
    fun hasPublicDownloadsAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
}
