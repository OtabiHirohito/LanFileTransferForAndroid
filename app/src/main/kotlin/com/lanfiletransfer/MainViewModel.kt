package com.lanfiletransfer

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lanfiletransfer.net.LocalAddresses
import com.lanfiletransfer.service.TransferService
import com.lanfiletransfer.storage.ReceiveDestinations
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class UiMode { SEND, RECEIVE }

data class PendingFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    /** 同名ファイルの判別用に表示する親フォルダ名 (分かる場合のみ)。 */
    val parentLabel: String? = null,
    val mimeType: String? = null
)

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

    fun clearFiles() {
        files = emptyList()
    }

    /**
     * フォルダ選択 (SAF ツリー) から、配下のファイルを再帰的にすべて追加する。
     * 走査はバックグラウンドで行い、追加件数をログに残す。
     */
    fun addFolder(treeUri: Uri) {
        viewModelScope.launch {
            val found = runCatching { collectFolderFiles(treeUri) }
                .getOrElse {
                    TransferEngine.log("フォルダを読み込めませんでした: ${it.message}")
                    return@launch
                }
            if (found.isEmpty()) {
                TransferEngine.log("選択したフォルダにファイルがありませんでした。")
                return@launch
            }
            val known = files.map { it.uri }.toHashSet()
            val appended = found.filterNot { it.uri in known }
            if (appended.isNotEmpty()) {
                files = files + appended
            }
            TransferEngine.log("フォルダから ${appended.size} 件追加しました。")
        }
    }

    /** 他アプリの共有 (ACTION_SEND / SEND_MULTIPLE) からファイルを受け取る。 */
    fun acceptSharedFiles(uris: List<Uri>) {
        if (uris.isEmpty() || engine.value.isRunning) {
            return
        }
        mode = UiMode.SEND
        settings.lastMode = AppSettings.MODE_SEND
        addFiles(uris)
        TransferEngine.log("共有されたファイルを追加しました。")
    }

    private suspend fun collectFolderFiles(treeUri: Uri): List<PendingFile> =
        withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootName = runCatching {
                val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
                resolver.query(
                    rootUri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull().orEmpty()

            val result = mutableListOf<PendingFile>()
            // (documentId, 表示用の相対フォルダ名) のスタック
            val stack = ArrayDeque<Pair<String, String>>()
            stack.addLast(rootId to rootName)

            while (stack.isNotEmpty() && result.size < MAX_FOLDER_FILES) {
                val (parentId, parentLabel) = stack.removeLast()
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri,
                    parentId
                )
                resolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext() && result.size < MAX_FOLDER_FILES) {
                        val documentId = cursor.getString(0) ?: continue
                        val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                        val mime = cursor.getString(2)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            val label = if (parentLabel.isBlank()) name else "$parentLabel/$name"
                            stack.addLast(documentId to label)
                        } else {
                            val size = if (cursor.isNull(3)) -1L else cursor.getLong(3)
                            result += PendingFile(
                                uri = DocumentsContract.buildDocumentUriUsingTree(
                                    treeUri,
                                    documentId
                                ),
                                name = name,
                                size = size,
                                parentLabel = parentLabel.takeIf { it.isNotBlank() },
                                mimeType = mime
                            )
                        }
                    }
                }
            }
            result
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

        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        // 親フォルダ名は表示専用の付加情報。取得に失敗してもファイル追加自体は
        // 絶対に失敗させないよう、ここで例外を握りつぶす。
        val parentLabel = runCatching { parentLabelFromUri(uri, displayName) }.getOrNull()
        return PendingFile(
            uri = uri,
            name = displayName,
            size = size,
            parentLabel = parentLabel,
            mimeType = mime
        )
    }

    /**
     * content Uri から親フォルダ名を推定する (表示専用のベストエフォート)。
     * 例: "primary:Download/photos/a.jpg" -> "photos"、取れなければ null。
     */
    private fun parentLabelFromUri(uri: Uri, fileName: String): String? {
        val segment = uri.lastPathSegment ?: return null
        val decoded = Uri.decode(segment)
        val path = decoded.substringAfter(':', decoded)
        val parts = path.split('/').filter { it.isNotBlank() }
        return if (parts.size >= 2 && parts.last() == fileName) {
            parts[parts.size - 2]
        } else {
            null
        }
    }

    private companion object {
        const val MAX_FOLDER_FILES = 10_000
    }
}
