package com.lanfiletransfer

import com.lanfiletransfer.protocol.FileSender
import com.lanfiletransfer.protocol.ReceiveServer
import com.lanfiletransfer.protocol.ReceiveSink
import com.lanfiletransfer.protocol.TransferProgress
import com.lanfiletransfer.util.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.ConnectException
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * 送受信の実行と状態管理。画面(ViewModel)とフォアグラウンドサービスから共有する。
 *
 * 画面を閉じても受信を続けられるよう、実際の待受はサービスが保持するこの
 * オブジェクト上で動かし、状態は StateFlow で画面へ流す。
 */
object TransferEngine {

    enum class Mode { IDLE, LISTENING, SENDING }

    data class State(
        val mode: Mode = Mode.IDLE,
        val port: Int = 0,
        val fileName: String? = null,
        val fileTotalBytes: Long = 0,
        val fileDoneBytes: Long = 0,
        val filesDone: Int = 0,
        val filesTotal: Int = 0,
        val overallTotalBytes: Long = 0,
        val overallDoneBytes: Long = 0,
        val lastSavedName: String? = null
    ) {
        val isRunning: Boolean
            get() = mode != Mode.IDLE

        val fraction: Float
            get() = when {
                overallTotalBytes > 0 ->
                    (overallDoneBytes.toDouble() / overallTotalBytes).toFloat().coerceIn(0f, 1f)
                fileTotalBytes > 0 ->
                    (fileDoneBytes.toDouble() / fileTotalBytes).toFloat().coerceIn(0f, 1f)
                else -> 0f
            }
    }

    /** 送信する 1 ファイル。open は呼ばれるたびに新しいストリームを返すこと。 */
    data class SendItem(val name: String, val size: Long, val open: () -> InputStream)

    private data class SendPlan(
        val host: String,
        val port: Int,
        val password: String?,
        val items: List<SendItem>
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionCounter = AtomicLong()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    @Volatile
    private var job: Job? = null

    @Volatile
    private var server: ReceiveServer? = null

    @Volatile
    private var activeSocket: Socket? = null

    @Volatile
    private var stopping = false

    @Volatile
    private var plan: SendPlan? = null

    @Volatile
    private var lastProgressNanos = 0L

    fun log(message: String) {
        val line = "${LocalTime.now().format(timeFormat)}  $message"
        _log.update { (listOf(line) + it).take(MAX_LOG_LINES) }
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    // ===== 受信 =====

    @Synchronized
    fun startReceiving(sink: ReceiveSink, port: Int, password: String?) {
        stopLocked()
        val session = sessionCounter.incrementAndGet()
        stopping = false

        _state.value = State(mode = Mode.LISTENING, port = port)
        log("受信待機を開始しました。ポート $port" + if (password != null) " / 暗号化あり" else "")

        val receiveServer = ReceiveServer(
            listenAddress = null,
            port = port,
            password = password,
            sink = sink,
            listener = receiveListener
        )
        server = receiveServer

        job = scope.launch {
            try {
                receiveServer.run { boundPort ->
                    log("待機中: ポート $boundPort で接続を待っています")
                }
            } catch (e: Exception) {
                if (sessionCounter.get() == session) {
                    log("受信待機エラー: ${e.message}")
                }
            } finally {
                receiveServer.stop()
                if (server === receiveServer) {
                    server = null
                }
                if (sessionCounter.get() == session && _state.value.mode != Mode.IDLE) {
                    _state.update {
                        it.copy(
                            mode = Mode.IDLE,
                            port = 0,
                            fileName = null,
                            fileTotalBytes = 0,
                            fileDoneBytes = 0
                        )
                    }
                    log("受信待機を停止しました。")
                }
            }
        }
    }

    private val receiveListener = object : ReceiveServer.Listener {
        override fun onFileStarted(displayName: String, fileSize: Long) {
            _state.update {
                it.copy(
                    fileName = displayName,
                    fileTotalBytes = fileSize,
                    fileDoneBytes = 0,
                    lastSavedName = null
                )
            }
            log("受信開始: $displayName (${formatBytes(fileSize)})")
        }

        override fun onProgress(progress: TransferProgress) {
            if (!shouldPublishProgress()) {
                return
            }
            _state.update {
                it.copy(
                    fileName = progress.fileName,
                    fileTotalBytes = progress.totalBytes,
                    fileDoneBytes = progress.transferredBytes
                )
            }
        }

        override fun onFileReceived(savedName: String) {
            _state.update {
                it.copy(
                    fileName = null,
                    fileTotalBytes = 0,
                    fileDoneBytes = 0,
                    filesDone = it.filesDone + 1,
                    lastSavedName = savedName
                )
            }
            log("受信完了: $savedName（待機を継続します）")
        }

        override fun onFileFailed(message: String) {
            _state.update { it.copy(fileTotalBytes = 0, fileDoneBytes = 0) }
            log("受信エラー: $message（待機を継続します）")
        }
    }

    // ===== 送信 =====

    @Synchronized
    fun prepareSend(host: String, port: Int, password: String?, items: List<SendItem>) {
        plan = SendPlan(host, port, password, items)
    }

    @Synchronized
    fun startSending() {
        val currentPlan = plan
        if (currentPlan == null) {
            log("送信する内容がありません。")
            return
        }
        plan = null

        stopLocked()
        val session = sessionCounter.incrementAndGet()
        stopping = false

        val overallTotal = currentPlan.items.sumOf { it.size.coerceAtLeast(0) }
        _state.value = State(
            mode = Mode.SENDING,
            port = currentPlan.port,
            filesTotal = currentPlan.items.size,
            overallTotalBytes = overallTotal
        )
        log(
            "送信を開始しました。${currentPlan.items.size}件 -> ${currentPlan.host}:${currentPlan.port}" +
                if (currentPlan.password != null) " / 暗号化あり" else ""
        )

        job = scope.launch {
            var completedBytes = 0L
            var sentCount = 0

            try {
                for ((index, item) in currentPlan.items.withIndex()) {
                    if (stopping) {
                        break
                    }

                    _state.update {
                        it.copy(
                            fileName = item.name,
                            fileTotalBytes = item.size,
                            fileDoneBytes = 0,
                            filesDone = index,
                            filesTotal = currentPlan.items.size,
                            overallDoneBytes = completedBytes
                        )
                    }
                    log("送信開始 (${index + 1}/${currentPlan.items.size}): ${item.name} -> ${currentPlan.host}:${currentPlan.port}")

                    try {
                        var waitingLogged = false

                        item.open().use { input ->
                            FileSender.send(
                                host = currentPlan.host,
                                port = currentPlan.port,
                                fileName = item.name,
                                fileSize = item.size,
                                input = input,
                                password = currentPlan.password,
                                onSocketReady = { socket -> activeSocket = socket },
                                shouldStop = { stopping },
                                onProgress = { progress ->
                                    if (shouldPublishProgress()) {
                                        _state.update {
                                            it.copy(
                                                fileDoneBytes = progress.transferredBytes,
                                                overallDoneBytes = completedBytes + progress.transferredBytes
                                            )
                                        }
                                    }
                                },
                                onWaiting = { message ->
                                    if (message.contains("待っています")) {
                                        if (!waitingLogged) {
                                            waitingLogged = true
                                            log(
                                                "受信側がまだ受信待機を開始していません。" +
                                                    "${FileSender.RECEIVER_WAIT_TIMEOUT_MS / 1000}秒間、接続を再試行します。"
                                            )
                                        }
                                    } else if (waitingLogged) {
                                        waitingLogged = false
                                        log(message)
                                    }
                                }
                            )
                        }

                        completedBytes += item.size
                        sentCount++
                        _state.update {
                            it.copy(
                                filesDone = sentCount,
                                fileDoneBytes = item.size,
                                overallDoneBytes = completedBytes
                            )
                        }
                        log("送信完了 (${index + 1}/${currentPlan.items.size}): ${item.name}")
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        if (!stopping) {
                            log("送信エラー: ${describeError(e)}")
                        }
                        break
                    }
                }

                if (stopping) {
                    log("送信を停止しました。（${sentCount}/${currentPlan.items.size} 件送信済み）")
                } else if (sentCount == currentPlan.items.size && sentCount > 0) {
                    log(
                        if (sentCount > 1) "${sentCount}件のファイル送信が完了しました。"
                        else "送信が完了しました。"
                    )
                }
            } finally {
                activeSocket = null
                if (sessionCounter.get() == session) {
                    _state.update {
                        it.copy(
                            mode = Mode.IDLE,
                            fileName = null,
                            fileTotalBytes = 0,
                            fileDoneBytes = 0
                        )
                    }
                }
            }
        }
    }

    // ===== 停止 =====

    @Synchronized
    fun stop() {
        if (!_state.value.isRunning) {
            return
        }

        stopLocked()
        _state.update {
            it.copy(
                mode = Mode.IDLE,
                port = 0,
                fileName = null,
                fileTotalBytes = 0,
                fileDoneBytes = 0
            )
        }
        log("転送を停止しました。")
    }

    /** 実行中のセッションを中断する(状態の更新は各セッションの finally に任せる場合に使う)。 */
    @Synchronized
    private fun stopLocked() {
        stopping = true
        val activeServer = server
        server = null
        runCatching { activeServer?.stop() }
        runCatching { activeSocket?.close() }
        activeSocket = null
        job?.cancel()
        job = null
        sessionCounter.incrementAndGet()
    }

    /** 進捗の通知は 150ms 間隔に間引く(通知と再描画の負荷対策)。 */
    private fun shouldPublishProgress(): Boolean {
        val now = System.nanoTime()
        if (now - lastProgressNanos < PROGRESS_INTERVAL_NANOS) {
            return false
        }
        lastProgressNanos = now
        return true
    }

    private fun describeError(e: Exception): String = when (e) {
        is ConnectException ->
            e.message ?: "接続できませんでした。受信側が待機しているか、IP アドレスとポートを確認してください。"
        is SocketTimeoutException ->
            "通信がタイムアウトしました。受信側の状態とネットワークを確認してください。"
        is FileNotFoundException ->
            e.message ?: "送信するファイルを開けませんでした。"
        else ->
            e.message ?: e.javaClass.simpleName
    }

    private const val MAX_LOG_LINES = 300
    private const val PROGRESS_INTERVAL_NANOS = 150_000_000L
}
