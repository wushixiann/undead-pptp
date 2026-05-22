package com.pptp.client

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pptp.client.vpn.PptpVpnService
import com.pptp.client.util.SettingsStore

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
        Spacer(Modifier.height(16.dp))
        SessionSection()
    }
}

@Composable
private fun SessionSection() {
    val context = LocalContext.current
    val state = PptpVpnService.observable.collectAsState().value

    val settings = remember { SettingsStore(context) }
    var server by remember { mutableStateOf(settings.server) }
    var port by remember { mutableStateOf(settings.port.toString()) }
    var username by remember { mutableStateOf(settings.username) }
    var password by remember { mutableStateOf("") }
    var pendingStart by remember { mutableStateOf(false) }

    val running = state.connecting || state.tunUp || state.phase !in arrayOf("Idle", "Closed", "Failed")
    val canEdit = !running

    fun sendStart() {
        settings.saveConnection(server, port.toIntOrNull() ?: 1723, username)
        val intent = Intent(context, PptpVpnService::class.java).apply {
            action = PptpVpnService.ACTION_START
            putExtra(PptpVpnService.EXTRA_HOST, server)
            putExtra(PptpVpnService.EXTRA_PORT, port.toIntOrNull() ?: 1723)
            putExtra(PptpVpnService.EXTRA_USERNAME, username)
            putExtra(PptpVpnService.EXTRA_PASSWORD, password)
        }
        context.startForegroundService(intent)
    }

    val prepareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && pendingStart) {
            sendStart()
        }
        pendingStart = false
    }

    Text("PPTP VPN", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text("阶段: ${state.phase}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    if (state.iface.isNotEmpty()) {
        val ifaceWarn = state.iface.startsWith("rmnet") || state.iface.startsWith("ccmni")
        Text(
            "底层接口: ${state.iface}" +
                if (ifaceWarn) "  ⚠️ 蜂窝网常封 GRE，建议切 WiFi" else "",
            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            color = if (ifaceWarn) Color(0xFFB71C1C) else Color.Unspecified,
        )
    }
    if (state.greTx > 0 || state.greRx > 0) {
        val rxStalled = state.greTx > 3 && state.greRx == 0
        Text(
            "GRE: TX=${state.greTx}  RX=${state.greRx}" +
                if (rxStalled) "  ⚠️ 发出去没回包" else "",
            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            color = if (rxStalled) Color(0xFFB71C1C) else Color.Unspecified,
        )
    }
    if (state.localIp.isNotEmpty()) {
        Text(
            "TUN 已建立：本端 ${state.localIp}  对端 ${state.peerIp}",
            fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFF1B5E20),
        )
    }
    if (state.mppeActive) {
        Text(
            "🔒 MPPE-128 stateless 已启用",
            fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFF1B5E20),
        )
    }
    Spacer(Modifier.height(8.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = server, onValueChange = { server = it.trim() },
            label = { Text("PPTP 服务器") }, singleLine = true,
            modifier = Modifier.weight(1f), enabled = canEdit,
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = port, onValueChange = { port = it.trim() },
            label = { Text("端口") }, singleLine = true,
            modifier = Modifier.width(96.dp), enabled = canEdit,
        )
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = username, onValueChange = { username = it },
        label = { Text("用户名") }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), enabled = canEdit,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = password, onValueChange = { password = it },
        label = { Text("密码") }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), enabled = canEdit,
    )
    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = canEdit && server.isNotEmpty() && username.isNotEmpty(),
            onClick = {
                val prepare = VpnService.prepare(context)
                if (prepare != null) {
                    pendingStart = true
                    prepareLauncher.launch(prepare)
                } else {
                    sendStart()
                }
            },
        ) { Text("连接 VPN") }

        Button(
            enabled = running,
            onClick = {
                val intent = Intent(context, PptpVpnService::class.java).apply {
                    action = PptpVpnService.ACTION_STOP
                }
                context.startService(intent)
            },
        ) { Text("断开") }
    }

    state.lastError?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = Color.Red, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}
