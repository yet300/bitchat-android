@file:OptIn(ExperimentalTime::class)

package com.app.data.repository

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import com.app.common.utils.Log
import com.app.database.dao.GeohashDao
import com.app.data.AppStateStore
import com.app.data.nostr.CurrentGeohashSource
import com.app.data.nostr.GeohashPresencePublisher
import com.app.data.gateway.GatewayRuntime
import com.app.domain.app.AppForegroundState
import com.app.domain.model.ConversationId
import com.app.domain.model.GeoPerson
import com.app.domain.model.PeerId
import com.app.domain.repository.GeohashRepository
import com.app.transport.model.BitchatMessage
import com.app.transport.nostr.GeohashAliasRegistry
import com.app.transport.nostr.GeohashConversationRegistry
import com.app.transport.nostr.NostrEvent
import com.app.transport.nostr.NostrFilter
import com.app.transport.nostr.NostrIdentityBridge
import com.app.transport.nostr.NostrKind
import com.app.transport.nostr.NostrProofOfWork
import com.app.transport.nostr.NostrRelayManager
import com.app.transport.nostr.PoWPreferenceManager
import com.app.transport.nostr.RelayDirectory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Geohash (location) chat over the existing :core:transport Nostr stack. Subscribes to the selected
 * geohash's ephemeral events (kind 20000 chat + 20001 presence), ingests incoming posts into the
 * shared `geo:<geohash>` channel timeline ([AppStateStore]) and tracks live participants/presence.
 *
 * Re-homes the logic that previously lived in the deleted `GeohashViewModel`/`GeohashMessageHandler`
 * (Phase D), without resurrecting a god-class: a single app-scoped repository over the Nostr ports.
 * Identity derivation, relay selection and dedup stay infrastructure concerns handled here; the
 * domain sees only [GeohashRepository].
 */
@SingleIn(AppScope::class)
@Inject
internal class GeohashRepositoryImpl(
    private val appStateStore: AppStateStore,
    private val relayManager: NostrRelayManager,
    private val relayDirectory: RelayDirectory,
    private val nostrIdentityBridge: NostrIdentityBridge,
    private val powPreferenceManager: PoWPreferenceManager,
    private val aliasRegistry: GeohashAliasRegistry,
    private val conversationRegistry: GeohashConversationRegistry,
    private val geohashDao: GeohashDao,
    private val appForegroundState: AppForegroundState,
    private val presencePublisher: GeohashPresencePublisher,
    private val scope: CoroutineScope,
    private val gatewayRuntime: GatewayRuntime? = null,
) : GeohashRepository, CurrentGeohashSource {

    private val lock = Lock()

    // Synchronous mirror of the blocked-pubkeys set (the DB is the source of truth). ingest() runs on
    // the non-suspend relay path and must consult block state without suspending; the mirror is
    // hydrated from the DB on start and written through on every change.
    private val blockedMirror = MutableStateFlow<Set<String>>(emptySet())

    init {
        gatewayRuntime?.bindInbound(
            current = { currentGeohash() },
            inject = { event -> currentGeohash()?.let { ingest(event, it) } },
        )
        scope.launch { geohashDao.observeBlocked().collect { blockedMirror.value = it.toSet() } }
        // Pause the high-volume presence firehose (kind 20001) while backgrounded; chat messages
        // (kind 20000) stay subscribed so the timeline and participant count keep updating. (#706)
        scope.launch {
            appForegroundState.isForeground.collect { foreground ->
                val geo = lock.withLock { activeGeohash } ?: return@collect
                if (foreground) subscribePresence(geo)
                else relayManager.unsubscribe(presenceSubId(geo))
            }
        }
    }

    // geohash -> (pubkeyHex(lower) -> last seen)
    private val participants = mutableMapOf<String, MutableMap<String, Instant>>()
    // pubkeyHex(lower) -> nickname (without #suffix)
    private val nicknames = mutableMapOf<String, String>()
    // pubkeyHex(lower) of participants that announced a teleport tag
    private val teleported = mutableSetOf<String>()

    // Bounded event-id dedup (matches the legacy handler's horizon).
    private val seenOrder = ArrayDeque<String>()
    private val seenSet = HashSet<String>()

    private var activeGeohash: String? = null

    private val _selected = MutableStateFlow<ConversationId>(ConversationId.PublicMesh)
    private val _participants = MutableStateFlow<List<GeoPerson>>(emptyList())
    private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())

    override fun observeParticipants(): Flow<List<GeoPerson>> = _participants.asStateFlow()
    override fun observeSelectedChannel(): Flow<ConversationId> = _selected.asStateFlow()
    override fun observeParticipantCounts(): Flow<Map<String, Int>> = _counts.asStateFlow()

    override fun currentGeohash(): String? = lock.withLock { activeGeohash }

    override suspend fun select(channel: ConversationId) {
        when (channel) {
            is ConversationId.Geohash -> {
                subscribe(channel.channel.geohash)
                _selected.value = channel
            }
            ConversationId.PublicMesh -> {
                unsubscribe()
                _selected.value = channel
            }
            else -> Unit // DMs / classic channels are not this repository's concern
        }
        refreshDerived()
    }

    override suspend fun startDirectMessage(pubkeyHex: String): ConversationId {
        val alias = aliasFor(pubkeyHex)
        aliasRegistry.put(alias, pubkeyHex)
        lock.withLock { activeGeohash }?.let { conversationRegistry.set(alias, it) }
        return ConversationId.Private(PeerId(alias))
    }

    override suspend fun setBlocked(pubkeyHex: String, blocked: Boolean) {
        val key = pubkeyHex.lowercase()
        if (blocked) geohashDao.insertBlocked(key) else geohashDao.deleteBlocked(key)
        // Write through so the synchronous ingest path sees the change immediately.
        blockedMirror.value = if (blocked) blockedMirror.value + key else blockedMirror.value - key
        refreshDerived()
    }

    override suspend fun isUserBlocked(pubkeyHex: String): Boolean = isBlocked(pubkeyHex)

    override suspend fun isTeleported(pubkeyHex: String): Boolean =
        lock.withLock { teleported.contains(pubkeyHex.lowercase()) }

    override suspend fun pubkeyForNickname(nickname: String): String? = lock.withLock {
        nicknames.entries.firstOrNull { (_, n) -> (n.substringBefore('#')) == nickname }?.key
    }

    override suspend fun pubkeyForShortId(shortId: String): String? = lock.withLock {
        nicknames.keys.firstOrNull { it.startsWith(shortId, ignoreCase = true) }
            ?: participants.values.firstNotNullOfOrNull { ps ->
                ps.keys.firstOrNull { it.startsWith(shortId, ignoreCase = true) }
            }
    }

    // --- subscription lifecycle ---

    private fun subscribe(geohash: String) {
        var previous: String? = null
        val alreadyActive = lock.withLock {
            if (activeGeohash == geohash) return@withLock true
            previous = activeGeohash
            activeGeohash = geohash
            false
        }
        if (alreadyActive) return
        previous?.let {
            relayManager.unsubscribe(messagesSubId(it))
            relayManager.unsubscribe(presenceSubId(it))
        }
        // Low-volume chat messages (kind 20000): always subscribed, kept alive in the background.
        runCatching {
            relayManager.subscribeForGeohash(
                geohash = geohash,
                filter = NostrFilter.geohashMessages(
                    geohash, Clock.System.now().toEpochMilliseconds() - SUBSCRIBE_WINDOW_MS, SUBSCRIBE_LIMIT,
                ),
                relayDirectory = relayDirectory,
                id = messagesSubId(geohash),
                handler = { event ->
                    gatewayRuntime?.onRelayEvent(event, geohash)
                    ingest(event, geohash)
                },
            )
        }.onFailure { Log.e(TAG, "subscribe messages($geohash) failed: ${it.message}") }
        // High-volume presence firehose (kind 20001): only while foreground (#706).
        if (appForegroundState.isForeground.value) subscribePresence(geohash)
        // Publish our own heartbeat on the same lifecycle, so other clients count us as present.
        presencePublisher.start(geohash)
    }

    /** Subscribe to the geohash presence firehose (kind 20001) — paused while backgrounded. */
    private fun subscribePresence(geohash: String) {
        runCatching {
            relayManager.subscribeForGeohash(
                geohash = geohash,
                filter = NostrFilter.geohashPresence(
                    geohash, Clock.System.now().toEpochMilliseconds() - SUBSCRIBE_WINDOW_MS, SUBSCRIBE_LIMIT,
                ),
                relayDirectory = relayDirectory,
                id = presenceSubId(geohash),
                handler = { event -> ingest(event, geohash) },
            )
        }.onFailure { Log.e(TAG, "subscribe presence($geohash) failed: ${it.message}") }
    }

    private fun unsubscribe() {
        var current: String? = null
        lock.withLock {
            current = activeGeohash
            activeGeohash = null
        }
        presencePublisher.stop()
        current?.let {
            relayManager.unsubscribe(messagesSubId(it))
            relayManager.unsubscribe(presenceSubId(it))
        }
    }

    // --- ingest (visible for tests) ---

    /** Process one incoming Nostr event for [geohash]. Public for unit tests; the relay calls it. */
    internal fun ingest(event: NostrEvent, geohash: String) {
        try {
            if (event.kind != NostrKind.EPHEMERAL_EVENT && event.kind != NostrKind.GEOHASH_PRESENCE) return
            val tagGeo = event.tags.firstOrNull { it.size >= 2 && it[0] == "g" }?.getOrNull(1)
            if (tagGeo == null || !tagGeo.equals(geohash, ignoreCase = true)) return
            if (dedupe(event.id)) return

            if (event.kind == NostrKind.EPHEMERAL_EVENT) {
                val pow = powPreferenceManager.getCurrentSettings()
                if (pow.enabled && pow.difficulty > 0 &&
                    !NostrProofOfWork.validateDifficulty(event, pow.difficulty)
                ) {
                    return
                }
            }

            // Normalize the pubkey to lowercase so block/participant/nickname/alias keys stay
            // consistent regardless of the hex casing a relay delivers. (upstream #645)
            val pubkey = event.pubkey.lowercase()
            if (isBlocked(pubkey)) return

            val lastSeen = Instant.fromEpochMilliseconds(event.createdAt * 1000L)
            val isTeleport = event.tags.any { it.size >= 2 && it[0] == "t" && it[1] == "teleport" }
            lock.withLock {
                // Cap to now (clock skew / malicious created_at) and keep the max lastSeen:
                // relays deliver newest-first, so a later, older event must not overwrite a
                // fresher timestamp and silently drop the user from the live count. (upstream #672)
                val now = Clock.System.now()
                val effective = if (lastSeen > now) now else lastSeen
                val ps = participants.getOrPut(geohash) { mutableMapOf() }
                val existing = ps[pubkey]
                if (existing == null || effective > existing) ps[pubkey] = effective
                event.tags.firstOrNull { it.size >= 2 && it[0] == "n" }
                    ?.let { nicknames[pubkey] = it[1] }
                if (isTeleport) teleported.add(pubkey)
            }
            runCatching { aliasRegistry.put(aliasFor(pubkey), pubkey) }
            refreshDerived()

            // Presence heartbeats only update participants; they carry no chat content.
            if (event.kind == NostrKind.GEOHASH_PRESENCE) return

            // Skip our own posts (they appear via local echo) and empty teleport presence beacons.
            val myHex = runCatching { nostrIdentityBridge.deriveIdentity(geohash).publicKeyHex }.getOrNull()
            if (myHex != null && myHex.equals(pubkey, ignoreCase = true)) return
            if (isTeleport && event.content.trim().isEmpty()) return

            val message = BitchatMessage(
                id = event.id,
                sender = displayName(pubkey, geohash, disambiguate = true),
                content = event.content,
                timestamp = lastSeen,
                isRelay = false,
                originalSender = displayName(pubkey, geohash, disambiguate = false) + "#" + pubkey.takeLast(4),
                senderPeerID = "nostr:${pubkey.take(8)}",
                mentions = null,
                channel = "#$geohash",
                powDifficulty = powDifficultyOf(event),
            )
            appStateStore.addChannelMessage(AppStateStore.geoChannelName(geohash), message)
        } catch (e: Exception) {
            Log.e(TAG, "ingest error: ${e.message}")
        }
    }

    // --- derived state ---

    private fun refreshDerived() {
        val cutoff = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds() - PRESENCE_TTL_MS)
        val blocked = blockedSet()
        lock.withLock {
            pruneExpired(cutoff)
            _counts.value = participants.mapValues { (_, ps) ->
                ps.count { (pk, seen) -> seen >= cutoff && pk !in blocked }
            }
            val geohash = activeGeohash
            _participants.value = if (geohash == null) {
                emptyList()
            } else {
                (participants[geohash] ?: emptyMap())
                    .filterKeys { it !in blocked }
                    .map { (pk, seen) ->
                        GeoPerson(
                            pubkeyHex = pk,
                            displayName = displayName(pk, geohash, disambiguate = true),
                            isTeleported = pk in teleported,
                            lastSeen = seen,
                        )
                    }
                    .sortedByDescending { it.lastSeen }
            }
        }
    }

    private fun pruneExpired(cutoff: Instant) {
        participants.values.forEach { ps -> ps.entries.removeAll { it.value < cutoff } }
    }

    /** Display nickname; with [disambiguate], append `#<last4>` when the base nick is not unique. */
    private fun displayName(pubkeyHex: String, geohash: String, disambiguate: Boolean): String {
        val lower = pubkeyHex.lowercase()
        val base = nicknames[lower] ?: ANON
        if (!disambiguate) return base
        val ps = participants[geohash] ?: return base
        val collisions = ps.keys.count { (nicknames[it] ?: ANON).equals(base, ignoreCase = true) }
        return if (collisions > 1) "$base#${pubkeyHex.takeLast(4)}" else base
    }

    private fun powDifficultyOf(event: NostrEvent): Int? = runCatching {
        if (NostrProofOfWork.hasNonce(event)) {
            NostrProofOfWork.calculateDifficulty(event.id).takeIf { it > 0 }
        } else {
            null
        }
    }.getOrNull()

    private fun dedupe(id: String): Boolean = lock.withLock {
        if (!seenSet.add(id)) return@withLock true
        seenOrder.addLast(id)
        if (seenOrder.size > SEEN_CAP) seenSet.remove(seenOrder.removeFirst())
        false
    }

    private fun isBlocked(pubkeyHex: String): Boolean = pubkeyHex.lowercase() in blockedSet()

    private fun blockedSet(): Set<String> = blockedMirror.value

    private fun aliasFor(pubkeyHex: String): String = "nostr_${pubkeyHex.take(16)}"

    private fun messagesSubId(geohash: String): String = "geohash-msg-$geohash"
    private fun presenceSubId(geohash: String): String = "geohash-presence-$geohash"

    private companion object {
        const val TAG = "GeohashRepositoryImpl"
        const val ANON = "anon"
        const val SEEN_CAP = 2000
        const val PRESENCE_TTL_MS = 5 * 60 * 1000L
        const val SUBSCRIBE_WINDOW_MS = 60 * 60 * 1000L
        const val SUBSCRIBE_LIMIT = 200
    }
}
