package com.app.domain.model

/**
 * One line in the debug packet log (P24): a human-readable event from the mesh traffic recorder.
 * [kind] classifies it for filtering/colour; [text] is the pre-formatted content.
 */
data class PacketLogEntry(
    val kind: PacketLogKind,
    val text: String,
    val timestampMs: Long,
)

enum class PacketLogKind {
    SYSTEM,
    PEER,
    PACKET,
    RELAY,
}
