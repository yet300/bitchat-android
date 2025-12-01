package com.bitchat.android.nostr

import android.app.Application
import android.util.Log
import com.bitchat.android.ui.GeoPerson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Date

/**
 * GeohashRepository
 * - Owns geohash participant tracking and nickname caching
 * - Maintains lightweight state for geohash-related UI
 */
class GeohashRepository(
    private val application: Application,
    private val dataManager: com.bitchat.android.ui.DataManager
) {
    companion object { private const val TAG = "GeohashRepository" }
    
    // Exposed state via StateFlows
    private val _geohashPeople = MutableStateFlow<List<GeoPerson>>(emptyList())
    val geohashPeople: StateFlow<List<GeoPerson>> = _geohashPeople.asStateFlow()
    
    private val _geohashParticipantCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val geohashParticipantCounts: StateFlow<Map<String, Int>> = _geohashParticipantCounts.asStateFlow()
    
    private val _currentGeohash = MutableStateFlow<String?>(null)
    val currentGeohash: StateFlow<String?> = _currentGeohash.asStateFlow()
    
    private val _teleportedGeo = MutableStateFlow<Set<String>>(emptySet())
    val teleportedGeo: StateFlow<Set<String>> = _teleportedGeo.asStateFlow()

    // geohash -> (participant pubkeyHex -> lastSeen)
    private val geohashParticipants: MutableMap<String, MutableMap<String, Date>> = mutableMapOf()


    // pubkeyHex(lowercase) -> nickname (without #hash)
    private val geoNicknames: MutableMap<String, String> = mutableMapOf()

    // conversation key (e.g., "nostr_<pub16>") -> source geohash it belongs to
    private val conversationGeohash: MutableMap<String, String> = mutableMapOf()

    fun setConversationGeohash(convKey: String, geohash: String) {
        if (geohash.isNotEmpty()) {
            conversationGeohash[convKey] = geohash
        }
    }

    fun getConversationGeohash(convKey: String): String? = conversationGeohash[convKey]

    fun findPubkeyByNickname(targetNickname: String): String? {
        return geoNicknames.entries.firstOrNull { (_, nickname) ->
            val base = nickname.split("#").firstOrNull() ?: nickname
            base == targetNickname
        }?.key
    }

    // peerID alias -> nostr pubkey mapping for geohash DMs and temp aliases
    private val nostrKeyMapping: MutableMap<String, String> = mutableMapOf()

    fun setCurrentGeohash(geo: String?) { 
        _currentGeohash.value = geo
    }
    
    fun getCurrentGeohash(): String? = _currentGeohash.value

    fun clearAll() {
        geohashParticipants.clear()
        geoNicknames.clear()
        nostrKeyMapping.clear()
        _geohashPeople.value = emptyList()
        _teleportedGeo.value = emptySet()
        _geohashParticipantCounts.value = emptyMap()
        _currentGeohash.value = null
    }

    fun cacheNickname(pubkeyHex: String, nickname: String) {
        val lower = pubkeyHex.lowercase()
        val previous = geoNicknames[lower]
        geoNicknames[lower] = nickname
        if (previous != nickname && _currentGeohash.value != null) {
            refreshGeohashPeople()
        }
    }

    fun getCachedNickname(pubkeyHex: String): String? = geoNicknames[pubkeyHex.lowercase()]

    fun markTeleported(pubkeyHex: String) {
        val set = _teleportedGeo.value.toMutableSet()
        val key = pubkeyHex.lowercase()
        if (!set.contains(key)) {
            set.add(key)
            _teleportedGeo.value = set
        }
    }

    fun isPersonTeleported(pubkeyHex: String): Boolean {
        return _teleportedGeo.value.contains(pubkeyHex.lowercase())
    }

    fun updateParticipant(geohash: String, participantId: String, lastSeen: Date) {
        val participants = geohashParticipants.getOrPut(geohash) { mutableMapOf() }
        participants[participantId] = lastSeen
        if (_currentGeohash.value == geohash) refreshGeohashPeople()
        updateReactiveParticipantCounts()
    }

    fun geohashParticipantCount(geohash: String): Int {
        val cutoff = Date(System.currentTimeMillis() - 5 * 60 * 1000)
        val participants = geohashParticipants[geohash] ?: return 0
        // prune expired
        val it = participants.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value.before(cutoff)) it.remove()
        }
        // exclude blocked users
        return participants.keys.count { !dataManager.isGeohashUserBlocked(it) }
    }

    fun refreshGeohashPeople(currentNickname: String? = null) {
        val geohash = _currentGeohash.value
        if (geohash == null) {
            _geohashPeople.value = emptyList()
            return
        }
        val cutoff = Date(System.currentTimeMillis() - 5 * 60 * 1000)
        val participants = geohashParticipants[geohash] ?: mutableMapOf()
        // prune expired
        val it = participants.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value.before(cutoff)) it.remove()
        }
        geohashParticipants[geohash] = participants
        // exclude blocked users from people list
        val people = participants.filterKeys { !dataManager.isGeohashUserBlocked(it) }
            .map { (pubkeyHex, lastSeen) ->
            // Use our actual nickname for self; otherwise use cached nickname or anon
            val base = try {
                val myHex = _currentGeohash.value?.let { NostrIdentityBridge.deriveIdentity(it, application).publicKeyHex }
                if (myHex != null && myHex.equals(pubkeyHex, true)) {
                    currentNickname ?: "anon"
                } else {
                    getCachedNickname(pubkeyHex) ?: "anon"
                }
            } catch (_: Exception) { getCachedNickname(pubkeyHex) ?: "anon" }
            GeoPerson(
                id = pubkeyHex.lowercase(),
                displayName = base, // UI can add #hash if necessary
                lastSeen = lastSeen
            )
        }.sortedByDescending { it.lastSeen }
        _geohashPeople.value = people
    }

    fun updateReactiveParticipantCounts() {
        val cutoff = Date(System.currentTimeMillis() - 5 * 60 * 1000)
        val counts = mutableMapOf<String, Int>()
        for ((gh, participants) in geohashParticipants) {
            val active = participants.filterKeys { !dataManager.isGeohashUserBlocked(it) }
                .values.count { !it.before(cutoff) }
            counts[gh] = active
        }
        _geohashParticipantCounts.value = counts
    }

    fun putNostrKeyMapping(tempKeyOrPeer: String, pubkeyHex: String) {
        nostrKeyMapping[tempKeyOrPeer] = pubkeyHex
    }

    fun getNostrKeyMapping(): Map<String, String> = nostrKeyMapping.toMap()

    fun displayNameForNostrPubkey(pubkeyHex: String, currentNickname: String? = null): String {
        val suffix = pubkeyHex.takeLast(4)
        val lower = pubkeyHex.lowercase()
        // Self nickname if matches current identity of current geohash
        val current = _currentGeohash.value
        if (current != null) {
            try {
                val my = NostrIdentityBridge.deriveIdentity(current, application)
                if (my.publicKeyHex.equals(lower, true)) {
                    return "${currentNickname ?: "anon"}#$suffix"
                }
            } catch (_: Exception) {}
        }
        val nick = geoNicknames[lower] ?: "anon"
        return "$nick#$suffix"
    }

    fun displayNameForNostrPubkeyUI(pubkeyHex: String, currentNickname: String? = null): String {
        val lower = pubkeyHex.lowercase()
        val suffix = pubkeyHex.takeLast(4)
        val current = _currentGeohash.value
        val base: String = try {
            if (current != null) {
                val my = NostrIdentityBridge.deriveIdentity(current, application)
                if (my.publicKeyHex.equals(lower, true)) {
                    currentNickname ?: "anon"
                } else geoNicknames[lower] ?: "anon"
            } else geoNicknames[lower] ?: "anon"
        } catch (_: Exception) { geoNicknames[lower] ?: "anon" }
        if (current == null) return base
        return try {
            val cutoff = Date(System.currentTimeMillis() - 5 * 60 * 1000)
            val participants = geohashParticipants[current] ?: emptyMap()
            var count = 0
            for ((k, t) in participants) {
                if (dataManager.isGeohashUserBlocked(k)) continue
                if (t.before(cutoff)) continue
                val name = if (k.equals(lower, true)) base else (geoNicknames[k.lowercase()] ?: "anon")
                if (name.equals(base, true)) { count++; if (count > 1) break }
            }
            if (!participants.containsKey(lower)) count += 1
            if (count > 1) "$base#$suffix" else base
        } catch (_: Exception) { base }
    }

    /**
     * Get display name for any geohash (not just current one) for header titles
     */
    fun displayNameForGeohashConversation(pubkeyHex: String, sourceGeohash: String): String {
        val lower = pubkeyHex.lowercase()
        val suffix = pubkeyHex.takeLast(4)
        val base = geoNicknames[lower] ?: "anon"
        return try {
            val cutoff = Date(System.currentTimeMillis() - 5 * 60 * 1000)
            val participants = geohashParticipants[sourceGeohash] ?: emptyMap()
            var count = 0
            for ((k, t) in participants) {
                if (dataManager.isGeohashUserBlocked(k)) continue
                if (t.before(cutoff)) continue
                val name = if (k.equals(lower, true)) base else (geoNicknames[k.lowercase()] ?: "anon")
                if (name.equals(base, true)) { count++; if (count > 1) break }
            }
            if (!participants.containsKey(lower)) count += 1
            if (count > 1) "$base#$suffix" else base
        } catch (_: Exception) { base }
    }
}
