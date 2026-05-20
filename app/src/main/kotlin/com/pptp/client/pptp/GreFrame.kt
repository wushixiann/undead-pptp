package com.pptp.client.pptp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encoder/decoder for the PPTP-specific "Enhanced GRE" header (RFC 2637 §4.1).
 *
 * Layout (variable size: 8/12/16 bytes depending on S, A flags):
 *
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |C|R|K|S|s|Recur|A| Flags | Ver |       Protocol Type 0x880B    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |       Payload Length          |          Call ID              |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                   Sequence Number (if S=1)                    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |               Acknowledgment Number (if A=1)                  |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *
 * Constraints we always enforce on send:
 *   K = 1 (Key/Call-ID present)        — required by PPTP
 *   Ver = 1                             — required by PPTP enhanced GRE
 *   Protocol = 0x880B (PPP)
 *   C = R = s = Recur = Flags = 0
 *
 * S (sequence) and A (acknowledgment) are set as needed: every TX gets S=1
 * with a monotonic sequence number; A=1 is set opportunistically when we
 * have a peer sequence number to acknowledge.
 */
object GreFrame {

    const val PROTOCOL_PPP = 0x880B
    const val VERSION_PPTP = 1

    data class Decoded(
        val sequence: Int?,
        val ack: Int?,
        val callId: Int,
        val payloadLength: Int,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Decoded) return false
            return sequence == other.sequence && ack == other.ack && callId == other.callId &&
                payloadLength == other.payloadLength && payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int {
            var r = sequence ?: 0
            r = 31 * r + (ack ?: 0)
            r = 31 * r + callId
            r = 31 * r + payloadLength
            r = 31 * r + payload.contentHashCode()
            return r
        }
    }

    /**
     * Build a TX GRE frame addressed to [peerCallId] carrying [pppPayload].
     * Always sets K=1 and Ver=1. If [sequence] is non-null S=1; if [ack] is
     * non-null A=1.
     */
    fun encode(
        peerCallId: Int,
        pppPayload: ByteArray,
        sequence: Int?,
        ack: Int?,
    ): ByteArray {
        var size = 8
        if (sequence != null) size += 4
        if (ack != null) size += 4
        size += pppPayload.size

        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)

        // byte 0: C R K S s Recur(3)
        val byte0 = 0x20 or  // K=1
            (if (sequence != null) 0x10 else 0)  // S
        buf.put(byte0.toByte())
        // byte 1: A Flags(4) Ver(3)
        val byte1 = (if (ack != null) 0x80 else 0) or VERSION_PPTP
        buf.put(byte1.toByte())
        buf.putShort(PROTOCOL_PPP.toShort())

        // Payload length / Call-ID (the "Key" field split in half)
        buf.putShort(pppPayload.size.toShort())
        buf.putShort(peerCallId.toShort())

        if (sequence != null) buf.putInt(sequence)
        if (ack != null) buf.putInt(ack)

        buf.put(pppPayload)
        return buf.array()
    }

    /**
     * Decode a GRE frame. Validates the K=1 / Ver=1 invariants and returns the
     * payload as a fresh ByteArray. Throws [IllegalArgumentException] on
     * malformed input.
     */
    fun decode(bytes: ByteArray): Decoded {
        require(bytes.size >= 8) { "GRE frame too short: ${bytes.size}" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val byte0 = buf.get().toInt() and 0xFF
        val byte1 = buf.get().toInt() and 0xFF
        val protocol = buf.short.toInt() and 0xFFFF
        val payloadLength = buf.short.toInt() and 0xFFFF
        val callId = buf.short.toInt() and 0xFFFF

        val hasSeq = byte0 and 0x10 != 0
        val hasAck = byte1 and 0x80 != 0
        val hasKey = byte0 and 0x20 != 0
        val version = byte1 and 0x07

        require(protocol == PROTOCOL_PPP) {
            "non-PPP GRE protocol: ${"0x%04x".format(protocol)}"
        }
        require(hasKey && version == VERSION_PPTP) {
            "not enhanced GRE: K=$hasKey ver=$version"
        }
        // Bits we should never see set in PPTP enhanced GRE on the wire:
        //   C (checksum), R (reserved0), s (strict source route), Recur (3 bits)
        // i.e. byte0 mask 0xC7 must be 0. We *warn* but tolerate to maximize interop.
        // (Real servers occasionally set stray bits; rejecting them locks the link out.)

        val seq: Int? = if (hasSeq) buf.int else null
        val ack: Int? = if (hasAck) buf.int else null

        val remaining = buf.remaining()
        // If declared payloadLength fits in remaining we use it; otherwise we
        // trust the actual buffer length (some servers mis-set payloadLength).
        val take = if (payloadLength in 0..remaining) payloadLength else remaining
        val payload = ByteArray(take)
        buf.get(payload)
        return Decoded(seq, ack, callId, payloadLength, payload)
    }

    /**
     * From a full IPv4 packet (as delivered by SOCK_RAW), strip the IP header
     * and return the GRE bytes. Returns null if the packet is not GRE or is
     * malformed.
     */
    fun stripIpv4(packet: ByteArray): ByteArray? {
        if (packet.size < 20) return null
        val versionIhl = packet[0].toInt() and 0xFF
        val version = versionIhl ushr 4
        val ihl = versionIhl and 0x0F
        if (version != 4) return null
        val headerBytes = ihl * 4
        if (headerBytes < 20 || headerBytes > packet.size) return null
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 47) return null // not GRE
        return packet.copyOfRange(headerBytes, packet.size)
    }
}
