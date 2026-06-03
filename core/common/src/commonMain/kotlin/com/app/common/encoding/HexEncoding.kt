package com.app.common.encoding

/**
 * Hex encoding/decoding extensions shared across layers.
 *
 * Pure Kotlin (commonMain-ready, no JVM `String.format`), compatible with the
 * iOS counterpart in BinaryEncodingUtils.swift. The byte representation must stay
 * stable, as it is used for fingerprints and wire-adjacent encodings.
 */

private const val HEX_DIGITS = "0123456789abcdef"

fun ByteArray.hexEncodedString(): String {
    if (this.isEmpty()) {
        return ""
    }
    val sb = StringBuilder(this.size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX_DIGITS[v ushr 4])
        sb.append(HEX_DIGITS[v and 0x0F])
    }
    return sb.toString()
}

fun String.dataFromHexString(): ByteArray? {
    val len = this.length / 2
    val data = ByteArray(len)
    var index = 0

    for (i in 0 until len) {
        val hexByte = this.substring(i * 2, i * 2 + 2)
        val byte = hexByte.toIntOrNull(16)?.toByte() ?: return null
        data[index++] = byte
    }

    return data
}

/**
 * Alias of [hexEncodedString]. Kept as a distinct name used by the transport/mesh
 * layers; delegates to the single hex implementation to avoid duplication.
 */
fun ByteArray.toHexString(): String = hexEncodedString()
