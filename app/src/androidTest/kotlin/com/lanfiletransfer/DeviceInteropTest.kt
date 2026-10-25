package com.lanfiletransfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lanfiletransfer.protocol.FileSender
import com.lanfiletransfer.protocol.LocalDirectorySink
import com.lanfiletransfer.protocol.ReceiveServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android ランタイム(Conscrypt の PBKDF2 / AES-GCM)で、
 * Windows 版の実装を動かしているホスト PC と相互接続できることを確認する。
 *
 * 事前にホスト側で以下を起動しておく(android/README.md 参照)。
 *  - 5061: Windows 版の受信側 (合言葉 "合言葉パス123")
 *  - 5062: Windows 版の送信側を adb reverse 経由で接続 (合言葉 "受信側パス")
 */
@RunWith(AndroidJUnit4::class)
class DeviceInteropTest {

    /** ホスト側ピアの IP(エミュレータなら 10.0.2.2)。未指定ならテストをスキップする。 */
    private val host: String? =
        InstrumentationRegistry.getArguments().getString("interopHost")

    @Test
    fun sendsToHostWindowsPeer() {
        Assume.assumeTrue("interopHost が未指定のためスキップします", host != null)
        val data = ByteArray(1_500_000) { 'B'.code.toByte() }

        FileSender.send(
            host = host!!,
            port = 5061,
            fileName = "device-to-windows.bin",
            fileSize = data.size.toLong(),
            input = ByteArrayInputStream(data),
            password = "合言葉パス123"
        )
    }

    @Test
    fun receivesFromHostWindowsPeer() {
        Assume.assumeTrue("interopHost が未指定のためスキップします", host != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "interop-from-windows").apply {
            deleteRecursively()
            mkdirs()
        }

        val done = CountDownLatch(1)
        val runError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

        val server = ReceiveServer(
            listenAddress = null,
            port = 5062,
            password = "受信側パス",
            sink = LocalDirectorySink(dir),
            listener = object : ReceiveServer.Listener {
                override fun onFileReceived(savedName: String) {
                    done.countDown()
                }

                override fun onFileFailed(message: String) {
                    // 1 件失敗しても待機を継続する仕様なので、ここでは何もしない
                }
            }
        )

        val worker = Thread {
            try {
                server.run()
            } catch (t: Throwable) {
                runError.set(t)
            }
        }.apply {
            isDaemon = true
            start()
        }

        try {
            assertTrue(
                "Windows 版からの受信がタイムアウトしました: ${runError.get()?.message}",
                done.await(90, TimeUnit.SECONDS)
            )
        } finally {
            server.stop()
            worker.join(5_000)
        }

        val file = dir.listFiles()?.singleOrNull()
            ?: throw AssertionError("受信ファイルがありません")

        assertEquals("windows-to-device.bin", file.name)
        assertEquals(1_000_000L, file.length())
        assertTrue("内容が一致しません", file.readBytes().all { it == 'A'.code.toByte() })
    }
}
