// SPDX-License-Identifier: GPL-3.0-or-later
package com.pptp.client.helper

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException

/**
 * Owns the AF_UNIX socket the helper connects back into. Lifecycle:
 *
 *   1. [start] binds an abstract-namespace LocalServerSocket and launches an
 *      accept coroutine. The abstract name is returned so the caller can pass
 *      it to the helper (prefixed with '@' to signal abstract namespace on the
 *      C side).
 *   2. The helper connects; the accept coroutine spawns RX and TX worker
 *      coroutines that move bytes between the LocalSocket and a Channel for
 *      received frames / a Channel for outbound frames.
 *   3. [send] enqueues an outbound frame.
 *   4. [stop] closes the socket; both workers wind down on EOF / cancellation.
 *
 * State transitions are surfaced via [state] for the UI.
 */
class UdsBridge {

    enum class State { Idle, WaitingForHelper, Connected, Stopped, Failed }

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Cumulative frame counts. Surface to UI so the user can confirm whether
     *  the data plane is actually carrying packets in both directions. */
    private val _txCount = MutableStateFlow(0)
    val txCount: StateFlow<Int> = _txCount.asStateFlow()

    private val _rxCount = MutableStateFlow(0)
    val rxCount: StateFlow<Int> = _rxCount.asStateFlow()

    /**
     * Frames received from the helper (raw socket → UDS direction).
     *
     * Capacity 1024: covers ~bursts up to a full CC wrap window without drops.
     * Each frame is at most a few KB so worst-case memory ≈ few MB.
     */
    val received: Channel<UdsFrame> = Channel(capacity = 1024)

    private var server: LocalServerSocket? = null
    private var client: LocalSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Outbound frame queue.
     *
     * Capacity 1024 (matched to [received]) — old 64 was too small for sustained
     * upload bursts (`git push`, photo upload). On overflow, [send] throws and
     * upstream sendPpp catches + drops the GRE frame; inner TCP then retransmits,
     * but only after RTO (200ms+) and with cwnd backoff. Concretely: small queue
     * = throughput collapses under sender-side memory pressure even when the
     * actual UDS write loop is fast enough. Larger queue absorbs Android's
     * coroutine scheduling jitter and keeps inner TCP healthy.
     */
    private val outbound = Channel<UdsFrame>(capacity = 1024)
    private var acceptJob: Job? = null

    /**
     * Binds a new abstract-namespace socket and starts accepting. Returns the
     * abstract name (no leading '@') so the caller can build the helper's
     * argv. The helper is expected to be invoked with `@<name>` to signal
     * abstract namespace.
     */
    fun start(): String {
        check(_state.value == State.Idle || _state.value == State.Stopped || _state.value == State.Failed) {
            "UdsBridge already started (state=${_state.value})"
        }
        val name = "pptp_bridge_" + System.currentTimeMillis().toString(16)
        val srv = LocalServerSocket(name)
        server = srv
        _state.value = State.WaitingForHelper
        _lastError.value = null
        acceptJob = scope.launch {
            try {
                val sock = withContext(Dispatchers.IO) { srv.accept() }
                client = sock
                _state.value = State.Connected
                runIo(sock)
            } catch (e: Throwable) {
                if (_state.value != State.Stopped) {
                    _lastError.value = "accept/io failed: ${e.message ?: e.javaClass.simpleName}"
                    _state.value = State.Failed
                    Log.w(TAG, "bridge io ended", e)
                }
            } finally {
                runCatching { srv.close() }
                runCatching { client?.close() }
            }
        }
        return name
    }

    private suspend fun runIo(sock: LocalSocket) = withContext(Dispatchers.IO) {
        val input = BufferedInputStream(sock.inputStream)
        val output = BufferedOutputStream(sock.outputStream)

        val txJob = launch {
            try {
                while (isActive) {
                    val frame = outbound.receive()
                    UdsFrameCodec.write(output, frame)
                    _txCount.value = _txCount.value + 1
                }
            } catch (e: Throwable) {
                if (isActive) Log.w(TAG, "tx worker ended", e)
            }
        }

        try {
            while (isActive) {
                val frame = try {
                    UdsFrameCodec.readOrNull(input)
                } catch (e: IOException) {
                    Log.w(TAG, "rx decode error", e)
                    null
                }
                if (frame == null) {
                    // EOF — helper has closed.
                    break
                }
                _rxCount.value = _rxCount.value + 1
                // SUSPENDING send (not trySend): when the app consumer is slow
                // (background CPU throttling, slow TUN write, etc.) we want
                // backpressure that pauses our UDS read instead of silently
                // dropping. Dropped frames desynchronize the MPPE coherency
                // count — accumulate >4096 drops and key derivation goes wrong
                // forever (12-bit CC wraps mod 4096).
                received.send(frame)
            }
        } finally {
            txJob.cancel()
            outbound.cancel()
            received.close()
            if (_state.value == State.Connected) {
                _state.value = State.Stopped
            }
        }
    }

    fun send(frame: UdsFrame) {
        require(_state.value == State.Connected) { "bridge not connected" }
        val result = outbound.trySend(frame)
        if (result.isFailure) {
            throw IllegalStateException(
                "outbound channel full or closed",
                result.exceptionOrNull(),
            )
        }
    }

    fun stop() {
        _state.value = State.Stopped
        runCatching { client?.shutdownInput() }
        runCatching { client?.shutdownOutput() }
        runCatching { client?.close() }
        runCatching { server?.close() }
        acceptJob?.cancel()
        scope.cancel()
    }

    /** Wait until [state] settles into Connected, or returns false on timeout. */
    suspend fun awaitConnected(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (_state.value) {
                State.Connected -> return true
                State.Failed, State.Stopped -> return false
                else -> delay(50)
            }
        }
        return false
    }

    companion object {
        private const val TAG = "UdsBridge"
    }
}
