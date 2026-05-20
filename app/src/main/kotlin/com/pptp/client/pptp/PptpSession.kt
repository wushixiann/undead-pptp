package com.pptp.client.pptp

import android.content.Context
import android.util.Log
import com.pptp.client.helper.BridgeResult
import com.pptp.client.helper.HelperLifecycle
import com.pptp.client.helper.UdsBridge
import com.pptp.client.helper.UdsFrame
import com.pptp.client.ppp.AuthChoice
import com.pptp.client.ppp.LcpCodec
import com.pptp.client.ppp.LcpPacket
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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * End-to-end orchestrator that brings the layered stack up:
 *
 *   ① ControlChannel : TCP 1723 → SCCRQ/SCCRP → OCRQ/OCRP → Call-IDs known
 *   ② HelperLifecycle: spawn root helper, open UDS bridge for GRE I/O
 *   ③ LCP            : run the PPP link-layer negotiation to Opened
 *   ④ Auth           : PAP or MS-CHAP-V2 (whichever LCP negotiated)
 *
 * v0.0.6 stops at step ④ (Authenticated). IPCP + VpnService TUN come in
 * v0.0.7, CCP/MPPE in v0.0.8. The orchestrator owns *all* lifecycle for
 * the call: a single [disconnect] tears down every layer cleanly.
 */
class PptpSession(private val context: Context) {

    enum class Phase {
        Idle,
        ControlConnecting,
        CallSetup,
        BridgeStarting,
        LcpNegotiating,
        LcpOpen,
        Authenticating,
        Authenticated,
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
    private var session: SessionState? = null
    private var peerIpv4Int: Int = 0
    private var rxJob: Job? = null

    private var papAuth: PapAuth? = null
    private var msChapAuth: MsChapV2Auth? = null

    /** MPPE master-key material — populated only when MS-CHAP-V2 succeeds. */
    @Volatile var mppeKeys: MppeKeyMaterial? = null
        private set

    fun controlChannel(): ControlChannel? = control
    fun lcpState(): LcpStateMachine.State? = lcp?.state?.value
    fun negotiatedAuth(): AuthChoice? = lcp?.negotiatedAuth

    /**
     * Bring the full PPTP stack up. Suspending; can be cancelled by calling
     * [disconnect]. On any failure [_phase] settles to [Phase.Failed] and
     * the underlying resources are torn down.
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

        // Stage ①: control channel + outgoing call.
        _phase.value = Phase.ControlConnecting
        val cc = ControlChannel()
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
        Log.i(TAG, "peer IPv4 resolved: ${ipFormat(peerIpv4Int)}; callIds local=${s.localCallId} peer=${s.peerCallId}")

        // Stage ②: helper bridge.
        _phase.value = Phase.BridgeStarting
        val iface = NetworkUtil.activeUnderlayInterface(context) ?: "wlan0"
        val br = when (val r = HelperLifecycle.startBridge(
            context, iface,
            onHelperExit = { code, out ->
                Log.w(TAG, "helper exited code=$code out=$out")
                if (_phase.value !in arrayOf(Phase.Disconnecting, Phase.Closed, Phase.Failed)) {
                    scope.launch { fail("helper 意外退出：code=$code\n$out") }
                }
            },
        )) {
            is BridgeResult.Ok -> r.bridge
            is BridgeResult.Fail -> { fail("启动 helper 失败：${r.message}"); return }
        }
        bridge = br

        // Stage ③: hook bridge RX → PPP dispatcher, then start LCP.
        rxJob = scope.launch {
            try {
                br.received.consumeAsFlow().collect { uds ->
                    handleRx(uds, s)
                }
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
                        // Kick off auth as soon as LCP is up.
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
            onAuthRequested = { auth ->
                Log.i(TAG, "peer requested auth: $auth")
            },
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
                    username = username,
                    password = password,
                    sender = { payload -> sendPpp(PppProtocol.PAP, payload, s) },
                    onResult = { ok, msg ->
                        _authMessage.value = msg
                        if (ok) {
                            _phase.value = Phase.Authenticated
                        } else {
                            scope.launch { fail("PAP 认证失败: $msg") }
                        }
                    },
                )
                papAuth = pap
                pap.start()
            }
            AuthChoice.MsChapV2 -> {
                val mc = MsChapV2Auth(
                    username = username,
                    password = password,
                    sender = { payload -> sendPpp(PppProtocol.CHAP, payload, s) },
                    onResult = { ok, msg, keys ->
                        _authMessage.value = msg
                        if (ok) {
                            mppeKeys = keys
                            _phase.value = Phase.Authenticated
                        } else {
                            scope.launch { fail("MS-CHAPv2 认证失败: $msg") }
                        }
                    },
                )
                msChapAuth = mc
                mc.start()
            }
            AuthChoice.MsChapV1, AuthChoice.Unknown -> {
                scope.launch { fail("不支持的认证协议: $auth（仅 PAP 与 MS-CHAP-V2）") }
            }
        }
    }

    suspend fun disconnect() {
        if (_phase.value in arrayOf(Phase.Idle, Phase.Closed)) return
        _phase.value = Phase.Disconnecting
        try {
            lcp?.close()
            delay(300) // give TermReq a moment to flush
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
        lcp = null
        papAuth = null
        msChapAuth = null
        control = null
        session = null
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
        try {
            br.send(UdsFrame(peerIpv4Int, greBytes))
        } catch (e: Throwable) {
            Log.w(TAG, "send via bridge failed", e)
        }
    }

    private fun handleRx(uds: UdsFrame, s: SessionState) {
        val greBytes = GreFrame.stripIpv4(uds.payload) ?: return
        val gre = try {
            GreFrame.decode(greBytes)
        } catch (e: Throwable) {
            Log.d(TAG, "ignoring malformed GRE: ${e.message}")
            return
        }
        if (gre.callId != s.localCallId) return
        gre.sequence?.let { s.setRxSeq(it) }
        if (gre.payload.isEmpty()) return

        val ppp = try {
            PppFrame.decode(gre.payload)
        } catch (e: Throwable) {
            Log.d(TAG, "ignoring malformed PPP: ${e.message}")
            return
        }
        when (ppp.protocol) {
            PppProtocol.LCP -> {
                val pkt = try {
                    LcpCodec.decodePacket(ppp.payload)
                } catch (e: Throwable) {
                    Log.w(TAG, "bad LCP packet", e); return
                }
                lcp?.onReceive(pkt)
            }
            PppProtocol.PAP -> papAuth?.onReceive(ppp.payload)
            PppProtocol.CHAP -> msChapAuth?.onReceive(ppp.payload)
            else -> {
                Log.d(TAG, "PPP protocol ${"0x%04x".format(ppp.protocol)} ignored at v0.0.6")
            }
        }
    }

    companion object {
        private const val TAG = "PptpSession"

        fun resolveIpv4(host: String): Int {
            val addr = InetAddress.getAllByName(host).firstOrNull { it.address.size == 4 }
                ?: throw IOException("$host has no IPv4 address")
            val b = addr.address
            return ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).int
        }

        fun ipFormat(packed: Int): String =
            "${(packed ushr 24) and 0xFF}.${(packed ushr 16) and 0xFF}." +
                "${(packed ushr 8) and 0xFF}.${packed and 0xFF}"
    }
}
