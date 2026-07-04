package com.app.transport.notification

/**
 * Outbound port for service-level (no-UI) notifications raised by the mesh layer
 * for background direct messages.
 *
 * The transport layer depends only on this narrow contract; the Android
 * implementation (which pulls in app resources and NotificationCompat) lives in
 * the app module and is injected into the mesh service.
 */
interface ServiceNotifier {

    fun setAppBackgroundState(inBackground: Boolean)

    fun showPrivateMessageNotification(
        senderPeerID: String,
        senderNickname: String,
        messageContent: String,
    )
}
