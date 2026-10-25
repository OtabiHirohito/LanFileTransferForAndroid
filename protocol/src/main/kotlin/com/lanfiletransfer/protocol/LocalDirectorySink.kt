package com.lanfiletransfer.protocol

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * 通常のフォルダに保存する [ReceiveSink]。
 * Android 以外(CLI・単体テスト)や、アプリ専用フォルダへの保存に使う。
 */
class LocalDirectorySink(private val directory: File) : ReceiveSink {

    init {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("保存先フォルダを作成できませんでした。(${directory.absolutePath})")
        }
    }

    override fun createTarget(fileName: String, fileSize: Long): ReceiveTarget {
        val usable = directory.usableSpace
        if (usable > 0 && fileSize > usable) {
            throw IOException("保存先の空き容量が不足しているため受信を中止しました。")
        }

        val existing = directory.list()?.map { it.lowercase() }?.toSet() ?: emptySet()
        val name = FileNames.uniqueName(fileName) { it in existing }
        val file = File(directory, name)
        val stream = BufferedOutputStream(FileOutputStream(file), 64 * 1024)

        return object : ReceiveTarget {
            override val displayName: String = name
            override val output: OutputStream = stream

            override fun finish() {
                stream.close()
            }

            override fun abort() {
                runCatching { stream.close() }
                runCatching { file.delete() }
            }
        }
    }
}
