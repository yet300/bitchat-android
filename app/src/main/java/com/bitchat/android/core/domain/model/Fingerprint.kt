package com.bitchat.android.core.domain.model

/**
 * Отпечаток личности — SHA-256 (hex) от Noise static public key.
 * Используется как стабильный ключ для favorites/blocked/верификации.
 */
@JvmInline
value class Fingerprint(val value: String)
