package com.yet.bitmessage.feature.chats.details.verify

import com.app.domain.repository.VerifyScanResult
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value

/**
 * QR-scan verification sheet: shows the camera (once CAMERA is granted), reports scanned
 * `bitchat://verify` payloads to the verification backend, and surfaces the outcome. The actual
 * verified state lands on the peer's fingerprint and is observed by the chat-header indicator.
 */
interface VerifyScanComponent {

    val model: Value<Model>

    fun onRequestCameraPermission()

    fun onQrScanned(payload: String)

    fun onCloseClicked()

    data class Model(
        val cameraGranted: Boolean,
        val result: VerifyScanResult?,
    )

    fun interface Factory {
        fun create(componentContext: ComponentContext, onClose: () -> Unit): VerifyScanComponent
    }
}
