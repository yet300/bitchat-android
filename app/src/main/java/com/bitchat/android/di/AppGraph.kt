package com.bitchat.android.di

import com.app.domain.repository.ChannelRepository
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.ConversationRepository
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.MessageRepository
import com.app.domain.repository.MessageTransport
import com.app.domain.repository.PeerRepository
import com.app.domain.repository.SearchRepository
import com.app.domain.repository.SettingsRepository
import com.app.data.AppStateStore
import com.app.transport.SeenMessageStore
import com.app.transport.mesh.TransferProgressManager
import com.app.transport.meshgraph.MeshGraphService
import com.app.transport.debug.DebugPreferenceManager
import com.app.transport.debug.DebugSettingsManager
import com.app.transport.nostr.GeohashAliasRegistry
import com.app.transport.nostr.GeohashConversationRegistry
import com.app.transport.net.TorPreferenceManager
import com.app.transport.nostr.PoWPreferenceManager
import com.app.transport.nostr.RelayDirectory

/**
 * Public API of the application dependency graph: the domain ports the app (and, in Phase C, the
 * Decompose component tree) resolves. The concrete graph is generated per platform
 * ([AndroidAppGraph]). Repository implementations stay internal to :core:data — only these domain
 * interfaces cross the module boundary (DIP).
 */
interface AppGraph {
    val settingsRepository: SettingsRepository
    val contactRepository: ContactRepository
    val channelRepository: ChannelRepository
    val messageRepository: MessageRepository
    val conversationRepository: ConversationRepository
    val peerRepository: PeerRepository
    val searchRepository: SearchRepository
    val identityRepository: IdentityRepository
    val messageTransport: MessageTransport

    // Temporary Phase-D bridges: transport prefs/registries migrated onto SettingsStore are exposed
    // here so the not-yet-graph :app consumers (god-classes, Composables) can resolve the single
    // graph-owned instance. These accessors retire as the consumers dissolve in Phase C.
    val geohashConversationRegistry: GeohashConversationRegistry
    val geohashAliasRegistry: GeohashAliasRegistry
    val debugSettingsManager: DebugSettingsManager
    val debugPreferenceManager: DebugPreferenceManager
    val powPreferenceManager: PoWPreferenceManager
    val relayDirectory: RelayDirectory
    val torPreferenceManager: TorPreferenceManager
    val appStateStore: AppStateStore
    // temporary Phase-D/DI-core bridge, retires Phase C
    val seenMessageStore: SeenMessageStore
    val transferProgressManager: TransferProgressManager
    val meshGraphService: MeshGraphService
}
