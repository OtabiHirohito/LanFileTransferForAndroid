package com.lanfiletransfer.protocol

/**
 * Windows 版 `TransferProtocol.cs` と同じヘッダ形式。
 *
 * ```
 * [4byte ファイル名長 (Int32 LE)][ファイル名 UTF-8][8byte ファイルサイズ (Int64 LE)]
 * [本体データ][32byte SHA-256]
 * ```
 */
object TransferProtocol {

    /** 読み書きに使うバッファサイズ(Windows 版と同じ 80KB)。 */
    const val BUFFER_SIZE = 80 * 1024

    /** 本体末尾に付ける検証用ハッシュの長さ (SHA-256)。 */
    const val HASH_SIZE = 32

    /** ファイル名(UTF-8)として許容する最大バイト数。 */
    const val MAX_FILE_NAME_BYTES = 4096

    /** 1 ファイルとして許容する最大サイズ (100 GiB)。 */
    const val MAX_FILE_SIZE = 100L * 1024 * 1024 * 1024

    fun writeHeader(channel: SecureChannel, fileName: String, fileSize: Long) {
        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > MAX_FILE_NAME_BYTES) {
            throw ProtocolException("ファイル名が長すぎるため送信できません。(${nameBytes.size} byte)")
        }
        if (fileSize < 0 || fileSize > MAX_FILE_SIZE) {
            throw ProtocolException("ファイルサイズが不正なため送信できません。($fileSize)")
        }

        channel.write(Wire.intLE(nameBytes.size))
        channel.write(nameBytes)
        channel.write(Wire.longLE(fileSize))
    }

    fun readHeader(channel: SecureChannel): TransferHeader {
        val lengthBytes = ByteArray(Int.SIZE_BYTES)
        channel.readExact(lengthBytes)
        val nameLength = Wire.readIntLE(lengthBytes)
        if (nameLength <= 0 || nameLength > MAX_FILE_NAME_BYTES) {
            throw ProtocolException("ファイル名長が不正なため接続を拒否しました。($nameLength)")
        }

        val nameBytes = ByteArray(nameLength)
        channel.readExact(nameBytes)

        val sizeBytes = ByteArray(Long.SIZE_BYTES)
        channel.readExact(sizeBytes)
        val fileSize = Wire.readLongLE(sizeBytes)
        if (fileSize < 0 || fileSize > MAX_FILE_SIZE) {
            throw ProtocolException("ファイルサイズが不正なため接続を拒否しました。($fileSize)")
        }

        return TransferHeader(String(nameBytes, Charsets.UTF_8), fileSize)
    }
}

data class TransferHeader(val fileName: String, val fileSize: Long)
