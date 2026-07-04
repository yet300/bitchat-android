package com.app.transport.protocol

import java.util.zip.Deflater as JDeflater
import java.util.zip.Inflater as JInflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Wire-contract anchor for CompressionUtil after the java.util.zip -> Kompress migration.
 *
 * The real iOS-compatibility contract is NOT byte-identical compressed output (Kompress and the
 * JDK/iOS zlib emit different but valid RFC 1951 streams) — it is mutual inflate-compatibility:
 *  - the JDK (proxy for the iOS COMPRESSION_ZLIB peer) must inflate what CompressionUtil produces, and
 *  - CompressionUtil must inflate raw-DEFLATE streams the JDK/iOS produces.
 * Both directions are asserted here against java.util.zip raw DEFLATE.
 */
class CompressionInteropTest {

    private fun jdkDeflateRaw(data: ByteArray): ByteArray {
        val d = JDeflater(JDeflater.DEFAULT_COMPRESSION, true)
        d.setInput(data); d.finish()
        val out = ArrayList<Byte>(); val buf = ByteArray(1024)
        while (!d.finished()) { val n = d.deflate(buf); for (i in 0 until n) out.add(buf[i]) }
        d.end(); return out.toByteArray()
    }

    private fun jdkInflateRaw(data: ByteArray, size: Int): ByteArray {
        val inf = JInflater(true); inf.setInput(data)
        val o = ByteArray(size); val n = inf.inflate(o); inf.end()
        return o.copyOf(n)
    }

    // Compressible payload (400 repeated bytes), same shape as the BinaryProtocol golden fixtures.
    private val payload = ByteArray(400) { 'A'.code.toByte() }

    @Test
    fun `CompressionUtil round-trips its own output`() {
        val c = CompressionUtil.compress(payload)
        assertNotNull(c)
        assertTrue(c.size < payload.size)
        val d = CompressionUtil.decompress(c, payload.size)
        assertEquals(payload.toList(), d?.toList())
    }

    @Test
    fun `CompressionUtil output is inflatable by the JDK (iOS peer can decode us)`() {
        val c = CompressionUtil.compress(payload)
        assertNotNull(c)
        val d = jdkInflateRaw(c, payload.size)
        assertEquals(payload.toList(), d.toList())
    }

    @Test
    fun `CompressionUtil inflates JDK raw DEFLATE (we can decode the iOS peer)`() {
        val jdk = jdkDeflateRaw(payload)
        val d = CompressionUtil.decompress(jdk, payload.size)
        assertEquals(payload.toList(), d?.toList())
    }

    @Test
    fun `compress is deterministic`() {
        assertEquals(
            CompressionUtil.compress(payload)?.toList(),
            CompressionUtil.compress(payload)?.toList(),
        )
    }

    @Test
    fun `self test passes`() {
        assertTrue(CompressionUtil.testCompression())
    }
}
