package com.pptp.client.helper

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Wire format for the AF_UNIX channel between the app and the native helper.
 *
 * Each frame is 4 bytes peer IPv4 address + 2 bytes payload length (both
 * big-endian) followed by `length` bytes of payload.
 *
 *  - TX (app → helper): `peer` is the destination address; payload is the
 *    GRE frame the kernel will wrap in an IPv4 header before transmit.
 *  - RX (helper → app): `peer` is the source address from recvfrom();
 *    payload is the full IPv4 packet as the kernel delivers it (raw
 *    IPPROTO sockets include the IP header on receive).
 *
 * Length field is unsigned 16-bit; payload must be ≤ 65535 bytes. We also
 * cap on the C side at 2048 bytes which is the realistic GRE/PPP MTU.
 */
data class UdsFrame(val peerIpv4: Int, val payload: ByteArray) {
    init {
        require(payload.size in 0..0xFFFF) { "payload too large: ${payload.size}" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UdsFrame) return false
        return peerIpv4 == other.peerIpv4 && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * peerIpv4 + payload.contentHashCode()
}

object UdsFrameCodec {
    const val HEADER_BYTES = 6
    const val MAX_PAYLOAD = 2048

    fun write(out: OutputStream, frame: UdsFrame) {
        val header = ByteArray(HEADER_BYTES)
        val peer = frame.peerIpv4
        header[0] = (peer ushr 24 and 0xFF).toByte()
        header[1] = (peer ushr 16 and 0xFF).toByte()
        header[2] = (peer ushr 8 and 0xFF).toByte()
        header[3] = (peer and 0xFF).toByte()
        header[4] = (frame.payload.size ushr 8 and 0xFF).toByte()
        header[5] = (frame.payload.size and 0xFF).toByte()
        out.write(header)
        if (frame.payload.isNotEmpty()) out.write(frame.payload)
        out.flush()
    }

    /**
     * Blocking read of one frame. Returns null on clean EOF; throws on malformed
     * data or partial header/body. Caller is responsible for buffering large
     * reads; this implementation issues one InputStream.read() per chunk.
     */
    fun readOrNull(input: InputStream): UdsFrame? {
        val header = ByteArray(HEADER_BYTES)
        if (!readFully(input, header)) return null
        val peer = (header[0].toInt() and 0xFF shl 24) or
            (header[1].toInt() and 0xFF shl 16) or
            (header[2].toInt() and 0xFF shl 8) or
            (header[3].toInt() and 0xFF)
        val len = (header[4].toInt() and 0xFF shl 8) or (header[5].toInt() and 0xFF)
        if (len > MAX_PAYLOAD) throw IOException("payload length $len exceeds cap $MAX_PAYLOAD")
        val payload = if (len == 0) ByteArray(0) else ByteArray(len)
        if (len > 0 && !readFully(input, payload)) {
            throw IOException("unexpected EOF after header (need $len bytes)")
        }
        return UdsFrame(peer, payload)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return off == 0 && buf.isEmpty()
            if (n == 0) return false
            off += n
        }
        return true
    }
}

/** Convenience helpers for converting between dotted-quad strings and packed int. */
object Ipv4 {
    fun parse(dotted: String): Int {
        val parts = dotted.split('.')
        require(parts.size == 4) { "not an IPv4 address: $dotted" }
        var acc = 0
        for (p in parts) {
            val n = p.toInt()
            require(n in 0..255) { "octet out of range: $p" }
            acc = (acc shl 8) or n
        }
        return acc
    }

    fun format(packed: Int): String {
        return "${(packed ushr 24) and 0xFF}.${(packed ushr 16) and 0xFF}." +
            "${(packed ushr 8) and 0xFF}.${packed and 0xFF}"
    }
}
