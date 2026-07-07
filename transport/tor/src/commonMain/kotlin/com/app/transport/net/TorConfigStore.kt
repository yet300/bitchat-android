package com.app.transport.net

/**
 * Persistence port for the effective [TorMode]. The transport SDK owns no storage: the interface
 * lives here next to its consumer ([TorPreferenceManager]) and the app supplies the implementation
 * (bitMessage backs it with the :core:data settings store).
 *
 * Implementations must be durable across process restarts and tolerate an unparseable stored
 * value by returning [default].
 */
interface TorConfigStore {

    fun getTorMode(default: TorMode): TorMode

    fun setTorMode(mode: TorMode)
}
