/*
 * pptp_helper — root-only native helper.
 *
 * Lifecycle: spawned by the app via libsu (`su -c <path> <mode> <args...>`).
 * Owns a raw IPPROTO_GRE socket that the app process (untrusted_app) cannot
 * acquire itself.
 *
 * Modes:
 *   probe  <iface>             open AF_INET/SOCK_RAW/IPPROTO_GRE,
 *                              SO_BINDTODEVICE, print "OK <iface>", exit.
 *   listen <iface>             probe + recv loop, dump incoming GRE headers
 *                              to stderr. For tcpdump-style verification.
 *   bridge <iface> <uds>       probe + connect AF_UNIX to <uds> (abstract
 *                              namespace if prefixed with '@', otherwise
 *                              filesystem path), then poll-multiplex:
 *                                UDS ──▶ sendto(raw, ...)   (TX from app)
 *                                raw ──▶ writev(UDS, ...)   (RX to app)
 *                              Frame on UDS (both directions, big-endian):
 *                                +0  uint32 peer_ip  (TX: dst, RX: src)
 *                                +4  uint16 length
 *                                +6  N bytes payload
 *                              On TX the payload is the GRE frame the kernel
 *                              will wrap in IPv4; on RX it is the full IPv4
 *                              packet as the kernel delivers it (because
 *                              raw IPPROTO sockets prepend the IP header on
 *                              receive).
 *
 * Error output: "ERR <stage> <errno> <strerror>" on stdout, exit code != 0.
 * Stages: socket | bindtodevice | uds-socket | uds-connect | recv | send.
 */

#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define LTAG "pptp_helper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LTAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LTAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LTAG, __VA_ARGS__)

#ifndef IPPROTO_GRE
#define IPPROTO_GRE 47
#endif

#define MAX_PACKET 2048
#define UDS_FRAME_HDR 6 /* 4 bytes peer_ip + 2 bytes length */

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

    uint8_t buf[MAX_PACKET];
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
        fprintf(stderr, "GRE from %s len=%zd: ", ip, n);
        hex_dump(buf, n, 32);
    }
    close(s);
    return 0;
}

/*
 * Connect to the app's AF_UNIX socket. If path begins with '@' we use the
 * Linux abstract namespace (path[1..] becomes the abstract name after a
 * leading NUL byte); otherwise we treat it as a filesystem path.
 */
static int connect_uds(const char *path) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        report_errno("uds-socket");
        return -1;
    }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    socklen_t addrlen;
    if (path[0] == '@') {
        size_t n = strlen(path + 1);
        if (n == 0 || n > sizeof(addr.sun_path) - 1) {
            errno = ENAMETOOLONG;
            report_errno("uds-connect");
            close(fd);
            return -1;
        }
        addr.sun_path[0] = '\0';
        memcpy(addr.sun_path + 1, path + 1, n);
        addrlen = (socklen_t) (offsetof(struct sockaddr_un, sun_path) + 1 + n);
    } else {
        size_t n = strlen(path);
        if (n >= sizeof(addr.sun_path)) {
            errno = ENAMETOOLONG;
            report_errno("uds-connect");
            close(fd);
            return -1;
        }
        memcpy(addr.sun_path, path, n);
        addrlen = sizeof(addr);
    }
    if (connect(fd, (struct sockaddr *) &addr, addrlen) < 0) {
        report_errno("uds-connect");
        close(fd);
        return -1;
    }
    return fd;
}

static int set_nonblock(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) return -1;
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

static int run_bridge(const char *iface, const char *uds_path) {
    int raw = open_gre_socket(iface);
    if (raw < 0) return 1;

    int uds = connect_uds(uds_path);
    if (uds < 0) {
        close(raw);
        return 1;
    }

    set_nonblock(raw);
    set_nonblock(uds);

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGPIPE, SIG_IGN);

    /* Greet the app. The app uses this line to detect bridge readiness. */
    fprintf(stdout, "OK bridge %s\n", iface);
    fflush(stdout);
    fprintf(stderr, "pptp_helper: bridge up on %s via %s\n", iface, uds_path);
    LOGI("bridge up on %s via %s", iface, uds_path);

    /* Per-direction counters surfaced to logcat every 5 s for live diagnosis. */
    uint64_t tx_packets = 0, tx_errors = 0;
    uint64_t rx_packets = 0, rx_errors = 0;
    time_t last_report = time(NULL);

    struct pollfd pfd[2];
    pfd[0].fd = uds;
    pfd[0].events = POLLIN;
    pfd[1].fd = raw;
    pfd[1].events = POLLIN;

    uint8_t in_buf[8 * 1024]; /* accumulating buffer for partial UDS frames */
    size_t in_len = 0;
    uint8_t rx_buf[MAX_PACKET];

    int rc = 0;
    while (!g_stop) {
        int n = poll(pfd, 2, 1000);
        if (n < 0) {
            if (errno == EINTR) continue;
            report_errno("poll");
            rc = 1;
            break;
        }
        if (n == 0) continue;

        /* UDS → raw socket (TX from app) */
        if (pfd[0].revents & POLLIN) {
            ssize_t r = read(uds, in_buf + in_len, sizeof(in_buf) - in_len);
            if (r == 0) {
                fprintf(stderr, "pptp_helper: app closed UDS, exiting\n");
                break;
            }
            if (r < 0) {
                if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
                    report_errno("recv");
                    rc = 1;
                    break;
                }
            } else {
                in_len += (size_t) r;
                /* Drain complete frames. */
                size_t off = 0;
                while (in_len - off >= UDS_FRAME_HDR) {
                    const uint8_t *h = in_buf + off;
                    uint32_t peer = ((uint32_t) h[0] << 24) | ((uint32_t) h[1] << 16) |
                                    ((uint32_t) h[2] << 8) | (uint32_t) h[3];
                    uint16_t len = (uint16_t) (((uint16_t) h[4] << 8) | (uint16_t) h[5]);
                    if (in_len - off < (size_t) UDS_FRAME_HDR + len) break;
                    if (len > MAX_PACKET) {
                        fprintf(stderr, "pptp_helper: oversized frame %u, dropping\n", len);
                    } else {
                        struct sockaddr_in dst;
                        memset(&dst, 0, sizeof(dst));
                        dst.sin_family = AF_INET;
                        dst.sin_addr.s_addr = htonl(peer);
                        ssize_t s = sendto(raw, h + UDS_FRAME_HDR, len, 0,
                                           (struct sockaddr *) &dst, sizeof(dst));
                        if (s < 0) {
                            int e = errno;
                            tx_errors++;
                            fprintf(stderr, "pptp_helper: sendto errno=%d (%s)\n", e, strerror(e));
                            LOGW("sendto failed errno=%d (%s) peer=0x%08x len=%u",
                                 e, strerror(e), peer, len);
                        } else {
                            tx_packets++;
                        }
                    }
                    off += UDS_FRAME_HDR + len;
                }
                if (off > 0) {
                    memmove(in_buf, in_buf + off, in_len - off);
                    in_len -= off;
                }
            }
        }
        if (pfd[0].revents & (POLLHUP | POLLERR)) {
            fprintf(stderr, "pptp_helper: UDS hangup\n");
            break;
        }

        /* raw socket → UDS (RX to app) */
        if (pfd[1].revents & POLLIN) {
            struct sockaddr_in from;
            socklen_t fromlen = sizeof(from);
            ssize_t r = recvfrom(raw, rx_buf, sizeof(rx_buf), 0,
                                 (struct sockaddr *) &from, &fromlen);
            if (r < 0) {
                if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
                    report_errno("recv");
                    rc = 1;
                    break;
                }
            } else if (r > 0 && r <= 0xFFFF) {
                rx_packets++;
                uint8_t hdr[UDS_FRAME_HDR];
                uint32_t src = ntohl(from.sin_addr.s_addr);
                hdr[0] = (uint8_t) (src >> 24);
                hdr[1] = (uint8_t) (src >> 16);
                hdr[2] = (uint8_t) (src >> 8);
                hdr[3] = (uint8_t) src;
                hdr[4] = (uint8_t) (r >> 8);
                hdr[5] = (uint8_t) r;
                struct iovec iov[2];
                iov[0].iov_base = hdr;
                iov[0].iov_len = sizeof(hdr);
                iov[1].iov_base = rx_buf;
                iov[1].iov_len = (size_t) r;
                ssize_t w = writev(uds, iov, 2);
                if (w < 0) {
                    if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
                        rx_errors++;
                        report_errno("send");
                        rc = 1;
                        break;
                    }
                }
            }
        }
        if (pfd[1].revents & (POLLHUP | POLLERR)) {
            fprintf(stderr, "pptp_helper: raw socket hangup\n");
            break;
        }

        time_t now = time(NULL);
        if (now - last_report >= 5) {
            LOGI("stats tx=%llu (errs=%llu)  rx=%llu (errs=%llu)",
                 (unsigned long long) tx_packets, (unsigned long long) tx_errors,
                 (unsigned long long) rx_packets, (unsigned long long) rx_errors);
            last_report = now;
        }
    }

    LOGI("bridge exiting; final stats tx=%llu (errs=%llu) rx=%llu (errs=%llu)",
         (unsigned long long) tx_packets, (unsigned long long) tx_errors,
         (unsigned long long) rx_packets, (unsigned long long) rx_errors);
    close(uds);
    close(raw);
    return rc;
}

static int usage(const char *prog) {
    fprintf(stdout, "ERR usage 0 %s {probe|listen|bridge} <iface> [<uds_path>]\n", prog);
    return 2;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IOLBF, 0);

    if (argc < 3) return usage(argv[0]);

    const char *mode = argv[1];
    const char *iface = argv[2];

    if (strcmp(mode, "probe") == 0) return run_probe(iface);
    if (strcmp(mode, "listen") == 0) return run_listen(iface);
    if (strcmp(mode, "bridge") == 0) {
        if (argc < 4) return usage(argv[0]);
        return run_bridge(iface, argv[3]);
    }
    return usage(argv[0]);
}
