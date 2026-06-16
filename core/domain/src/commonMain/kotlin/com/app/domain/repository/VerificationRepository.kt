package com.app.domain.repository

/**
 * Per-contact QR verification (Noise challenge/response). This slice exposes only "show my QR":
 * build this device's signed verification payload (a `bitchat://verify` URL) that a contact scans.
 */
interface VerificationRepository {

    /** Build this device's verification payload, or null if the identity keys are unavailable. */
    suspend fun buildMyVerificationQr(): String?
}
