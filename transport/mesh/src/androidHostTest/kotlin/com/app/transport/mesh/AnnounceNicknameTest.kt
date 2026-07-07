package com.app.transport.mesh

import android.os.Build
import com.app.common.AppDispatchers
import com.app.crypto.EncryptionService
import com.app.crypto.identity.PeerFingerprintManager
import com.app.transport.FavoriteNostrLink
import com.app.transport.GeohashReadReceiptRouter
import com.app.transport.IncomingMessageSink
import com.app.transport.MeshTelemetry
import com.app.transport.NicknameHolder
import com.app.transport.SeenMessageStore
import com.app.transport.VerificationService
import com.app.transport.debug.DebugPreferenceManager
import com.app.transport.features.file.IncomingFileStore
import com.app.transport.meshgraph.MeshGraphService
import com.app.transport.model.IdentityAnnouncement
import com.app.transport.model.RoutedPacket
import com.app.transport.notification.ServiceNotifier
import com.app.transport.protocol.MessageType
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves the announce path reads the nickname from the transport-owned in-memory
 * [NicknameHolder]: a value pushed by the data layer is used immediately (no settings
 * read anywhere), and the peer-id fallback appears only when nothing was ever pushed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class AnnounceNicknameTest {

    private companion object {
        const val FINGERPRINT = "a1b2c3d4e5f60718a1b2c3d4e5f60718"
        val MY_PEER_ID = FINGERPRINT.take(16)
    }

    /** Second bearer alongside the BLE one; records every broadcast the engine sends. */
    private class RecordingBearer : MeshBearer {
        override val id = BearerId.WIFI_AWARE
        override val incoming: Flow<RoutedPacket> = emptyFlow()
        override val neighbors = MutableStateFlow<Set<PeerLink>>(emptySet())
        override val events: Flow<BearerEvent> = emptyFlow()
        val broadcasts = mutableListOf<RoutedPacket>()
        override fun start(): Boolean = true
        override fun stop() = Unit
        override fun broadcast(packet: RoutedPacket) { broadcasts += packet }
        override fun sendToPeer(peerID: String, packet: RoutedPacket): Boolean = false
        override fun cancelTransfer(transferId: String): Boolean = false
        override fun bindPeer(peerID: String, linkAddress: String) = Unit
    }

    private class Harness {
        val holder = NicknameHolder()
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)

        val encryptionService: EncryptionService = mock {
            on { getIdentityFingerprint() } doReturn FINGERPRINT
            on { getStaticPublicKey() } doReturn ByteArray(32) { 1 }
            on { getSigningPublicKey() } doReturn ByteArray(32) { 2 }
        }
        val telemetry: MeshTelemetry = mock()
        val debugPrefs: DebugPreferenceManager = mock {
            on { getSeenPacketCapacity(any()) } doAnswer { it.getArgument(0) }
            on { getGcsMaxFilterBytes(any()) } doAnswer { it.getArgument(0) }
            on { getGcsFprPercent(any()) } doAnswer { it.getArgument(0) }
        }

        val recordingBearer = RecordingBearer()
        val bleBearer = BleBearer(
            myPeerID = MY_PEER_ID,
            debugSettingsManager = telemetry,
            connectionManagerFactory = {
                mock<BearerTransport>().also {
                    whenever(it.startServices()).thenReturn(true)
                    whenever(it.addressPeerMap).thenReturn(ConcurrentHashMap())
                }
            },
        )

        val bms = MeshCoordinator(
            incomingFileStore = mock<IncomingFileStore>(),
            debugSettingsManager = telemetry,
            debugPreferenceManager = debugPrefs,
            seenMessageStore = mock<SeenMessageStore>(),
            meshGraphService = MeshGraphService(),
            peerFingerprintManager = PeerFingerprintManager(),
            encryptionService = encryptionService,
            serviceNotifier = mock<ServiceNotifier>(),
            nicknameSource = holder,
            incomingSink = mock<IncomingMessageSink>(),
            favoriteNostrLink = mock<FavoriteNostrLink>(),
            geohashReadReceiptRouter = GeohashReadReceiptRouter { _, _ -> false },
            verificationService = mock<VerificationService>(),
            fragmentManager = FragmentManager(),
            bleBearer = bleBearer,
            meshNetwork = MeshNetwork(setOf(bleBearer, recordingBearer)),
            dispatchers = AppDispatchers(io = dispatcher),
        )

        /** Decoded nickname of the last ANNOUNCE broadcast the engine emitted. */
        fun lastAnnouncedNickname(): String {
            val announce = recordingBearer.broadcasts
                .last { it.packet.type == MessageType.ANNOUNCE.value }
            val decoded = IdentityAnnouncement.decode(announce.packet.payload)
            requireNotNull(decoded) { "announce payload must stay decodable" }
            return decoded.nickname
        }

        fun announceAndSettle() {
            bms.sendBroadcastAnnounce()
            scheduler.runCurrent()
        }
    }

    @Test
    fun pushedNicknameIsAnnouncedImmediately() {
        val h = Harness()
        h.holder.set("Alice")
        h.bms.startServices()
        h.scheduler.runCurrent()

        h.announceAndSettle()
        assertEquals("Alice", h.lastAnnouncedNickname())
    }

    @Test
    fun fallbackIsUsedOnlyWhenNothingWasEverPushed() {
        val h = Harness()
        h.bms.startServices()
        h.scheduler.runCurrent()

        h.announceAndSettle()
        assertEquals(MY_PEER_ID, h.lastAnnouncedNickname())

        // A later push (data layer sync or setNickname) takes effect on the next announce.
        h.holder.set("Bob")
        h.announceAndSettle()
        assertEquals("Bob", h.lastAnnouncedNickname())
    }

    @Test
    fun blankPushKeepsFallback() {
        val h = Harness()
        h.holder.set("   ")
        h.bms.startServices()
        h.scheduler.runCurrent()

        h.announceAndSettle()
        assertEquals(MY_PEER_ID, h.lastAnnouncedNickname())
        assertTrue(h.recordingBearer.broadcasts.isNotEmpty())
    }
}
