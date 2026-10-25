package com.lanfiletransfer.protocol

import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest

/** 受信ファイルの保存先。SAF(Android)でも通常のフォルダでも実装できるよう抽象化する。 */
interface ReceiveSink {
    /**
     * 受信開始前に保存先を用意する。
     * 空き容量不足などで保存できない場合は IOException を投げる。
     */
    fun createTarget(fileName: String, fileSize: Long): ReceiveTarget
}

/** 受信中の 1 ファイル。成功時は [finish]、失敗時は [abort] を呼ぶ。 */
interface ReceiveTarget {
    /** 実際に保存されるファイル名(重複回避後の名前)。 */
    val displayName: String

    val output: OutputStream

    fun finish()

    fun abort()
}

/**
 * Windows 版 `FileTransferService.StartReceivingAsync` と同じ待受ループ。
 * キャンセルされるまで接続を受け付け、1 接続につき 1 ファイルを受信する。
 * 1 件の失敗ではループを止めない。
 */
class ReceiveServer(
    private val listenAddress: InetAddress?,
    private val port: Int,
    private val password: String?,
    private val sink: ReceiveSink,
    private val listener: Listener
) {

    interface Listener {
        fun onFileStarted(displayName: String, fileSize: Long) {}
        fun onProgress(progress: TransferProgress) {}
        fun onFileReceived(savedName: String) {}
        fun onFileFailed(message: String) {}
    }

    @Volatile
    private var stopped = false

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var activeSocket: Socket? = null

    @Volatile
    var boundPort: Int = 0
        private set

    val isRunning: Boolean
        get() = !stopped

    /**
     * 待ち受けを開始する。[onReady] で実際の待受ポートを受け取れる(ポート 0 指定時は必須)。
     * 停止するまで戻らない。
     */
    fun run(onReady: (Int) -> Unit = {}) {
        if (stopped) {
            return
        }

        val server = ServerSocket()
        server.reuseAddress = true
        try {
            server.bind(InetSocketAddress(listenAddress, port), 16)
            serverSocket = server
            boundPort = server.localPort
            onReady(boundPort)

            while (!stopped) {
                val socket = try {
                    server.accept()
                } catch (e: SocketException) {
                    if (stopped) break else throw e
                }

                activeSocket = socket
                try {
                    receiveOne(socket)
                } catch (e: InterruptedException) {
                    if (!stopped) listener.onFileFailed(e.message ?: "受信を中断しました。")
                } catch (e: Exception) {
                    if (!stopped) listener.onFileFailed(e.message ?: e.javaClass.simpleName)
                } finally {
                    activeSocket = null
                    runCatching { socket.close() }
                }
            }
        } finally {
            serverSocket = null
            runCatching { server.close() }
            activeSocket = null
        }
    }

    /** 待受と進行中の接続を閉じる。別スレッドから呼んでよい。 */
    fun stop() {
        stopped = true
        runCatching { serverSocket?.close() }
        runCatching { activeSocket?.close() }
    }

    private fun receiveOne(socket: Socket) {
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS

        SecureChannel.createReader(socket.getInputStream(), socket.getOutputStream(), password).use { channel ->
            socket.soTimeout = IDLE_TIMEOUT_MS

            val header = TransferProtocol.readHeader(channel)
            val fileName = FileNames.sanitize(header.fileName)

            val target = sink.createTarget(fileName, header.fileSize)
            var succeeded = false

            try {
                listener.onFileStarted(target.displayName, header.fileSize)

                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(TransferProtocol.BUFFER_SIZE)
                var received = 0L

                while (received < header.fileSize) {
                    if (stopped) {
                        throw InterruptedException("受信を停止しました。")
                    }

                    val toRead = minOf(buffer.size.toLong(), header.fileSize - received).toInt()
                    channel.readExact(buffer, 0, toRead)
                    target.output.write(buffer, 0, toRead)
                    digest.update(buffer, 0, toRead)
                    received += toRead
                    listener.onProgress(TransferProgress(target.displayName, header.fileSize, received))
                }

                target.output.flush()

                val expected = ByteArray(TransferProtocol.HASH_SIZE)
                channel.readExact(expected)
                if (!MessageDigest.isEqual(expected, digest.digest())) {
                    throw ProtocolException("ハッシュが一致しません。(データ破損または改ざんの可能性があります)")
                }

                target.finish()
                succeeded = true
                listener.onFileReceived(target.displayName)
            } finally {
                if (!succeeded) {
                    target.abort()
                }
            }
        }
    }

    companion object {
        const val HANDSHAKE_TIMEOUT_MS = 30_000
        const val IDLE_TIMEOUT_MS = 60_000
    }
}
