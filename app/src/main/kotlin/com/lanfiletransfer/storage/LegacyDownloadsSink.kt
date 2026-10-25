package com.lanfiletransfer.storage

import android.os.Environment
import com.lanfiletransfer.protocol.LocalDirectorySink
import com.lanfiletransfer.protocol.ReceiveSink
import com.lanfiletransfer.protocol.ReceiveTarget
import java.io.File

/**
 * Android 9 以前で「ダウンロード」フォルダに保存する。
 *
 * こちらは MediaStore ではなく通常のファイル書き込みになるため、
 * WRITE_EXTERNAL_STORAGE の許可が必要(呼び出し側で確認する)。
 */
@Suppress("DEPRECATION")
class LegacyDownloadsSink : ReceiveSink {

    private val delegate = LocalDirectorySink(
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            ReceiveDestinations.FOLDER_NAME
        )
    )

    val description: String
        get() = "ダウンロード / ${ReceiveDestinations.FOLDER_NAME}"

    override fun createTarget(fileName: String, fileSize: Long): ReceiveTarget =
        delegate.createTarget(fileName, fileSize)
}
