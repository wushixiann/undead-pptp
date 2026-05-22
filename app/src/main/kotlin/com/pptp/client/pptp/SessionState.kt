// SPDX-License-Identifier: GPL-3.0-or-later
package com.pptp.client.pptp

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-call session state shared between the control channel and the future
 * data plane (GRE/PPP layers).
 *
 *  - `localCallId`: the 16-bit Call-ID we advertise in OCRQ. The server will
 *    place this in the Call-ID field of GRE packets sent to us.
 *  - `peerCallId`: the 16-bit Call-ID the server returns in OCRP. We place it
 *    in the GRE Call-ID field when transmitting.
 *  - `callSerialNumber`: a monotonically increasing per-control-connection ID.
 *  - `txSeq` / `rxSeq`: GRE sequence/ack counters (RFC 2637 §4.4).
 *
 * Instances are constructed fresh per call attempt. All mutable counters are
 * AtomicInteger so multiple coroutines can update them without explicit locks.
 */
class SessionState(
    val localCallId: Int,
    val callSerialNumber: Int,
) {
    @Volatile var peerCallId: Int = 0
        internal set

    /** Outbound GRE Sequence Number (S=1 packets). Starts at 0, incremented per data packet. */
    private val tx = AtomicInteger(0)

    /** Highest GRE sequence number we have received from the peer. -1 = none yet. */
    private val rx = AtomicInteger(-1)

    fun nextTxSeq(): Int = tx.getAndIncrement()
    fun txCount(): Int = tx.get()

    fun setRxSeq(seq: Int) {
        // Allow forward jumps; never go backwards (would be a reorder we ignore).
        while (true) {
            val cur = rx.get()
            if (Integer.compareUnsigned(seq, cur) <= 0) return
            if (rx.compareAndSet(cur, seq)) return
        }
    }
    fun rxSeq(): Int = rx.get()
}

/**
 * Factory for fresh session IDs. Call IDs are 16-bit; we draw a non-zero
 * random value per call to avoid collisions if a server caches them. Serial
 * numbers monotonically increment per process lifetime.
 */
object CallIdAllocator {
    private val rng = SecureRandom()
    private val serial = AtomicInteger(0)

    fun newSession(): SessionState {
        var cid: Int
        do {
            cid = rng.nextInt(0xFFFF) + 1
        } while (cid == 0)
        return SessionState(localCallId = cid, callSerialNumber = serial.incrementAndGet())
    }
}
