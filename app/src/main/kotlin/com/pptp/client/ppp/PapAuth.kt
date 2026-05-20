package com.pptp.client.ppp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PAP authentication (RFC 1334 §2).
 *
 *   Code 1: Authenticate-Request  (client → server)
 *           [Peer-ID-Length][Peer-ID...][Passwd-Length][Password...]
 *   Code 2: Authenticate-Ack
 *           [Msg-Length][Msg...]
 *   Code 3: Authenticate-Nak
 *           [Msg-Length][Msg...]
 *
 * PAP is single-shot: send the request, wait for Ack/Nak, done. We retry
 * the Authenticate-Request a few times in case the first one is lost (the
 * underlying GRE is unreliable). After the configured number of attempts
 * without any reply we surface failure.
 *
 * SECURITY: PAP sends the password in plaintext. The caller MUST NOT offer
 * PAP unless the user has explicitly accepted the risk (no MPPE possible
 * with PAP — the tunnel is unencrypted end to end).
 */
class PapAuth(
    private val username: String,
    private val password: String,
    /** Pure PPP-payload bytes (just the CHAP/PAP packet, no protocol field). */
    private val sender: (ByteArray) -> Unit,
    private val onResult: (success: Boolean, message: String) -> Unit,
    private val timeoutMs: Long = 10_000,
    private val maxAttempts: Int = 3,
) {
    private val identifier: Int = (System.nanoTime() and 0xFF).toInt()
    private val finished = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var timeoutJob: Job? = null
    private var attempts: Int = 0

    fun start() {
        send()
        scheduleTimeout()
    }

    private fun scheduleTimeout() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeoutMs)
            if (finished.get()) return@launch
            attempts++
            if (attempts < maxAttempts) {
                Log.w(TAG, "PAP no reply, retransmit #$attempts")
                send()
                scheduleTimeout()
            } else if (finished.compareAndSet(false, true)) {
                scope.cancel()
                onResult(false, "PAP 超时（${maxAttempts} 次无应答）")
            }
        }
    }

    private fun send() {
        val userBytes = username.toByteArray(Charsets.US_ASCII)
        val passBytes = password.toByteArray(Charsets.US_ASCII)
        require(userBytes.size <= 255) { "username too long for PAP" }
        require(passBytes.size <= 255) { "password too long for PAP" }
        val length = 4 + 1 + userBytes.size + 1 + passBytes.size
        val buf = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN)
        buf.put(1)                          // Code = Authenticate-Request
        buf.put(identifier.toByte())
        buf.putShort(length.toShort())
        buf.put(userBytes.size.toByte())
        buf.put(userBytes)
        buf.put(passBytes.size.toByte())
        buf.put(passBytes)
        sender(buf.array())
    }

    fun onReceive(payload: ByteArray) {
        if (finished.get()) return
        if (payload.size < 4) {
            Log.w(TAG, "PAP packet too short (${payload.size})")
            return
        }
        val code = payload[0].toInt() and 0xFF
        val id = payload[1].toInt() and 0xFF
        if (id != identifier) {
            Log.d(TAG, "PAP reply id=$id ≠ ours=$identifier — ignoring")
            return
        }
        val msg = if (payload.size > 5) {
            val msgLen = payload[4].toInt() and 0xFF
            val end = (5 + msgLen).coerceAtMost(payload.size)
            String(payload, 5, end - 5, Charsets.US_ASCII)
        } else ""
        when (code) {
            2 -> {
                if (finished.compareAndSet(false, true)) {
                    timeoutJob?.cancel(); scope.cancel()
                    onResult(true, msg.ifEmpty { "PAP Ack" })
                }
            }
            3 -> {
                if (finished.compareAndSet(false, true)) {
                    timeoutJob?.cancel(); scope.cancel()
                    onResult(false, msg.ifEmpty { "PAP Nak" })
                }
            }
            else -> Log.w(TAG, "PAP unexpected code=$code")
        }
    }

    companion object {
        private const val TAG = "PapAuth"
    }
}
