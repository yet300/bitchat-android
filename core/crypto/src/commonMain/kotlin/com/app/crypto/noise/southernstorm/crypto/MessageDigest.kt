package com.app.crypto.noise.southernstorm.crypto

/**
 * Minimal multiplatform stand-in for the subset of java.security.MessageDigest that the Noise
 * engine relies on. Subclasses implement the `engine*` primitives (the streaming SHA-2 / BLAKE2
 * cores); the public methods delegate to them. Replaces the JVM base class so the digests can live
 * in commonMain unchanged in behaviour.
 */
internal abstract class MessageDigest(@Suppress("unused") val algorithm: String) {

    protected abstract fun engineReset()
    protected abstract fun engineUpdate(input: ByteArray, offset: Int, len: Int)
    @Throws(DigestException::class)
    protected abstract fun engineDigest(buf: ByteArray, offset: Int, len: Int): Int
    protected abstract fun engineGetDigestLength(): Int

    protected open fun engineUpdate(input: Byte): Unit = engineUpdate(byteArrayOf(input), 0, 1)

    protected open fun engineDigest(): ByteArray =
        ByteArray(engineGetDigestLength()).also { engineDigest(it, 0, it.size) }

    val digestLength: Int get() = engineGetDigestLength()

    fun reset(): Unit = engineReset()

    fun update(input: Byte): Unit = engineUpdate(input)

    fun update(input: ByteArray): Unit = engineUpdate(input, 0, input.size)

    fun update(input: ByteArray, offset: Int, len: Int): Unit = engineUpdate(input, offset, len)

    fun digest(): ByteArray = engineDigest()

    fun digest(buf: ByteArray, offset: Int, len: Int): Int = engineDigest(buf, offset, len)
}
