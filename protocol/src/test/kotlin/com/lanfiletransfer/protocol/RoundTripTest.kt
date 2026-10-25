package com.lanfiletransfer.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * 送信側と受信側を同じ実装でループバック接続し、平文・暗号化の両方で
 * 1 ファイルが正しく転送されることを確認する。
 * (Windows 版との相互接続は android/README.md の手順で別途確認する)
 */
class RoundTripTest {

    @Test
    fun plaintextRoundTrip() {
        roundTrip(password = null)
    }

    @Test
    fun encryptedRoundTrip() {
        roundTrip(password = "合言葉 pass-123")
    }

    @Test
    fun emptyFileRoundTrip() {
        roundTrip(password = "secret", size = 0)
    }

    @Test
    fun unicodeAndUnsafeFileNameIsSanitized() {
        roundTrip(password = null, fileName = "../危険な:名前?.txt")
    }

    @Test
    fun mismatchedPasswordIsRejected() {
        withServer(password = "one") { port, dir ->
            val error = runCatching {
                FileSender.send(
                    host = "127.0.0.1",
                    port = port,
                    fileName = "a.bin",
                    fileSize = 4,
                    input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
                    password = "two"
                )
            }.exceptionOrNull()

            assertTrue("拒否されるはず: $error", error is ProtocolException)
            assertTrue("理由が伝わるはず: ${error?.message}", error?.message?.contains("合言葉") == true)
            assertEquals(0, dir.listFiles()?.size ?: 0)
        }
    }

    @Test
    fun sizeMismatchLeavesNoFile() {
        withServer(password = null) { port, dir ->
            runCatching {
                FileSender.send(
                    host = "127.0.0.1",
                    port = port,
                    fileName = "short.bin",
                    fileSize = 100,
                    input = ByteArrayInputStream(ByteArray(50))
                )
            }
            // 受信側は 100byte を待って切断 → 不完全ファイルは削除される
            Thread.sleep(300)
            assertEquals(0, dir.listFiles()?.size ?: 0)
        }
    }

    private fun roundTrip(
        password: String?,
        size: Int = 300_000,
        fileName: String = "テスト ファイル.bin"
    ) {
        withServer(password) { port, dir ->
            val data = Random(42).nextBytes(size)
            FileSender.send(
                host = "127.0.0.1",
                port = port,
                fileName = fileName,
                fileSize = size.toLong(),
                input = ByteArrayInputStream(data),
                password = password
            )

            waitForFile(dir)
            val saved = dir.listFiles()?.singleOrNull()
                ?: throw AssertionError("受信ファイルがありません")
            val expectedName = FileNames.sanitize(fileName)
            assertEquals(expectedName, saved.name)
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(data), sha256(saved))
        }
    }

    private fun withServer(password: String?, block: (port: Int, dir: File) -> Unit) {
        val dir = Files.createTempDirectory("lft-test").toFile()
        val server = ReceiveServer(
            listenAddress = null,
            port = 0,
            password = password,
            sink = LocalDirectorySink(dir),
            listener = object : ReceiveServer.Listener {}
        )

        val ready = CountDownLatch(1)
        val worker = thread(isDaemon = true) {
            server.run { ready.countDown() }
        }
        assertTrue("待受が開始されませんでした", ready.await(10, TimeUnit.SECONDS))

        try {
            block(server.boundPort, dir)
        } finally {
            server.stop()
            worker.join(5_000)
            dir.deleteRecursively()
        }
    }

    private fun waitForFile(dir: File, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if ((dir.listFiles()?.size ?: 0) > 0) {
                // 書き込み完了を待つ
                Thread.sleep(200)
                return
            }
            Thread.sleep(20)
        }
        throw IOException("受信ファイルが現れませんでした")
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest()
    }
}
