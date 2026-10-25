package com.lanfiletransfer.storage

import android.content.Context
import android.os.Environment
import com.lanfiletransfer.protocol.LocalDirectorySink
import com.lanfiletransfer.protocol.ReceiveSink
import com.lanfiletransfer.protocol.ReceiveTarget
import java.io.File

/**
 * アプリ専用の外部ストレージに保存する(権限不要)。
 * 保存先: Android/data/com.lanfiletransfer/files/Download/LanFileTransfer
 *
 * Android 9 以前で「ダウンロード」への書き込み権限がない場合のフォールバック。
 * Android 10 以降は MediaStore 経由で端末の「ダウンロード」に保存する。
 */
class AppDirSink(context: Context) : ReceiveSink {

    private val root: File = File(
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
        FOLDER_NAME
    )

    private val delegate = LocalDirectorySink(root)

    val description: String
        get() = "アプリ専用フォルダ ($FOLDER_NAME)"

    override fun createTarget(fileName: String, fileSize: Long): ReceiveTarget =
        delegate.createTarget(fileName, fileSize)

    companion object {
        const val FOLDER_NAME = "LanFileTransfer"
    }
}
