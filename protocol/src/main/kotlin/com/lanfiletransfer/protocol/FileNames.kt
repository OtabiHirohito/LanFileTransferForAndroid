package com.lanfiletransfer.protocol

/**
 * Windows 版 `SafeFileStore.SanitizeFileName` の移植。
 * 受信したファイル名からパス区切り・危険な文字・予約名を除去する。
 */
object FileNames {

    private const val MAX_FILE_NAME_LENGTH = 200

    /** Windows / Android の両方でファイル名に使えない文字。 */
    private val INVALID_CHARS = charArrayOf(
        '\u0000', '/', '\\', ':', '*', '?', '"', '<', '>', '|',
        // 制御文字
        '\u0001', '\u0002', '\u0003', '\u0004', '\u0005', '\u0006', '\u0007',
        '\b', '\t', '\n', '\u000B', '\u000C', '\r', '\u000E', '\u000F',
        '\u0010', '\u0011', '\u0012', '\u0013', '\u0014', '\u0015', '\u0016', '\u0017',
        '\u0018', '\u0019', '\u001A', '\u001B', '\u001C', '\u001D', '\u001E', '\u001F'
    )

    fun sanitize(name: String): String {
        var result = name
        for (c in INVALID_CHARS) {
            result = result.replace(c, '_')
        }

        // 末尾のドット・空白を除去(".", ".." もここで弾く)
        result = result.trim().trimEnd('.')

        if (result.isBlank() || result == "." || result == "..") {
            return "received_file"
        }

        val baseName = result.substringBeforeLast('.', result)
        if (isReservedDeviceName(baseName)) {
            result = "_$result"
        }

        if (result.length > MAX_FILE_NAME_LENGTH) {
            val dot = result.lastIndexOf('.')
            val ext = if (dot > 0) result.substring(dot) else ""
            var stem = if (dot > 0) result.substring(0, dot) else result
            val keep = maxOf(1, MAX_FILE_NAME_LENGTH - ext.length)
            if (stem.length > keep) {
                stem = stem.substring(0, keep)
            }
            result = stem + ext
        }

        return result
    }

    /** Windows の予約デバイス名 (CON, PRN, AUX, NUL, COM1-9, LPT1-9) かどうか。 */
    private fun isReservedDeviceName(baseName: String): Boolean {
        val n = baseName.trim().uppercase()
        if (n == "CON" || n == "PRN" || n == "AUX" || n == "NUL") {
            return true
        }
        return n.length == 4 &&
            (n.startsWith("COM") || n.startsWith("LPT")) &&
            n[3] in '1'..'9'
    }

    /** "report.pdf" -> ("report", ".pdf") */
    fun splitExtension(fileName: String): Pair<String, String> {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) {
            fileName.substring(0, dot) to fileName.substring(dot)
        } else {
            fileName to ""
        }
    }

    /** 同名がある場合に "名前(1).拡張子" を作る。isTaken は小文字比較済みの名前を受け取る。 */
    fun uniqueName(fileName: String, isTaken: (String) -> Boolean): String {
        if (!isTaken(fileName.lowercase())) {
            return fileName
        }

        val (stem, ext) = splitExtension(fileName)
        var index = 1
        while (true) {
            val candidate = "$stem($index)$ext"
            if (!isTaken(candidate.lowercase())) {
                return candidate
            }
            index++
        }
    }
}
