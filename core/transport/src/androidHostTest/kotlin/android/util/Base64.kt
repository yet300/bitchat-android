package android.util

/**
 * JVM test shim for android.util.Base64 (the real class is an Android stub returning null under
 * plain JUnit). Encode/decode go through java.util.Base64 — consistent for round-trips in tests.
 */
object Base64 {
    const val DEFAULT = 0
    const val NO_WRAP = 2
    const val NO_PADDING = 1
    const val URL_SAFE = 8

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String =
        java.util.Base64.getEncoder().encodeToString(input)

    @JvmStatic
    fun encode(input: ByteArray, flags: Int): ByteArray =
        java.util.Base64.getEncoder().encode(input)

    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray =
        java.util.Base64.getDecoder().decode(str.trim())

    @JvmStatic
    fun decode(input: ByteArray, flags: Int): ByteArray =
        java.util.Base64.getDecoder().decode(input)
}
