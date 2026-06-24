package com.app.crypto.secure

import android.content.Context

/**
 * Single construction seam for the encrypted [SecureKeyValueStore]. Production builds the
 * KSafe-backed store over the shared [KSafeStores.vault]; the field exists so host tests (which run
 * on Robolectric, where there is no Android Keystore and KSafe fails closed) can install an
 * in-memory store instead. Never reassign [factory] in production code.
 */
internal object SecureStores {

    private val default: (Context) -> SecureKeyValueStore = { ctx ->
        KSafeSecureKeyValueStore(KSafeStores.vault(ctx))
    }

    @Volatile
    var factory: (Context) -> SecureKeyValueStore = default

    fun secure(context: Context): SecureKeyValueStore = factory(context.applicationContext)

    /** Test-only: restore the production KSafe-backed factory. */
    fun resetFactory() {
        factory = default
    }
}
