// SPDX-License-Identifier: GPL-3.0-or-later
package me.jinsei.pptp.pptp

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives the PPTP control channel (TCP/1723) state machine per RFC 2637 §2.
 *
 *      IDLE ──connect()──▶ CONNECTING ──TCP up──▶ WAIT_SCCRP ──SCCRP(OK)──▶ ESTABLISHED
 *                              │                       │                         │
 *                              │ TCP fail              │ SCCRP(fail)/timeout     │ openCall()
 *                              ▼                       ▼                         ▼
 *                            CLOSED ◀──teardown──── CLOSED ◀──teardown────  WAIT_OCRP
 *                                                                              │
 *                                                                              ▼
 *                              CALL_UP ──disconnect() / CDN / StopCCRQ──▶ STOPPING ──▶ CLOSED
 *
 * Echo-Request is sent every [echoIntervalMs]; a missed Echo-Reply does NOT
 * (in v0.0.4) tear the link down — we just count failures and surface them.
 * v0.0.7 will add explicit retry/reconnect policy.
 */
class ControlChannel(
    private val echoIntervalMs: Long = 60_000,
    private val rpcTimeoutMs: Long = 8_000,
    private val socketProtector: ((Socket) -> Unit)? = null,
) {

    enum class State { Idle, Connecting, WaitSccrp, Established, WaitOcrp, CallUp, Stopping, Closed }

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile var session: SessionState? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var readerJob: Job? = null
    private var echoJob: Job? = null

    /** Outstanding per-Echo-Request matchers, keyed by identifier. */
    private val pendingEchoes = HashMap<Int, CompletableDeferred<ControlMessage.EchoReply>>()
    private val echoIdGen = AtomicInteger(0)

    /** Promises woken up by the reader as control messages arrive. */
    private var sccrpPromise: CompletableDeferred<ControlMessage.StartControlConnectionReply>? = null
    private var ocrpPromise: CompletableDeferred<ControlMessage.OutgoingCallReply>? = null
    private var stopReplyPromise: CompletableDeferred<ControlMessage.StopControlConnectionReply>? = null

    /**
     * Connect TCP, send SCCRQ, wait for SCCRP. Returns the SCCRP on success;
     * throws on TCP failure, decode error, server-side reject, or RPC timeout.
     */
    suspend fun connect(host: String, port: Int = 1723): ControlMessage.StartControlConnectionReply {
        check(_state.value == State.Idle || _state.value == State.Closed) {
            "ControlChannel already running (state=${_state.value})"
        }
        _state.value = State.Connecting
        _lastError.value = null
        val s = try {
            withContext(Dispatchers.IO) {
                Socket().apply {
                    tcpNoDelay = true
                    keepAlive = true
                    // Protect must be called BEFORE connect to prevent the
                    // initial TCP handshake from looping through a (future) VPN.
                    socketProtector?.invoke(this)
                    connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                }
            }
        } catch (e: Throwable) {
            _state.value = State.Closed
            _lastError.value = "TCP 连接失败：${e.message ?: e.javaClass.simpleName}"
            throw e
        }
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
        readerJob = scope.launch { readLoop() }
        echoJob = scope.launch { echoLoop() }

        val promise = CompletableDeferred<ControlMessage.StartControlConnectionReply>()
        sccrpPromise = promise
        _state.value = State.WaitSccrp
        send(ControlMessage.StartControlConnectionRequest())

        val reply = withTimeoutOrNull(rpcTimeoutMs) { promise.await() }
            ?: run {
                teardown("等待 SCCRP 超时（${rpcTimeoutMs}ms）")
                throw IOException("SCCRP timeout")
            }
        if (reply.resultCode != ResultCode.OK) {
            teardown("服务器拒绝 SCCRQ：result=${reply.resultCode} error=${reply.errorCode}")
            throw IOException("SCCRP non-OK: result=${reply.resultCode}")
        }
        _state.value = State.Established
        return reply
    }

    /**
     * Send OCRQ with a freshly-allocated session and wait for OCRP.
     * On success the [session] property holds the negotiated Call-IDs.
     */
    suspend fun openCall(): ControlMessage.OutgoingCallReply {
        check(_state.value == State.Established) { "control not established (state=${_state.value})" }
        val s = CallIdAllocator.newSession()
        session = s
        val promise = CompletableDeferred<ControlMessage.OutgoingCallReply>()
        ocrpPromise = promise
        _state.value = State.WaitOcrp
        send(ControlMessage.OutgoingCallRequest(callId = s.localCallId, callSerialNumber = s.callSerialNumber))

        val reply = withTimeoutOrNull(rpcTimeoutMs) { promise.await() }
            ?: run {
                teardown("等待 OCRP 超时")
                throw IOException("OCRP timeout")
            }
        if (reply.resultCode != ResultCode.OK) {
            teardown("服务器拒绝 OCRQ：result=${reply.resultCode} cause=${reply.causeCode}")
            throw IOException("OCRP non-OK: result=${reply.resultCode}")
        }
        if (reply.peerCallId != s.localCallId) {
            Log.w(TAG, "OCRP peer-call-id mismatch: got ${reply.peerCallId} expected ${s.localCallId}")
        }
        s.peerCallId = reply.callId
        _state.value = State.CallUp
        return reply
    }

    /**
     * Initiate a graceful shutdown. Sends StopCCRQ, waits up to [rpcTimeoutMs]
     * for StopCCRP, then closes the TCP socket. Safe to call from any state.
     */
    suspend fun disconnect() {
        if (_state.value == State.Idle || _state.value == State.Closed) return
        val promise = CompletableDeferred<ControlMessage.StopControlConnectionReply>()
        stopReplyPromise = promise
        _state.value = State.Stopping
        try {
            send(ControlMessage.StopControlConnectionRequest())
        } catch (_: Throwable) {
            // Socket may already be dead; fall through to teardown.
        }
        withTimeoutOrNull(rpcTimeoutMs) { promise.await() }
        teardown("用户断开")
    }

    /**
     * Manually trigger one Echo-Request and wait for its reply.
     * Returns null on timeout, the reply otherwise.
     */
    suspend fun ping(): ControlMessage.EchoReply? {
        if (_state.value !in arrayOf(State.Established, State.WaitOcrp, State.CallUp)) return null
        val id = echoIdGen.incrementAndGet()
        val promise = CompletableDeferred<ControlMessage.EchoReply>()
        synchronized(pendingEchoes) { pendingEchoes[id] = promise }
        send(ControlMessage.EchoRequest(id))
        val reply = withTimeoutOrNull(rpcTimeoutMs) { promise.await() }
        synchronized(pendingEchoes) { pendingEchoes.remove(id) }
        return reply
    }

    // ----------------- internal -----------------

    private fun send(msg: ControlMessage) {
        val out = output ?: throw IOException("socket not open")
        val bytes = ControlCodec.encode(msg)
        synchronized(out) {
            out.write(bytes)
            out.flush()
        }
        Log.d(TAG, "TX ${msg::class.simpleName} (${bytes.size}B)")
    }

    private suspend fun readLoop() = withContext(Dispatchers.IO) {
        val input = this@ControlChannel.input ?: return@withContext
        try {
            while (isActive) {
                val msg = readOneMessage(input) ?: break
                Log.d(TAG, "RX ${msg::class.simpleName}")
                dispatch(msg)
            }
        } catch (e: Throwable) {
            if (_state.value != State.Closed) {
                _lastError.value = "读循环错误：${e.message ?: e.javaClass.simpleName}"
                Log.w(TAG, "read loop ended", e)
            }
        } finally {
            teardown("读循环结束")
        }
    }

    private fun readOneMessage(input: InputStream): ControlMessage? {
        // 1) read 2 bytes to learn total length
        val lenBuf = readN(input, ControlCodec.HEADER_PEEK_BYTES) ?: return null
        val length = ControlCodec.peekLength(lenBuf)
        if (length < PPTP_COMMON_HEADER_BYTES || length > 0x4000) {
            throw IOException("implausible message length=$length")
        }
        // 2) read the rest
        val rest = readN(input, length - lenBuf.size) ?: return null
        val full = ByteArray(length)
        System.arraycopy(lenBuf, 0, full, 0, lenBuf.size)
        System.arraycopy(rest, 0, full, lenBuf.size, rest.size)
        return ControlCodec.decode(full)
    }

    private fun readN(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) return null
            off += r
        }
        return buf
    }

    private fun dispatch(msg: ControlMessage) {
        when (msg) {
            is ControlMessage.StartControlConnectionReply -> sccrpPromise?.complete(msg)
            is ControlMessage.OutgoingCallReply -> ocrpPromise?.complete(msg)
            is ControlMessage.StopControlConnectionReply -> stopReplyPromise?.complete(msg)
            is ControlMessage.EchoRequest -> {
                // Server pinging us — respond.
                runCatching { send(ControlMessage.EchoReply(msg.identifier, ResultCode.OK, 0)) }
            }
            is ControlMessage.EchoReply -> {
                val p = synchronized(pendingEchoes) { pendingEchoes.remove(msg.identifier) }
                p?.complete(msg)
            }
            is ControlMessage.StopControlConnectionRequest -> {
                // Server tells us to stop. Acknowledge then close.
                runCatching { send(ControlMessage.StopControlConnectionReply(ResultCode.OK, 0)) }
                _lastError.value = "服务器请求断开（reason=${msg.reason}）"
                scope.launch { teardown("server-initiated stop") }
            }
            is ControlMessage.CallDisconnectNotify -> {
                _lastError.value = "服务器断开呼叫：result=${msg.resultCode} cause=${msg.causeCode}"
                if (_state.value == State.CallUp || _state.value == State.WaitOcrp) {
                    scope.launch { teardown("CDN received") }
                }
            }
            // SetLinkInfo / WanErrorNotify / etc. — server-side informational
            // messages we don't currently act on. Logged at the readLoop level.
            else -> { /* ignored */ }
        }
    }

    private suspend fun echoLoop() {
        while (true) {
            delay(echoIntervalMs)
            if (_state.value !in arrayOf(State.Established, State.CallUp, State.WaitOcrp)) continue
            try {
                ping()
            } catch (e: Throwable) {
                Log.w(TAG, "echo failed", e)
            }
        }
    }

    private fun teardown(reason: String) {
        if (_state.value == State.Closed) return
        Log.i(TAG, "teardown: $reason")
        _state.value = State.Closed
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        readerJob?.cancel()
        echoJob?.cancel()
        sccrpPromise?.cancel()
        ocrpPromise?.cancel()
        stopReplyPromise?.cancel()
        synchronized(pendingEchoes) {
            pendingEchoes.values.forEach { it.cancel() }
            pendingEchoes.clear()
        }
        scope.cancel()
    }

    companion object {
        private const val TAG = "PptpControl"
        private const val CONNECT_TIMEOUT_MS = 8_000
    }
}
