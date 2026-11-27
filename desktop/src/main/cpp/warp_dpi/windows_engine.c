#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <process.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "byedpi/proxy.h"
#include "warp_dpi_arguments.h"
#include "windows_engine.h"

int byedpi_configure(int argc, char **argv, bool runtime, char *error);
void clear_params(void);
void byedpi_windows_drain(void);
static volatile LONG owner;

struct engine {
    struct warp_arguments args;
    SOCKET listener, wake, worker_wake, stream;
    HANDLE worker;
    volatile LONG stopping;
};

static void close_socket(SOCKET *s) {
    if (*s != INVALID_SOCKET) { closesocket(*s); *s = INVALID_SOCKET; }
}

static int configure(struct warp_arguments *args, bool runtime, char *error) {
    if (InterlockedCompareExchange(&owner, 1, 0) != 0) {
        snprintf(error, WARP_DPI_ERROR_SIZE, "The ByeDPI engine is already running.");
        return -1;
    }
    int rc = byedpi_configure(args->count, args->argv, runtime, error);
    if (rc || !runtime) {
        clear_params();
        InterlockedExchange(&owner, 0);
    }
    return rc;
}

int win_dpi_validate(int count, char **tokens, char *error) {
    struct warp_arguments args;
    if (warp_arguments_prepare(count, tokens, &args, error)) return -1;
    int rc = configure(&args, false, error);
    warp_arguments_free(&args);
    return rc;
}

void *win_dpi_start(int count, char **tokens) {
    struct engine *e = calloc(1, sizeof(*e));
    char error[WARP_DPI_ERROR_SIZE];
    if (!e) return NULL;
    e->listener = e->wake = e->worker_wake = e->stream = INVALID_SOCKET;
    if (warp_arguments_prepare(count, tokens, &e->args, error) ||
            configure(&e->args, true, error)) {
        warp_arguments_free(&e->args);
        free(e);
        return NULL;
    }
    return e;
}

static SOCKET local_listener(int *port) {
    SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s == INVALID_SOCKET) return s;
    BOOL exclusive = TRUE;
    struct sockaddr_in addr = {0};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    int length = sizeof(addr);
    if (setsockopt(s, SOL_SOCKET, SO_EXCLUSIVEADDRUSE, (char *)&exclusive, sizeof(exclusive)) ||
        bind(s, (struct sockaddr *)&addr, sizeof(addr)) || listen(s, 1) ||
        getsockname(s, (struct sockaddr *)&addr, &length)) {
        closesocket(s);
        return INVALID_SOCKET;
    }
    *port = ntohs(addr.sin_port);
    return s;
}

/* Accept only the endpoint whose source port is known to our process. */
static SOCKET accept_local(SOCKET listener, int expected_port) {
    fd_set readable;
    FD_ZERO(&readable);
    FD_SET(listener, &readable);
    struct timeval timeout = {1, 0};
    if (select(0, &readable, NULL, NULL, &timeout) != 1) return INVALID_SOCKET;
    struct sockaddr_in peer = {0};
    int size = sizeof(peer);
    SOCKET s = accept(listener, (struct sockaddr *)&peer, &size);
    if (s != INVALID_SOCKET && (peer.sin_addr.s_addr != htonl(INADDR_LOOPBACK) ||
            ntohs(peer.sin_port) != expected_port)) {
        closesocket(s);
        s = INVALID_SOCKET;
    }
    return s;
}

static int wake_pair(SOCKET *writer, SOCKET *reader) {
    int port = 0;
    SOCKET listener = local_listener(&port);
    if (listener == INVALID_SOCKET) return -1;
    *writer = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    *reader = INVALID_SOCKET;
    if (*writer != INVALID_SOCKET) {
        struct sockaddr_in addr = {0};
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        addr.sin_port = htons(port);
        if (!connect(*writer, (struct sockaddr *)&addr, sizeof(addr))) {
            int len = sizeof(addr);
            if (!getsockname(*writer, (struct sockaddr *)&addr, &len))
                *reader = accept_local(listener, ntohs(addr.sin_port));
        }
    }
    closesocket(listener);
    if (*reader == INVALID_SOCKET) {
        close_socket(writer);
        return -1;
    }
    return 0;
}

static unsigned __stdcall worker_main(void *opaque) {
    struct engine *e = opaque;
    struct addrinfo hints = {0}, *addresses = NULL;
    hints.ai_family = AF_INET; /* WARP API has IPv4; no route racing. */
    hints.ai_socktype = SOCK_STREAM;
    if (!InterlockedCompareExchange(&e->stopping, 0, 0) &&
        !getaddrinfo(WARP_DPI_HOST, "443", &hints, &addresses) && addresses) {
        union sockaddr_u target = {0};
        memcpy(&target, addresses->ai_addr, addresses->ai_addrlen);
        freeaddrinfo(addresses);
        if (!InterlockedCompareExchange(&e->stopping, 0, 0)) {
            byedpi_private_loop((warp_socket_t)e->stream, (warp_socket_t)e->worker_wake, &target);
            byedpi_windows_drain();
            return 0;
        }
    }
    closesocket(e->stream);
    closesocket(e->worker_wake);
    return 0;
}

static void stop_stream(struct engine *e) {
    close_socket(&e->listener);
    if (e->worker) {
        InterlockedExchange(&e->stopping, 1);
        /* EOF on the wake socket is sufficient; no blocking send. */
        shutdown(e->wake, SD_BOTH);
        close_socket(&e->wake);
        WaitForSingleObject(e->worker, INFINITE);
        CloseHandle(e->worker);
        e->worker = NULL;
        e->stream = e->worker_wake = INVALID_SOCKET;
    } else {
        close_socket(&e->wake);
        close_socket(&e->stream);
        close_socket(&e->worker_wake);
    }
}

int win_dpi_prepare(void *handle) {
    struct engine *e = handle;
    stop_stream(e);
    InterlockedExchange(&e->stopping, 0);
    int port = 0;
    e->listener = local_listener(&port);
    return e->listener == INVALID_SOCKET ? -1 : port;
}

int win_dpi_connect(void *handle, int client_port) {
    struct engine *e = handle;
    if (e->listener == INVALID_SOCKET || client_port < 1 || client_port > 65535) return -1;
    e->stream = accept_local(e->listener, client_port);
    close_socket(&e->listener);
    if (e->stream == INVALID_SOCKET) return -1;
    u_long nonblocking = 1;
    if (ioctlsocket(e->stream, FIONBIO, &nonblocking) ||
        wake_pair(&e->wake, &e->worker_wake) ||
        ioctlsocket(e->worker_wake, FIONBIO, &nonblocking)) {
        stop_stream(e);
        return -1;
    }
    e->worker = (HANDLE)_beginthreadex(NULL, 0, worker_main, e, 0, NULL);
    if (!e->worker) { stop_stream(e); return -1; }
    return 0;
}

void win_dpi_stop(void *handle) {
    struct engine *e = handle;
    stop_stream(e);
    clear_params();
    warp_arguments_free(&e->args);
    free(e);
    InterlockedExchange(&owner, 0);
}
