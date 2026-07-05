package com.app.data.tor

/**
 * Platform seam for the current location-permission authorization, used by the Tor activation
 * policy (mirrors the reference iOS `LocationChannelManager.permissionState == .authorized`).
 *
 * A synchronous snapshot is enough: [TorActivationController] re-evaluates the policy whenever the
 * app returns to the foreground (via [com.app.domain.app.AppForegroundState]), which is when a
 * permission granted in system settings takes effect — so we don't need a live permission stream.
 */
fun interface LocationAuthorizationSource {
    /** `true` when the user has authorized location access (when-in-use or always). */
    fun isAuthorized(): Boolean
}
