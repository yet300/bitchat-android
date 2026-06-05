@file:OptIn(ExperimentalTime::class)

package com.app.data.mapper

import com.app.common.encoding.hexEncodedString
import com.app.data.favorites.FavoriteRelationship
import com.app.domain.model.Contact
import com.app.domain.model.PeerIdentity
import kotlin.time.ExperimentalTime

/**
 * Maps a [FavoriteRelationship] (the Noise<->Nostr bridge record) to the domain [Contact], replacing
 * the raw Noise key bytes with their hex form.
 */
internal fun FavoriteRelationship.toDomain(): Contact =
    Contact(
        identity = PeerIdentity(
            noiseKeyHex = peerNoisePublicKey.hexEncodedString(),
            nostrNpub = peerNostrPublicKey,
        ),
        nickname = peerNickname,
        isFavorite = isFavorite,
        theyFavoritedUs = theyFavoritedUs,
        favoritedAt = favoritedAt,
        lastUpdated = lastUpdated,
    )
