package com.app.transport.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Serializes [Instant] as epoch milliseconds (Long) — the same representation the
 * binary protocol and previous persistence used, so values round-trip exactly.
 *
 * The descriptor name is deliberately NOT "kotlin.time.Instant": kotlinx-serialization 1.9 ships a
 * built-in Instant serializer under that (now reserved) primitive name, and reusing it makes
 * [PrimitiveSerialDescriptor] throw at class-init. The name is descriptor metadata only — it does not
 * affect the encoded value (still a plain Long), so the wire/JSON output is unchanged.
 */
@OptIn(ExperimentalTime::class)
object DateSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("InstantEpochMillis", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeLong(value.toEpochMilliseconds())
    }

    override fun deserialize(decoder: Decoder): Instant =
        Instant.fromEpochMilliseconds(decoder.decodeLong())
}
