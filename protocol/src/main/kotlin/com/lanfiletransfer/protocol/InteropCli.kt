package com.lanfiletransfer.protocol

import java.io.File
import java.io.FileInputStream

/**
 * 開発用 CLI。Windows 版アプリとの相互接続確認に使う。
 *
 * ```
 * # 受信(Windows 版の受信待機と同じ)
 * runInteropCli --args="serve 5000 ./received 合言葉"
 *
 * # 送信
 * runInteropCli --args="send 192.168.1.10 5000 ./file.bin 合言葉"
 * ```
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull()
    when (command) {
        "send" -> {
            if (args.size < 4) {
                println("usage: send <host> <port> <file> [password]")
                return
            }
            val host = args[1]
            val port = args[2].toInt()
            val file = File(args[3])
            val password = args.getOrNull(4)?.takeIf { it.isNotEmpty() }

            var lastPercent = -1
            FileInputStream(file).use { input ->
                FileSender.send(
                    host = host,
                    port = port,
                    fileName = file.name,
                    fileSize = file.length(),
                    input = input,
                    password = password
                ) { progress ->
                    val percent = progress.percentage.toInt()
                    if (percent / 10 != lastPercent / 10) {
                        lastPercent = percent
                        println("送信 ${progress.fileName}: ${progress.transferredBytes}/${progress.totalBytes} (${percent}%)")
                    }
                }
            }
            println("送信完了: ${file.name}")
        }

        "serve" -> {
            if (args.size < 3) {
                println("usage: serve <port> <directory> [password]")
                return
            }
            val port = args[1].toInt()
            val directory = File(args[2])
            val password = args.getOrNull(3)?.takeIf { it.isNotEmpty() && it != "--once" }
            val stopAfterOne = args.contains("--once")

            var server: ReceiveServer? = null
            server = ReceiveServer(
                listenAddress = null,
                port = port,
                password = password,
                sink = LocalDirectorySink(directory),
                listener = object : ReceiveServer.Listener {
                    override fun onFileStarted(displayName: String, fileSize: Long) {
                        println("受信開始: $displayName ($fileSize bytes)")
                    }

                    override fun onFileReceived(savedName: String) {
                        println("受信完了: $savedName -> ${File(directory, savedName).absolutePath}")
                        if (stopAfterOne) server?.stop()
                    }

                    override fun onFileFailed(message: String) {
                        println("受信エラー: $message")
                        if (stopAfterOne) server?.stop()
                    }
                }
            )

            server.run { boundPort -> println("待受開始: 0.0.0.0:$boundPort 保存先=${directory.absolutePath}") }
        }

        else -> {
            println("usage: (send|serve) ...")
        }
    }
}
