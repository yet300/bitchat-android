package com.app.transport.mesh

import android.os.Build
import com.app.common.AppDispatchers
import com.app.crypto.EncryptionService
import com.app.crypto.identity.PeerFingerprintManager
import com.app.transport.FavoriteNostrLink
import com.app.transport.GeohashReadReceiptRouter
import com.app.transport.IncomingMessageSink
import com.app.transport.MeshTelemetry
import com.app.transport.NicknameSource
import com.app.transport.SeenMessageStore
import com.app.transport.VerificationService
import com.app.transport.debug.DebugPreferenceManager
import com.app.transport.meshgraph.MeshGraphService
import com.app.transport.model.RoutedPacket
import com.app.transport.notification.ServiceNotifier
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.timeout
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Lifecycle tests for [BluetoothMeshService] start/stop/reset with generation rebuild.
 *
 * The audited machinery under test (do NOT redesign): the [BluetoothMeshService] lifecycle
 * monitor, the pendingStopJob + generation-guarded finishStop, terminated-state revival via
 * rebuildComponents, and BleBearer.reset() delegate detach.
 *
 * The injected [StandardTestDispatcher] backs every serviceScope generation, so the
 * stop-grace period (STOP_GRACE_PERIOD_MS) runs on virtual time. Deliberately NOT runTest:
 * BMS owns infinite periodic loops (announce every 30 s) on this scheduler, and runTest's
 * cleanup advancing a shared scheduler to idle would spin those loops forever. The harness
 * therefore owns a private [TestCoroutineScheduler] advanced explicitly.
 *
 * Engine traversal is observed at [MeshTelemetry.logIncomingPacket]: a LEAVE packet skips
 * signature verification, so it passes SecurityManager validation and proves the current
 * generation's collector → PacketProcessor pipeline is alive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class BluetoothMeshServiceLifecycleTest {

    private companion object {
        const val FINGERPRINT_A = "a1b2c3d4e5f60718a1b2c3d4e5f60718"
        const val FINGERPRINT_B = "ffeeddccbbaa9988ffeeddccbbaa9988"
        const val REMOTE_PEER = "cafe000000000001"
        /** Past the stop grace period, with margin (still below the 30 s announce loop). */
        const val PAST_GRACE_MS = BluetoothMeshService.STOP_GRACE_PERIOD_MS * 5
    }

    /** Injection point for packets: a second bearer alongside the real BleBearer. */
    private class FakeBearer : MeshBearer {
        override val id = BearerId.WIFI_AWARE
        val incomingFlow = MutableSharedFlow<RoutedPacket>(extraBufferCapacity = 64)
        override val incoming: Flow<RoutedPacket> = incomingFlow
        override val neighbors = MutableStateFlow<Set<PeerLink>>(emptySet())
        override val events: Flow<BearerEvent> = emptyFlow()
        override fun start(): Boolean = true
        override fun stop() = Unit
        override fun broadcast(packet: RoutedPacket) = Unit
        override fun sendToPeer(peerID: String, packet: RoutedPacket): Boolean = false
        override fun cancelTransfer(transferId: String): Boolean = false
        override fun bindPeer(peerID: String, linkAddress: String) = Unit
    }

    /**
     * Full BMS construction with a mocked BLE stack. Each BluetoothConnectionManager the
     * BleBearer factory hands out is a mock whose `delegate` assignments are recorded in
     * [delegates] — emulating the real GATT stack, which always reaches its delegate
     * through that field (so a post-reset null detaches it).
     */
    private class Harness {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val context = RuntimeEnvironment.getApplication()

        @Volatile
        var fingerprint: String = FINGERPRINT_A

        val encryptionService: EncryptionService = mock {
            on { getIdentityFingerprint() } doAnswer { fingerprint }
        }
        val telemetry: MeshTelemetry = mock()
        val debugPrefs: DebugPreferenceManager = mock {
            on { getSeenPacketCapacity(any()) } doAnswer { it.getArgument(0) }
            on { getGcsMaxFilterBytes(any()) } doAnswer { it.getArgument(0) }
            on { getGcsFprPercent(any()) } doAnswer { it.getArgument(0) }
        }

        val managers = mutableListOf<BluetoothConnectionManager>()
        val delegates = ConcurrentHashMap<BluetoothConnectionManager, BluetoothConnectionManagerDelegate>()

        private fun newConnectionManager(): BluetoothConnectionManager {
            val cm: BluetoothConnectionManager = mock()
            whenever(cm.startServices()).thenReturn(true)
            whenever(cm.addressPeerMap).thenReturn(ConcurrentHashMap())
            whenever(cm.isClientConnection(any())).thenReturn(false)
            doAnswer { invocation ->
                val d: BluetoothConnectionManagerDelegate? = invocation.getArgument(0)
                if (d == null) delegates.remove(cm) else delegates[cm] = d
                null
            }.whenever(cm).delegate = anyOrNull()
            managers += cm
            return cm
        }

        val fragmentManager = FragmentManager()
        val bleBearer = BleBearer(
            context = context,
            myPeerID = fingerprint.take(16),
            debugSettingsManager = telemetry,
            fragmentManager = fragmentManager,
            transferProgressManager = mock(),
            connectionManagerFactory = { newConnectionManager() },
        )
        val fakeBearer = FakeBearer()
        val meshNetwork = MeshNetwork(setOf(bleBearer, fakeBearer))

        val bms = BluetoothMeshService(
            context = context,
            debugSettingsManager = telemetry,
            debugPreferenceManager = debugPrefs,
            seenMessageStore = mock<SeenMessageStore>(),
            meshGraphService = MeshGraphService(),
            peerFingerprintManager = PeerFingerprintManager(),
            encryptionService = encryptionService,
            serviceNotifier = mock<ServiceNotifier>(),
            nicknameSource = NicknameSource { it },
            incomingSink = mock<IncomingMessageSink>(),
            favoriteNostrLink = mock<FavoriteNostrLink>(),
            geohashReadReceiptRouter = GeohashReadReceiptRouter { _, _ -> false },
            verificationService = mock<VerificationService>(),
            fragmentManager = fragmentManager,
            bleBearer = bleBearer,
            meshNetwork = meshNetwork,
            dispatchers = AppDispatchers(io = dispatcher),
        )

        fun startAndSettle() {
            bms.startServices()
            scheduler.runCurrent()
        }

        fun advancePastGrace() {
            scheduler.advanceTimeBy(PAST_GRACE_MS)
            scheduler.runCurrent()
        }

        /** LEAVE skips signature verification, so it traverses the engine with mocked crypto. */
        fun leavePacket(timestamp: ULong) = BitchatPacket(
            type = MessageType.LEAVE.value,
            ttl = 3u,
            senderID = ByteArray(8) { 0x22 },
            payload = ByteArray(0),
            timestamp = timestamp,
        )

        /**
         * Emits a packet on the fake bearer and asserts the CURRENT generation's collector
         * delivered it to the engine (logIncomingPacket fires past security validation on
         * the PacketProcessor's real-IO actor, hence the verify timeout). Exactly-once also
         * guards against a zombie generation double-processing.
         */
        fun assertEngineReceives(timestamp: ULong) {
            fakeBearer.incomingFlow.tryEmit(RoutedPacket(leavePacket(timestamp), REMOTE_PEER, "WIFI-1"))
            scheduler.runCurrent()
            verify(telemetry, timeout(5_000).times(1))
                .logIncomingPacket(eq(REMOTE_PEER), anyOrNull(), eq("LEAVE"), anyOrNull())
        }
    }

    // ------------------------------------------------------------------
    // stop → start inside the grace window
    // ------------------------------------------------------------------

    @Test
    fun restartInsideGraceWindowCompletesStopAndRevives() {
        val h = Harness()
        h.startAndSettle()
        assertTrue(h.bms.isMeshActive)

        h.bms.stopServices()
        // Still inside the grace window: the delayed teardown has not fired yet.
        h.bms.startServices() // must eagerly finishStop, then revive via rebuildComponents

        // Let the (cancelled) delayed teardown's deadline pass on virtual time.
        h.advancePastGrace()

        assertTrue("revived generation must be active", h.bms.isMeshActive)
        // Revival rebuilt the BLE stack: a second connection manager generation exists
        // and was started.
        assertEquals(2, h.managers.size)
        verify(h.managers[1], timeout(1_000)).startServices()

        // No zombie: the new generation's collector receives packets, exactly once.
        h.assertEngineReceives(timestamp = 1_000u)
    }

    // ------------------------------------------------------------------
    // reset() while a stop teardown is pending
    // ------------------------------------------------------------------

    @Test
    fun resetDuringGraceWindowSkipsStaleTeardown() {
        val h = Harness()
        h.startAndSettle()

        h.bms.stopServices() // schedules teardown at +STOP_GRACE_PERIOD_MS
        h.bms.reset()        // replaces the generation before the teardown fires

        // Advance well past the point where the stale teardown would have fired; the
        // generation guard (stoppingScope identity) must keep it away from the new
        // generation even if the job were still alive.
        h.advancePastGrace()

        assertTrue("new generation must stay active", h.bms.isMeshActive)
        h.assertEngineReceives(timestamp = 2_000u)
    }

    /**
     * The race the generation guard exists for: a delayed teardown thread that already
     * resumed from its grace delay and was blocked on the lifecycle monitor while reset()
     * replaced the generation. Not reproducible on a virtual-time scheduler, so the stale
     * call is replayed directly via the test seam — deleting the guard makes this fail.
     */
    @Test
    fun staleTeardownSurvivingIntoNewGenerationIsSkippedByGuard() {
        val h = Harness()
        h.startAndSettle()
        val gen1Scope = h.bms.currentGenerationScope

        h.bms.stopServices()
        h.bms.reset() // installs generation 2
        h.advancePastGrace()
        assertTrue(h.bms.isMeshActive)

        // The stale teardown finally gets the lock — generation guard must no-op it.
        h.bms.simulateStaleTeardown(gen1Scope)

        assertTrue("stale teardown must not deactivate the new generation", h.bms.isMeshActive)
        h.assertEngineReceives(timestamp = 5_000u)

        // And the service must still stop normally afterwards (not marked terminated).
        h.bms.stopServices()
        h.advancePastGrace()
        assertFalse(h.bms.isMeshActive)
    }

    // ------------------------------------------------------------------
    // Idempotency
    // ------------------------------------------------------------------

    @Test
    fun doubleStartIsIdempotent() {
        val h = Harness()
        h.startAndSettle()
        h.bms.startServices() // duplicate — must be ignored

        assertTrue(h.bms.isMeshActive)
        // Only the original generation exists and only one BLE start happened.
        assertEquals(1, h.managers.size)
        verify(h.managers[0], times(1)).startServices()
    }

    @Test
    fun doubleStopIsIdempotentAndStartRevives() {
        val h = Harness()
        h.startAndSettle()

        h.bms.stopServices()
        h.bms.stopServices() // duplicate — must be ignored (not active anymore)
        h.advancePastGrace()

        assertFalse(h.bms.isMeshActive)
        verify(h.managers[0], times(1)).stopServices()

        // A terminated service must revive on the next start.
        h.startAndSettle()
        assertTrue(h.bms.isMeshActive)
        h.assertEngineReceives(timestamp = 3_000u)
    }

    // ------------------------------------------------------------------
    // Panic flow: stopServices(); reset() — what ChatViewModel.panicClearAllData does
    // ------------------------------------------------------------------

    @Test
    fun panicFlowEndsActiveWithNewPeerId() {
        val h = Harness()
        h.startAndSettle()
        val oldPeerId = h.bms.myPeerID
        assertEquals(FINGERPRINT_A.take(16), oldPeerId)

        h.bms.stopServices()
        h.fingerprint = FINGERPRINT_B // simulated key rotation before reset re-derives
        h.bms.reset()
        h.advancePastGrace()

        assertTrue("mesh must be active after panic reset", h.bms.isMeshActive)
        assertNotEquals(oldPeerId, h.bms.myPeerID)
        assertEquals(FINGERPRINT_B.take(16), h.bms.myPeerID)
        h.assertEngineReceives(timestamp = 4_000u)
    }

    // ------------------------------------------------------------------
    // BleBearer late-callback isolation (audit finding A6)
    // ------------------------------------------------------------------

    @Test
    fun lateBleCallbacksAfterResetCannotReachNewGeneration() {
        val h = Harness()
        h.startAndSettle()

        val oldCm = h.managers.single()
        assertNotNull("old stack must be wired", h.delegates[oldCm])

        // Bind a neighbor on the old generation so a stale disconnect has something to evict.
        h.bleBearer.bindPeer(REMOTE_PEER, "AA:BB:CC:DD:EE:FF")

        h.bleBearer.reset(FINGERPRINT_B.take(16))

        // reset() must have detached the old stack's delegate (the GATT stack reaches its
        // delegate exclusively through this field, so null = isolated).
        assertNull("old connection manager delegate must be detached", h.delegates[oldCm])
        verify(oldCm).stopServices()

        val newCm = h.managers.last()
        assertNotEquals(oldCm, newCm)
        // Re-bind the same link address in the new generation.
        h.bleBearer.bindPeer(REMOTE_PEER, "AA:BB:CC:DD:EE:FF")
        val neighborsAfterRebind = h.bleBearer.neighbors.value

        val received = mutableListOf<RoutedPacket>()
        val collectScope = CoroutineScope(UnconfinedTestDispatcher(h.scheduler))
        collectScope.launch { h.bleBearer.incoming.collect { received.add(it) } }

        // Late asynchronous GATT callbacks on the OLD stack: production code always goes
        // through `delegate?.…`, which reset() nulled — so nothing may be invoked.
        h.delegates[oldCm]?.onPacketReceived(h.leavePacket(9_000u), "stalePeer", null)
        h.delegates[oldCm]?.onDeviceDisconnected(mock())
        h.scheduler.runCurrent()

        assertTrue("stale packet must not reach the new generation's incoming", received.isEmpty())
        assertEquals(
            "stale disconnect must not evict the new generation's neighbor",
            neighborsAfterRebind,
            h.bleBearer.neighbors.value,
        )

        // Sanity: the NEW stack's delegate is live and feeds the bearer flow.
        assertNotNull(h.delegates[newCm])
        h.delegates[newCm]?.onPacketReceived(h.leavePacket(9_001u), REMOTE_PEER, null)
        h.scheduler.runCurrent()
        assertEquals(1, received.size)

        collectScope.cancel()
    }
}
