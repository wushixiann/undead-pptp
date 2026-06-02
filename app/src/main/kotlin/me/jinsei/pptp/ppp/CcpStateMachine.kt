// SPDX-License-Identifier: GPL-3.0-or-later
package me.jinsei.pptp.ppp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * CCP (Compression Control Protocol — RFC 1962) negotiating MPPE-128 stateless.
 *
 * Packet structure reuses LCP encoding (Code/Id/Length/Data). CCP-specific
 * codes (in addition to standard 1-9 for Configure-* / Terminate-* / Reject):
 *   - 14: Reset-Request (sent when decryption desyncs)
 *   - 15: Reset-Ack
 *
 * MPPE option type = 18, value is a 4-byte bitmask:
 *
 *   bit 24 (S) = stateless mode
 *   bit 26 (L) = 40-bit key
 *   bit 27 (S) = 56-bit key       — note RFC names overlap; the 56-bit bit is
 *                                    sometimes denoted "M"; layout per RFC 3078
 *   bit 28 (H) = 128-bit key
 *   bits 0-23 = "Supported bits" — usually 0
 *
 * We propose: MPPE-128 stateless only. We Nak any server proposal that
 * conflicts (e.g. wants 40/56-bit or stateful).
 */
class CcpStateMachine(
    private val sender: (LcpPacket) -> Unit,
    private val onOpened: () -> Unit = {},
    private val onClosed: () -> Unit = {},
    private val onResetRequest: (id: Int) -> Unit = {},
    private val onResetAck: (id: Int) -> Unit = {},
) {
    enum class State { Initial, ReqSent, AckRcvd, AckSent, Opened, Closing, Closed }

    private val _state = MutableStateFlow(State.Initial)
    val state: StateFlow<State> = _state.asStateFlow()

    private val nextId = AtomicInteger(1)
    private var lastReqId: Int = -1
    private var retryCount = 0
    private val maxConfigure = 10
    private val retryTimeoutMs: Long = 3_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var retryJob: Job? = null

    fun open() {
        if (_state.value != State.Initial && _state.value != State.Closed) return
        retryCount = 0
        sendConfReq()
        transitionTo(State.ReqSent)
    }

    fun close() {
        if (_state.value == State.Closed || _state.value == State.Initial) {
            transitionTo(State.Closed); return
        }
        sender(LcpPacket(LcpCode.TerminateRequest, nextId.getAndIncrement() and 0xFF, ByteArray(0)))
        transitionTo(State.Closing)
        scope.launch {
            delay(retryTimeoutMs)
            if (_state.value == State.Closing) transitionTo(State.Closed)
        }
    }

    fun shutdown() {
        retryJob?.cancel()
        scope.cancel()
    }

    /** Called by session when MPPE detects a coherency gap and we need to ask the peer to flush. */
    fun sendResetRequest() {
        sender(LcpPacket(CODE_RESET_REQUEST, nextId.getAndIncrement() and 0xFF, ByteArray(0)))
    }

    fun onReceive(p: LcpPacket) {
        Log.d(TAG, "CCP RX code=${p.code} id=${p.identifier}")
        when (p.code) {
            LcpCode.ConfigureRequest -> handlePeerConfReq(p)
            LcpCode.ConfigureAck -> handleConfAck(p)
            LcpCode.ConfigureNak -> handleConfNak(p)
            LcpCode.ConfigureReject -> handleConfReject(p)
            LcpCode.TerminateRequest -> {
                sender(LcpPacket(LcpCode.TerminateAck, p.identifier, p.data))
                transitionTo(State.Closed)
            }
            LcpCode.TerminateAck -> if (_state.value == State.Closing) transitionTo(State.Closed)
            CODE_RESET_REQUEST -> {
                onResetRequest(p.identifier)
                sender(LcpPacket(CODE_RESET_ACK, p.identifier, ByteArray(0)))
            }
            CODE_RESET_ACK -> onResetAck(p.identifier)
            else -> Log.d(TAG, "CCP unhandled code=${p.code}")
        }
    }

    private fun handlePeerConfReq(p: LcpPacket) {
        val opts = try { LcpCodec.decodeOptions(p.data) } catch (e: Throwable) {
            Log.w(TAG, "bad CCP ConfReq options", e); return
        }
        val ack = mutableListOf<LcpOption>()
        val nak = mutableListOf<LcpOption>()
        val rej = mutableListOf<LcpOption>()
        // The only bitset we can interoperate with: MPPE-128 stateless,
        // NO MPPC compression, NO 40/56-bit fallback, NO obsolete D bit.
        val acceptable = FLAG_128_BIT or FLAG_STATELESS
        for (o in opts) {
            when (o.type) {
                OPTION_MPPE -> {
                    if (o.value.size != 4) { rej.add(o); continue }
                    val flags = ByteBuffer.wrap(o.value).order(ByteOrder.BIG_ENDIAN).int
                    if (flags == acceptable) {
                        // Exact match — Ack as-is per RFC 1661.
                        ack.add(o)
                    } else {
                        // Any deviation (peer wants compression / different key size /
                        // stateful) → Nak with our preferred bitset. ACKing as-is when
                        // peer set the C (MPPC compression) bit was the v0.2.0 bug:
                        // it made the server compress packets after encryption, while
                        // we only un-encrypted; the "inner protocol" was actually
                        // a byte from MPPC compression header → looks like garbage
                        // (or like a high-byte=0x00 mystery protocol).
                        Log.w(TAG, "peer MPPE bits ${"0x%08x".format(flags)} ≠ wanted ${"0x%08x".format(acceptable)}; nak")
                        nak.add(mppeOption(acceptable))
                    }
                }
                else -> rej.add(o)
            }
        }
        when {
            rej.isNotEmpty() -> sender(LcpPacket(LcpCode.ConfigureReject, p.identifier, LcpCodec.encodeOptions(rej)))
            nak.isNotEmpty() -> sender(LcpPacket(LcpCode.ConfigureNak, p.identifier, LcpCodec.encodeOptions(nak)))
            else -> {
                sender(LcpPacket(LcpCode.ConfigureAck, p.identifier, LcpCodec.encodeOptions(ack)))
                when (_state.value) {
                    State.ReqSent -> transitionTo(State.AckSent)
                    State.AckRcvd -> transitionTo(State.Opened).also { onOpened() }
                    else -> {}
                }
            }
        }
    }

    private fun handleConfAck(p: LcpPacket) {
        if (p.identifier != lastReqId) return
        retryJob?.cancel()
        retryCount = 0
        when (_state.value) {
            State.ReqSent -> transitionTo(State.AckRcvd)
            State.AckSent -> { transitionTo(State.Opened); onOpened() }
            else -> {}
        }
    }

    private fun handleConfNak(p: LcpPacket) {
        // Peer wants a different MPPE flag set. We only support 128 stateless,
        // so if their proposal differs we just resend ours unchanged a few times.
        if (retryCount < maxConfigure) {
            retryCount++
            sendConfReq()
        } else {
            Log.w(TAG, "CCP Nak max retries — closing")
            close()
        }
    }

    private fun handleConfReject(p: LcpPacket) {
        // Peer rejected MPPE option. Can't negotiate encryption; give up.
        Log.w(TAG, "CCP peer rejected MPPE — closing")
        close()
    }

    private fun sendConfReq() {
        val opt = mppeOption(FLAG_128_BIT or FLAG_STATELESS)
        val data = LcpCodec.encodeOptions(listOf(opt))
        val id = nextId.getAndIncrement() and 0xFF
        lastReqId = id
        sender(LcpPacket(LcpCode.ConfigureRequest, id, data))
        scheduleRetry(id, data)
    }

    private fun scheduleRetry(id: Int, data: ByteArray) {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(retryTimeoutMs)
            if (_state.value == State.ReqSent || _state.value == State.AckSent) {
                if (retryCount < maxConfigure) {
                    retryCount++
                    sender(LcpPacket(LcpCode.ConfigureRequest, id, data))
                    scheduleRetry(id, data)
                } else close()
            }
        }
    }

    private fun transitionTo(s: State) {
        if (_state.value == s) return
        Log.i(TAG, "CCP ${_state.value} → $s")
        _state.value = s
        if (s == State.Closed) {
            retryJob?.cancel()
            scope.cancel()
            onClosed()
        }
    }

    companion object {
        private const val TAG = "Ccp"

        const val CODE_RESET_REQUEST = 14
        const val CODE_RESET_ACK = 15
        const val OPTION_MPPE = 18

        // RFC 3078 MPPE/MPPC bitmask bits (in network/big-endian 4-byte field).
        // Wire format byte layout (4 bytes BE):
        //   byte 0: bit 0 (LSB) = S (stateless)  → 0x01000000
        //   byte 3:
        //     bit 7 (MSB) = M (56-bit)            → 0x00000080
        //     bit 6        = H (128-bit)           → 0x00000040
        //     bit 5        = L (40-bit)            → 0x00000020
        //     bit 4        = D (obsolete)          → 0x00000010
        //     bit 0 (LSB)  = C (MPPC compression)  → 0x00000001
        const val FLAG_STATELESS = 0x01000000  // S
        const val FLAG_56_BIT    = 0x00000080  // M
        const val FLAG_128_BIT   = 0x00000040  // H
        const val FLAG_40_BIT    = 0x00000020  // L
        const val FLAG_OBSOLETE  = 0x00000010  // D
        const val FLAG_MPPC      = 0x00000001  // C — MPPC compression (we do NOT support)

        fun mppeOption(flags: Int): LcpOption {
            val v = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(flags).array()
            return LcpOption(OPTION_MPPE, v)
        }
    }
}
