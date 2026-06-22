package com.app.crypto.noise.southernstorm.crypto

/**
 * Multiplatform replacements for the JVM crypto exceptions the Noise engine throws/catches. Same
 * names as the former java.security / javax.crypto types so the engine code is otherwise unchanged.
 */
internal open class DigestException(message: String? = null) : Exception(message)

internal open class NoSuchAlgorithmException(message: String? = null) : Exception(message)

internal open class BadPaddingException(message: String? = null) : Exception(message)

internal open class ShortBufferException(message: String? = null) : Exception(message)
