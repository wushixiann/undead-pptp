/*
 * pptp_helper — root-only native helper.
 *
 * Lifecycle: spawned by the app via libsu (`su -c <path> <mode> <args...>`).
 * In v0.0.1 the helper only validates that this process can acquire a raw GRE
 * socket on the target device — the foundation everything else builds on.
 *
 * Modes:
 *   probe <iface>   open AF_INET/SOCK_RAW/IPPROTO_GRE, SO_BINDTODEVICE,
 *                   print "OK <iface>" on success, exit 0.
 *   listen <iface>  probe + recv loop, dump incoming GRE headers to stderr.
 *                   Used for tcpdump-style verification from adb shell.
 *
 * Error output: "ERR <stage> <errno> <strerror>" on stdout, exit code != 0.
 * Stage is one of: socket | bindtodevice | recv.
 */

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

#ifndef IPPROTO_GRE
#define IPPROTO_GRE 47
#endif

static volatile sig_atomic_t g_stop = 0;

static void on_signal(int signo) {
    (void) signo;
    g_stop = 1;
}

static int report_errno(const char *stage) {
    int err = errno;
    fprintf(stdout, "ERR %s %d %s\n", stage, err, strerror(err));
    fflush(stdout);
    return err;
}

static int open_gre_socket(const char *iface) {
    int s = socket(AF_INET, SOCK_RAW, IPPROTO_GRE);
    if (s < 0) {
        report_errno("socket");
        return -1;
    }
    /*
     * SO_BINDTODEVICE requires CAP_NET_RAW (we have it as root with
     * magisk SELinux domain). Pinning the socket to the underlay
     * interface keeps GRE traffic off the VPN tun once VpnService is up.
     */
    if (setsockopt(s, SOL_SOCKET, SO_BINDTODEVICE, iface, (socklen_t) strlen(iface)) < 0) {
        report_errno("bindtodevice");
        close(s);
        return -1;
    }
    return s;
}

static int run_probe(const char *iface) {
    int s = open_gre_socket(iface);
    if (s < 0) return 1;
    fprintf(stdout, "OK %s\n", iface);
    fflush(stdout);
    close(s);
    return 0;
}

static void hex_dump(const uint8_t *buf, ssize_t n, ssize_t max) {
    ssize_t lim = n < max ? n : max;
    for (ssize_t i = 0; i < lim; i++) {
        fprintf(stderr, "%02x", buf[i]);
        if ((i & 1) == 1) fputc(' ', stderr);
    }
    if (n > max) fprintf(stderr, "... (%zd more)", n - max);
    fputc('\n', stderr);
}

static int run_listen(const char *iface) {
    int s = open_gre_socket(iface);
    if (s < 0) return 1;

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);

    fprintf(stderr, "pptp_helper: listening for GRE on %s (Ctrl-C to stop)\n", iface);
    fprintf(stdout, "OK %s\n", iface);
    fflush(stdout);

    uint8_t buf[2048];
    while (!g_stop) {
        struct sockaddr_in from;
        socklen_t fromlen = sizeof(from);
        ssize_t n = recvfrom(s, buf, sizeof(buf), 0,
                             (struct sockaddr *) &from, &fromlen);
        if (n < 0) {
            if (errno == EINTR) continue;
            report_errno("recv");
            close(s);
            return 1;
        }
        char ip[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &from.sin_addr, ip, sizeof(ip));
        /*
         * Raw IPv4 sockets on Linux deliver the IP header along with the
         * payload, so byte 0 is the IP version+IHL and the GRE header starts
         * at IHL*4. For v0.0.1 we just hex-dump the whole packet.
         */
        fprintf(stderr, "GRE from %s len=%zd: ", ip, n);
        hex_dump(buf, n, 32);
    }
    close(s);
    return 0;
}

static int usage(const char *prog) {
    fprintf(stdout, "ERR usage 0 %s {probe|listen} <iface>\n", prog);
    return 2;
}

int main(int argc, char **argv) {
    /* Line-buffer stdout so the Kotlin side reads results without delay. */
    setvbuf(stdout, NULL, _IOLBF, 0);

    if (argc < 3) return usage(argv[0]);

    const char *mode = argv[1];
    const char *iface = argv[2];

    if (strcmp(mode, "probe") == 0) return run_probe(iface);
    if (strcmp(mode, "listen") == 0) return run_listen(iface);
    return usage(argv[0]);
}
