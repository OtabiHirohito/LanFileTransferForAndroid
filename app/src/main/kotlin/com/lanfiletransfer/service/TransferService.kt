package com.lanfiletransfer.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lanfiletransfer.TransferEngine
import com.lanfiletransfer.protocol.ReceiveSink
import com.lanfiletransfer.storage.ReceiveDestinations
import com.lanfiletransfer.storage.SafTreeSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 画面を閉じても転送を続けるためのフォアグラウンドサービス。
 * 実際の通信は [TransferEngine] が行い、このサービスは常駐通知と生存維持を担当する。
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var finishing = false

    override fun onCreate() {
        super.onCreate()
        TransferNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECEIVE -> {
                startReceiveEngine(intent)
                startAsForeground()
            }

            ACTION_START_SEND -> {
                TransferEngine.startSending()
                startAsForeground()
            }

            ACTION_STOP -> {
                TransferEngine.stop()
                if (TransferEngine.state.value.isRunning) {
                    startAsForeground()
                } else {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }

            else -> {
                if (TransferEngine.state.value.isRunning) {
                    startAsForeground()
                } else {
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observer?.cancel()
        observer = null
        scope.cancel()
        TransferEngine.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startReceiveEngine(intent: Intent) {
        val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
        val password = intent.getStringExtra(EXTRA_PASSWORD)?.takeIf { it.isNotEmpty() }
        val treeUri = intent.getStringExtra(EXTRA_TREE_URI)

        val sink: ReceiveSink
        val label: String
        try {
            if (treeUri != null) {
                val safSink = SafTreeSink(this, Uri.parse(treeUri))
                sink = safSink
                label = safSink.description
            } else {
                sink = ReceiveDestinations.create(this)
                label = ReceiveDestinations.label(this)
            }
        } catch (e: Exception) {
            TransferEngine.log("保存先を開けませんでした: ${e.message}")
            return
        }

        TransferEngine.log("保存先: $label")
        TransferEngine.startReceiving(sink, port, password)
    }

    private fun startAsForeground() {
        val state = TransferEngine.state.value
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

        ServiceCompat.startForeground(
            this,
            TransferNotifications.ID,
            TransferNotifications.build(this, state),
            type
        )

        observe()
    }

    private fun observe() {
        if (observer != null) {
            return
        }

        observer = scope.launch {
            TransferEngine.state.collect { state ->
                if (state.mode == TransferEngine.Mode.IDLE) {
                    if (!finishing) {
                        finishing = true
                        ServiceCompat.stopForeground(
                            this@TransferService,
                            ServiceCompat.STOP_FOREGROUND_REMOVE
                        )
                        stopSelf()
                    }
                } else {
                    TransferNotifications.update(this@TransferService, state)
                }
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.lanfiletransfer.action.STOP"
        const val DEFAULT_PORT = 5000

        private const val ACTION_START_RECEIVE = "com.lanfiletransfer.action.START_RECEIVE"
        private const val ACTION_START_SEND = "com.lanfiletransfer.action.START_SEND"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_PASSWORD = "password"
        private const val EXTRA_TREE_URI = "tree_uri"

        fun startReceive(context: Context, port: Int, password: String?, treeUri: String?) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_START_RECEIVE
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_PASSWORD, password)
                putExtra(EXTRA_TREE_URI, treeUri)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun startSend(context: Context) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_START_SEND
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
