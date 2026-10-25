package com.lanfiletransfer.protocol

import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest

/**
 * Windows 版 `FileTransferService.SendFileAsync` と同じ手順で 1 ファイルを送る。
 * 1 接続 = 1 ファイル。
 */
object FileSender {

    const val CONNECT_TIMEOUT_MS = 5_000

    /** 受信側が「受信待機を開始」するまで接続を再試行し続ける時間。 */
    const val RECEIVER_WAIT_TIMEOUT_MS = 60_000

    const val IDLE_TIMEOUT_MS = 60_000

    private const val CONNECT_RETRY_INTERVAL_MS = 1_000L

    /**
     * @param onSocketReady 接続後にソケットを通知する(停止時に close するため)
     * @param shouldStop 各チャンク送信前に呼ばれる。true を返すと中断する。
     * @param onWaiting 受信側の待機を待っている間の状況通知。接続できたときは接続完了のメッセージを渡す。
     */
    fun send(
        host: String,
        port: Int,
        fileName: String,
        fileSize: Long,
        input: InputStream,
        password: String? = null,
        onSocketReady: (Socket) -> Unit = {},
        shouldStop: () -> Boolean = { false },
        onWaiting: (String) -> Unit = {},
        onProgress: (TransferProgress) -> Unit = {}
    ) {
        val socket = connectWithRetry(host, port, shouldStop, onWaiting)
        try {
            socket.soTimeout = IDLE_TIMEOUT_MS
            onSocketReady(socket)

            SecureChannel.createWriter(socket.getInputStream(), socket.getOutputStream(), password).use { channel ->
                TransferProtocol.writeHeader(channel, fileName, fileSize)

                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(TransferProtocol.BUFFER_SIZE)
                var sent = 0L

                while (true) {
                    if (shouldStop()) {
                        throw InterruptedException("送信を停止しました。")
                    }

                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    if (read == 0) {
                        continue
                    }

                    digest.update(buffer, 0, read)
                    channel.write(buffer, 0, read)
                    sent += read
                    onProgress(TransferProgress(fileName, fileSize, sent))
                }

                if (sent != fileSize) {
                    throw IOException("ファイルサイズが送信前に変わったため中止しました。($sent / $fileSize)")
                }

                channel.write(digest.digest())
                channel.flush()
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * 接続を試み、受信側がまだ待機していない場合は一定時間つなぎ直す。
     * 受信側で「受信待機を開始」してから送信開始を押す順番でなくても送信できるようにする。
     */
    private fun connectWithRetry(
        host: String,
        port: Int,
        shouldStop: () -> Boolean,
        onWaiting: (String) -> Unit
    ): Socket {
        val deadline = System.currentTimeMillis() + RECEIVER_WAIT_TIMEOUT_MS
        var waited = false

        while (true) {
            if (shouldStop()) {
                throw InterruptedException("送信を停止しました。")
            }

            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

                if (waited) {
                    onWaiting("受信側に接続しました。送信を開始します…")
                }

                return socket
            } catch (e: IOException) {
                runCatching { socket.close() }

                // 相手がまだ待機していない(拒否)、一時的に到達できない、などの再接続で解消しうるものだけ再試行する
                if (e !is ConnectException && e !is SocketTimeoutException && e !is NoRouteToHostException) {
                    throw e
                }

                waited = true

                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    throw ConnectException(
                        "受信側に接続できませんでした。受信側のアプリで「受信待機を開始」しているか、" +
                            "IP アドレスとポート番号が合っているか確認してください。" +
                            "（${RECEIVER_WAIT_TIMEOUT_MS / 1000}秒間 接続を待ちました）"
                    ).apply { initCause(e) }
                }

                onWaiting("受信側の受信待機を待っています…（あと${(remaining + 999) / 1000}秒）")
                Thread.sleep(minOf(remaining, CONNECT_RETRY_INTERVAL_MS))
            }
        }
    }
}
