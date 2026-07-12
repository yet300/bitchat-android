package com.app.domain.repository

/** Business API for the opt-in internet-sharing gateway; UI intentionally does not consume it yet. */
interface GatewayRepository {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
}
