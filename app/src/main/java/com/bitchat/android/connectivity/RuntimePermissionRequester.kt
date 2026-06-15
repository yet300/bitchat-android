package com.bitchat.android.connectivity

/**
 * Graph-scoped bridge between the in-app system permission dialog (ActivityResult API, owned by
 * [com.bitchat.android.BitMessageActivity]) and the data layer's [AndroidConnectivityRepository].
 *
 * The Activity attaches a [Host] in `onCreate` and detaches it in `onDestroy`; while no Activity is
 * attached (background, between launches) a request resolves to [PermissionOutcome.NO_HOST] so the
 * caller can fall back to the app-settings deep link. Holding a single graph-owned instance keeps
 * the repository decoupled from the framework lifecycle.
 */
class RuntimePermissionRequester {

    @Volatile
    private var host: Host? = null

    fun attach(host: Host) {
        this.host = host
    }

    /** Detach only if [host] is still the current one (guards against a stale Activity clearing). */
    fun detach(host: Host) {
        if (this.host === host) this.host = null
    }

    suspend fun request(permissions: List<String>): PermissionOutcome =
        host?.request(permissions) ?: PermissionOutcome.NO_HOST

    /** Implemented by the Activity: launches the system dialog and reports the user's decision. */
    interface Host {
        suspend fun request(permissions: List<String>): PermissionOutcome
    }
}

enum class PermissionOutcome {
    /** All requested permissions are now granted. */
    GRANTED,

    /** Denied this time, but the system will still show the dialog on a retry. */
    DENIED,

    /** Permanently denied ("don't ask again") — only the app-settings screen can grant it now. */
    PERMANENTLY_DENIED,

    /** No Activity is attached to show the dialog. */
    NO_HOST,
}
