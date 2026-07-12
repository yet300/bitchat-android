package com.app.data.gateway

import com.app.domain.repository.GatewayRepository
import com.app.transport.mesh.MeshService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@Inject
internal class GatewayRepositoryImpl(
    private val meshService: MeshService,
    private val runtime: GatewayRuntime,
) : GatewayRepository {
    override fun isEnabled(): Boolean = meshService.isGatewayEnabled()

    override fun setEnabled(enabled: Boolean) {
        meshService.setGatewayEnabled(enabled)
        if (!enabled) runtime.onGatewayDisabled()
    }
}
