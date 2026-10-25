package com.lanfiletransfer

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.lanfiletransfer.net.LocalAddresses
import com.lanfiletransfer.service.TransferService
import com.lanfiletransfer.storage.ReceiveDestinations
import java.io.IOException

enum class UiMode { SEND, RECEIVE }

data class PendingFile(val uri: Uri, val name: String, val size: Long)

/** 画面の状態と操作。 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = AppSettings(application)

    val engine = TransferEngine.state
    val log = TransferEngine.log

    var mode by mutableStateOf(
        if (settings.lastMode == AppSettings.MODE_RECEIVE) UiMode.RECEIVE else UiMode.SEND
    )
        private set

    var host by mutableStateOf(settings.lastHost)
    var portText by mutableStateOf(settings.lastPort.toString())
    var passwordEnabled by mutableStateOf(false)
    var password by mutableStateOf("")
    var files by mutableStateOf<List<PendingFile>>(emptyList())
        private set

    var saveTreeUri by mutableStateOf(settings.saveTreeUri)
        private set

    private var saveLocationRevision by mutableStateOf(0)

    var addresses by mutableStateOf(LocalAddresses.list())
        private set

    val saveLocationLabel: String
        get() {
            // ストレージ権限の許可後に表示を更新するための依存。
            saveLocationRevision
            val tree = saveTreeUri
                ?: return ReceiveDestinations.label(getApplication())
            return runCatching {
                val documentId = android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(tree))
                val path = documentId.substringAfter(':', documentId)
                if (path.isBlank()) "選択したフォルダ" else path
            }.getOrDefault("選択したフォルダ")
        }

    /** Android 9 以前で保存先の権限が変わったときに呼ぶ。 */
    fun refreshSaveLocationLabel() {
        saveLocationRevision++
    }

    fun selectMode(newMode: UiMode) {
        if (engine.value.isRunning) {
            return
        }
        mode = newMode
        settings.lastMode = if (newMode == UiMode.SEND) AppSettings.MODE_SEND else AppSettings.MODE_RECEIVE
    }

    fun refreshAddresses() {
        addresses = LocalAddresses.list()
    }

    // ===== 送信 =====

    fun addFiles(uris: List<Uri>) {
        val known = files.map { it.uri }.toHashSet()
        val appended = uris.asSequence()
            .filterNot { it in known }
            .mapNotNull { describeFile(it) }
            .toList()

        if (appended.isNotEmpty()) {
            files = files + appended
        }
    }

    fun removeFile(uri: Uri) {
        files = files.filterNot { it.uri == uri }
    }

    fun startSend(): String? {
        if (engine.value.isRunning) {
            return null
        }

        val port = parsePort() ?: return "ポート番号が不正です。"
        val target = host.trim()
        if (target.isEmpty()) {
            return "相手の IP アドレスを入力してください。"
        }
        if (files.isEmpty()) {
            return "送信するファイルを選択してください。"
        }
        if (files.any { it.size < 0 }) {
            return "ファイルサイズを取得できないファイルがあります。別のファイルを選んでください。"
        }

        val passphrase = validatedPassword() ?: return passwordError ?: "合言葉を入力してください。"
        val resolver = getApplication<Application>().contentResolver

        val items = files.map { file ->
            TransferEngine.SendItem(file.name, file.size) {
                resolver.openInputStream(file.uri)
                    ?: throw IOException("ファイルを開けませんでした。(${file.name})")
            }
        }

        // 合言葉なしの場合は null を渡す
        val passwordOrNull = if (passwordEnabled) passphrase else null

        settings.lastHost = target
        settings.lastPort = port
        settings.lastMode = AppSettings.MODE_SEND

        TransferEngine.prepareSend(target, port, passwordOrNull, items)
        TransferService.startSend(getApplication())
        return null
    }

    // ===== 受信 =====

    fun setSaveFolder(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(uri, flags) }
        settings.rememberSaveTree(uri)
        saveTreeUri = uri.toString()
    }

    fun clearSaveFolder() {
        settings.rememberSaveTree(null)
        saveTreeUri = null
    }

    fun startReceive(): String? {
        if (engine.value.isRunning) {
            return null
        }

        val port = parsePort() ?: return "待受ポート番号が不正です。"
        val passphrase = validatedPassword() ?: return passwordError ?: "合言葉を入力してください。"
        val passwordOrNull = if (passwordEnabled) passphrase else null

        settings.lastPort = port
        settings.lastMode = AppSettings.MODE_RECEIVE

        TransferService.startReceive(getApplication(), port, passwordOrNull, saveTreeUri)
        return null
    }

    fun stop() {
        TransferEngine.stop()
    }

    fun clearLog() {
        TransferEngine.clearLog()
    }

    // ===== 内部 =====

    private var passwordError: String? = null

    private fun validatedPassword(): String? {
        passwordError = null
        if (!passwordEnabled) {
            return ""
        }
        if (password.isEmpty()) {
            passwordError = "合言葉を入力してください。"
            return null
        }
        return password
    }

    private fun parsePort(): Int? {
        val port = portText.trim().toIntOrNull() ?: return null
        return if (port in 1..65535) port else null
    }

    private fun describeFile(uri: Uri): PendingFile? {
        val resolver = getApplication<Application>().contentResolver
        var name: String? = null
        var size = -1L

        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        name = cursor.getString(nameIndex)
                    }

                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        }

        val displayName = name
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: return null

        return PendingFile(uri, displayName, size)
    }
}
