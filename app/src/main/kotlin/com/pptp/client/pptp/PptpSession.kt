package com.pptp.client.pptp

import android.content.Context
import android.util.Log
import com.pptp.client.helper.BridgeResult
import com.pptp.client.helper.HelperLifecycle
import com.pptp.client.helper.UdsBridge
import com.pptp.client.helper.UdsFrame
import com.pptp.client.ppp.AuthChoice
import com.pptp.client.ppp.IpcpStateMachine
import com.pptp.client.ppp.LcpCodec
import com.pptp.client.ppp.LcpStateMachine
import com.pptp.client.ppp.MppeKeyMaterial
import com.pptp.client.ppp.MsChapV2Auth
import com.pptp.client.ppp.PapAuth
import com.pptp.client.ppp.PppFrame
import com.pptp.client.ppp.PppProtocol
import com.pptp.client.util.NetworkUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * End-to-end orchestrator for the PPTP stack.
 *
 *   Phases  ── Stage ─────────────────────────────────────────────────
 *   Idle
 *   ControlConnecting ── TCP 1723 connect (with optional socketProtector)
 *   CallSetup         ── SCCRQ/SCCRP → OCRQ/OCRP
 *   BridgeStarting    ── spawn root helper, accept UDS
 *   LcpNegotiating    ── PPP LCP up
 *   LcpOpen           ── LCP Opened, auth not started yet
 *   Authenticating    ── PAP / MS-CHAP-V2 in flight
 *   Authenticated     ── auth OK; mppeKeys populated for MS-CHAP-V2
 *   IpcpNegotiating   ── IPCP up; once Opened we invoke onIpcpOpened()
 *   IpcpOpen          ── IP/DNS assigned; caller (VpnService) builds TUN
 *   Connected         ── TUN bound via [bindTun]; IP traffic flowing
 *   Disconnecting / Closed / Failed
 */
class PptpSession(
    private val context: Context,
    private val socketProtector: ((Socket) -> Unit)? = null,
    private val onIpcpOpened: ((IpcpStateMachine.LocalConfig, IpcpStateMachine.PeerConfig) -> Unit)? = null,
) {

    enum class Phase {
        Idle,
        ControlConnecting,
        CallSetup,
        BridgeStarting,
        LcpNegotiating,
        LcpOpen,
        Authenticating,
        Authenticated,
        IpcpNegotiating,
        IpcpOpen,
        Connected,
        Disconnecting,
        Closed,
        Failed,
    }

    private val _phase = MutableStateFlow(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _authMessage = MutableStateFlow<String?>(null)
    val authMessage: StateFlow<String?> = _authMessage.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var control: ControlChannel? = null
    private var bridge: UdsBridge? = null
    private var lcp: LcpStateMachine? = null
    private var ipcp: IpcpStateMachine? = null
    private var session: SessionState? = null
    private var peerIpv4Int: Int = 0
    private var rxJob: Job? = null

    private var papAuth: PapAuth? = null
    private var msChapAuth: MsChapV2Auth? = null

    /** Invoked when an IPv4 packet arrives from the wire (after IPCP open). */
    @Volatile private var tunDeliver: ((ByteArray) -> Unit)? = null

    @Volatile var mppeKeys: MppeKeyMaterial? = null
        private set

    fun controlChannel(): ControlChannel? = control
    fun lcpState(): LcpStateMachine.State? = lcp?.state?.value
    fun ipcpState(): IpcpStateMachine.State? = ipcp?.state?.value
    fun negotiatedAuth(): AuthChoice? = lcp?.negotiatedAuth
    fun ipcpLocalConfig(): IpcpStateMachine.LocalConfig? = ipcp?.localConfig
    fun ipcpPeerConfig(): IpcpStateMachine.PeerConfig? = ipcp?.peerConfig

    /**
     * Bind the TUN delivery callback after the caller (typically VpnService)
     * has built the tun interface. From this moment IPv4 packets arriving from
     * the wire are passed to [deliver], and phase becomes Connected.
     */
    fun bindTun(deliver: (ByteArray) -> Unit) {
        tunDeliver = deliver
        if (_phase.value == Phase.IpcpOpen) _phase.value = Phase.Connected
    }

    /** Send an outbound IPv4 packet (called by TunPipe). */
    fun sendIpv4(packet: ByteArray) {
        val s = session ?: return
        sendPpp(PppProtocol.IPV4, packet, s)
    }

    /**
     * Bring the full PPTP stack up. On failure [_phase] settles to [Phase.Failed].
     */
    suspend fun connect(
        host: String,
        port: Int = 1723,
        username: String,
        password: String,
    ) {
        check(_phase.value == Phase.Idle || _phase.value == Phase.Closed || _phase.value == Phase.Failed) {
            "session already running (phase=${_phase.value})"
        }
        _lastError.value = null
        _authMessage.value = null
        mppeKeys = null

        _phase.value = Phase.ControlConnecting
        val cc = ControlChannel(socketProtector = socketProtector)
        control = cc
        try {
            cc.connect(host, port)
            _phase.value = Phase.CallSetup
            cc.openCall()
        } catch (e: Throwable) {
            fail("控制通道失败：${e.message ?: e.javaClass.simpleName}")
            throw e
        }
        val s = cc.session ?: run { fail("缺少 SessionState"); return }
        session = s

        peerIpv4Int = try {
            withContext(Dispatchers.IO) { resolveIpv4(host) }
        } catch (e: Throwable) {
            fail("无法解析 $host 为 IPv4：${e.message}")
            return
        }
        Log.i(TAG, "peer IPv4=${ipFormat(peerIpv4Int)} callIds local=${s.localCallId} peer=${s.peerCallId}")

        _phase.value = Phase.BridgeStarting
        val iface = NetworkUtil.activeUnderlayInterface(context) ?: "wlan0"
        val br = when (val r = HelperLifecycle.startBridge(
            context, iface,
            onHelperExit = { code, out ->
                Log.w(TAG, "helper exited code=$code")
                if (_phase.value !in arrayOf(Phase.Disconnecting, Phase.Closed, Phase.Failed)) {
                    scope.launch { fail("helper 意外退出：code=$code\n$out") }
                }
            },
        )) {
            is BridgeResult.Ok -> r.bridge
            is BridgeResult.Fail -> { fail("启动 helper 失败：${r.message}"); return }
        }
        bridge = br

        rxJob = scope.launch {
            try {
                br.received.consumeAsFlow().collect { uds -> handleRx(uds, s) }
            } catch (e: Throwable) {
                Log.w(TAG, "rx loop ended", e)
            }
        }

        _phase.value = Phase.LcpNegotiating
        val sm = LcpStateMachine(
            sender = { packet -> sendPpp(PppProtocol.LCP, LcpCodec.encodePacket(packet), s) },
            onStateChange = { st ->
                when (st) {
                    LcpStateMachine.State.Opened -> {
                        _phase.value = Phase.LcpOpen
                        scope.launch { startAuth(username, password, s) }
                    }
                    LcpStateMachine.State.Closed -> {
                        if (_phase.value !in arrayOf(Phase.Disconnecting, Phase.Closed, Phase.Failed)) {
                            scope.launch { fail("LCP 异常关闭") }
                        }
                    }
                    else -> {}
                }
            },
            onAuthRequested = { auth -> Log.i(TAG, "peer requested auth: $auth") },
        )
        lcp = sm
        sm.open()
    }

    private fun startAuth(username: String, password: String, s: SessionState) {
        val auth = lcp?.negotiatedAuth ?: AuthChoice.Unknown
        Log.i(TAG, "starting auth: $auth")
        _phase.value = Phase.Authenticating

        when (auth) {
            AuthChoice.Pap -> {
                val pap = PapAuth(
                    username, password,
                    sender = { sendPpp(PppProtocol.PAP, it, s) },
                    onResult = { ok, msg ->
                        _authMessage.value = msg
                        if (ok) onAuthOk(s) else scope.launch { fail("PAP 认证失败: $msg") }
                    },
                )
                papAuth = pap; pap.start()
            }
            AuthChoice.MsChapV2 -> {
                val mc = MsChapV2Auth(
                    username, password,
                    sender = { sendPpp(PppProtocol.CHAP, it, s) },
                    onResult = { ok, msg, keys ->
                        _authMessage.value = msg
                        if (ok) {
                            mppeKeys = keys
                            onAuthOk(s)
                        } else scope.launch { fail("MS-CHAPv2 认证失败: $msg") }
                    },
                )
                msChapAuth = mc; mc.start()
            }
            AuthChoice.MsChapV1, AuthChoice.Unknown ->
                scope.launch { fail("不支持的认证协议: $auth") }
        }
    }

    private fun onAuthOk(s: SessionState) {
        _phase.value = Phase.Authenticated
        // Immediately drive IPCP. (In a real PPTP setup CCP may also start in parallel.)
        startIpcp(s)
    }

    private fun startIpcp(s: SessionState) {
        _phase.value = Phase.IpcpNegotiating
        val sm = IpcpStateMachine(
            sender = { packet -> sendPpp(PppProtocol.IPCP, LcpCodec.encodePacket(packet), s) },
            onStateChange = { st ->
                if (st == IpcpStateMachine.State.Opened) {
                    _phase.value = Phase.IpcpOpen
                    onIpcpOpened?.invoke(ipcp!!.localConfig, ipcp!!.peerConfig)
                } else if (st == IpcpStateMachine.State.Closed) {
                    if (_phase.value !in arrayOf(Phase.Disconnecting, Phase.Closed, Phase.Failed)) {
                        scope.launch { fail("IPCP 异常关闭") }
                    }
                }
            },
        )
        ipcp = sm
        sm.open()
    }

    suspend fun disconnect() {
        if (_phase.value in arrayOf(Phase.Idle, Phase.Closed)) return
        _phase.value = Phase.Disconnecting
        try {
            ipcp?.close()
            lcp?.close()
            delay(300)
            control?.disconnect()
        } catch (_: Throwable) {}
        teardownNetwork()
        _phase.value = Phase.Closed
    }

    private fun fail(msg: String) {
        Log.w(TAG, "fail: $msg")
        _lastError.value = msg
        teardownNetwork()
        _phase.value = Phase.Failed
    }

    private fun teardownNetwork() {
        rxJob?.cancel()
        rxJob = null
        runCatching { bridge?.stop() }
        bridge = null
        runCatching { lcp?.shutdown() }
        runCatching { ipcp?.shutdown() }
        lcp = null
        ipcp = null
        papAuth = null
        msChapAuth = null
        control = null
        session = null
        tunDeliver = null
    }

    // ----- PPP I/O -----

    private fun sendPpp(protocol: Int, payload: ByteArray, s: SessionState) {
        val br = bridge ?: return
        val pppBytes = PppFrame.encode(protocol, payload, addressControl = true)
        val ackVal = s.rxSeq().takeIf { it != -1 }
        val greBytes = GreFrame.encode(
            peerCallId = s.peerCallId,
            pppPayload = pppBytes,
            sequence = s.nextTxSeq(),
            ack = ackVal,
        )
        try { br.send(UdsFrame(peerIpv4Int, greBytes)) } catch (e: Throwable) {
            Log.w(TAG, "send via bridge failed", e)
        }
    }

    private fun handleRx(uds: UdsFrame, s: SessionState) {
        val greBytes = GreFrame.stripIpv4(uds.payload) ?: return
        val gre = try { GreFrame.decode(greBytes) } catch (_: Throwable) { return }
        if (gre.callId != s.localCallId) return
        gre.sequence?.let { s.setRxSeq(it) }
        if (gre.payload.isEmpty()) return

        val ppp = try { PppFrame.decode(gre.payload) } catch (_: Throwable) { return }
        when (ppp.protocol) {
            PppProtocol.LCP -> {
                val pkt = try { LcpCodec.decodePacket(ppp.payload) } catch (_: Throwable) { return }
                lcp?.onReceive(pkt)
            }
            PppProtocol.PAP -> papAuth?.onReceive(ppp.payload)
            PppProtocol.CHAP -> msChapAuth?.onReceive(ppp.payload)
            PppProtocol.IPCP -> {
                val pkt = try { LcpCodec.decodePacket(ppp.payload) } catch (_: Throwable) { return }
                ipcp?.onReceive(pkt)
            }
            PppProtocol.IPV4 -> tunDeliver?.invoke(ppp.payload)
            else -> Log.d(TAG, "PPP protocol ${"0x%04x".format(ppp.protocol)} ignored")
        }
    }

    companion object {
        private const val TAG = "PptpSession"

        fun resolveIpv4(host: String): Int {
            val addr = InetAddress.getAllByName(host).firstOrNull { it.address.size == 4 }
                ?: throw IOException("$host has no IPv4 address")
            return ByteBuffer.wrap(addr.address).order(ByteOrder.BIG_ENDIAN).int
        }

        fun ipFormat(packed: Int): String =
            "${(packed ushr 24) and 0xFF}.${(packed ushr 16) and 0xFF}." +
                "${(packed ushr 8) and 0xFF}.${packed and 0xFF}"
    }
}
