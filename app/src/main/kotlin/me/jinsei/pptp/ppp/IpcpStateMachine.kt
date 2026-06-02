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
 * IPCP state machine (RFC 1332) — same skeleton as the LCP one but with a
 * different option universe and a "let the server tell me my IP" opener
 * policy:
 *
 *   - First ConfReq carries IP-Address=0.0.0.0, Primary-DNS=0.0.0.0,
 *     Secondary-DNS=0.0.0.0 (i.e. "please tell me").
 *   - Server returns ConfNak with the addresses to use. We adopt them and
 *     resend ConfReq; server then ConfAcks.
 *   - In parallel the server sends its own ConfReq (carrying its router
 *     side IP). We ack it.
 *
 * Once both sides are Opened we publish [localConfig] for the VpnService to
 * pick up.
 */
class IpcpStateMachine(
    private val sender: (LcpPacket) -> Unit, // reusing LcpPacket — IPCP uses same header
    private val onStateChange: (State) -> Unit = {},
) {
    enum class State { Initial, ReqSent, AckRcvd, AckSent, Opened, Closing, Closed }

    object Option {
        const val IP_ADDRESS = 3
        const val PRIMARY_DNS = 129
        const val SECONDARY_DNS = 131
    }

    data class LocalConfig(
        val localIpv4: Int,
        val primaryDns: Int,
        val secondaryDns: Int,
    ) {
        fun isComplete(): Boolean = localIpv4 != 0
    }

    data class PeerConfig(val peerIpv4: Int)

    private val _state = MutableStateFlow(State.Initial)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile var localConfig: LocalConfig = LocalConfig(0, 0, 0)
        private set
    @Volatile var peerConfig: PeerConfig = PeerConfig(0)
        private set

    private val nextId = AtomicInteger(1)
    private var lastReqId: Int = -1
    private var lastReqBytes: ByteArray = ByteArray(0)
    private var retryCount: Int = 0
    private val maxConfigure = 10
    private val retryTimeoutMs: Long = 3_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var retryJob: Job? = null

    fun open() {
        if (_state.value != State.Initial && _state.value != State.Closed) return
        retryCount = 0
        sendOurConfigureRequest()
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

    fun onReceive(p: LcpPacket) {
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
            else -> Log.d(TAG, "IPCP unhandled code=${p.code}")
        }
    }

    fun shutdown() {
        retryJob?.cancel()
        scope.cancel()
    }

    // ----- handlers -----

    private fun handlePeerConfReq(p: LcpPacket) {
        val opts = try { LcpCodec.decodeOptions(p.data) } catch (e: Throwable) {
            Log.w(TAG, "bad IPCP ConfReq options", e); return
        }
        val ack = mutableListOf<LcpOption>()
        val nak = mutableListOf<LcpOption>()
        val rej = mutableListOf<LcpOption>()
        for (o in opts) {
            when (o.type) {
                Option.IP_ADDRESS -> if (o.value.size == 4) {
                    peerConfig = PeerConfig(readIp(o.value))
                    ack.add(o)
                } else rej.add(o)
                Option.PRIMARY_DNS, Option.SECONDARY_DNS -> if (o.value.size == 4) ack.add(o) else rej.add(o)
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
                    State.AckRcvd -> transitionTo(State.Opened)
                    else -> {}
                }
            }
        }
    }

    private fun handleConfAck(p: LcpPacket) {
        if (p.identifier != lastReqId) return
        retryJob?.cancel()
        retryCount = 0
        // Adopt our advertised addresses (server confirmed them).
        adoptOptionsAsLocal(LcpCodec.decodeOptions(p.data))
        when (_state.value) {
            State.ReqSent -> transitionTo(State.AckRcvd)
            State.AckSent -> transitionTo(State.Opened)
            else -> {}
        }
    }

    private fun handleConfNak(p: LcpPacket) {
        try {
            val suggested = LcpCodec.decodeOptions(p.data)
            adoptOptionsAsLocal(suggested)
        } catch (e: Throwable) { Log.w(TAG, "bad IPCP Nak", e) }
        if (retryCount < maxConfigure) {
            retryCount++
            sendOurConfigureRequest()
        } else {
            Log.w(TAG, "IPCP Nak max retries")
            close()
        }
    }

    private fun handleConfReject(p: LcpPacket) {
        // Peer doesn't recognize one of our options. Drop it from our proposal and retry.
        try {
            val rejected = LcpCodec.decodeOptions(p.data).map { it.type }.toSet()
            val remaining = ourOptions().filter { it.type !in rejected }
            if (remaining.isEmpty()) { close(); return }
            sendOurConfigureRequest(remaining)
        } catch (e: Throwable) {
            Log.w(TAG, "bad IPCP Reject", e); close()
        }
    }

    private fun adoptOptionsAsLocal(opts: List<LcpOption>) {
        var lc = localConfig
        for (o in opts) when (o.type) {
            Option.IP_ADDRESS -> if (o.value.size == 4) lc = lc.copy(localIpv4 = readIp(o.value))
            Option.PRIMARY_DNS -> if (o.value.size == 4) lc = lc.copy(primaryDns = readIp(o.value))
            Option.SECONDARY_DNS -> if (o.value.size == 4) lc = lc.copy(secondaryDns = readIp(o.value))
        }
        localConfig = lc
    }

    private fun ourOptions(): List<LcpOption> = listOf(
        ipOption(Option.IP_ADDRESS, localConfig.localIpv4),
        ipOption(Option.PRIMARY_DNS, localConfig.primaryDns),
        ipOption(Option.SECONDARY_DNS, localConfig.secondaryDns),
    )

    private fun sendOurConfigureRequest(options: List<LcpOption> = ourOptions()) {
        val data = LcpCodec.encodeOptions(options)
        val id = nextId.getAndIncrement() and 0xFF
        lastReqId = id
        lastReqBytes = data
        sender(LcpPacket(LcpCode.ConfigureRequest, id, data))
        scheduleRetry()
    }

    private fun scheduleRetry() {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(retryTimeoutMs)
            if (_state.value == State.ReqSent || _state.value == State.AckSent) {
                if (retryCount < maxConfigure) {
                    retryCount++
                    Log.d(TAG, "IPCP ConfReq retry #$retryCount")
                    sender(LcpPacket(LcpCode.ConfigureRequest, lastReqId, lastReqBytes))
                    scheduleRetry()
                } else {
                    close()
                }
            }
        }
    }

    private fun transitionTo(s: State) {
        if (_state.value == s) return
        Log.i(TAG, "IPCP ${_state.value} → $s  local=$localConfig peer=$peerConfig")
        _state.value = s
        onStateChange(s)
        if (s == State.Closed) {
            retryJob?.cancel()
            scope.cancel()
        }
    }

    companion object {
        private const val TAG = "Ipcp"

        fun ipOption(type: Int, ipPacked: Int): LcpOption {
            val v = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(ipPacked).array()
            return LcpOption(type, v)
        }

        fun readIp(b: ByteArray): Int =
            ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).int
    }
}
