@file:OptIn(ExperimentalForeignApi::class)

package com.app.transport.mesh

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * NSData <-> ByteArray bridges for the CoreBluetooth bearer. CoreBluetooth speaks NSData; the mesh
 * wire layer (BitchatPacket.toBinaryData / fromBinaryData) speaks ByteArray. Both copy through a
 * pinned buffer — the byte content is preserved verbatim, so wire bytes are unchanged.
 */
@OptIn(BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.convert())
    }
}

internal fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), this@toByteArray.bytes, this@toByteArray.length)
    }
    return out
}
