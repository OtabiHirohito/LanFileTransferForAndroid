package com.lanfiletransfer.util

import java.util.Locale

private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

/** 1234567 -> "1.18 MB" */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) {
        return "-"
    }
    if (bytes < 1024) {
        return "$bytes B"
    }

    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < UNITS.size - 1) {
        value /= 1024.0
        unit++
    }

    return String.format(Locale.US, "%.2f %s", value, UNITS[unit])
}
