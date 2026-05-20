package com.pptp.client.pptp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary codec for PPTP control messages (RFC 2637 §3).
 *
 * Wire format is big-endian throughout. Fixed-width ASCII string fields
 * (host name, vendor string, phone number, sub-address) are null-padded
 * to their declared length; on decode they are trimmed at the first NUL.
 */
object ControlCodec {

    /** Number of bytes available in the peek buffer required before length is knowable. */
    const val HEADER_PEEK_BYTES = 2

    /**
     * Encode a [ControlMessage] to a freshly-allocated ByteArray sized exactly
     * for the wire form. Throws [IllegalArgumentException] for messages whose
     * size cannot be determined (e.g. [ControlMessage.Unknown] with empty raw).
     */
    fun encode(msg: ControlMessage): ByteArray {
        val size = expectedMessageSize(msg.type)
        require(size > 0) { "no expected size for type=${msg.type}" }
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        writeHeader(buf, size, msg.type)
        when (msg) {
            is ControlMessage.StartControlConnectionRequest -> encodeSccrq(buf, msg)
            is ControlMessage.StartControlConnectionReply -> encodeSccrp(buf, msg)
            is ControlMessage.StopControlConnectionRequest -> encodeStopCcrq(buf, msg)
            is ControlMessage.StopControlConnectionReply -> encodeStopCcrp(buf, msg)
            is ControlMessage.EchoRequest -> encodeEchoReq(buf, msg)
            is ControlMessage.EchoReply -> encodeEchoRep(buf, msg)
            is ControlMessage.OutgoingCallRequest -> encodeOcrq(buf, msg)
            is ControlMessage.OutgoingCallReply -> encodeOcrp(buf, msg)
            is ControlMessage.CallDisconnectNotify -> encodeCdn(buf, msg)
            is ControlMessage.WanErrorNotify -> encodeWen(buf, msg)
            is ControlMessage.SetLinkInfo -> encodeSli(buf, msg)
            is ControlMessage.Unknown -> {
                require(msg.raw.size == size - PPTP_COMMON_HEADER_BYTES) {
                    "unknown message body size mismatch"
                }
                buf.put(msg.raw)
            }
        }
        require(buf.position() == size) {
            "encoder under-filled buffer: wrote ${buf.position()} of $size"
        }
        return buf.array()
    }

    /**
     * Decode a complete message from [bytes]. The buffer MUST hold exactly one
     * message (sized per the length field). Use [peekLength] when reading from
     * a stream to learn how many bytes to consume.
     */
    fun decode(bytes: ByteArray): ControlMessage {
        require(bytes.size >= PPTP_COMMON_HEADER_BYTES) {
            "buffer shorter than header"
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val header = readHeader(buf)
        require(header.length == bytes.size) {
            "header length=${header.length} but buffer has ${bytes.size} bytes"
        }
        require(header.magicCookie == PPTP_MAGIC_COOKIE) {
            "bad magic cookie: ${"0x%08x".format(header.magicCookie)}"
        }
        require(header.pptpMessageType == PPTP_MESSAGE_TYPE_CONTROL) {
            "non-control PPTP message type ${header.pptpMessageType}"
        }
        return when (header.controlMessageType) {
            PptpCmType.SCCRQ -> decodeSccrq(buf)
            PptpCmType.SCCRP -> decodeSccrp(buf)
            PptpCmType.StopCCRQ -> decodeStopCcrq(buf)
            PptpCmType.StopCCRP -> decodeStopCcrp(buf)
            PptpCmType.EchoRequest -> decodeEchoReq(buf)
            PptpCmType.EchoReply -> decodeEchoRep(buf)
            PptpCmType.OCRQ -> decodeOcrq(buf)
            PptpCmType.OCRP -> decodeOcrp(buf)
            PptpCmType.CDN -> decodeCdn(buf)
            PptpCmType.WEN -> decodeWen(buf)
            PptpCmType.SLI -> decodeSli(buf)
            else -> {
                val raw = ByteArray(bytes.size - PPTP_COMMON_HEADER_BYTES)
                buf.get(raw)
                ControlMessage.Unknown(header.controlMessageType, raw)
            }
        }
    }

    /** Read just the length field from a 2-byte big-endian buffer. */
    fun peekLength(headerStart: ByteArray): Int {
        require(headerStart.size >= HEADER_PEEK_BYTES) { "need ≥$HEADER_PEEK_BYTES bytes" }
        return ((headerStart[0].toInt() and 0xFF) shl 8) or (headerStart[1].toInt() and 0xFF)
    }

    // -------- Header --------

    private fun writeHeader(buf: ByteBuffer, length: Int, cmType: Int) {
        buf.putShort(length.toShort())
        buf.putShort(PPTP_MESSAGE_TYPE_CONTROL.toShort())
        buf.putInt(PPTP_MAGIC_COOKIE)
        buf.putShort(cmType.toShort())
        buf.putShort(0) // reserved0
    }

    private fun readHeader(buf: ByteBuffer): CommonHeader {
        val length = buf.short.toInt() and 0xFFFF
        val pptpMt = buf.short.toInt() and 0xFFFF
        val magic = buf.int
        val cmType = buf.short.toInt() and 0xFFFF
        buf.short // reserved0
        return CommonHeader(length, pptpMt, magic, cmType)
    }

    // -------- Per-message encoders --------

    private fun encodeSccrq(buf: ByteBuffer, m: ControlMessage.StartControlConnectionRequest) {
        buf.putShort(m.protocolVersion.toShort())
        buf.putShort(0) // reserved1
        buf.putInt(m.framingCapabilities)
        buf.putInt(m.bearerCapabilities)
        buf.putShort(m.maximumChannels.toShort())
        buf.putShort(m.firmwareRevision.toShort())
        putFixedString(buf, m.hostName, 64)
        putFixedString(buf, m.vendorString, 64)
    }

    private fun encodeSccrp(buf: ByteBuffer, m: ControlMessage.StartControlConnectionReply) {
        buf.putShort(m.protocolVersion.toShort())
        buf.put(m.resultCode.toByte())
        buf.put(m.errorCode.toByte())
        buf.putInt(m.framingCapabilities)
        buf.putInt(m.bearerCapabilities)
        buf.putShort(m.maximumChannels.toShort())
        buf.putShort(m.firmwareRevision.toShort())
        putFixedString(buf, m.hostName, 64)
        putFixedString(buf, m.vendorString, 64)
    }

    private fun encodeStopCcrq(buf: ByteBuffer, m: ControlMessage.StopControlConnectionRequest) {
        buf.put(m.reason.toByte())
        buf.put(0) // reserved1
        buf.putShort(0) // reserved2
    }

    private fun encodeStopCcrp(buf: ByteBuffer, m: ControlMessage.StopControlConnectionReply) {
        buf.put(m.resultCode.toByte())
        buf.put(m.errorCode.toByte())
        buf.putShort(0) // reserved
    }

    private fun encodeEchoReq(buf: ByteBuffer, m: ControlMessage.EchoRequest) {
        buf.putInt(m.identifier)
    }

    private fun encodeEchoRep(buf: ByteBuffer, m: ControlMessage.EchoReply) {
        buf.putInt(m.identifier)
        buf.put(m.resultCode.toByte())
        buf.put(m.errorCode.toByte())
        buf.putShort(0) // reserved
    }

    private fun encodeOcrq(buf: ByteBuffer, m: ControlMessage.OutgoingCallRequest) {
        buf.putShort(m.callId.toShort())
        buf.putShort(m.callSerialNumber.toShort())
        buf.putInt(m.minimumBps)
        buf.putInt(m.maximumBps)
        buf.putInt(m.bearerType)
        buf.putInt(m.framingType)
        buf.putShort(m.pktWindowSize.toShort())
        buf.putShort(m.pktProcessingDelay.toShort())
        buf.putShort(m.phoneNumber.length.toShort()) // phone number length
        buf.putShort(0) // reserved1
        putFixedString(buf, m.phoneNumber, 64)
        putFixedString(buf, m.subAddress, 64)
    }

    private fun encodeOcrp(buf: ByteBuffer, m: ControlMessage.OutgoingCallReply) {
        buf.putShort(m.callId.toShort())
        buf.putShort(m.peerCallId.toShort())
        buf.put(m.resultCode.toByte())
        buf.put(m.errorCode.toByte())
        buf.putShort(m.causeCode.toShort())
        buf.putInt(m.connectSpeed)
        buf.putShort(m.pktWindowSize.toShort())
        buf.putShort(m.pktProcessingDelay.toShort())
        buf.putInt(m.physicalChannelId)
    }

    private fun encodeCdn(buf: ByteBuffer, m: ControlMessage.CallDisconnectNotify) {
        buf.putShort(m.callId.toShort())
        buf.put(m.resultCode.toByte())
        buf.put(m.errorCode.toByte())
        buf.putShort(m.causeCode.toShort())
        buf.putShort(0) // reserved
        require(m.callStatistics.size == 128) { "Call Statistics must be 128 bytes" }
        buf.put(m.callStatistics)
    }

    private fun encodeWen(buf: ByteBuffer, m: ControlMessage.WanErrorNotify) {
        buf.putShort(m.peerCallId.toShort())
        buf.putShort(0) // reserved
        buf.putInt(m.crcErrors)
        buf.putInt(m.framingErrors)
        buf.putInt(m.hardwareOverruns)
        buf.putInt(m.bufferOverruns)
        buf.putInt(m.timeoutErrors)
        buf.putInt(m.alignmentErrors)
    }

    private fun encodeSli(buf: ByteBuffer, m: ControlMessage.SetLinkInfo) {
        buf.putShort(m.peerCallId.toShort())
        buf.putShort(0) // reserved
        buf.putInt(m.sendAccm)
        buf.putInt(m.recvAccm)
    }

    // -------- Per-message decoders --------

    private fun decodeSccrq(buf: ByteBuffer): ControlMessage.StartControlConnectionRequest {
        val pv = buf.short.toInt() and 0xFFFF
        buf.short // reserved1
        val fc = buf.int
        val bc = buf.int
        val mc = buf.short.toInt() and 0xFFFF
        val fw = buf.short.toInt() and 0xFFFF
        val host = readFixedString(buf, 64)
        val vendor = readFixedString(buf, 64)
        return ControlMessage.StartControlConnectionRequest(pv, fc, bc, mc, fw, host, vendor)
    }

    private fun decodeSccrp(buf: ByteBuffer): ControlMessage.StartControlConnectionReply {
        val pv = buf.short.toInt() and 0xFFFF
        val rc = buf.get().toInt() and 0xFF
        val ec = buf.get().toInt() and 0xFF
        val fc = buf.int
        val bc = buf.int
        val mc = buf.short.toInt() and 0xFFFF
        val fw = buf.short.toInt() and 0xFFFF
        val host = readFixedString(buf, 64)
        val vendor = readFixedString(buf, 64)
        return ControlMessage.StartControlConnectionReply(pv, rc, ec, fc, bc, mc, fw, host, vendor)
    }

    private fun decodeStopCcrq(buf: ByteBuffer): ControlMessage.StopControlConnectionRequest {
        val reason = buf.get().toInt() and 0xFF
        buf.get() // reserved1
        buf.short // reserved2
        return ControlMessage.StopControlConnectionRequest(reason)
    }

    private fun decodeStopCcrp(buf: ByteBuffer): ControlMessage.StopControlConnectionReply {
        val rc = buf.get().toInt() and 0xFF
        val ec = buf.get().toInt() and 0xFF
        buf.short // reserved
        return ControlMessage.StopControlConnectionReply(rc, ec)
    }

    private fun decodeEchoReq(buf: ByteBuffer): ControlMessage.EchoRequest {
        return ControlMessage.EchoRequest(buf.int)
    }

    private fun decodeEchoRep(buf: ByteBuffer): ControlMessage.EchoReply {
        val id = buf.int
        val rc = buf.get().toInt() and 0xFF
        val ec = buf.get().toInt() and 0xFF
        buf.short // reserved
        return ControlMessage.EchoReply(id, rc, ec)
    }

    private fun decodeOcrq(buf: ByteBuffer): ControlMessage.OutgoingCallRequest {
        val callId = buf.short.toInt() and 0xFFFF
        val csn = buf.short.toInt() and 0xFFFF
        val minBps = buf.int
        val maxBps = buf.int
        val bt = buf.int
        val ft = buf.int
        val pktWin = buf.short.toInt() and 0xFFFF
        val pktDelay = buf.short.toInt() and 0xFFFF
        buf.short // phone number length
        buf.short // reserved1
        val phone = readFixedString(buf, 64)
        val sub = readFixedString(buf, 64)
        return ControlMessage.OutgoingCallRequest(callId, csn, minBps, maxBps, bt, ft, pktWin, pktDelay, phone, sub)
    }

    private fun decodeOcrp(buf: ByteBuffer): ControlMessage.OutgoingCallReply {
        val callId = buf.short.toInt() and 0xFFFF
        val peerCallId = buf.short.toInt() and 0xFFFF
        val rc = buf.get().toInt() and 0xFF
        val ec = buf.get().toInt() and 0xFF
        val cause = buf.short.toInt() and 0xFFFF
        val speed = buf.int
        val win = buf.short.toInt() and 0xFFFF
        val delay = buf.short.toInt() and 0xFFFF
        val phys = buf.int
        return ControlMessage.OutgoingCallReply(callId, peerCallId, rc, ec, cause, speed, win, delay, phys)
    }

    private fun decodeCdn(buf: ByteBuffer): ControlMessage.CallDisconnectNotify {
        val callId = buf.short.toInt() and 0xFFFF
        val rc = buf.get().toInt() and 0xFF
        val ec = buf.get().toInt() and 0xFF
        val cause = buf.short.toInt() and 0xFFFF
        buf.short // reserved
        val stats = ByteArray(128)
        buf.get(stats)
        return ControlMessage.CallDisconnectNotify(callId, rc, ec, cause, stats)
    }

    private fun decodeWen(buf: ByteBuffer): ControlMessage.WanErrorNotify {
        val pcid = buf.short.toInt() and 0xFFFF
        buf.short // reserved
        val crc = buf.int
        val fr = buf.int
        val ho = buf.int
        val bo = buf.int
        val to = buf.int
        val ae = buf.int
        return ControlMessage.WanErrorNotify(pcid, crc, fr, ho, bo, to, ae)
    }

    private fun decodeSli(buf: ByteBuffer): ControlMessage.SetLinkInfo {
        val pcid = buf.short.toInt() and 0xFFFF
        buf.short // reserved
        val sendAccm = buf.int
        val recvAccm = buf.int
        return ControlMessage.SetLinkInfo(pcid, sendAccm, recvAccm)
    }

    // -------- ASCII fixed-string helpers --------

    private fun putFixedString(buf: ByteBuffer, s: String, totalBytes: Int) {
        val raw = s.toByteArray(Charsets.US_ASCII)
        require(raw.size <= totalBytes) { "string '$s' too long (${raw.size} > $totalBytes)" }
        buf.put(raw)
        val pad = totalBytes - raw.size
        if (pad > 0) buf.put(ByteArray(pad))
    }

    private fun readFixedString(buf: ByteBuffer, totalBytes: Int): String {
        val raw = ByteArray(totalBytes)
        buf.get(raw)
        val end = raw.indexOf(0.toByte()).let { if (it < 0) raw.size else it }
        return String(raw, 0, end, Charsets.US_ASCII)
    }
}
