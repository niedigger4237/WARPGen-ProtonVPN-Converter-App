#include "warp_dpi_engine.h"
#include "byedpi/proxy.h"
#include "warp_dpi_arguments.h"
#include <stdio.h>

#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <pthread.h>
#include <signal.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <string.h>
#include <sys/eventfd.h>

int byedpi_configure(int argc, char **argv, bool runtime, char *error);
void clear_params(void);
int warp_private_stream_pair(int *client, int *engine);

struct engine {
    pthread_t worker;
    int stop;
    int worker_stop;
    int stream;
    atomic_bool stopping;
    struct warp_arguments arguments;
};

static atomic_flag owner = ATOMIC_FLAG_INIT;

static int configure(struct warp_arguments *arguments, bool runtime, char *error)
{
    if (atomic_flag_test_and_set(&owner)) {
        snprintf(error, WARP_DPI_ERROR_SIZE, "The ByeDPI engine is already running.");
        return -1;
    }
    int result = byedpi_configure(arguments->count, arguments->argv, runtime, error);
    if (result || !runtime) {
        clear_params();
        atomic_flag_clear(&owner);
    }
    return result;
}

int warp_dpi_validate_args(int count, char **tokens, char *error)
{
    struct warp_arguments arguments;
    if (warp_arguments_prepare(count, tokens, &arguments, error))
        return -1;
    int result = configure(&arguments, false, error);
    warp_arguments_free(&arguments);
    return result;
}

void *warp_dpi_start_args(int count, char **tokens)
{
    char error[WARP_DPI_ERROR_SIZE];
    struct engine *engine = calloc(1, sizeof(*engine));
    if (!engine)
        return NULL;
    if (warp_arguments_prepare(count, tokens, &engine->arguments, error)
            || configure(&engine->arguments, true, error)) {
        warp_arguments_free(&engine->arguments);
        free(engine);
        return NULL;
    }
    engine->stop = -1;
    atomic_init(&engine->stopping, false);
    return engine;
}

static void *run_stream(void *context)
{
    struct engine *engine = context;
    sigset_t signals;
    sigemptyset(&signals);
    sigaddset(&signals, SIGPIPE);
    pthread_sigmask(SIG_BLOCK, &signals, NULL);

    struct addrinfo hints = {
        .ai_family = AF_UNSPEC, .ai_socktype = SOCK_STREAM, .ai_flags = AI_ADDRCONFIG
    };
    struct addrinfo *addresses = NULL;
    if (!atomic_load(&engine->stopping) && !getaddrinfo(WARP_DPI_HOST, "443", &hints, &addresses)
            && addresses) {
        union sockaddr_u target = {0};
        memcpy(&target, addresses->ai_addr, addresses->ai_addrlen);
        freeaddrinfo(addresses);
        if (!atomic_load(&engine->stopping)) {
            byedpi_private_loop(engine->stream, engine->worker_stop, &target);
            return NULL;
        }
    }
    close(engine->stream);
    close(engine->worker_stop);
    return NULL;
}

static void stop_stream(struct engine *engine)
{
    if (engine->stop < 0)
        return;
    atomic_store(&engine->stopping, true);
    while (eventfd_write(engine->stop, 1) < 0 && errno == EINTR) {}
    pthread_join(engine->worker, NULL);
    close(engine->stop);
    engine->stop = -1;
}

int warp_dpi_open_stream(void *handle)
{
    struct engine *engine = handle;
    stop_stream(engine);
    atomic_store(&engine->stopping, false);
    int client;
    if (warp_private_stream_pair(&client, &engine->stream))
        return -1;
    engine->stop = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (engine->stop >= 0) {
        engine->worker_stop = fcntl(engine->stop, F_DUPFD_CLOEXEC, 0);
        if (engine->worker_stop >= 0) {
            if (!pthread_create(&engine->worker, NULL, run_stream, engine))
                return client;
            close(engine->worker_stop);
        }
        close(engine->stop);
        engine->stop = -1;
    }
    close(engine->stream);
    close(client);
    return -1;
}

void warp_dpi_stop(void *handle)
{
    struct engine *engine = handle;
    stop_stream(engine);
    clear_params();
    warp_arguments_free(&engine->arguments);
    free(engine);
    atomic_flag_clear(&owner);
}
