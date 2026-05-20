package com.pptp.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pptp.client.helper.BridgeResult
import com.pptp.client.helper.HelperLifecycle
import com.pptp.client.helper.Ipv4
import com.pptp.client.helper.ProbeResult
import com.pptp.client.helper.UdsBridge
import com.pptp.client.helper.UdsFrame
import com.pptp.client.pptp.ControlChannel
import com.pptp.client.pptp.ControlMessage
import com.pptp.client.pptp.PptpSession
import com.pptp.client.ppp.LcpStateMachine
import com.pptp.client.util.NetworkUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold { inner ->
                    Screen(inner)
                }
            }
        }
    }
}

@Composable
private fun Screen(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("${stringResource(R.string.version_label)}: ${BuildConfig.VERSION_NAME}")
        Spacer(Modifier.height(8.dp))
        Text("${stringResource(R.string.milestone_label)}: ${stringResource(R.string.milestone_v005)}")
        Spacer(Modifier.height(16.dp))

        ProbeSection()
        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        BridgeSection()
        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        ControlSection()
        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        SessionSection()
    }
}

@Composable
private fun ProbeSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(context.getString(R.string.helper_check_idle)) }
    var running by remember { mutableStateOf(false) }
    var helperPath by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        helperPath = HelperLifecycle.helperBinaryPath(context)
    }

    Text("① root + raw GRE socket 自检", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text("Helper: $helperPath", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    Spacer(Modifier.height(8.dp))
    Button(
        enabled = !running,
        onClick = {
            running = true
            status = context.getString(R.string.helper_check_running)
            scope.launch {
                val iface = NetworkUtil.activeUnderlayInterface(context) ?: "wlan0"
                val result = withContext(Dispatchers.IO) {
                    HelperLifecycle.probe(context, iface)
                }
                status = when (result) {
                    is ProbeResult.Ok ->
                        context.getString(R.string.helper_check_ok, result.iface) +
                            "\n\n" + result.diagnostic
                    is ProbeResult.Fail ->
                        context.getString(R.string.helper_check_fail, result.message)
                }
                running = false
            }
        },
    ) {
        Text(stringResource(R.string.helper_check_button))
    }
    Spacer(Modifier.height(8.dp))
    Text(status, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
}

@Composable
private fun BridgeSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bridge by remember { mutableStateOf<UdsBridge?>(null) }
    val state = bridge?.state?.collectAsState()?.value
    var helperOutput by remember { mutableStateOf<String?>(null) }
    var peerIp by remember { mutableStateOf("192.168.1.1") }
    var txCount by remember { mutableStateOf(0) }
    val rxLog = remember { mutableStateListOf<String>() }
    var rxCount by remember { mutableStateOf(0) }
    var rxJob by remember { mutableStateOf<Job?>(null) }
    var working by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Text("② Helper bridge（UDS 桥接 raw GRE socket）", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "状态: ${state?.name ?: "未启动"}",
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
    )
    Spacer(Modifier.height(8.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            enabled = !working && bridge == null,
            onClick = {
                working = true
                errorMsg = null
                helperOutput = null
                scope.launch {
                    val iface = NetworkUtil.activeUnderlayInterface(context) ?: "wlan0"
                    val result = withContext(Dispatchers.IO) {
                        HelperLifecycle.startBridge(
                            context = context,
                            iface = iface,
                            onHelperExit = { code, out ->
                                helperOutput = "helper exit=$code\n$out"
                            },
                        )
                    }
                    when (result) {
                        is BridgeResult.Ok -> {
                            bridge = result.bridge
                            // Drain received frames into the rolling log.
                            rxJob = scope.launch {
                                result.bridge.received.consumeAsFlow().collect { f ->
                                    rxCount++
                                    val ts = System.currentTimeMillis() % 100_000
                                    val hex = f.payload.take(16)
                                        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                                    val line = "[$ts] ${Ipv4.format(f.peerIpv4)} " +
                                        "len=${f.payload.size} ${hex}${if (f.payload.size > 16) "…" else ""}"
                                    rxLog.add(0, line)
                                    if (rxLog.size > 20) rxLog.removeAt(rxLog.lastIndex)
                                }
                            }
                        }
                        is BridgeResult.Fail -> errorMsg = result.message
                    }
                    working = false
                }
            },
        ) { Text("启动 bridge") }

        Spacer(Modifier.width(8.dp))

        Button(
            enabled = bridge != null,
            onClick = {
                bridge?.stop()
                rxJob?.cancel()
                rxJob = null
                bridge = null
                txCount = 0
                rxCount = 0
                rxLog.clear()
            },
        ) { Text("停止") }
    }

    if (state == UdsBridge.State.Connected) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = peerIp,
            onValueChange = { peerIp = it.trim() },
            label = { Text("测试目标 IP") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    try {
                        val frame = buildTestGreFrame(peerIp)
                        bridge?.send(frame)
                        txCount++
                    } catch (e: Throwable) {
                        errorMsg = "send 失败：${e.message}"
                    }
                },
            ) { Text("发送测试 GRE 包") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "TX: $txCount   RX: $rxCount",
            fontFamily = FontFamily.Monospace,
        )
    }

    errorMsg?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = Color.Red, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }

    helperOutput?.let {
        Spacer(Modifier.height(8.dp))
        Text("helper 输出:", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.DarkGray)
    }

    if (rxLog.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text("RX 日志（最近 20 条，新→旧）:", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        rxLog.forEach { line ->
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ControlSection() {
    val scope = rememberCoroutineScope()
    var channel by remember { mutableStateOf<ControlChannel?>(null) }
    val state = channel?.state?.collectAsState()?.value ?: ControlChannel.State.Idle
    val lastError = channel?.lastError?.collectAsState()?.value
    val echoOk = channel?.echoSuccesses?.collectAsState()?.value ?: 0
    val echoFail = channel?.echoFailures?.collectAsState()?.value ?: 0
    var server by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("1723") }
    var working by remember { mutableStateOf(false) }
    var lastEvent by remember { mutableStateOf<String?>(null) }
    val asyncLog = remember { mutableStateListOf<String>() }
    var asyncJob by remember { mutableStateOf<Job?>(null) }

    val session = channel?.session
    val peerInfo = channel?.negotiatedPeerInfo

    Text("③ PPTP 控制通道（TCP 1723）", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text("状态: ${state.name}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    Spacer(Modifier.height(8.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = server,
            onValueChange = { server = it.trim() },
            label = { Text("服务器 IP / 域名") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            enabled = channel == null,
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.trim() },
            label = { Text("端口") },
            singleLine = true,
            modifier = Modifier.width(96.dp),
            enabled = channel == null,
        )
    }
    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = !working && channel == null && server.isNotEmpty(),
            onClick = {
                working = true
                lastEvent = null
                asyncLog.clear()
                val cc = ControlChannel()
                scope.launch {
                    try {
                        val reply = withContext(Dispatchers.IO) {
                            cc.connect(server, port.toIntOrNull() ?: 1723)
                        }
                        channel = cc
                        lastEvent = "SCCRP OK: host='${reply.hostName}' vendor='${reply.vendorString}'"
                        asyncJob = scope.launch {
                            try {
                                cc.asyncEvents.consumeAsFlow().collect { ev ->
                                    val line = when (ev) {
                                        is ControlMessage.CallDisconnectNotify ->
                                            "CDN: callId=${ev.callId} rc=${ev.resultCode} cause=${ev.causeCode}"
                                        is ControlMessage.SetLinkInfo ->
                                            "SLI: peer=${ev.peerCallId} sACCM=${ev.sendAccm.toString(16)} rACCM=${ev.recvAccm.toString(16)}"
                                        is ControlMessage.WanErrorNotify ->
                                            "WEN: peer=${ev.peerCallId} crc=${ev.crcErrors} fr=${ev.framingErrors}"
                                        else -> "EV ${ev::class.simpleName}"
                                    }
                                    asyncLog.add(0, line)
                                    if (asyncLog.size > 10) asyncLog.removeAt(asyncLog.lastIndex)
                                }
                            } catch (_: Throwable) {}
                        }
                    } catch (e: Throwable) {
                        lastEvent = "连接失败：${e.message}"
                        channel = null
                    } finally {
                        working = false
                    }
                }
            },
        ) { Text("连接") }

        Button(
            enabled = !working && state == ControlChannel.State.Established,
            onClick = {
                working = true
                scope.launch {
                    try {
                        val reply = withContext(Dispatchers.IO) {
                            channel!!.openCall()
                        }
                        lastEvent = "OCRP OK: serverCallId=${reply.callId} ourCallId=${reply.peerCallId} speed=${reply.connectSpeed}"
                    } catch (e: Throwable) {
                        lastEvent = "呼叫失败：${e.message}"
                    } finally {
                        working = false
                    }
                }
            },
        ) { Text("呼叫") }

        Button(
            enabled = !working && state in listOf(ControlChannel.State.Established, ControlChannel.State.CallUp),
            onClick = {
                working = true
                scope.launch {
                    try {
                        val reply = withContext(Dispatchers.IO) { channel!!.ping() }
                        lastEvent = if (reply != null)
                            "Echo OK id=${reply.identifier}"
                        else
                            "Echo 超时"
                    } finally {
                        working = false
                    }
                }
            },
        ) { Text("Ping") }

        Button(
            enabled = !working && channel != null,
            onClick = {
                working = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { channel?.disconnect() }
                    } finally {
                        asyncJob?.cancel()
                        asyncJob = null
                        channel = null
                        working = false
                    }
                }
            },
        ) { Text("断开") }
    }

    if (peerInfo != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            "服务器: host='${peerInfo.hostName}' vendor='${peerInfo.vendorString}' " +
                "framing=${"0x%x".format(peerInfo.framingCapabilities)}",
            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
        )
    }
    if (session != null) {
        Text(
            "Call-IDs: 本端=${session.localCallId} 服务器=${session.peerCallId} 序号=${session.callSerialNumber}",
            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
        )
    }
    Spacer(Modifier.height(4.dp))
    Text("Echo: ok=$echoOk fail=$echoFail", fontFamily = FontFamily.Monospace, fontSize = 11.sp)

    lastEvent?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
    lastError?.let {
        Spacer(Modifier.height(4.dp))
        Text(it, color = Color.Red, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }

    if (asyncLog.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("服务器事件日志:", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        asyncLog.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
    }
}

@Composable
private fun SessionSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<PptpSession?>(null) }
    val phase = session?.phase?.collectAsState()?.value ?: PptpSession.Phase.Idle
    val lastError = session?.lastError?.collectAsState()?.value
    var server by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("1723") }
    var working by remember { mutableStateOf(false) }

    val lcpState = session?.lcpState() ?: LcpStateMachine.State.Initial
    val auth = session?.negotiatedAuth()
    val callSession = session?.controlChannel()?.session

    Text("④ 全栈一键连接（控制通道 + helper + LCP）", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text("阶段: ${phase.name}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    Spacer(Modifier.height(4.dp))
    Text("LCP: ${lcpState.name}${auth?.let { " · auth=${it.name}" } ?: ""}",
        fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    Spacer(Modifier.height(8.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = server,
            onValueChange = { server = it.trim() },
            label = { Text("PPTP 服务器") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            enabled = session == null || phase in arrayOf(PptpSession.Phase.Closed, PptpSession.Phase.Failed),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.trim() },
            label = { Text("端口") },
            singleLine = true,
            modifier = Modifier.width(96.dp),
            enabled = session == null || phase in arrayOf(PptpSession.Phase.Closed, PptpSession.Phase.Failed),
        )
    }
    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = !working && server.isNotEmpty() &&
                (session == null || phase in arrayOf(PptpSession.Phase.Closed, PptpSession.Phase.Failed)),
            onClick = {
                working = true
                val s = PptpSession(context)
                session = s
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            s.connect(server, port.toIntOrNull() ?: 1723)
                        }
                    } catch (_: Throwable) {
                        // Errors surface via s.lastError; phase already → Failed.
                    } finally {
                        working = false
                    }
                }
            },
        ) { Text("一键连接") }

        Button(
            enabled = !working && session != null && phase !in arrayOf(PptpSession.Phase.Closed, PptpSession.Phase.Failed, PptpSession.Phase.Idle),
            onClick = {
                working = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { session?.disconnect() }
                    } finally {
                        working = false
                    }
                }
            },
        ) { Text("断开") }
    }

    if (callSession != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Call-IDs: 本端=${callSession.localCallId} 服务器=${callSession.peerCallId}  " +
                "GRE tx=${callSession.txCount()} rxSeq=${callSession.rxSeq()}",
            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
        )
    }
    lastError?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = Color.Red, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
    if (phase == PptpSession.Phase.LcpOpen) {
        Spacer(Modifier.height(8.dp))
        Text(
            "✅ LCP Opened — v0.0.5 验收通过。下一步 v0.0.6 加 PAP / MS-CHAP-V2 认证。",
            fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFF1B5E20),
        )
    }
}

/**
 * Build a 12-byte minimal PPTP-style GRE frame (no real PPP payload). The
 * receiver won't honor it as PPTP, but it's a well-formed GRE packet that
 * tcpdump can recognize, sufficient to prove the app → helper → kernel
 * raw-socket → wire path works end-to-end.
 *
 * Header layout (RFC 2637 §4.1):
 *   byte 0: K=1, others 0  → 0x20
 *   byte 1: Ver=1          → 0x01
 *   byte 2-3: Protocol 0x880B (PPP)
 *   byte 4-5: Key high = payload length (4)
 *   byte 6-7: Key low  = Call ID (1)
 *   byte 8-11: "TEST"
 */
private fun buildTestGreFrame(peerIpDotted: String): UdsFrame {
    val peer = Ipv4.parse(peerIpDotted)
    val payload = byteArrayOf(
        0x20, 0x01, 0x88.toByte(), 0x0B,
        0x00, 0x04, 0x00, 0x01,
        'T'.code.toByte(), 'E'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte(),
    )
    return UdsFrame(peer, payload)
}
