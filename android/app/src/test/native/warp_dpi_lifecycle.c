#include <assert.h>
#include <dirent.h>
#include <fcntl.h>
#include <errno.h>
#include <netdb.h>
#include <sys/select.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>

#include "warp_dpi_engine.h"
#include "warp_dpi_arguments.h"
#include "byedpi/proxy.h"
#include "warp_dpi_test_strategies.h"

static char test_port[6];
static bool no_address;

int __real_getaddrinfo(const char *, const char *, const struct addrinfo *, struct addrinfo **);

int __wrap_getaddrinfo(const char *host, const char *port,
        const struct addrinfo *hints, struct addrinfo **out)
{
    assert(!strcmp(host, "api.cloudflareclient.com"));
    assert(!strcmp(port, "443"));
    if (no_address)
        return EAI_NONAME;
    return __real_getaddrinfo("127.0.0.1", test_port, hints, out);
}

static int injected_error;
static bool async_failure;
static bool inject_next;
static int failed_socket = -1;
static int groups[8];
static size_t group_count;

int __real_connect(int fd, const struct sockaddr *addr, socklen_t len);
int __real_getsockopt(int fd, int level, int option, void *value, socklen_t *len);
int __real_create_conn(struct poolhd *, struct eval *, const union sockaddr_u *, evcb_t);

int __wrap_create_conn(struct poolhd *pool, struct eval *client, const union sockaddr_u *addr, evcb_t next)
{
    if (injected_error) {
        assert(group_count < sizeof(groups) / sizeof(*groups));
        groups[group_count++] = client->dp->id;
    }
    return __real_create_conn(pool, client, addr, next);
}

int __wrap_connect(int fd, const struct sockaddr *addr, socklen_t len)
{
    if (inject_next) {
        inject_next = false;
        if (!async_failure) {
            errno = injected_error;
            return -1;
        }
        failed_socket = fd;
    }
    return __real_connect(fd, addr, len);
}

int __wrap_getsockopt(int fd, int level, int option, void *value, socklen_t *len)
{
    if (fd == failed_socket && level == SOL_SOCKET && option == SO_ERROR) {
        failed_socket = -1;
        assert(*len >= sizeof(int));
        *(int *)value = injected_error;
        *len = sizeof(int);
        return 0;
    }
    return __real_getsockopt(fd, level, option, value, len);
}

static size_t short_write_at;
static size_t short_write_count;
static bool would_block;

ssize_t __real_send(int fd, const void *buf, size_t len, int flags);

ssize_t __wrap_send(int fd, const void *buf, size_t len, int flags)
{
    if (short_write_at && len >= short_write_at && !flags) {
        short_write_at = 0;
        short_write_count++;
        if (would_block) {
            errno = EAGAIN;
            return -1;
        }
        return __real_send(fd, buf, 1, flags);
    }
    return __real_send(fd, buf, len, flags);
}

static int count_entries(const char *path)
{
    DIR *dir = opendir(path);
    assert(dir);
    int count = 0;
    while (readdir(dir))
        count++;
    closedir(dir);
    return count;
}

static void readable(int fd)
{
    assert(fd >= 0 && fd < FD_SETSIZE);
    fd_set readable_fds;
    FD_ZERO(&readable_fds);
    FD_SET(fd, &readable_fds);
    struct timeval timeout = {.tv_sec = 3};
    assert(select(fd + 1, &readable_fds, NULL, NULL, &timeout) == 1);
}

static void *stop_from_other_thread(void *engine)
{
    warp_dpi_stop(engine);
    return NULL;
}

static void check_connect_trigger(int listener, int error, bool asynchronous, bool retry)
{
    injected_error = error;
    async_failure = asynchronous;
    inject_next = true;
    group_count = 0;
    char *args[] = {retry ? "-Ac" : "-At"};
    void *engine = warp_dpi_start_args(1, args);
    assert(engine);
    int client = warp_dpi_open_stream(engine);
    assert(client >= 0);
    if (retry)
        assert(write(client, "request", 7) == 7);
    if (asynchronous) {
        readable(listener);
        int rejected = accept(listener, NULL, NULL);
        assert(rejected >= 0);
        close(rejected);
    }
    if (retry) {
        readable(listener);
        int server = accept(listener, NULL, NULL);
        assert(server >= 0);
        readable(server);
        char data[7];
        assert(recv(server, data, sizeof(data), MSG_WAITALL) == sizeof(data));
        assert(!memcmp(data, "request", sizeof(data)));
        assert(write(server, "a", 1) == 1);
        readable(client);
        assert(read(client, data, sizeof(data)) == 1 && data[0] == 'a');
        close(server);
    } else {
        char data;
        readable(client);
        assert(read(client, &data, 1) == 0);
    }
    warp_dpi_stop(engine);
    close(client);
    assert(group_count == (retry ? 2 : 1));
    assert(groups[0] == 0);
    if (retry)
        assert(groups[1] == 1);
    injected_error = 0;
}

static void check_tls_replay(int listener)
{
    char *args[] = {"-As,c"};
    void *engine = warp_dpi_start_args(1, args);
    assert(engine);
    int client = warp_dpi_open_stream(engine);
    assert(client >= 0);
    assert(write(client, fake_tls.data, fake_tls.size) == fake_tls.size);

    for (int attempt = 0; attempt < 2; attempt++) {
        readable(listener);
        int server = accept(listener, NULL, NULL);
        assert(server >= 0);
        struct timeval timeout = {.tv_sec = 3};
        assert(!setsockopt(server, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)));
        char request[16384];
        assert(fake_tls.size <= sizeof(request));
        assert(recv(server, request, fake_tls.size, MSG_WAITALL) == fake_tls.size);
        assert(!memcmp(request, fake_tls.data, fake_tls.size));
        if (!attempt)
            assert(write(server, "not a TLS record", 16) == 16);
        close(server);
    }

    warp_dpi_stop(engine);
    close(client);
}

static void check_short_write(int listener, char *strategy)
{
    static const char request[] = "abcdefghijkl";
    short_write_at = 4;
    short_write_count = 0;
    void *engine = warp_dpi_start_args(1, &strategy);
    assert(engine);
    int client = warp_dpi_open_stream(engine);
    assert(client >= 0);
    assert(write(client, request, sizeof(request)) == sizeof(request));
    readable(listener);
    int server = accept(listener, NULL, NULL);
    assert(server >= 0);
    struct timeval timeout = {.tv_sec = 3};
    assert(!setsockopt(server, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)));
    char received[sizeof(request)];
    assert(recv(server, received, sizeof(received), MSG_WAITALL) == sizeof(received));
    assert(!memcmp(received, request, sizeof(request)));
    warp_dpi_stop(engine);
    close(server);
    close(client);
    assert(short_write_count == 1);
}

static void check_tls_would_block(int listener)
{
    char *strategy[] = {"-r10"};
    would_block = true;
    short_write_at = 1;
    short_write_count = 0;
    void *engine = warp_dpi_start_args(1, strategy);
    assert(engine);
    int client = warp_dpi_open_stream(engine);
    assert(client >= 0);
    assert(write(client, fake_tls.data, fake_tls.size) == fake_tls.size);
    readable(listener);
    int server = accept(listener, NULL, NULL);
    assert(server >= 0);
    struct timeval timeout = {.tv_sec = 3};
    assert(!setsockopt(server, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)));

    // One TLS record split adds exactly one five-byte header.
    unsigned char wire[16384];
    size_t expected = fake_tls.size + 5;
    assert(expected < sizeof(wire));
    assert(recv(server, wire, expected, MSG_WAITALL) == (ssize_t)expected);
    size_t first_record = ((size_t)wire[3] << 8) | wire[4];
    size_t second_header = 5 + first_record;
    assert(first_record && second_header + 5 < expected);
    assert(!memcmp(wire + 5, fake_tls.data + 5, first_record));
    assert(!memcmp(wire + second_header + 5, fake_tls.data + second_header,
        fake_tls.size - second_header));
    assert(recv(server, wire, 1, MSG_DONTWAIT) == -1 && errno == EAGAIN);
    warp_dpi_stop(engine);
    close(server);
    close(client);
    assert(short_write_count == 1);
    would_block = false;
}

static void check_closed_client_does_not_retry(int listener)
{
    char *strategy[] = {"-As,c"};
    void *engine = warp_dpi_start_args(1, strategy);
    assert(engine);
    int client = warp_dpi_open_stream(engine);
    assert(client >= 0);
    assert(write(client, fake_tls.data, fake_tls.size) == fake_tls.size);
    readable(listener);
    int server = accept(listener, NULL, NULL);
    assert(server >= 0);
    struct timeval timeout = {.tv_sec = 3};
    assert(!setsockopt(server, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)));
    char request[16384];
    assert(fake_tls.size <= sizeof(request));
    assert(recv(server, request, fake_tls.size, MSG_WAITALL) == fake_tls.size);
    close(client);
    assert(recv(server, request, 1, 0) == 0);
    fd_set pending;
    FD_ZERO(&pending);
    FD_SET(listener, &pending);
    struct timeval grace = {.tv_usec = 100000};
    assert(select(listener + 1, &pending, NULL, NULL, &grace) == 0);
    warp_dpi_stop(engine);
    close(server);
}

int main(void)
{
    const int descriptors = count_entries("/proc/self/fd");
    char error[WARP_DPI_ERROR_SIZE];
    const struct {
        char *argv[4];
        int count;
        const char *message;
    } invalid[] = {
        {{"--split", "0++"}, 2, "Invalid value"},
        {{"-d1:0:2+sm"}, 1, "Invalid value"},
        {{"-r1:999999999"}, 1, "Invalid value"},
        {{"--fake-offset=-999999999"}, 1, "Invalid value"},
        {{"-At,r,s,c,unknown"}, 1, "Invalid value"},
        {{"-Qr,m=Inf"}, 1, "Invalid value"},
        {{"--round=3-1"}, 1, "Invalid value"},
        {{"--pf=443-80"}, 1, "Invalid value"},
        {{"-n", "https://other.example/"}, 2, "requires a DNS name"},
        {{"-n", "{other}"}, 2, "requires a DNS name"},
        {{"--split"}, 1, "Missing value"},
        {{"--split", "--connect-to=127.0.0.1"}, 2, "Missing value"},
        {{"--connect-to=127.0.0.1"}, 1, "Unsupported or restricted"},
        {{"-C127.0.0.1"}, 1, "Unsupported or restricted"},
        {{"-I127.0.0.1"}, 1, "Unsupported or restricted"},
        {{"--port=1080"}, 1, "Unsupported or restricted"},
        {{"-SZG"}, 1, "Unsupported or restricted"},
        {{"--protect-path=/tmp/protect"}, 1, "Unsupported or restricted"},
        {{"--fake-data=/proc/self/maps"}, 1, "Unsupported or restricted"},
        {{"--hosts=/proc/self/maps"}, 1, "Unsupported or restricted"},
        {{"--spl=1"}, 1, "Unsupported or restricted"},
        {{"--", "-s1"}, 2, "Unsupported or restricted"},
    };
    for (size_t i = 0; i < sizeof(invalid) / sizeof(*invalid); i++) {
        char *args[4];
        memcpy(args, invalid[i].argv, sizeof(args));
        assert(warp_dpi_validate_args(invalid[i].count, args, error));
        assert(strstr(error, invalid[i].message));
        assert(!warp_dpi_start_args(invalid[i].count, args));
        assert(count_entries("/proc/self/fd") == descriptors);
        assert(!warp_dpi_validate_args(0, NULL, error));
    }
    char *complex[] = {
        "-SZ", "-s1:5:2+sm", "--disorder=2:3:4+he", "-q", "3+nr",
        "--fake-offset", "4", "-f-203", "-Q", "r,o,m=-100", "--fake-sni={sni}",
        "-n", "example.org", "--auto=torst,ssl_err", "-r-5+se",
        "--round=1-2", "-V443", "-e\\x2a", "-Mh,d,r", "-t", "6",
        "--tlsminor=2", "--await-int=10"
    };
    int complex_count = sizeof(complex) / sizeof(*complex);
    assert(complex_count > 16);
    assert(!warp_dpi_validate_args(complex_count, complex, error));
    struct warp_arguments normalized;
    assert(!warp_arguments_prepare(complex_count, complex, &normalized, error));
    bool has_warp_sni = false;
    for (int i = 2; i < normalized.count; i++)
        has_warp_sni |= !strcmp(normalized.argv[i], "--fake-sni=" WARP_DPI_HOST);
    assert(has_warp_sni);
    warp_arguments_free(&normalized);

    char *large[WARP_DPI_MAX_ARGS + 1];
    for (size_t i = 0; i < sizeof(large) / sizeof(*large); i++)
        large[i] = "-s1";
    assert(!warp_dpi_validate_args(WARP_DPI_MAX_ARGS, large, error));
    assert(warp_dpi_validate_args(WARP_DPI_MAX_ARGS + 1, large, error));
    for (int i = 0; i < 64; i++)
        large[i] = "-At";
    assert(!warp_dpi_validate_args(62, large, error));
    assert(warp_dpi_validate_args(64, large, error));

    for (size_t i = 0; i < sizeof(strategies) / sizeof(*strategies); i++) {
        assert(!warp_dpi_validate_args(strategies[i].length, strategies[i].argv, error));
        void *engine = warp_dpi_start_args(strategies[i].length, strategies[i].argv);
        assert(engine);
        assert(!warp_dpi_start_args(0, NULL));
        assert(warp_dpi_validate_args(0, NULL, error));
        assert(strstr(error, "already running"));
        pthread_t stopper;
        assert(!pthread_create(&stopper, NULL, stop_from_other_thread, engine));
        assert(!pthread_join(stopper, NULL));
        assert(count_entries("/proc/self/fd") == descriptors);
    }

    int listener = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    assert(listener >= 0);
    struct sockaddr_in addr = {
        .sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK)
    };
    assert(!bind(listener, (struct sockaddr *)&addr, sizeof(addr)));
    assert(!listen(listener, 1));
    socklen_t len = sizeof(addr);
    assert(!getsockname(listener, (struct sockaddr *)&addr, &len));
    snprintf(test_port, sizeof(test_port), "%u", ntohs(addr.sin_port));

    void *engine = warp_dpi_start_args(0, NULL);
    assert(engine);
    for (int i = 0; i < 20; i++) {
        int client = warp_dpi_open_stream(engine);
        assert(client >= 0 && fcntl(client, F_GETFD) & FD_CLOEXEC);
        struct sockaddr_storage peer;
        len = sizeof(peer);
        assert(!getpeername(client, (struct sockaddr *)&peer, &len));
        assert(peer.ss_family == AF_UNIX);
        readable(listener);
        int server = accept(listener, NULL, NULL);
        assert(server >= 0);
        char byte = 0;
        assert(write(client, "q", 1) == 1);
        readable(server);
        assert(read(server, &byte, 1) == 1 && byte == 'q');
        assert(write(server, "a", 1) == 1);
        readable(client);
        assert(read(client, &byte, 1) == 1 && byte == 'a');
        close(server);
        close(client);
    }
    int client = warp_dpi_open_stream(engine);
    assert(client >= 0);
    warp_dpi_stop(engine);
    readable(client);
    char byte;
    assert(read(client, &byte, 1) == 0);
    close(client);
    char *temporary_sni = strdup(WARP_DPI_HOST);
    assert(temporary_sni);
    char *fake_args[] = {"-n", temporary_sni, "-f12", "-O4", "-Qr", "-t8"};
    engine = warp_dpi_start_args(sizeof(fake_args) / sizeof(*fake_args), fake_args);
    free(temporary_sni);
    assert(engine);
    client = warp_dpi_open_stream(engine);
    assert(client >= 0);
    readable(listener);
    int server = accept(listener, NULL, NULL);
    assert(server >= 0);
    assert(write(client, fake_tls.data, fake_tls.size) == fake_tls.size);
    readable(server);
    char fake_bytes[32];
    assert(read(server, fake_bytes, sizeof(fake_bytes)) > 0);
    warp_dpi_stop(engine);
    close(server);
    close(client);
    check_connect_trigger(listener, ECONNREFUSED, false, true);
    check_connect_trigger(listener, ECONNRESET, true, true);
    check_connect_trigger(listener, ETIMEDOUT, false, true);
    check_connect_trigger(listener, ECONNREFUSED, false, false);
    check_tls_replay(listener);
    check_short_write(listener, "-s1");
    check_short_write(listener, "-s8");
    check_tls_would_block(listener);
    check_closed_client_does_not_retry(listener);
    close(listener);

    no_address = true;
    engine = warp_dpi_start_args(0, NULL);
    assert(engine);
    client = warp_dpi_open_stream(engine);
    assert(client >= 0);
    readable(client);
    assert(read(client, &byte, 1) == 0);
    warp_dpi_stop(engine);
    close(client);
    assert(count_entries("/proc/self/fd") == descriptors);
    puts("78 presets, private TCP retries, rejection paths, cancellation, and descriptor cleanup passed");
    return 0;
}
