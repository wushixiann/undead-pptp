// SPDX-License-Identifier: GPL-3.0-or-later
package com.pptp.client.ppp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * LCP wire format and option codecs (RFC 1661 §5–6, RFC 1570 §2).
 *
 *   +----+-----+--------+-------+
 *   |Code| Id  | Length | Data  |
 *   +----+-----+--------+-------+
 *     1    1      2       N-4
 *
 *   Data for Configure-* messages is a sequence of options:
 *     +----+----+-------+
 *     |Type|Len | Value |
 *     +----+----+-------+
 *       1    1    Len-2
 */

object LcpCode {
    const val ConfigureRequest = 1
    const val ConfigureAck = 2
    const val ConfigureNak = 3
    const val ConfigureReject = 4
    const val TerminateRequest = 5
    const val TerminateAck = 6
    const val CodeReject = 7
    const val ProtocolReject = 8
    const val EchoRequest = 9
    const val EchoReply = 10
    const val DiscardRequest = 11
}

object LcpOptionType {
    const val MRU = 1
    const val ASYNC_CONTROL_CHARACTER_MAP = 2 // not used for synchronous PPTP
    const val AUTHENTICATION_PROTOCOL = 3
    const val QUALITY_PROTOCOL = 4
    const val MAGIC_NUMBER = 5
    const val PROTOCOL_FIELD_COMPRESSION = 7
    const val ADDRESS_CONTROL_FIELD_COMPRESSION = 8
}

/** Authentication protocol option payload constants. */
object AuthProto {
    const val PAP = 0xC023
    const val CHAP = 0xC223
    const val ALGORITHM_MS_CHAP_V1 = 0x80
    const val ALGORITHM_MS_CHAP_V2 = 0x81
}

/** A parsed LCP packet. Options are kept in raw form for stage-by-stage processing. */
data class LcpPacket(val code: Int, val identifier: Int, val data: ByteArray) {
    val length: Int get() = 4 + data.size
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LcpPacket) return false
        return code == other.code && identifier == other.identifier && data.contentEquals(other.data)
    }
    override fun hashCode(): Int {
        var r = code; r = 31 * r + identifier; r = 31 * r + data.contentHashCode(); return r
    }
}

/** A single LCP option: Type + Value bytes. */
data class LcpOption(val type: Int, val value: ByteArray) {
    fun encodedSize(): Int = 2 + value.size
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LcpOption) return false
        return type == other.type && value.contentEquals(other.value)
    }
    override fun hashCode(): Int = 31 * type + value.contentHashCode()
}

object LcpCodec {

    fun encodePacket(p: LcpPacket): ByteArray {
        val buf = ByteBuffer.allocate(p.length).order(ByteOrder.BIG_ENDIAN)
        buf.put(p.code.toByte())
        buf.put(p.identifier.toByte())
        buf.putShort(p.length.toShort())
        buf.put(p.data)
        return buf.array()
    }

    fun decodePacket(bytes: ByteArray): LcpPacket {
        require(bytes.size >= 4) { "LCP packet < 4 bytes" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val code = buf.get().toInt() and 0xFF
        val id = buf.get().toInt() and 0xFF
        val length = buf.short.toInt() and 0xFFFF
        require(length <= bytes.size) { "LCP declared length $length > buffer ${bytes.size}" }
        val data = ByteArray(length - 4)
        buf.get(data)
        return LcpPacket(code, id, data)
    }

    fun encodeOptions(opts: List<LcpOption>): ByteArray {
        val total = opts.sumOf { it.encodedSize() }
        val buf = ByteBuffer.allocate(total)
        for (o in opts) {
            require(o.value.size <= 253) { "option ${o.type} too long" }
            buf.put(o.type.toByte())
            buf.put((2 + o.value.size).toByte())
            buf.put(o.value)
        }
        return buf.array()
    }

    fun decodeOptions(data: ByteArray): List<LcpOption> {
        val out = mutableListOf<LcpOption>()
        var i = 0
        while (i < data.size) {
            require(data.size - i >= 2) { "option header truncated" }
            val type = data[i].toInt() and 0xFF
            val len = data[i + 1].toInt() and 0xFF
            require(len >= 2 && i + len <= data.size) {
                "option length $len at offset $i invalid"
            }
            val value = data.copyOfRange(i + 2, i + len)
            out.add(LcpOption(type, value))
            i += len
        }
        return out
    }

    // ------- Option value helpers -------

    fun mru(v: Int): LcpOption {
        val b = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(v.toShort()).array()
        return LcpOption(LcpOptionType.MRU, b)
    }
    fun magicNumber(v: Int): LcpOption {
        val b = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array()
        return LcpOption(LcpOptionType.MAGIC_NUMBER, b)
    }
    fun authProtocolPap(): LcpOption =
        LcpOption(LcpOptionType.AUTHENTICATION_PROTOCOL,
            ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(AuthProto.PAP.toShort()).array())
    fun authProtocolMsChapV2(): LcpOption =
        LcpOption(LcpOptionType.AUTHENTICATION_PROTOCOL,
            byteArrayOf(
                (AuthProto.CHAP ushr 8 and 0xFF).toByte(),
                (AuthProto.CHAP and 0xFF).toByte(),
                AuthProto.ALGORITHM_MS_CHAP_V2.toByte(),
            ))

    /** Parse a Magic-Number option value to an Int (network order). */
    fun readMagicNumber(o: LcpOption): Int {
        require(o.type == LcpOptionType.MAGIC_NUMBER && o.value.size == 4)
        return ByteBuffer.wrap(o.value).order(ByteOrder.BIG_ENDIAN).int
    }

    /** Parse an MRU option value. */
    fun readMru(o: LcpOption): Int {
        require(o.type == LcpOptionType.MRU && o.value.size == 2)
        return ByteBuffer.wrap(o.value).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
    }

    /** Identify an Auth-Protocol option's authentication type. */
    fun classifyAuth(o: LcpOption): AuthChoice {
        require(o.type == LcpOptionType.AUTHENTICATION_PROTOCOL)
        if (o.value.size < 2) return AuthChoice.Unknown
        val proto = ((o.value[0].toInt() and 0xFF) shl 8) or (o.value[1].toInt() and 0xFF)
        return when {
            proto == AuthProto.PAP -> AuthChoice.Pap
            proto == AuthProto.CHAP && o.value.size >= 3 -> when (o.value[2].toInt() and 0xFF) {
                AuthProto.ALGORITHM_MS_CHAP_V2 -> AuthChoice.MsChapV2
                AuthProto.ALGORITHM_MS_CHAP_V1 -> AuthChoice.MsChapV1
                else -> AuthChoice.Unknown
            }
            else -> AuthChoice.Unknown
        }
    }
}

enum class AuthChoice { Pap, MsChapV2, MsChapV1, Unknown }

/** Helpers for choosing/initializing local LCP options. */
object LcpDefaults {
    fun newMagic(): Int {
        var m: Int
        do { m = Random.nextInt() } while (m == 0)
        return m
    }
    /** PPTP convention: MRU 1400 (matches Microsoft default; avoids GRE fragmentation). */
    const val MRU = 1400
}
