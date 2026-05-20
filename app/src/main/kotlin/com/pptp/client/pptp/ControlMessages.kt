package com.pptp.client.pptp

/**
 * PPTP control-channel message types (RFC 2637 §2).
 *
 * All messages share a 12-byte common header:
 *   uint16 length            // total bytes incl. header
 *   uint16 pptpMessageType   // 1 = Control Message
 *   uint32 magicCookie       // 0x1A2B3C4D
 *   uint16 controlMessageType
 *   uint16 reserved0         // 0
 *
 * v0.0.4 implements only the subset required to bring a single outgoing
 * call up and tear it down: SCCRQ/SCCRP, OCRQ/OCRP, Echo, StopCCRQ/StopCCRP,
 * plus inbound CDN/SLI/WEN that the server may send asynchronously.
 *
 * Constants (RFC 2637 §3) live alongside the data classes so all wire-level
 * details are visible without jumping files.
 */

const val PPTP_MAGIC_COOKIE: Int = 0x1A2B3C4D
const val PPTP_MESSAGE_TYPE_CONTROL: Int = 1
const val PPTP_COMMON_HEADER_BYTES: Int = 12

/** Control message type IDs. */
object PptpCmType {
    const val SCCRQ = 1     // Start-Control-Connection-Request
    const val SCCRP = 2     // Start-Control-Connection-Reply
    const val StopCCRQ = 3
    const val StopCCRP = 4
    const val EchoRequest = 5
    const val EchoReply = 6
    const val OCRQ = 7      // Outgoing-Call-Request
    const val OCRP = 8      // Outgoing-Call-Reply
    const val ICRQ = 9      // Incoming-Call-Request    (server → client; we just NAK)
    const val ICRP = 10
    const val ICCN = 11
    const val CCRQ = 12     // Call-Clear-Request
    const val CDN = 13      // Call-Disconnect-Notify    (server → client async)
    const val WEN = 14      // WAN-Error-Notify          (server → client async)
    const val SLI = 15      // Set-Link-Info             (server → client async)
}

/** Framing capability bitmask (RFC 2637 §2.2). */
object Framing {
    const val ASYNC = 0x1
    const val SYNC = 0x2
}

/** Bearer capability bitmask. */
object Bearer {
    const val ANALOG = 0x1
    const val DIGITAL = 0x2
}

/** Generic result codes — message-type specific tables in RFC 2637 §2.3+. */
object ResultCode {
    const val OK = 1
    const val GENERAL_ERROR = 2
    const val COMMAND_CHANNEL_EXISTS = 3  // SCCRP only
    const val NOT_AUTHORIZED = 4
    const val UNKNOWN_PROTOCOL_VERSION = 5

    // OCRP-specific
    const val OCRP_NO_CARRIER = 3
    const val OCRP_BUSY = 4
    const val OCRP_NO_DIAL_TONE = 5
    const val OCRP_TIMEOUT = 6
    const val OCRP_DO_NOT_ACCEPT = 7

    // StopCCRP reasons
    const val STOP_NONE = 1
    const val STOP_STOP_PROTOCOL = 2
    const val STOP_LOCAL_SHUTDOWN = 3
}

/** Common header parsed off the wire. */
data class CommonHeader(
    val length: Int,
    val pptpMessageType: Int,
    val magicCookie: Int,
    val controlMessageType: Int,
)

/** Sealed hierarchy of decoded / decodable control messages. */
sealed class ControlMessage {
    abstract val type: Int

    /** v0.0.4 sends: SCCRQ, OCRQ, EchoRequest, EchoReply, StopCCRQ */

    data class StartControlConnectionRequest(
        val protocolVersion: Int = 0x0100,
        val framingCapabilities: Int = Framing.SYNC,
        val bearerCapabilities: Int = Bearer.ANALOG or Bearer.DIGITAL,
        val maximumChannels: Int = 0,
        val firmwareRevision: Int = 1,
        val hostName: String = "android-pptp",
        val vendorString: String = "pptp-client",
    ) : ControlMessage() {
        override val type get() = PptpCmType.SCCRQ
    }

    data class StartControlConnectionReply(
        val protocolVersion: Int,
        val resultCode: Int,
        val errorCode: Int,
        val framingCapabilities: Int,
        val bearerCapabilities: Int,
        val maximumChannels: Int,
        val firmwareRevision: Int,
        val hostName: String,
        val vendorString: String,
    ) : ControlMessage() {
        override val type get() = PptpCmType.SCCRP
    }

    data class StopControlConnectionRequest(
        val reason: Int = ResultCode.STOP_NONE,
    ) : ControlMessage() {
        override val type get() = PptpCmType.StopCCRQ
    }

    data class StopControlConnectionReply(
        val resultCode: Int,
        val errorCode: Int,
    ) : ControlMessage() {
        override val type get() = PptpCmType.StopCCRP
    }

    data class EchoRequest(val identifier: Int) : ControlMessage() {
        override val type get() = PptpCmType.EchoRequest
    }

    data class EchoReply(
        val identifier: Int,
        val resultCode: Int,
        val errorCode: Int,
    ) : ControlMessage() {
        override val type get() = PptpCmType.EchoReply
    }

    data class OutgoingCallRequest(
        val callId: Int,
        val callSerialNumber: Int,
        val minimumBps: Int = 0,
        val maximumBps: Int = 100_000_000,
        val bearerType: Int = Bearer.ANALOG or Bearer.DIGITAL,
        val framingType: Int = Framing.SYNC,
        val pktWindowSize: Int = 64,
        val pktProcessingDelay: Int = 0,
        val phoneNumber: String = "",
        val subAddress: String = "",
    ) : ControlMessage() {
        override val type get() = PptpCmType.OCRQ
    }

    data class OutgoingCallReply(
        val callId: Int,           // server's Call-ID (we use this when sending GRE to server)
        val peerCallId: Int,       // our Call-ID echoed back
        val resultCode: Int,
        val errorCode: Int,
        val causeCode: Int,
        val connectSpeed: Int,
        val pktWindowSize: Int,
        val pktProcessingDelay: Int,
        val physicalChannelId: Int,
    ) : ControlMessage() {
        override val type get() = PptpCmType.OCRP
    }

    /** Server may send these unsolicited. */
    data class CallDisconnectNotify(
        val callId: Int,
        val resultCode: Int,
        val errorCode: Int,
        val causeCode: Int,
        val callStatistics: ByteArray, // 128 bytes — opaque, we just log
    ) : ControlMessage() {
        override val type get() = PptpCmType.CDN
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CallDisconnectNotify) return false
            return callId == other.callId && resultCode == other.resultCode &&
                errorCode == other.errorCode && causeCode == other.causeCode &&
                callStatistics.contentEquals(other.callStatistics)
        }
        override fun hashCode(): Int {
            var r = callId
            r = 31 * r + resultCode
            r = 31 * r + errorCode
            r = 31 * r + causeCode
            r = 31 * r + callStatistics.contentHashCode()
            return r
        }
    }

    data class WanErrorNotify(
        val peerCallId: Int,
        val crcErrors: Int,
        val framingErrors: Int,
        val hardwareOverruns: Int,
        val bufferOverruns: Int,
        val timeoutErrors: Int,
        val alignmentErrors: Int,
    ) : ControlMessage() {
        override val type get() = PptpCmType.WEN
    }

    data class SetLinkInfo(
        val peerCallId: Int,
        val sendAccm: Int,
        val recvAccm: Int,
    ) : ControlMessage() {
        override val type get() = PptpCmType.SLI
    }

    /** Catch-all for messages we recognize by type but don't fully model. */
    data class Unknown(
        val controlMessageType: Int,
        val raw: ByteArray,
    ) : ControlMessage() {
        override val type get() = controlMessageType
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Unknown) return false
            return controlMessageType == other.controlMessageType && raw.contentEquals(other.raw)
        }
        override fun hashCode(): Int = 31 * controlMessageType + raw.contentHashCode()
    }
}

/** Expected wire size for a given message type. Used by the encoder for `length`. */
internal fun expectedMessageSize(cmType: Int): Int = when (cmType) {
    PptpCmType.SCCRQ, PptpCmType.SCCRP -> 156
    PptpCmType.StopCCRQ -> 16
    PptpCmType.StopCCRP -> 16
    PptpCmType.EchoRequest -> 16
    PptpCmType.EchoReply -> 20
    PptpCmType.OCRQ -> 168
    PptpCmType.OCRP -> 32
    PptpCmType.CDN -> 148
    PptpCmType.WEN -> 40
    PptpCmType.SLI -> 24
    else -> -1
}
