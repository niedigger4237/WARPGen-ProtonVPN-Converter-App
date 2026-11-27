#ifndef WARP_DPI_ARGUMENTS_H
#define WARP_DPI_ARGUMENTS_H

#include <stddef.h>

// The limits bound JNI copies, getopt storage and per-strategy parser work.
#define WARP_DPI_MAX_ARGS 256
#define WARP_DPI_MAX_TOKEN 2048
#define WARP_DPI_MAX_BYTES 16384
#define WARP_DPI_ERROR_SIZE 192
#define WARP_DPI_HOST "api.cloudflareclient.com"

struct warp_arguments {
    int count;
    char **argv;
};

int warp_arguments_prepare(int count, char **tokens, struct warp_arguments *out, char *error);
void warp_arguments_free(struct warp_arguments *args);

#endif
