@file:OptIn(ExperimentalTime::class, ExperimentalEncodingApi::class)

package com.app.transport

import com.app.crypto.EncryptionService
import com.app.common.encoding.dataFromHexString
import com.app.common.encoding.hexEncodedString
import dev.whyoleg.cryptography.random.CryptographyRandom
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * QR verification helpers: schema, signing, and basic challenge/response helpers.
 * Graph-owned; the EncryptionService is injected (formerly a static configure()
 * call from BluetoothMeshService with a WeakReference holder).
 */
@SingleIn(AppScope::class)
@Inject
class VerificationService(private val encryptionService: EncryptionService) {

    private companion object {
        const val CONTEXT = "bitchat-verify-v1"
        const val RESPONSE_CONTEXT = "bitchat-verify-resp-v1"
        const val QR_MAX_AGE_SECONDS = 300L // 5 minutes
    }

    data class VerificationQR(
        val v: Int,
        val noiseKeyHex: String,
        val signKeyHex: String,
        val npub: String?,
        val nickname: String,
        val ts: Long,
        val nonceB64: String,
        val sigHex: String
    ) {
        fun canonicalBytes(): ByteArray {
            val out = Buffer()

            fun appendField(value: String) {
                val data = value.encodeToByteArray()
                val len = minOf(data.size, 255)
                out.writeByte(len.toByte())
                out.write(data, 0, len)
            }

            appendField(CONTEXT)
            appendField(v.toString())
            appendField(noiseKeyHex.lowercase())
            appendField(signKeyHex.lowercase())
            appendField(npub ?: "")
            appendField(nickname)
            appendField(ts.toString())
            appendField(nonceB64)
            return out.readByteArray()
        }

        fun toUrlString(): String {
            val params = buildList {
                add("v" to v.toString())
                add("noise" to noiseKeyHex)
                add("sign" to signKeyHex)
                add("nick" to nickname)
                add("ts" to ts.toString())
                add("nonce" to nonceB64)
                add("sig" to sigHex)
                if (npub != null) add("npub" to npub)
            }
            return "bitchat://verify?" + params.joinToString("&") { (k, value) ->
                "$k=${encodeQueryComponent(value)}"
            }
        }

        companion object {
            fun fromUrlString(urlString: String): VerificationQR? {
                val params = parseBitchatVerifyQuery(urlString) ?: return null

                val v = params["v"]?.toIntOrNull() ?: return null
                val noise = params["noise"] ?: return null
                val sign = params["sign"] ?: return null
                val nick = params["nick"] ?: return null
                val ts = params["ts"]?.toLongOrNull() ?: return null
                val nonce = params["nonce"] ?: return null
                val sig = params["sig"] ?: return null
                val npub = params["npub"]

                return VerificationQR(
                    v = v,
                    noiseKeyHex = noise,
                    signKeyHex = sign,
                    npub = npub,
                    nickname = nick,
                    ts = ts,
                    nonceB64 = nonce,
                    sigHex = sig
                )
            }
        }
    }

    fun buildMyQRString(nickname: String, npub: String?): String? {
        val service = encryptionService
        val cache = cachedQR
        if (cache != null && cache.nickname == nickname && cache.npub == npub) {
            if (Clock.System.now().toEpochMilliseconds() - cache.builtAtMs < 60_000L) {
                return cache.value
            }
        }

        val noiseKey = service.getStaticPublicKey()?.hexEncodedString() ?: return null
        val signKey = service.getSigningPublicKey()?.hexEncodedString() ?: return null
        val ts = Clock.System.now().toEpochMilliseconds() / 1000L
        val nonce = ByteArray(16)
        CryptographyRandom.Default.nextBytes(nonce)
        val nonceB64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(nonce)

        val payload = VerificationQR(
            v = 1,
            noiseKeyHex = noiseKey,
            signKeyHex = signKey,
            npub = npub,
            nickname = nickname,
            ts = ts,
            nonceB64 = nonceB64,
            sigHex = ""
        )

        val signature = service.signData(payload.canonicalBytes()) ?: return null
        val signed = payload.copy(sigHex = signature.hexEncodedString())
        val out = signed.toUrlString()
        cachedQR = CacheEntry(nickname, npub, Clock.System.now().toEpochMilliseconds(), out)
        return out
    }

    fun verifyScannedQR(
        urlString: String,
        maxAgeSeconds: Long = QR_MAX_AGE_SECONDS
    ): VerificationQR? {
        val service = encryptionService
        val qr = VerificationQR.fromUrlString(urlString) ?: return null
        val now = Clock.System.now().toEpochMilliseconds() / 1000L
        if (now - qr.ts > maxAgeSeconds) return null

        val sig = qr.sigHex.dataFromHexString() ?: return null
        val signKey = qr.signKeyHex.dataFromHexString() ?: return null
        val ok = service.verifyEd25519Signature(sig, qr.canonicalBytes(), signKey)
        return if (ok) qr else null
    }

    fun buildVerifyChallenge(noiseKeyHex: String, nonceA: ByteArray): ByteArray {
        val noiseData = noiseKeyHex.encodeToByteArray()
        val out = Buffer()
        out.writeByte(0x01.toByte())
        val nl = minOf(noiseData.size, 255)
        out.writeByte(nl.toByte())
        out.write(noiseData, 0, nl)
        out.writeByte(0x02.toByte())
        val al = minOf(nonceA.size, 255)
        out.writeByte(al.toByte())
        out.write(nonceA, 0, al)
        return out.readByteArray()
    }

    fun buildVerifyResponse(noiseKeyHex: String, nonceA: ByteArray): ByteArray? {
        val service = encryptionService
        val noiseData = noiseKeyHex.encodeToByteArray()
        val msg = Buffer()
        msg.write(RESPONSE_CONTEXT.encodeToByteArray())
        val nl = minOf(noiseData.size, 255)
        msg.writeByte(nl.toByte())
        msg.write(noiseData, 0, nl)
        msg.write(nonceA)
        val sig = service.signData(msg.readByteArray()) ?: return null

        val out = Buffer()
        out.writeByte(0x01.toByte())
        out.writeByte(nl.toByte())
        out.write(noiseData, 0, nl)
        out.writeByte(0x02.toByte())
        val al = minOf(nonceA.size, 255)
        out.writeByte(al.toByte())
        out.write(nonceA, 0, al)
        out.writeByte(0x03.toByte())
        val sl = minOf(sig.size, 255)
        out.writeByte(sl.toByte())
        out.write(sig, 0, sl)
        return out.readByteArray()
    }

    fun parseVerifyChallenge(data: ByteArray): Pair<String, ByteArray>? {
        var idx = 0

        fun take(n: Int): ByteArray? {
            if (idx + n > data.size) return null
            val out = data.copyOfRange(idx, idx + n)
            idx += n
            return out
        }

        val t1 = take(1) ?: return null
        if (t1[0].toInt() != 0x01) return null
        val l1 = take(1)?.get(0)?.toInt() ?: return null
        val noiseBytes = take(l1) ?: return null
        val noise = noiseBytes.decodeToString()

        val t2 = take(1) ?: return null
        if (t2[0].toInt() != 0x02) return null
        val l2 = take(1)?.get(0)?.toInt() ?: return null
        val nonce = take(l2) ?: return null

        return noise to nonce
    }

    data class VerifyResponse(val noiseKeyHex: String, val nonceA: ByteArray, val signature: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as VerifyResponse

            if (noiseKeyHex != other.noiseKeyHex) return false
            if (!nonceA.contentEquals(other.nonceA)) return false
            if (!signature.contentEquals(other.signature)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = noiseKeyHex.hashCode()
            result = 31 * result + nonceA.contentHashCode()
            result = 31 * result + signature.contentHashCode()
            return result
        }
    }

    fun parseVerifyResponse(data: ByteArray): VerifyResponse? {
        var idx = 0

        fun take(n: Int): ByteArray? {
            if (idx + n > data.size) return null
            val out = data.copyOfRange(idx, idx + n)
            idx += n
            return out
        }

        val t1 = take(1) ?: return null
        if (t1[0].toInt() != 0x01) return null
        val l1 = take(1)?.get(0)?.toInt() ?: return null
        val noiseBytes = take(l1) ?: return null
        val noise = noiseBytes.decodeToString()

        val t2 = take(1) ?: return null
        if (t2[0].toInt() != 0x02) return null
        val l2 = take(1)?.get(0)?.toInt() ?: return null
        val nonce = take(l2) ?: return null

        val t3 = take(1) ?: return null
        if (t3[0].toInt() != 0x03) return null
        val l3 = take(1)?.get(0)?.toInt() ?: return null
        val sig = take(l3) ?: return null

        return VerifyResponse(noise, nonce, sig)
    }

    fun verifyResponseSignature(
        noiseKeyHex: String,
        nonceA: ByteArray,
        signature: ByteArray,
        signerPublicKeyHex: String
    ): Boolean {
        val service = encryptionService
        val noiseData = noiseKeyHex.encodeToByteArray()
        val msg = Buffer()
        msg.write(RESPONSE_CONTEXT.encodeToByteArray())
        val nl = minOf(noiseData.size, 255)
        msg.writeByte(nl.toByte())
        msg.write(noiseData, 0, nl)
        msg.write(nonceA)
        val signerKey = signerPublicKeyHex.dataFromHexString() ?: return false
        return service.verifyEd25519Signature(signature, msg.readByteArray(), signerKey)
    }

    private data class CacheEntry(
        val nickname: String,
        val npub: String?,
        val builtAtMs: Long,
        val value: String
    )

    @Volatile
    private var cachedQR: CacheEntry? = null
}

// MARK: - URL helpers (replaces android.net.Uri; output is byte-compatible with the previous
// Uri.Builder / Uri.parse behavior, verified by VerificationServiceGoldenTest).

private const val UPPER_HEX = "0123456789ABCDEF"

/**
 * Percent-encodes a query-parameter value exactly like Android's `Uri.encode(value, null)`:
 * letters, digits, and `_-!.~'()*` are left intact; every other byte of the UTF-8 encoding
 * becomes `%XX` with uppercase hex (e.g. space -> %20, 'π' -> %CF%80).
 */
private fun encodeQueryComponent(value: String): String {
    val sb = StringBuilder()
    for (b in value.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        val allowed = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "_-!.~'()*"
        if (allowed) {
            sb.append(ch)
        } else {
            sb.append('%').append(UPPER_HEX[c ushr 4]).append(UPPER_HEX[c and 0x0F])
        }
    }
    return sb.toString()
}

/**
 * Parses a `bitchat://verify?...` URL into its decoded query parameters (first value per key),
 * or null if the scheme/host do not match. Mirrors Uri.parse + getQueryParameter.
 */
private fun parseBitchatVerifyQuery(urlString: String): Map<String, String>? {
    val schemeSep = "://"
    val schemeIdx = urlString.indexOf(schemeSep)
    if (schemeIdx <= 0) return null
    if (urlString.substring(0, schemeIdx) != "bitchat") return null

    val rest = urlString.substring(schemeIdx + schemeSep.length)
    val queryIdx = rest.indexOf('?')
    val authority = if (queryIdx >= 0) rest.substring(0, queryIdx) else rest
    // Host is the authority up to any path separator.
    val host = authority.substringBefore('/')
    if (host != "verify") return null
    if (queryIdx < 0) return emptyMap()

    val query = rest.substring(queryIdx + 1)
    val result = LinkedHashMap<String, String>()
    for (pair in query.split('&')) {
        if (pair.isEmpty()) continue
        val eq = pair.indexOf('=')
        val key = if (eq >= 0) pair.substring(0, eq) else pair
        val rawValue = if (eq >= 0) pair.substring(eq + 1) else ""
        val decodedKey = percentDecode(key)
        if (!result.containsKey(decodedKey)) {
            result[decodedKey] = percentDecode(rawValue)
        }
    }
    return result
}

/** Percent-decodes a URL component (UTF-8). Leaves malformed `%` sequences as-is. */
private fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val bytes = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        if (ch == '%' && i + 2 < s.length) {
            val hi = hexDigit(s.getOrNull(i + 1))
            val lo = hexDigit(s.getOrNull(i + 2))
            if (hi >= 0 && lo >= 0) {
                bytes.add(((hi shl 4) or lo).toByte())
                i += 3
                continue
            }
        }
        bytes.add(ch.code.toByte())
        i++
    }
    return bytes.toByteArray().decodeToString()
}

private fun hexDigit(c: Char?): Int = when (c) {
    in '0'..'9' -> c!! - '0'
    in 'a'..'f' -> c!! - 'a' + 10
    in 'A'..'F' -> c!! - 'A' + 10
    else -> -1
}
