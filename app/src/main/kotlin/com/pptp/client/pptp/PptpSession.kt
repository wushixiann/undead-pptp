package com.pptp.client.pptp

import android.content.Context
import android.util.Log
import com.pptp.client.helper.BridgeResult
import com.pptp.client.helper.HelperLifecycle
import com.pptp.client.helper.UdsBridge
import com.pptp.client.helper.UdsFrame
import com.pptp.client.ppp.AuthChoice
import com.pptp.client.ppp.CcpStateMachine
import com.pptp.client.ppp.IpcpStateMachine
import com.pptp.client.ppp.LcpCodec
import com.pptp.client.ppp.LcpStateMachine
import com.pptp.client.ppp.Mppe
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
        CcpNegotiating,
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
    private var ccp: CcpStateMachine? = null
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
    @Volatile var mppe: Mppe? = null
        private set
    @Volatile var mppeActive: Boolean = false
        private set

    fun controlChannel(): ControlChannel? = control
    fun lcpState(): LcpStateMachine.State? = lcp?.state?.value
    fun ccpState(): CcpStateMachine.State? = ccp?.state?.value
    fun ipcpState(): IpcpStateMachine.State? = ipcp?.state?.value
    fun negotiatedAuth(): AuthChoice? = lcp?.negotiatedAuth
    fun ipcpLocalConfig(): IpcpStateMachine.LocalConfig? = ipcp?.localConfig
    fun ipcpPeerConfig(): IpcpStateMachine.PeerConfig? = ipcp?.peerConfig
    fun bridgeTxCount(): Int = bridge?.txCount?.value ?: 0
    fun bridgeRxCount(): Int = bridge?.rxCount?.value ?: 0
    fun underlayInterface(): String = chosenIface
    fun peerIp(): String = ipFormat(peerIpv4Int)

    @Volatile private var chosenIface: String = ""

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
        val mppe = this.mppe
        if (mppeActive && mppe != null) {
            // MPPE encrypts the *PPP protocol + payload* together (RFC 3078 §2),
            // and the outer PPP protocol becomes 0x00FD (compressed datagram).
            val inner = ByteBuffer.allocate(2 + packet.size).order(ByteOrder.BIG_ENDIAN)
                .putShort(PppProtocol.IPV4.toShort()).put(packet).array()
            val ciphertext = mppe.encrypt(inner)
            sendPpp(PppProtocol.MPPE_COMPRESSED, ciphertext, s)
        } else {
            sendPpp(PppProtocol.IPV4, packet, s)
        }
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
        chosenIface = iface
        // Log all visible interfaces so the user can sanity-check what Android
        // actually sees vs what we picked. Most actionable signal when GRE
        // replies don't come back.
        val cands = runCatching { NetworkUtil.listCandidates(context) }.getOrDefault(emptyList())
        Log.i(TAG, "chose iface=$iface; all candidates: " +
            cands.joinToString(", ") { "${it.ifaceName}(${it.transport}${if (it.validated) "" else ",unvalidated"})" })
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
        // If MS-CHAPv2 derived MPPE master key, start CCP in parallel with IPCP.
        // Otherwise (PAP path), skip CCP entirely — tunnel will be unencrypted.
        if (mppeKeys != null) {
            startCcp(s)
        }
        startIpcp(s)
    }

    private fun startCcp(s: SessionState) {
        _phase.value = Phase.CcpNegotiating
        val keyMat = mppeKeys ?: return
        // We are the client (not server); send/recv asymmetric keys derived in Mppe ctor.
        val m = Mppe(keyMat.masterKey, isServer = false)
        mppe = m
        val sm = CcpStateMachine(
            sender = { packet -> sendPpp(PppProtocol.CCP, LcpCodec.encodePacket(packet), s) },
            onOpened = {
                mppeActive = true
                Log.i(TAG, "CCP opened — MPPE-128 stateless active")
            },
            onClosed = {
                mppeActive = false
                if (_phase.value !in arrayOf(Phase.Disconnecting, Phase.Closed, Phase.Failed)) {
                    scope.launch { fail("CCP 异常关闭 — MPPE 协商失败") }
                }
            },
            onResetRequest = { _ ->
                // Server tells us their decryption desynced. Reset our state.
                mppe?.reset()
            },
            onResetAck = { _ ->
                // We previously sent a Reset-Request; nothing more to do (state already reset).
            },
        )
        ccp = sm
        sm.open()
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
            ccp?.close()
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
        runCatching { ccp?.shutdown() }
        runCatching { ipcp?.shutdown() }
        lcp = null
        ccp = null
        ipcp = null
        mppe = null
        mppeActive = false
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
            PppProtocol.CCP -> {
                val pkt = try { LcpCodec.decodePacket(ppp.payload) } catch (_: Throwable) { return }
                ccp?.onReceive(pkt)
            }
            PppProtocol.IPCP -> {
                val pkt = try { LcpCodec.decodePacket(ppp.payload) } catch (_: Throwable) { return }
                ipcp?.onReceive(pkt)
            }
            PppProtocol.IPV4 -> tunDeliver?.invoke(ppp.payload)
            PppProtocol.MPPE_COMPRESSED -> handleMppeRx(ppp.payload)
            else -> Log.d(TAG, "PPP protocol ${"0x%04x".format(ppp.protocol)} ignored")
        }
    }

    private fun handleMppeRx(payload: ByteArray) {
        val m = mppe ?: return
        val plain = m.decrypt(payload)
        if (plain == null) {
            // Coherency gap or decryption failure → ask peer to flush.
            ccp?.sendResetRequest()
            return
        }
        if (plain.size < 2) return
        val innerProto = ((plain[0].toInt() and 0xFF) shl 8) or (plain[1].toInt() and 0xFF)
        val innerPayload = plain.copyOfRange(2, plain.size)
        when (innerProto) {
            PppProtocol.IPV4 -> tunDeliver?.invoke(innerPayload)
            else -> Log.d(TAG, "MPPE inner protocol ${"0x%04x".format(innerProto)} ignored")
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
