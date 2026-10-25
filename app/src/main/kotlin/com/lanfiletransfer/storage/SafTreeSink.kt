package com.lanfiletransfer.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import com.lanfiletransfer.protocol.FileNames
import com.lanfiletransfer.protocol.ReceiveSink
import com.lanfiletransfer.protocol.ReceiveTarget
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Storage Access Framework で選んだフォルダに保存する。
 * Android 11 以降のスコープ付きストレージでも、ユーザーが選んだフォルダなら書き込める。
 */
class SafTreeSink(private val context: Context, private val treeUri: Uri) : ReceiveSink {

    private val resolver = context.contentResolver
    private val rootDocumentId: String = DocumentsContract.getTreeDocumentId(treeUri)
    private val rootDocumentUri: Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId)

    val description: String
        get() {
            val path = rootDocumentId.substringAfter(':', rootDocumentId)
            return if (path.isBlank()) "選択したフォルダ" else path
        }

    override fun createTarget(fileName: String, fileSize: Long): ReceiveTarget {
        ensureFreeSpace(fileSize)

        val name = FileNames.uniqueName(fileName) { it in existingNames() }
        val created = DocumentsContract.createDocument(resolver, rootDocumentUri, MIME_TYPE, name)
            ?: throw IOException("保存先にファイルを作成できませんでした。フォルダの権限を確認してください。")

        val actualName = queryDisplayName(created) ?: name
        val rawStream: OutputStream? = try {
            resolver.openOutputStream(created, "w")
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw IOException("保存先を開けませんでした: ${e.message}", e)
        }

        if (rawStream == null) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw IOException("保存先を開けませんでした。")
        }

        val stream = BufferedOutputStream(rawStream, 64 * 1024)

        return object : ReceiveTarget {
            override val displayName: String = actualName
            override val output: OutputStream = stream

            override fun finish() {
                stream.close()
            }

            override fun abort() {
                runCatching { stream.close() }
                runCatching { DocumentsContract.deleteDocument(resolver, created) }
            }
        }
    }

    private fun existingNames(): Set<String> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocumentId)
        val names = mutableSetOf<String>()

        runCatching {
            resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.lowercase()?.let(names::add)
                }
            }
        }

        return names
    }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            resolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

    /** 空き容量の判定は内部ストレージの値を目安にする(取得できない環境では続行する)。 */
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
    }
}
