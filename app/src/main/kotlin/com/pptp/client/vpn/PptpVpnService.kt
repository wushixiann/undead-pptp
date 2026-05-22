// SPDX-License-Identifier: GPL-3.0-or-later
package com.pptp.client.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.pptp.client.MainActivity
import com.pptp.client.ppp.IpcpStateMachine
import com.pptp.client.pptp.PptpSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * VpnService that owns:
 *   - The PptpSession instance (control channel + helper + PPP + auth + IPCP/CCP)
 *   - The Android TUN file descriptor (only after IPCP opens)
 *   - The TunPipe that translates between TUN IP packets and the PPP/GRE
 *     pipeline.
 *
 * Started from MainActivity via a regular Intent after the user has
 * acknowledged VpnService.prepare(). State is exposed via [State] in the
 * companion so the UI can collect on it without binding the Service.
 *
 * Current limitations (kept short — see CHANGELOG for history):
 *   - No automatic reconnect when the underlay (WiFi ↔ cellular) changes.
 *   - MPPE-128 stateless only; stateful MPPE and 40/56-bit unsupported.
 *   - IPv4 only inside the tunnel (server-sent IPCPv6 / IPv6 packets ignored).
 */
class PptpVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: PptpSession? = null
    private var tunPipe: TunPipe? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: return abort("missing host")
                val port = intent.getIntExtra(EXTRA_PORT, 1723)
                val user = intent.getStringExtra(EXTRA_USERNAME) ?: ""
                val pass = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
                Log.i(TAG, "starting connect host=$host port=$port user=$user")
                startForeground(
                    ForegroundNotification.NOTIFICATION_ID,
                    ForegroundNotification.build(this, "PPTP 客户端", "正在连接 $host…"),
                )
                state.value = State(connecting = true, host = host)
                scope.launch { runConnect(host, port, user, pass) }
            }
            ACTION_STOP -> {
                Log.i(TAG, "stop requested")
                scope.launch {
                    runCatching { session?.disconnect() }
                    cleanup()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun abort(reason: String): Int {
        Log.w(TAG, "abort: $reason")
        stopSelf()
        return START_NOT_STICKY
    }

    private suspend fun runConnect(host: String, port: Int, user: String, pass: String) {
        val sess = PptpSession(
            context = this,
            socketProtector = { socket -> protect(socket) },
            onIpcpOpened = { local, peer ->
                Log.i(TAG, "IPCP opened: local=${ipStr(local.localIpv4)} dns=${ipStr(local.primaryDns)} peer=${ipStr(peer.peerIpv4)}")
                buildTun(local, peer)
            },
        )
        session = sess
        // Observe phase to keep notification text useful.
        scope.launch {
            sess.phase.collect { ph ->
                state.value = state.value.copy(phase = ph.name, mppeActive = sess.mppeActive)
                updateNotification("PPTP", "状态: ${ph.name}")
            }
        }
        scope.launch { sess.lastError.collect { err -> state.value = state.value.copy(lastError = err) } }
        // Poll bridge counters once a second so the UI can show whether GRE
        // packets are actually getting back from the wire — vital for diagnosing
        // cellular black-holing / NAT mishaps in the field.
        scope.launch {
            while (isActive) {
                val s = sess
                state.value = state.value.copy(
                    iface = s.underlayInterface(),
                    greTx = s.bridgeTxCount(),
                    greRx = s.bridgeRxCount(),
                )
                delay(1000)
            }
        }
        try {
            sess.connect(host, port, user, pass)
        } catch (e: Throwable) {
            Log.w(TAG, "connect threw", e)
        }
    }

    private fun buildTun(
        local: IpcpStateMachine.LocalConfig,
        peer: IpcpStateMachine.PeerConfig,
    ) {
        // PPTP MTU budget worst case:
        //   outer IPv4 (20) + GRE-PPTP (16, with S+A flags)
        //   + PPP framing (4: FF 03 + 2B proto) + MPPE header (2)
        //   + inner PPP proto (2)         = 44 bytes overhead
        // 1500 underlay − 44 = 1456 max inner. We use 1380 for extra slack:
        //   - some underlay can be 1492 (PPPoE-on-DSL); 1492 − 44 = 1448
        //   - intermediate links (cellular, encapsulating tunnels) may eat a few more
        //   - MSS will be 1380 − 40 = 1340, well below typical 1460
        // If the server lacks `iptables -t mangle … TCPMSS --clamp-mss-to-pmtu`,
        // browsers can hit a PMTUD black-hole (small ICMP passes, large TCP doesn't).
        // A conservative MTU here keeps things working without server-side cooperation.
        val builder = Builder()
            .setSession("PPTP")
            .setMtu(1380)
            .addAddress(ipStr(local.localIpv4), 32)
            .addRoute("0.0.0.0", 0)
            .setBlocking(true)
            .setMetered(false) // mark unmetered so apps that avoid mobile data still use VPN
        if (local.primaryDns != 0) {
            val dns = ipStr(local.primaryDns)
            builder.addDnsServer(dns)
            // Belt-and-suspenders: also add explicit route so OS doesn't try to reach
            // it through the underlying network if some app caches the route.
            runCatching { builder.addRoute(dns, 32) }
        }
        if (local.secondaryDns != 0) {
            val dns = ipStr(local.secondaryDns)
            builder.addDnsServer(dns)
            runCatching { builder.addRoute(dns, 32) }
        }
        // Don't loop our own app's traffic through the VPN — control channel keep-alives,
        // libsu shell, helper UDS, etc. all need direct access to the underlay.
        runCatching { builder.addDisallowedApplication(packageName) }
        // Configure intent to open the app when user taps the system VPN notification.
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        val fd: ParcelFileDescriptor = builder.establish()
            ?: run {
                Log.w(TAG, "VpnService.Builder.establish() returned null")
                scope.launch { session?.disconnect() }
                return
            }
        Log.i(TAG, "TUN established fd=${fd.fd}")
        val pipe = TunPipe(fd) { packet -> session?.sendIpv4(packet) }
        tunPipe = pipe
        session?.bindTun { packet -> pipe.deliver(packet) }
        pipe.start()
        state.value = state.value.copy(
            connecting = false,
            tunUp = true,
            localIp = ipStr(local.localIpv4),
            peerIp = ipStr(peer.peerIpv4),
        )
        updateNotification("PPTP 已连接", "本端 ${ipStr(local.localIpv4)}")
    }

    private fun updateNotification(title: String, body: String) {
        try {
            NotificationManagerCompat.from(this).notify(
                ForegroundNotification.NOTIFICATION_ID,
                ForegroundNotification.build(this, title, body),
            )
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied — silently ignore.
        }
    }

    private fun cleanup() {
        tunPipe?.stop()
        tunPipe = null
        session = null
        state.value = State()
        scope.cancel()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        cleanup()
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.i(TAG, "onRevoke (system reclaimed VPN)")
        scope.launch {
            runCatching { session?.disconnect() }
            cleanup()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    data class State(
        val connecting: Boolean = false,
        val tunUp: Boolean = false,
        val host: String = "",
        val phase: String = "Idle",
        val localIp: String = "",
        val peerIp: String = "",
        val mppeActive: Boolean = false,
        val iface: String = "",
        val greTx: Int = 0,
        val greRx: Int = 0,
        val lastError: String? = null,
    )

    companion object {
        private const val TAG = "PptpVpnService"
        const val ACTION_START = "com.pptp.client.action.START"
        const val ACTION_STOP = "com.pptp.client.action.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"

        /** UI observes this for live state. */
        val state: MutableStateFlow<State> = MutableStateFlow(State())
        val observable: StateFlow<State> = state.asStateFlow()

        private fun ipStr(v: Int): String =
            "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"
    }
}
