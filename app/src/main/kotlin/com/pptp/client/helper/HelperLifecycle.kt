package com.pptp.client.helper

import android.content.Context
import com.topjohnwu.superuser.Shell
import java.io.File

sealed class ProbeResult {
    data class Ok(val iface: String, val diagnostic: String) : ProbeResult()
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
            return ProbeResult.Fail("helper 二进制不存在：$path\n（NDK 未配置编译，或本机 ABI 与打包 ABI 不匹配）")
        }

        // Shell.getShell() blocks until the shell is initialized. On first call this
        // is when libsu actually spawns `su` and Magisk shows its grant dialog.
        // The previous code mistakenly used Shell.isAppGrantedRoot() which returns
        // null before any shell exists, causing a spurious "not granted" error.
        val shell: Shell = try {
            Shell.getShell()
        } catch (e: Throwable) {
            return ProbeResult.Fail("无法启动 shell：${e.message ?: e.javaClass.simpleName}")
        }

        if (!shell.isRoot) {
            val idOut = Shell.cmd("id").exec().out.joinToString(" ").trim()
            return ProbeResult.Fail(
                buildString {
                    append("libsu 拿到的是非 root shell。\n")
                    append("id → $idOut\n")
                    append("排查：\n")
                    append("  1) Magisk 是否已安装并能成功打开?\n")
                    append("  2) Magisk 设置→超级用户 里是否能看到本应用并已 Grant?\n")
                    append("  3) `which su` 在 adb shell 中能否返回路径?")
                },
            )
        }

        val cmd = buildString {
            append('"').append(path).append('"').append(' ')
            append("probe").append(' ')
            append(shellQuote(iface))
        }
        val result = Shell.cmd(cmd).exec()
        val stdout = result.out.joinToString("\n").trim()
        return if (result.code == 0 && stdout.startsWith("OK")) {
            val uname = Shell.cmd("id; getenforce; uname -r").exec().out.joinToString(" | ").trim()
            ProbeResult.Ok(iface, "root shell ✓\n$uname")
        } else {
            ProbeResult.Fail(
                if (stdout.isNotEmpty()) stdout else "helper exit=${result.code}（空输出）",
            )
        }
    }

    private fun shellQuote(s: String): String {
        if (s.isEmpty()) return "''"
        if (s.all { it.isLetterOrDigit() || it in "_-.+:/" }) return s
        return "'" + s.replace("'", "'\\''") + "'"
    }
}
