// SPDX-License-Identifier: GPL-3.0-or-later
package com.pptp.client.vpn

import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Bridges the Android VpnService TUN file descriptor and the PPTP/PPP data
 * pipeline. One side (egress / TX) reads IP packets the kernel writes into
 * the TUN device; the other side (ingress / RX) writes IP packets that
 * arrived from the wire.
 *
 *   Kernel ──write IP──▶ TUN fd ──read──▶ TunPipe ──sendIp──▶ PPP/GRE → wire
 *   Wire   ──IP via PPP/GRE──▶ TunPipe.deliver() ──write──▶ TUN fd ──▶ Kernel
 */
class TunPipe(
    private val tunFd: ParcelFileDescriptor,
    private val sendIp: (ByteArray) -> Unit,
) {
    private val input = FileInputStream(tunFd.fileDescriptor)
    private val output = FileOutputStream(tunFd.fileDescriptor)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var stopped = false
    private var readJob: Job? = null

    fun start() {
        readJob = scope.launch {
            val buf = ByteArray(2048)
            try {
                while (isActive && !stopped) {
                    val n = input.read(buf)
                    if (n <= 0) {
                        if (n < 0) break else continue
                    }
                    // Only IPv4 for v0.0.7 — IPv6 would need IPCP6
                    if (buf[0].toInt() and 0xF0 != 0x40) continue
                    val packet = buf.copyOf(n)
                    try {
                        sendIp(packet)
                    } catch (e: Throwable) {
                        Log.w(TAG, "sendIp failed", e)
                    }
                }
            } catch (e: Throwable) {
                if (!stopped) Log.w(TAG, "TUN read loop ended", e)
            }
        }
    }

    /** Write an IP packet received from the wire into the TUN device. */
    fun deliver(packet: ByteArray) {
        if (stopped) return
        try {
            output.write(packet)
        } catch (e: Throwable) {
            Log.w(TAG, "TUN write failed", e)
        }
    }

    fun stop() {
        stopped = true
        readJob?.cancel()
        scope.cancel()
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { tunFd.close() }
    }

    companion object { private const val TAG = "TunPipe" }
}
