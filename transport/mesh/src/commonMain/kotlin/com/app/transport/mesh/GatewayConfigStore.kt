package com.app.transport.mesh

interface GatewayConfigStore {
    fun isGatewayEnabled(): Boolean
    fun setGatewayEnabled(enabled: Boolean)
}

object DisabledGatewayConfigStore : GatewayConfigStore {
    override fun isGatewayEnabled(): Boolean = false
    override fun setGatewayEnabled(enabled: Boolean) = Unit
}
