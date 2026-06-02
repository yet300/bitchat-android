package com.app.domain.model

/**
 * Identity fingerprint — SHA-256 (hex) of the Noise static public key.
 * Used as a stable key for favorites / blocked / verification.
 */
@JvmInline
value class Fingerprint(val value: String)
