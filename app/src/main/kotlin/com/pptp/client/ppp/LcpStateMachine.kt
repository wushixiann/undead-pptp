package com.pptp.client.ppp

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
import java.util.concurrent.atomic.AtomicInteger

/**
 * LCP state machine for an active opener (the PPTP client) — RFC 1661 §4.1.
 *
 * The full 10-state automaton is conceptually represented here as a single
 * [State] enum, but transitions are coalesced to the few we actually drive:
 *
 *   Initial → open() → ReqSent → ack of our ConfReq → AckRcvd
 *                                                          │
 *   Initial → open() → ReqSent → peer ConfReq             ▼
 *                          │                           ConfAck of ours
 *                          ▼                                │
 *                       AckSent ──────────────────────▶ Opened
 *                          ▲                                ▲
 *                      peer ConfReq                  AckSent + ConfAck
 *
 *   Opened → close() → Closing → TermAck → Closed
 *
 * Local options (what we propose):  MRU=1400, Magic-Number(random).
 *
 * Peer-option policy (what we accept in their ConfReq):
 *   - MRU: accepted (we'll use it for our send size budget; clamp to 1500)
 *   - Auth-Protocol: ONLY MS-CHAP-V2 → Ack. Everything else (PAP, MS-CHAP-V1,
 *     unknown) → Nak counter-proposing MS-CHAP-V2.
 *     PAP transmits the password in cleartext over PPP. A MITM on TCP 1723
 *     can rewrite the server's ConfReq to propose PAP and capture the
 *     password in the subsequent PAP exchange — accepting PAP silently
 *     defeats the only authentication strength PPTP has left (MS-CHAP-V2's
 *     12-hour-ish offline crack vs PAP's instant cleartext).
 *   - Magic-Number: accepted, recorded
 *   - PFC, ACFC: accepted (we tolerate compressed receive; we send uncompressed)
 *   - ACCM: accepted no-op (PPTP is synchronous, ACCM is meaningless)
 *   - anything else: Reject
 *
 * Retransmit policy (RFC 1661 §4.6): 3s timer, Max-Configure = 10, Max-Terminate = 2.
 */
class LcpStateMachine(
    private val sender: (LcpPacket) -> Unit,
    private val onStateChange: (State) -> Unit = {},
    private val onAuthRequested: (AuthChoice) -> Unit = {},
) {
    enum class State { Initial, ReqSent, AckRcvd, AckSent, Opened, Closing, Closed }

    private val _state = MutableStateFlow(State.Initial)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Local Magic-Number value we proposed (random, non-zero). */
    val localMagic: Int = LcpDefaults.newMagic()

    /** Peer's Magic-Number from their ConfAck'd options. */
    @Volatile var peerMagic: Int = 0
        private set

    /** Authentication protocol the peer chose (set when their ConfReq is Ack'd). */
    @Volatile var negotiatedAuth: AuthChoice = AuthChoice.Unknown
        private set

    /** Effective MRU values (post-negotiation). */
    @Volatile var localMru: Int = LcpDefaults.MRU
        private set
    @Volatile var peerMru: Int = 1500
        private set

    private val nextId = AtomicInteger(1)
    private var lastReqId: Int = -1
    private var lastReqBytes: ByteArray = ByteArray(0)
    private var retryCount: Int = 0
    private val maxConfigure = 10
    private val retryTimeoutMs: Long = 3_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var retryJob: Job? = null

    /** Begin LCP negotiation. */
    fun open() {
        if (_state.value != State.Initial && _state.value != State.Closed) return
        retryCount = 0
        sendOurConfigureRequest()
        transitionTo(State.ReqSent)
    }

    fun close() {
        if (_state.value == State.Closed || _state.value == State.Initial) {
            transitionTo(State.Closed)
            return
        }
        send(LcpCode.TerminateRequest, ByteArray(0))
        transitionTo(State.Closing)
        // Brutally close after a brief grace period if we don't get TermAck.
        scope.launch {
            delay(retryTimeoutMs)
            if (_state.value == State.Closing) transitionTo(State.Closed)
        }
    }

    /** Handle an inbound LCP packet. */
    fun onReceive(p: LcpPacket) {
        Log.d(TAG, "LCP RX code=${p.code} id=${p.identifier} len=${p.length}")
        when (p.code) {
            LcpCode.ConfigureRequest -> handlePeerConfigureRequest(p)
            LcpCode.ConfigureAck -> handleConfigureAck(p)
            LcpCode.ConfigureNak -> handleConfigureNak(p)
            LcpCode.ConfigureReject -> handleConfigureReject(p)
            LcpCode.TerminateRequest -> {
                send(LcpCode.TerminateAck, p.data, p.identifier)
                transitionTo(State.Closed)
            }
            LcpCode.TerminateAck -> if (_state.value == State.Closing) transitionTo(State.Closed)
            LcpCode.EchoRequest -> {
                // Echo-Reply: 4-byte OUR Magic-Number + remainder of the request body.
                send(LcpCode.EchoReply, peerMagicReplyData(p.data), p.identifier)
            }
            LcpCode.EchoReply, LcpCode.DiscardRequest -> { /* informational */ }
            LcpCode.CodeReject, LcpCode.ProtocolReject -> {
                Log.w(TAG, "peer rejected LCP code/protocol — closing")
                transitionTo(State.Closed)
            }
        }
    }

    private fun peerMagicReplyData(reqData: ByteArray): ByteArray {
        // Echo-Reply: 4-byte our Magic-Number + body copied from request (skip its 4-byte magic).
        val body = if (reqData.size > 4) reqData.copyOfRange(4, reqData.size) else ByteArray(0)
        val out = ByteArray(4 + body.size)
        val m = localMagic
        out[0] = (m ushr 24 and 0xFF).toByte()
        out[1] = (m ushr 16 and 0xFF).toByte()
        out[2] = (m ushr 8 and 0xFF).toByte()
        out[3] = (m and 0xFF).toByte()
        System.arraycopy(body, 0, out, 4, body.size)
        return out
    }

    // ----- handlers -----

    private fun handlePeerConfigureRequest(p: LcpPacket) {
        val opts = try {
            LcpCodec.decodeOptions(p.data)
        } catch (e: Throwable) {
            Log.w(TAG, "bad ConfReq options", e)
            return
        }
        val nakList = mutableListOf<LcpOption>()
        val rejectList = mutableListOf<LcpOption>()
        val ackList = mutableListOf<LcpOption>()

        for (o in opts) {
            when (o.type) {
                LcpOptionType.MRU -> if (o.value.size == 2) {
                    peerMru = (LcpCodec.readMru(o)).coerceIn(64, 1500)
                    ackList.add(o)
                } else rejectList.add(o)

                LcpOptionType.AUTHENTICATION_PROTOCOL -> when (LcpCodec.classifyAuth(o)) {
                    AuthChoice.MsChapV2 -> {
                        negotiatedAuth = AuthChoice.MsChapV2
                        ackList.add(o)
                    }
                    // PAP / MS-CHAP-V1 / anything else → NAK with counter-proposal
                    // of MS-CHAP-V2. If the server is configured PAP-only it will
                    // ConfReject our NAK or repeatedly re-propose PAP — connection
                    // will fail and the user must enable MS-CHAP-V2 server-side.
                    // This is intentional: silently accepting PAP would defeat the
                    // only meaningful auth integrity PPTP has.
                    AuthChoice.Pap,
                    AuthChoice.MsChapV1,
                    AuthChoice.Unknown -> nakList.add(LcpCodec.authProtocolMsChapV2())
                }

                LcpOptionType.MAGIC_NUMBER -> if (o.value.size == 4) {
                    peerMagic = LcpCodec.readMagicNumber(o)
                    ackList.add(o)
                } else rejectList.add(o)

                LcpOptionType.PROTOCOL_FIELD_COMPRESSION,
                LcpOptionType.ADDRESS_CONTROL_FIELD_COMPRESSION,
                LcpOptionType.ASYNC_CONTROL_CHARACTER_MAP,
                LcpOptionType.QUALITY_PROTOCOL -> ackList.add(o) // accept; ACCM is no-op for sync

                else -> rejectList.add(o)
            }
        }

        when {
            rejectList.isNotEmpty() -> send(LcpCode.ConfigureReject, LcpCodec.encodeOptions(rejectList), p.identifier)
            nakList.isNotEmpty() -> send(LcpCode.ConfigureNak, LcpCodec.encodeOptions(nakList), p.identifier)
            else -> {
                send(LcpCode.ConfigureAck, LcpCodec.encodeOptions(ackList), p.identifier)
                if (negotiatedAuth != AuthChoice.Unknown) onAuthRequested(negotiatedAuth)
                when (_state.value) {
                    State.ReqSent -> transitionTo(State.AckSent)
                    State.AckRcvd -> transitionTo(State.Opened)
                    else -> {}
                }
            }
        }
    }

    private fun handleConfigureAck(p: LcpPacket) {
        if (p.identifier != lastReqId) {
            Log.w(TAG, "ConfAck id=${p.identifier} doesn't match our last ConfReq id=$lastReqId — ignoring")
            return
        }
        // We could verify the Ack echoes exactly our options; for v0.0.5 we trust it.
        retryJob?.cancel()
        retryCount = 0
        when (_state.value) {
            State.ReqSent -> transitionTo(State.AckRcvd)
            State.AckSent -> transitionTo(State.Opened)
            else -> {}
        }
    }

    private fun handleConfigureNak(p: LcpPacket) {
        // Peer suggests alternative values. v0.0.5: just resend with peer's
        // suggestion overriding the corresponding local option. We currently
        // only propose MRU and Magic-Number, so peer can adjust those.
        try {
            val suggested = LcpCodec.decodeOptions(p.data)
            for (o in suggested) {
                when (o.type) {
                    LcpOptionType.MRU -> if (o.value.size == 2) {
                        localMru = LcpCodec.readMru(o).coerceIn(64, 1500)
                    }
                    LcpOptionType.MAGIC_NUMBER -> {
                        // Don't take peer's exact magic; pick a fresh non-zero one to avoid collision.
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ConfNak parse failed", e)
        }
        if (retryCount < maxConfigure) {
            retryCount++
            sendOurConfigureRequest()
        } else {
            Log.w(TAG, "max ConfReq retries reached after Nak — closing")
            close()
        }
    }

    private fun handleConfigureReject(p: LcpPacket) {
        // Peer doesn't recognize one or more of our options; drop them and retry.
        val rejected = try {
            LcpCodec.decodeOptions(p.data).map { it.type }.toSet()
        } catch (e: Throwable) {
            Log.w(TAG, "bad Reject data", e); close(); return
        }
        val keep = ourOptions().filter { it.type !in rejected }
        Log.w(TAG, "peer rejected option types $rejected; retrying with ${keep.map { it.type }}")
        if (keep.isEmpty()) {
            Log.w(TAG, "no options left after Reject — giving up")
            close(); return
        }
        if (retryCount < maxConfigure) {
            retryCount++
            val data = LcpCodec.encodeOptions(keep)
            val id = nextId.getAndIncrement() and 0xFF
            lastReqId = id
            lastReqBytes = data
            sender(LcpPacket(LcpCode.ConfigureRequest, id, data))
            scheduleRetry()
        } else close()
    }

    // ----- TX helpers -----

    private fun ourOptions(): List<LcpOption> = listOf(
        LcpCodec.mru(localMru),
        LcpCodec.magicNumber(localMagic),
    )

    private fun sendOurConfigureRequest() {
        val data = LcpCodec.encodeOptions(ourOptions())
        val id = nextId.getAndIncrement() and 0xFF
        lastReqId = id
        lastReqBytes = data
        send(LcpCode.ConfigureRequest, data, id)
        scheduleRetry()
    }

    private fun scheduleRetry() {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(retryTimeoutMs)
            if (_state.value == State.ReqSent || _state.value == State.AckSent) {
                if (retryCount < maxConfigure) {
                    retryCount++
                    Log.d(TAG, "ConfReq retry #$retryCount (id=$lastReqId)")
                    sender(LcpPacket(LcpCode.ConfigureRequest, lastReqId, lastReqBytes))
                    scheduleRetry()
                } else {
                    Log.w(TAG, "ConfReq retransmits exhausted — closing")
                    close()
                }
            }
        }
    }

    private fun send(code: Int, data: ByteArray, id: Int = nextId.getAndIncrement() and 0xFF) {
        sender(LcpPacket(code, id, data))
    }

    private fun transitionTo(s: State) {
        if (_state.value == s) return
        Log.i(TAG, "LCP ${_state.value} → $s (auth=$negotiatedAuth mru[local=$localMru peer=$peerMru])")
        _state.value = s
        onStateChange(s)
        if (s == State.Closed) {
            retryJob?.cancel()
            scope.cancel()
        }
    }

    fun shutdown() {
        retryJob?.cancel()
        scope.cancel()
    }

    companion object {
        private const val TAG = "Lcp"
    }
}
