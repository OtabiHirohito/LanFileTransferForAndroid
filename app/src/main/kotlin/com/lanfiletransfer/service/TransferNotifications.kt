package com.lanfiletransfer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lanfiletransfer.MainActivity
import com.lanfiletransfer.R
import com.lanfiletransfer.TransferEngine
import com.lanfiletransfer.util.formatBytes

/** 転送中の常駐通知(フォアグラウンドサービス用)。 */
object TransferNotifications {

    const val ID = 1001

    private const val CHANNEL_ID = "transfer"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }

        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, state: TransferEngine.State): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setColor(0xFF6366F1.toInt())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context))
            .addAction(0, "停止", stopIntent(context))

        when (state.mode) {
            TransferEngine.Mode.LISTENING -> {
                builder.setContentTitle("受信待機中")
                val received = if (state.filesDone > 0) "（${state.filesDone}件受信済み）" else ""
                builder.setContentText("ポート ${state.port} で接続を待っています$received")
                if (state.fileName != null) {
                    builder.setProgress(
                        1000,
                        (state.fraction * 1000).toInt().coerceIn(0, 1000),
                        false
                    )
                } else {
                    builder.setProgress(0, 0, true)
                }
            }

            TransferEngine.Mode.SENDING -> {
                builder.setContentTitle("送信中: ${state.fileName ?: ""}")
                builder.setContentText(
                    "${state.filesDone + 1}/${state.filesTotal} 件目  " +
                        "${formatBytes(state.overallDoneBytes)} / ${formatBytes(state.overallTotalBytes)}"
                )
                if (state.overallTotalBytes > 0) {
                    builder.setProgress(1000, (state.fraction * 1000).toInt().coerceIn(0, 1000), false)
                } else {
                    builder.setProgress(0, 0, true)
                }
            }

            TransferEngine.Mode.IDLE -> {
                builder.setContentTitle("待機中")
                builder.setContentText("転送は停止しています")
                builder.setOngoing(false)
            }
        }

        return builder.build()
    }

    fun update(context: Context, state: TransferEngine.State) {
        try {
            NotificationManagerCompat.from(context).notify(ID, build(context, state))
        } catch (e: SecurityException) {
            // 通知権限がない場合でも転送自体は継続する
        }
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun stopIntent(context: Context): PendingIntent {
        val intent = Intent(context, TransferService::class.java).apply {
            action = TransferService.ACTION_STOP
        }
        return PendingIntent.getService(
            context,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
