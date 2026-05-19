package com.pptp.client.helper

import android.content.Context
import com.topjohnwu.superuser.Shell
import java.io.File

sealed class ProbeResult {
    data class Ok(val iface: String) : ProbeResult()
    data class Fail(val message: String) : ProbeResult()
}

object HelperLifecycle {

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
     * Run the helper in `probe` mode: opens AF_INET/SOCK_RAW/IPPROTO_GRE,
     * binds to [iface] via SO_BINDTODEVICE, then exits.
     *
     * Returns Ok if the helper exits 0 and stdout's first token is "OK".
     */
    fun probe(context: Context, iface: String): ProbeResult {
        val path = helperBinaryPath(context)
        if (!File(path).exists()) {
            return ProbeResult.Fail("helper 二进制不存在：$path（NDK 未配置或 APK 未打包 native lib）")
        }
        if (Shell.isAppGrantedRoot() != true) {
            return ProbeResult.Fail("未获得 root 权限（libsu 未授权）")
        }
        val cmd = buildString {
            append('"').append(path).append('"').append(' ')
            append("probe").append(' ')
            append(shellQuote(iface))
        }
        val result = Shell.cmd(cmd).exec()
        val output = result.out.joinToString("\n").trim()
        return if (result.code == 0 && output.startsWith("OK")) {
            ProbeResult.Ok(iface)
        } else {
            val detail = if (output.isNotEmpty()) output else "exit code ${result.code}"
            ProbeResult.Fail(detail)
        }
    }

    private fun shellQuote(s: String): String {
        if (s.isEmpty()) return "''"
        if (s.all { it.isLetterOrDigit() || it in "_-.+:/" }) return s
        return "'" + s.replace("'", "'\\''") + "'"
    }
}
