package com.lanfiletransfer.protocol

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Windows 版 (`LanFileTransfer/TransferProtocol.cs`, `SecureChannel.cs`) と同じ
 * ワイヤ形式を扱うための低レベルヘルパー。
 *
 * .NET の `BitConverter` はリトルエンディアンなので、すべて LE で読み書きする。
 * ここをビッグエンディアンにすると Windows 版と接続できない。
 */
internal object Wire {

    /** 'L','F','T','2' */
    const val MAGIC = 0x4C465432

    fun intLE(value: Int): ByteArray =
        ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    fun longLE(value: Long): ByteArray =
        ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    fun readIntLE(bytes: ByteArray, offset: Int = 0): Int =
        ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).int

    fun readLongLE(bytes: ByteArray, offset: Int = 0): Long =
        ByteBuffer.wrap(bytes, offset, Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).long
}

/** プロトコル上の不整合(相手が古い・合言葉違い・データ破損など)を表す例外。 */
class ProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)
