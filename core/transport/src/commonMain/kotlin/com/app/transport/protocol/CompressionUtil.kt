@file:OptIn(ExperimentalCompressionApi::class)

package com.app.transport.protocol

import com.app.common.utils.Log
import dev.karmakrafts.kompress.ExperimentalCompressionApi
import dev.karmakrafts.kompress.deflate.Deflater
import dev.karmakrafts.kompress.deflate.Inflater

/**
 * Compression utilities - iOS-compatible raw DEFLATE (RFC 1951, no zlib/gzip header).
 *
 * Backed by the pure-Kotlin multiplatform Kompress codec (commonMain), replacing the
 * java.util.zip Deflater/Inflater. The emitted bytes differ from the old JDK encoder
 * (Kompress is a different, deterministic DEFLATE implementation) but remain valid
 * RFC 1951 streams: the iOS COMPRESSION_ZLIB peer decodes them and Kompress decodes the
 * peer's streams. The wire contract is mutual inflate-compatibility, not byte-identical
 * compress — proven both directions against java.util.zip in CompressionInteropTest.
 */
internal object CompressionUtil {
    private const val COMPRESSION_THRESHOLD = 100  // bytes - same as iOS
    private const val DEFLATE_LEVEL = 6            // zlib default level; deterministic output
    private const val DEFLATE_BUFFER = 1024
    // Hard cap on the decompressed size we will ever allocate for a single payload.
    // Mirrors BinaryProtocol.MAX_PAYLOAD_LENGTH (10 MiB, same as iOS): a peer claiming
    // more is lying, because the plain payload could never be carried on the wire.
    private const val MAX_DECOMPRESSED_SIZE = 10_485_760

    /**
     * Helper to check if compression is worth it - exact same logic as iOS
     */
    fun shouldCompress(data: ByteArray): Boolean {
        // Don't compress if:
        // 1. Data is too small
        // 2. Data appears to be already compressed (high entropy)
        if (data.size < COMPRESSION_THRESHOLD) return false
        
        // Simple entropy check - count unique bytes (exact same as iOS)
        val byteFrequency = mutableMapOf<Byte, Int>()
        for (byte in data) {
            byteFrequency[byte] = (byteFrequency[byte] ?: 0) + 1
        }
        
        // If we have very high byte diversity, data is likely already compressed
        val uniqueByteRatio = byteFrequency.size.toDouble() / minOf(data.size, 256).toDouble()
        return uniqueByteRatio < 0.9 // Compress if less than 90% unique bytes
    }
    
    /**
     * Compress data using raw DEFLATE - iOS COMPRESSION_ZLIB produces raw deflate (no headers).
     */
    fun compress(data: ByteArray): ByteArray? {
        // Skip compression for small data
        if (data.size < COMPRESSION_THRESHOLD) return null

        return try {
            val compressedData = Deflater.compress(data, level = DEFLATE_LEVEL, bufferSize = DEFLATE_BUFFER)
            // Only return if compression was beneficial (same logic as iOS)
            if (compressedData.isNotEmpty() && compressedData.size < data.size) compressedData else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decompress raw DEFLATE compressed data - iOS COMPRESSION_ZLIB produces raw deflate (no headers).
     */
    fun decompress(compressedData: ByteArray, originalSize: Int): ByteArray? {
        // Bounded inflate (H2 hardening): originalSize is attacker-controlled and this runs
        // at the bearer receive boundary before any signature check, so it must also be the
        // allocation bound. The stream must inflate to exactly originalSize bytes — ending
        // early or producing past the claim is rejected. Honest peers always declare the
        // exact plain size, so this only rejects frames no honest peer ever sends.
        if (originalSize !in 1..MAX_DECOMPRESSED_SIZE) {
            Log.w("CompressionUtil", "Rejected decompression with claimed size $originalSize")
            return null
        }
        return try {
            Inflater().use { inflater ->
                inflater.setInput(compressedData)
                inflater.finish()
                val output = ByteArray(originalSize)
                var produced = 0
                while (produced < originalSize) {
                    val n = inflater.decompress(output, offset = produced, size = originalSize - produced)
                    if (n == 0) break
                    produced += n
                }
                when {
                    // Stream ended before the claimed size — the declared size was a lie.
                    produced < originalSize -> null
                    // Buffer filled: the stream must be exhausted, otherwise it inflates
                    // past the claimed size (compression bomb) — probe for one extra byte.
                    inflater.decompress(ByteArray(1)) != 0 -> null
                    else -> output
                }
            }
        } catch (e: Exception) {
            Log.d("CompressionUtil", "Raw deflate decompression failed: ${e.message}")
            null
        }
    }

    /**
     * Test function to verify deflate compression works correctly
     * This can be called during app initialization to ensure compatibility
     */
    fun testCompression(): Boolean {
        try {
            // Create test data that should compress well (repeating pattern like iOS would use)
            val testMessage = "This is a test message that should compress well. ".repeat(10)
            val originalData = testMessage.encodeToByteArray()

            Log.d("CompressionUtil", "Testing deflate compression with ${originalData.size} bytes")

            // Test shouldCompress
            val shouldCompress = shouldCompress(originalData)
            Log.d("CompressionUtil", "shouldCompress() returned: $shouldCompress")

            if (!shouldCompress) {
                Log.e("CompressionUtil", "shouldCompress failed for test data")
                return false
            }

            // Test compression
            val compressed = compress(originalData)
            if (compressed == null) {
                Log.e("CompressionUtil", "Compression failed")
                return false
            }

            Log.d("CompressionUtil", "Compressed ${originalData.size} bytes to ${compressed.size} bytes (${(compressed.size.toDouble() / originalData.size * 100).toInt()}%)")

            // Test decompression
            val decompressed = decompress(compressed, originalData.size)
            if (decompressed == null) {
                Log.e("CompressionUtil", "Decompression failed")
                return false
            }

            // Verify data integrity
            val isIdentical = originalData.contentEquals(decompressed)
            Log.d("CompressionUtil", "Data integrity check: $isIdentical")

            if (!isIdentical) {
                Log.e("CompressionUtil", "Decompressed data doesn't match original")
                return false
            }

            Log.i("CompressionUtil", "✅ deflate compression test PASSED - ready for iOS compatibility")
            return true

        } catch (e: Exception) {
            Log.e("CompressionUtil", "deflate compression test failed: ${e.message}")
            return false
        }
    }
}
