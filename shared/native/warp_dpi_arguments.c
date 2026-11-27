#include "warp_dpi_arguments.h"

#include <ctype.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct option_policy {
    char short_name;
    const char *long_name;
    int has_value;
};

static const struct option_policy options[] = {
    {'s', "split", 1}, {'d', "disorder", 1}, {'o', "oob", 1},
    {'q', "disoob", 1}, {'f', "fake", 1}, {'r', "tlsrec", 1},
    {'m', "tlsminor", 1}, {'t', "ttl", 1}, {'g', "def-ttl", 1},
    {'O', "fake-offset", 1}, {'Q', "fake-tls-mod", 1},
    {'n', "fake-sni", 1}, {'e', "oob-data", 1}, {'M', "mod-http", 1},
    {'A', "auto", 1}, {'L', "auto-mode", 1}, {'u', "cache-ttl", 1},
    {'K', "proto", 1}, {'V', "pf", 1}, {'R', "round", 1},
    {'a', "udp-fake", 1}, {'W', "await-int", 1},
    {'S', "md5sig", 0}, {'Y', "drop-sack", 0}, {'Z', "wait-send", 0},
};

static const struct option_policy *find_option(char name, const char *long_name, size_t len)
{
    for (size_t i = 0; i < sizeof(options) / sizeof(*options); i++) {
        if (long_name ? strlen(options[i].long_name) == len &&
                !strncmp(options[i].long_name, long_name, len) : options[i].short_name == name)
            return &options[i];
    }
    return NULL;
}

static int valid_host(const char *host)
{
    size_t total = strlen(host);
    if (!total || total > 253)
        return 0;
    for (const char *part = host; *part; ) {
        size_t len = strcspn(part, ".");
        if (!len || len > 63 || part[0] == '-' || part[len - 1] == '-')
            return 0;
        for (size_t i = 0; i < len; i++) {
            unsigned char ch = (unsigned char)part[i];
            if (!(isalnum(ch) || ch == '-') || ch > 127)
                return 0;
        }
        part += len;
        if (*part && !*++part)
            return 0;
    }
    return 1;
}

static int append(struct warp_arguments *out, const struct option_policy *option,
        const char *value, char *error)
{
    if (out->count >= WARP_DPI_MAX_ARGS + 2) {
        snprintf(error, WARP_DPI_ERROR_SIZE, "Too many options (maximum %d).", WARP_DPI_MAX_ARGS);
        return -1;
    }
    if (value && option->short_name == 'n') {
        if (!strcmp(value, "{sni}"))
            value = WARP_DPI_HOST;
        if (!valid_host(value)) {
            snprintf(error, WARP_DPI_ERROR_SIZE, "--fake-sni requires a DNS name or {sni}.");
            return -1;
        }
    }
    if (value && (option->short_name == 'W' || option->short_name == 'u')) {
        char *end;
        errno = 0;
        long parsed = strtol(value, &end, 10);
        long max = option->short_name == 'W' ? 1000 : 604800;
        if (errno || end == value || *end || parsed < 1 || parsed > max) {
            snprintf(error, WARP_DPI_ERROR_SIZE, "--%s requires an integer from 1 to %ld.",
                    option->long_name, max);
            return -1;
        }
    }
    size_t size = strlen(option->long_name) + (value ? strlen(value) + 1 : 0) + 3;
    char *arg = malloc(size);
    if (!arg) {
        snprintf(error, WARP_DPI_ERROR_SIZE, "Unable to allocate strategy arguments.");
        return -1;
    }
    snprintf(arg, size, "--%s%s%s", option->long_name, value ? "=" : "", value ? value : "");
    out->argv[out->count++] = arg;
    return 0;
}

void warp_arguments_free(struct warp_arguments *args)
{
    for (int i = 2; i < args->count; i++)
        free(args->argv[i]);
    free(args->argv);
    *args = (struct warp_arguments){0};
}

int warp_arguments_prepare(int count, char **tokens, struct warp_arguments *out, char *error)
{
    *out = (struct warp_arguments){0};
    if (count < 0 || count > WARP_DPI_MAX_ARGS) {
        snprintf(error, WARP_DPI_ERROR_SIZE, "Too many arguments (maximum %d).", WARP_DPI_MAX_ARGS);
        return -1;
    }
    size_t total = 0, capacity = 3;
    for (int i = 0; i < count; i++) {
        size_t len = strlen(tokens[i]);
        total += len + 1;
        if (!len || len > WARP_DPI_MAX_TOKEN || total > WARP_DPI_MAX_BYTES) {
            snprintf(error, WARP_DPI_ERROR_SIZE, "An argument is empty or the strategy exceeds the size limit.");
            return -1;
        }
        for (const unsigned char *ch = (const unsigned char *)tokens[i]; *ch; ch++) {
            if ((*ch < 32 && *ch != '\t' && *ch != '\n' && *ch != '\r') || *ch > 126) {
                snprintf(error, WARP_DPI_ERROR_SIZE, "Use ASCII characters in strategy arguments.");
                return -1;
            }
        }
        capacity += (tokens[i][0] == '-' && tokens[i][1] != '-') ? len : 1;
    }
    if (capacity > WARP_DPI_MAX_ARGS + 3)
        capacity = WARP_DPI_MAX_ARGS + 3;
    out->argv = calloc(capacity, sizeof(*out->argv));
    if (!out->argv) {
        snprintf(error, WARP_DPI_ERROR_SIZE, "Unable to allocate strategy arguments.");
        return -1;
    }
    out->count = 2;
    out->argv[0] = "warp_dpi";
    out->argv[1] = "-U";

    for (int i = 0; i < count; i++) {
        const char *token = tokens[i];
        const char *name = token + 1;
        if (*token != '-' || !*name || !strcmp(token, "--"))
            goto unsupported;
        int is_long = *name == '-';
        if (is_long)
            name++;
        while (*name) {
            const char *equals = is_long ? strchr(name, '=') : NULL;
            size_t len = is_long ? (equals ? (size_t)(equals - name) : strlen(name)) : 1;
            const struct option_policy *option = find_option(*name, is_long ? name : NULL, len);
            if (!option || (equals && !option->has_value))
                goto unsupported;
            name += len;
            const char *value = NULL;
            if (option->has_value) {
                if (equals)
                    value = equals + 1;
                else if (!is_long && *name)
                    value = name;
                else if (i + 1 < count)
                    value = tokens[++i];
                if (!value || !*value || !strncmp(value, "--", 2)) {
                    snprintf(error, WARP_DPI_ERROR_SIZE, "Missing value for --%s.", option->long_name);
                    goto fail;
                }
            }
            if (append(out, option, value, error))
                goto fail;
            if (is_long || option->has_value)
                break;
        }
        continue;
unsupported:
        snprintf(error, WARP_DPI_ERROR_SIZE, "Unsupported or restricted argument: %.72s.", token);
        goto fail;
    }
    return 0;
fail:
    warp_arguments_free(out);
    return -1;
}
