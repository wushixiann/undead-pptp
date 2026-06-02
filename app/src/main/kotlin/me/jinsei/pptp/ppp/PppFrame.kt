// SPDX-License-Identifier: GPL-3.0-or-later
package me.jinsei.pptp.ppp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PPP frame inside a PPTP GRE payload.
 *
 * PPTP carries PPP over a synchronous, packet-oriented medium (GRE), so the
 * HDLC-like framing (0x7E flags, byte stuffing, FCS) used on async serial
 * links is omitted entirely — RFC 2637 §4.1.
 *
 * The on-wire layout we use is the minimum that real PPTP servers accept:
 *
 *   +------+------+----------+
 *   | 0xFF | 0x03 | Protocol |  ← Address + Control + Protocol (uncompressed)
 *   +------+------+----------+
 *   |        payload         |
 *   +------------------------+
 *
 * After ACFC has been negotiated the 0xFF 0x03 prefix can be dropped on
 * send; we always emit it pre-ACFC and accept either form on receive
 * (most interop bugs come from the *receiver* being intolerant).
 *
 * Protocol is a 2-byte big-endian field. When the high byte's low bit is
 * 1 the protocol is a single byte (RFC 1661 §6.5 PFC). We do not currently
 * compress on send; we tolerate compressed on receive.
 */
object PppProtocol {
    const val LCP = 0xC021
    const val PAP = 0xC023
    const val CHAP = 0xC223         // includes MS-CHAP variants (distinguished by Algorithm byte)
    const val IPCP = 0x8021
    const val CCP = 0x80FD
    const val IPV4 = 0x0021
    const val MPPE_COMPRESSED = 0x00FD
}

object PppFrame {

    /** Default ACFC = false: include the 0xFF 0x03 prefix on send. */
    fun encode(protocol: Int, payload: ByteArray, addressControl: Boolean = true): ByteArray {
        val prefix = if (addressControl) 2 else 0
        val buf = ByteBuffer.allocate(prefix + 2 + payload.size).order(ByteOrder.BIG_ENDIAN)
        if (addressControl) {
            buf.put(0xFF.toByte())
            buf.put(0x03.toByte())
        }
        buf.putShort(protocol.toShort())
        buf.put(payload)
        return buf.array()
    }

    /** Decoded PPP frame: protocol + payload (no header bytes). */
    data class Decoded(val protocol: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Decoded) return false
            return protocol == other.protocol && payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int = 31 * protocol + payload.contentHashCode()
    }

    /**
     * Decode a PPP frame, tolerating both ACFC-compressed (no 0xFF 0x03) and
     * PFC-compressed (single-byte protocol) forms.
     */
    fun decode(bytes: ByteArray): Decoded {
        var off = 0
        // ACFC: skip 0xFF 0x03 if present
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0x03.toByte()) {
            off = 2
        }
        require(bytes.size > off) { "PPP frame truncated at protocol field" }
        // PFC: if low bit of first byte is 1, protocol is 1 byte
        val proto: Int
        if (bytes[off].toInt() and 0x01 == 0x01) {
            proto = bytes[off].toInt() and 0xFF
            off += 1
        } else {
            require(bytes.size >= off + 2) { "PPP frame truncated at 2-byte protocol" }
            proto = ((bytes[off].toInt() and 0xFF) shl 8) or (bytes[off + 1].toInt() and 0xFF)
            off += 2
        }
        val payload = bytes.copyOfRange(off, bytes.size)
        return Decoded(proto, payload)
    }
}
