package com.app.crypto.secure

import android.content.Context
import eu.anifantakis.lib.ksafe.KSafe

/**
 * Process-wide [KSafe] singletons. DataStore is single-process and KSafe must be a singleton per
 * `fileName` (two live instances on one file diverge on web and waste backends elsewhere), so every
 * caller — graph-provided or directly constructed — fetches the shared instance from here. Mirrors
 * the existing process-global [TinkAead] holder it replaces.
 *
 * Two buckets:
 * - [vault]  — encrypted (AES-256-GCM, key in the Android Keystore/TEE). All secrets at rest.
 * - [prefs]  — plain (no per-value encryption). Non-threat preferences only.
 *
 * Both are built once from the application context; passing an Activity context would leak.
 */
object KSafeStores {

    private const val VAULT_FILE = "bitchat_vault"
    private const val PREFS_FILE = "bitchat_prefs"

    @Volatile private var vaultInstance: KSafe? = null
    @Volatile private var prefsInstance: KSafe? = null

    /** Encrypted store for secrets at rest (identity/signing/Nostr keys, DB passphrase, favorites). */
    fun vault(context: Context): KSafe =
        vaultInstance ?: synchronized(this) {
            vaultInstance ?: KSafe(context.applicationContext, fileName = VAULT_FILE).also { vaultInstance = it }
        }

    /** Plain store for non-secret preferences (theme, toggles, onboarding flags, mesh settings). */
    fun prefs(context: Context): KSafe =
        prefsInstance ?: synchronized(this) {
            prefsInstance ?: KSafe(context.applicationContext, fileName = PREFS_FILE).also { prefsInstance = it }
        }
}
