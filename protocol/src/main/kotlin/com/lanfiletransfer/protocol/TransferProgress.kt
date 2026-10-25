package com.lanfiletransfer.protocol

/** 転送進捗。Windows 版 `TransferProgressInfo` に相当する。 */
data class TransferProgress(
    val fileName: String,
    val totalBytes: Long,
    val transferredBytes: Long
) {
    val fraction: Double
        get() = if (totalBytes <= 0) 0.0 else (transferredBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0)

    val percentage: Double
        get() = fraction * 100.0
}
