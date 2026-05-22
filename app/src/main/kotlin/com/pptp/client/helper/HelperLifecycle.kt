package com.pptp.client.helper

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File

sealed class BridgeResult {
    data class Ok(val bridge: UdsBridge) : BridgeResult()
    data class Fail(val message: String) : BridgeResult()
}

object HelperLifecycle {

    private const val TAG = "HelperLifecycle"

    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10),
        )
    }

    /**
     * The native helper is packaged as a .so in jniLibs so the Android installer
     * extracts it to applicationInfo.nativeLibraryDir with execute permission.
     */
    fun helperBinaryPath(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libpptp_helper.so").absolutePath

    /**
     * Start the helper in `bridge` mode. Returns a connected [UdsBridge] (the
     * helper has already attached) or [BridgeResult.Fail] if anything along the
     * way went wrong: missing binary, no root, helper exec failure, or accept
     * timeout.
     *
     * The bridge runs until [UdsBridge.stop] is called. On exit (clean or
     * unexpected) [onHelperExit] fires with the exit code and concatenated
     * helper stdout/stderr.
     *
     * NOTE: libsu uses a single shared shell — while the bridge is running, no
     * other Shell.cmd() calls can be issued. We accept this for now since the
     * bridge is the only long-running helper we need.
     */
    suspend fun startBridge(
        context: Context,
        iface: String,
        connectTimeoutMs: Long = 5_000,
        onHelperExit: (exitCode: Int, output: String) -> Unit = { _, _ -> },
    ): BridgeResult {
        val path = helperBinaryPath(context)
        if (!File(path).exists()) {
            return BridgeResult.Fail("helper 二进制不存在：$path")
        }
        val shell = try {
            Shell.getShell()
        } catch (e: Throwable) {
            return BridgeResult.Fail("无法启动 shell：${e.message}")
        }
        if (!shell.isRoot) {
            return BridgeResult.Fail("libsu 拿到的不是 root shell（先用 probe 排查）")
        }

        val bridge = UdsBridge()
        val socketName = bridge.start()
        val cmd = buildString {
            append('"').append(path).append('"').append(' ')
            append("bridge").append(' ')
            append(shellQuote(iface)).append(' ')
            // '@' prefix instructs the C side to use the Linux abstract namespace.
            append(shellQuote("@$socketName"))
        }
        Log.i(TAG, "spawning helper: $cmd")
        Shell.cmd(cmd).submit { result ->
            val output = (result.out + result.err).joinToString("\n").trim()
            Log.i(TAG, "helper exited code=${result.code} output=$output")
            onHelperExit(result.code, output)
        }

        val ok = bridge.awaitConnected(connectTimeoutMs)
        return if (ok) {
            BridgeResult.Ok(bridge)
        } else {
            bridge.stop()
            BridgeResult.Fail(
                "helper 未在 ${connectTimeoutMs}ms 内连入 UDS：${bridge.lastError.value ?: "状态=${bridge.state.value}"}",
            )
        }
    }

    private fun shellQuote(s: String): String {
        if (s.isEmpty()) return "''"
        if (s.all { it.isLetterOrDigit() || it in "_-.+:/@" }) return s
        return "'" + s.replace("'", "'\\''") + "'"
    }
}
