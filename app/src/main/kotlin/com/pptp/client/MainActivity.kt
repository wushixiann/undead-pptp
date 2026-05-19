package com.pptp.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pptp.client.helper.HelperLifecycle
import com.pptp.client.helper.ProbeResult
import com.pptp.client.util.NetworkUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold { inner ->
                    ProbeScreen(inner)
                }
            }
        }
    }
}

@Composable
private fun ProbeScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String>(context.getString(R.string.helper_check_idle)) }
    var running by remember { mutableStateOf(false) }
    var helperPath by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        helperPath = HelperLifecycle.helperBinaryPath(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("${stringResource(R.string.version_label)}: ${BuildConfig.VERSION_NAME}")
        Spacer(Modifier.height(8.dp))
        Text("${stringResource(R.string.milestone_label)}: ${stringResource(R.string.milestone_v001)}")
        Spacer(Modifier.height(16.dp))
        Text("${stringResource(R.string.helper_path_label)}: $helperPath")
        Spacer(Modifier.height(16.dp))
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
        Spacer(Modifier.height(16.dp))
        Text(status)
    }
}
