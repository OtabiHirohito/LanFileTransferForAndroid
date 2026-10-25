package com.lanfiletransfer.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import com.lanfiletransfer.protocol.FileNames
import com.lanfiletransfer.protocol.ReceiveSink
import com.lanfiletransfer.protocol.ReceiveTarget
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * 端末の「ダウンロード」フォルダに保存する(Android 10 以降)。
 *
 * MediaStore を使うため、ストレージの権限は不要。保存先は
 * `Download/LanFileTransfer` で、Files アプリやダウンロード一覧から開ける。
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaStoreDownloadsSink(context: Context) : ReceiveSink {

    private val resolver = context.contentResolver
    private val collection =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val relativePath =
        "${Environment.DIRECTORY_DOWNLOADS}/${ReceiveDestinations.FOLDER_NAME}"

    init {
        // 前回の異常終了などで残った「保留中」のファイルを掃除する。
        runCatching {
            resolver.delete(
                collection,
                "${MediaStore.Downloads.IS_PENDING}=1 AND " +
                    "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf("$relativePath/%")
            )
        }
    }

    override fun createTarget(fileName: String, fileSize: Long): ReceiveTarget {
        ensureFreeSpace(fileSize)

        val name = FileNames.uniqueName(fileName) { it in existingNames() }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeTypeFor(name))
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri: Uri = resolver.insert(collection, values)
            ?: throw IOException("保存先にファイルを作成できませんでした。")

        val rawStream: OutputStream? = try {
            resolver.openOutputStream(uri, "w")
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw IOException("保存先を開けませんでした: ${e.message}", e)
        }

        if (rawStream == null) {
            runCatching { resolver.delete(uri, null, null) }
            throw IOException("保存先を開けませんでした。")
        }

        val stream = BufferedOutputStream(rawStream, BUFFER_SIZE)
        val actualName = queryDisplayName(uri) ?: name

        return object : ReceiveTarget {
            override val displayName: String = actualName
            override val output: OutputStream = stream

            override fun finish() {
                stream.close()
                // IS_PENDING を解除して、他のアプリからも見えるようにする。
                runCatching {
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                        null,
                        null
                    )
                }
            }

            override fun abort() {
                runCatching { stream.close() }
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }

    /** `Download/LanFileTransfer` にある既存ファイル名(小文字)。 */
    private fun existingNames(): Set<String> {
        val names = mutableSetOf<String>()
        runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf("$relativePath/%"),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.lowercase()?.let(names::add)
                }
            }
        }
        return names
    }

    private fun mimeTypeFor(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) {
            return MIME_TYPE
        }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: MIME_TYPE
    }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            resolver.query(
                uri,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

    private fun ensureFreeSpace(fileSize: Long) {
        val available = runCatching {
            StatFs(Environment.getDataDirectory().absolutePath).availableBytes
        }.getOrDefault(0L)

        if (available > 0 && fileSize > available) {
            throw IOException("保存先の空き容量が不足しているため受信を中止しました。")
        }
    }

    companion object {
        private const val MIME_TYPE = "application/octet-stream"
        private const val BUFFER_SIZE = 64 * 1024
    }
}
