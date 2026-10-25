package com.lanfiletransfer.protocol

import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Windows 版 `SecureChannel.cs` と互換の簡易セキュアチャネル。
 *
 * ハンドシェイク:
 * ```
 * 送信→受信: [4byte マジック 'LFT2'][1byte フラグ(bit0=暗号化)][暗号化時のみ 16byte ソルト]
 * 受信→送信: [1byte ステータス(0=OK, 1=拒否)]
 *            拒否時: [2byte 理由長][理由 UTF-8]
 *            OK かつ暗号化時: [32byte 鍵確認値]
 * ```
 *
 * 暗号化レコード(1 書き込み単位):
 * ```
 * [4byte 平文長][平文長 byte の暗号文][16byte 認証タグ]
 * ```
 *
 * 鍵 = PBKDF2-HMAC-SHA256(合言葉, ソルト, 100000 回, 32byte)
 * nonce = 12byte のうち末尾 8byte にビッグエンディアンの連番(読み書き別カウンタ)
 *
 * 読み取りのタイムアウトは呼び出し側の Socket の soTimeout で行う
 * (Windows 版はハンドシェイク 30 秒 / 無通信 60 秒)。
 */
class SecureChannel private constructor(
    private val input: InputStream,
    private val output: OutputStream,
    private val key: ByteArray?
) : Closeable {

    private val cipher: Cipher? = if (key != null) Cipher.getInstance("AES/GCM/NoPadding") else null
    private val secretKey: SecretKeySpec? = key?.let { SecretKeySpec(it, "AES") }

    private val plainBuffer = ByteArray(MAX_RECORD_SIZE)
    private var plainOffset = 0
    private var plainCount = 0

    private var readCounter = 0L
    private var writeCounter = 0L
    private val readNonce = ByteArray(NONCE_SIZE)
    private val writeNonce = ByteArray(NONCE_SIZE)

    /** 平文(暗号化なし)の場合は素通しで書き込む。 */
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        if (length <= 0) {
            return
        }

        val aes = cipher
        if (aes == null) {
            output.write(data, offset, length)
            return
        }

        if (length > MAX_RECORD_SIZE) {
            throw IllegalArgumentException("1レコードの最大サイズを超えています。($length)")
        }

        val record = ByteArray(Int.SIZE_BYTES + length + TAG_SIZE)
        record[0] = (length and 0xFF).toByte()
        record[1] = ((length ushr 8) and 0xFF).toByte()
        record[2] = ((length ushr 16) and 0xFF).toByte()
        record[3] = ((length ushr 24) and 0xFF).toByte()

        aes.init(
            Cipher.ENCRYPT_MODE,
            secretKey!!,
            GCMParameterSpec(TAG_SIZE * 8, nextNonce(writeCounter++, writeNonce))
        )

        val produced = aes.doFinal(data, offset, length, record, Int.SIZE_BYTES)
        output.write(record, 0, Int.SIZE_BYTES + produced)
    }

    /** 指定長をちょうど読み切る。不足したまま切断された場合は例外。 */
    fun readExact(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset) {
        var done = 0
        while (done < length) {
            if (key == null) {
                val n = input.read(buffer, offset + done, length - done)
                if (n < 0) {
                    throw EOFException("接続が予期せず切断されました。")
                }
                done += n
            } else {
                if (plainOffset >= plainCount) {
                    fillPlainBuffer()
                }

                val take = minOf(plainCount - plainOffset, length - done)
                System.arraycopy(plainBuffer, plainOffset, buffer, offset + done, take)
                plainOffset += take
                done += take
            }
        }
    }

    fun flush() {
        output.flush()
    }

    override fun close() {
        // ソケットそのものは呼び出し側が閉じる
    }

    /** 暗号化レコードを 1 つ読み、平文バッファに展開する。 */
    private fun fillPlainBuffer() {
        val lengthBytes = ByteArray(Int.SIZE_BYTES)
        readRawExact(lengthBytes)
        val length = Wire.readIntLE(lengthBytes)
        if (length <= 0 || length > MAX_RECORD_SIZE) {
            throw ProtocolException("暗号化レコードの長さが不正です。($length)")
        }

        val record = ByteArray(length + TAG_SIZE)
        readRawExact(record)

        val aes = cipher!!
        aes.init(
            Cipher.DECRYPT_MODE,
            secretKey!!,
            GCMParameterSpec(TAG_SIZE * 8, nextNonce(readCounter++, readNonce))
        )

        try {
            plainCount = aes.doFinal(record, 0, length + TAG_SIZE, plainBuffer, 0)
            plainOffset = 0
        } catch (e: AEADBadTagException) {
            throw ProtocolException("復号に失敗しました。(合言葉が違う可能性があります)", e)
        }
    }

    private fun readRawExact(buffer: ByteArray) {
        var done = 0
        while (done < buffer.size) {
            val n = input.read(buffer, done, buffer.size - done)
            if (n < 0) {
                throw EOFException("接続が予期せず切断されました。")
            }
            done += n
        }
    }

    companion object {
        const val SALT_SIZE = 16
        const val TAG_SIZE = 16
        const val NONCE_SIZE = 12
        const val MAX_RECORD_SIZE = 128 * 1024
        const val PBKDF2_ITERATIONS = 100_000

        private const val MAX_REJECT_REASON_BYTES = 4096

        private const val STATUS_OK: Byte = 0
        private const val STATUS_REJECTED: Byte = 1
        private const val ENCRYPTION_FLAG: Byte = 1

        private val random = SecureRandom()

        /** 送信側: ハンドシェイクを行ってチャネルを用意する。 */
        fun createWriter(input: InputStream, output: OutputStream, password: String?): SecureChannel {
            val encrypted = !password.isNullOrEmpty()

            val salt: ByteArray?
            val key: ByteArray?
            if (encrypted) {
                salt = ByteArray(SALT_SIZE).also { random.nextBytes(it) }
                key = deriveKey(password, salt)
            } else {
                salt = null
                key = null
            }

            val handshake = buildHandshake(salt)
            output.write(handshake)
            output.flush()

            val status = readStatus(input)
            if (status != STATUS_OK) {
                val reason = tryReadRejectReason(input)
                throw ProtocolException(
                    "受信側が接続を拒否しました。" + if (reason.isNullOrEmpty()) "" else " ($reason)"
                )
            }

            if (key != null) {
                val expected = computeConfirmation(handshake, key)
                val actual = ByteArray(expected.size)
                readRawFully(input, actual)
                if (!MessageDigest.isEqual(expected, actual)) {
                    throw ProtocolException("合言葉が一致しません。送信側と受信側で同じ合言葉を設定してください。")
                }
            }

            return SecureChannel(input, output, key)
        }

        /** 受信側: ハンドシェイクを行ってチャネルを用意する。 */
        fun createReader(input: InputStream, output: OutputStream, password: String?): SecureChannel {
            val magic = ByteArray(Int.SIZE_BYTES)
            readRawFully(input, magic)
            if (Wire.readIntLE(magic) != Wire.MAGIC) {
                throw ProtocolException("プロトコルが一致しません。(相手が古いバージョンの可能性があります)")
            }

            val flag = readOneByte(input)
            val peerEncrypted = flag == ENCRYPTION_FLAG
            val havePassword = !password.isNullOrEmpty()

            // 相手が暗号化を指定している場合は、判定より前に必ずソルトまで読み切る
            // (未読データを残したまま切断すると RST になり、拒否理由が届かなくなる)
            val salt: ByteArray?
            if (peerEncrypted) {
                salt = ByteArray(SALT_SIZE)
                readRawFully(input, salt)
            } else {
                salt = null
            }

            if (peerEncrypted != havePassword) {
                val reason = if (peerEncrypted) {
                    "相手は合言葉(暗号化)を指定していますが、受信側で合言葉が未設定です。"
                } else {
                    "相手が合言葉(暗号化)を指定していません。受信側の合言葉設定と一致しません。"
                }
                trySendReject(output, reason)
                throw ProtocolException(reason)
            }

            if (!peerEncrypted) {
                output.write(byteArrayOf(STATUS_OK))
                output.flush()
                return SecureChannel(input, output, null)
            }

            val key = deriveKey(password!!, salt!!)
            val handshake = buildHandshake(salt)

            output.write(byteArrayOf(STATUS_OK))
            output.write(computeConfirmation(handshake, key))
            output.flush()

            return SecureChannel(input, output, key)
        }

        private fun buildHandshake(salt: ByteArray?): ByteArray {
            val handshake = ByteArray(Int.SIZE_BYTES + 1 + (salt?.size ?: 0))
            Wire.intLE(Wire.MAGIC).copyInto(handshake, 0)
            handshake[Int.SIZE_BYTES] = if (salt == null) 0 else ENCRYPTION_FLAG
            salt?.copyInto(handshake, Int.SIZE_BYTES + 1)
            return handshake
        }

        private fun readStatus(input: InputStream): Byte =
            try {
                readOneByte(input)
            } catch (e: EOFException) {
                throw ProtocolException(
                    "受信側と通信できませんでした。受信側が旧バージョン、または受信待機していない可能性があります。", e
                )
            }

        private fun readOneByte(input: InputStream): Byte {
            val value = input.read()
            if (value < 0) {
                throw EOFException("接続が予期せず切断されました。")
            }
            return value.toByte()
        }

        private fun tryReadRejectReason(input: InputStream): String? =
            try {
                val lengthBytes = ByteArray(2)
                readRawFully(input, lengthBytes)
                val length = (lengthBytes[0].toInt() and 0xFF) or ((lengthBytes[1].toInt() and 0xFF) shl 8)
                if (length <= 0 || length > MAX_REJECT_REASON_BYTES) {
                    null
                } else {
                    val message = ByteArray(length)
                    readRawFully(input, message)
                    String(message, Charsets.UTF_8)
                }
            } catch (e: Exception) {
                null
            }

        private fun trySendReject(output: OutputStream, reason: String) {
            try {
                var message = reason.toByteArray(Charsets.UTF_8)
                if (message.size > MAX_REJECT_REASON_BYTES) {
                    message = message.copyOf(MAX_REJECT_REASON_BYTES)
                }

                val buffer = ByteArray(1 + 2 + message.size)
                buffer[0] = STATUS_REJECTED
                buffer[1] = (message.size and 0xFF).toByte()
                buffer[2] = ((message.size ushr 8) and 0xFF).toByte()
                message.copyInto(buffer, 3)
                output.write(buffer)
                output.flush()
            } catch (e: Exception) {
                // 理由を伝えられなくても、切断すれば受信ループは継続できる
            }
        }

        private fun computeConfirmation(handshake: ByteArray, key: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(handshake)
        }

        private fun deriveKey(password: String, salt: ByteArray): ByteArray =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256))
                .encoded

        private fun readRawFully(input: InputStream, buffer: ByteArray) {
            var done = 0
            while (done < buffer.size) {
                val n = input.read(buffer, done, buffer.size - done)
                if (n < 0) {
                    throw EOFException("接続が予期せず切断されました。")
                }
                done += n
            }
        }

        private fun nextNonce(counter: Long, buffer: ByteArray): ByteArray {
            buffer.fill(0)
            var value = counter
            // 末尾 8byte にビッグエンディアンで格納する
            for (i in NONCE_SIZE - 1 downTo NONCE_SIZE - 8) {
                buffer[i] = (value and 0xFF).toByte()
                value = value ushr 8
            }
            return buffer
        }
    }
}
