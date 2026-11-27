#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>

#include "warp_dpi_engine.h"
#include "warp_dpi_arguments.h"
#include <stdio.h>

static void free_tokens(char **tokens, jsize count)
{
    for (jsize i = 0; i < count; i++)
        free(tokens[i]);
    free(tokens);
}

static char **copy_tokens(JNIEnv *env, jobjectArray arguments, jsize count, char *error)
{
    if (count > WARP_DPI_MAX_ARGS) {
        snprintf(error, WARP_DPI_ERROR_SIZE, "Too many arguments (maximum %d).", WARP_DPI_MAX_ARGS);
        return NULL;
    }
    snprintf(error, WARP_DPI_ERROR_SIZE, "Unable to copy strategy arguments.");
    char **tokens = calloc((size_t)count + 1, sizeof(*tokens));
    if (!tokens)
        return NULL;
    size_t total = 0;
    for (jsize i = 0; i < count; i++) {
        jstring token = (*env)->GetObjectArrayElement(env, arguments, i);
        if (!token)
            break;
        size_t len = (*env)->GetStringUTFLength(env, token);
        total += len + 1;
        if (len > WARP_DPI_MAX_TOKEN || total > WARP_DPI_MAX_BYTES) {
            snprintf(error, WARP_DPI_ERROR_SIZE, "The strategy exceeds the argument size limit.");
        } else {
            const char *bytes = (*env)->GetStringUTFChars(env, token, NULL);
            if (bytes) {
                tokens[i] = strdup(bytes);
                (*env)->ReleaseStringUTFChars(env, token, bytes);
            }
        }
        (*env)->DeleteLocalRef(env, token);
        if (!tokens[i]) {
            free_tokens(tokens, count);
            return NULL;
        }
    }
    if (count && !tokens[count - 1]) {
        free_tokens(tokens, count);
        return NULL;
    }
    return tokens;
}

JNIEXPORT jstring JNICALL
Java_io_nekohasekai_sagernet_generator_warp_NativeByeDpi_nativeValidate(
    JNIEnv *env, jclass clazz, jobjectArray arguments)
{
    (void)clazz;
    char error[WARP_DPI_ERROR_SIZE];
    jsize count = (*env)->GetArrayLength(env, arguments);
    char **tokens = copy_tokens(env, arguments, count, error);
    if ((*env)->ExceptionCheck(env))
        return NULL;
    if (!tokens)
        return (*env)->NewStringUTF(env, error);
    int result = warp_dpi_validate_args(count, tokens, error);
    free_tokens(tokens, count);
    return result ? (*env)->NewStringUTF(env, error) : NULL;
}

JNIEXPORT jlong JNICALL
Java_io_nekohasekai_sagernet_generator_warp_NativeByeDpi_nativeStart(
    JNIEnv *env, jclass clazz, jobjectArray arguments)
{
    (void)clazz;
    char error[WARP_DPI_ERROR_SIZE];
    jsize count = (*env)->GetArrayLength(env, arguments);
    char **tokens = copy_tokens(env, arguments, count, error);
    if (!tokens)
        return 0;
    void *engine = warp_dpi_start_args(count, tokens);
    free_tokens(tokens, count);
    return (jlong)(intptr_t)engine;
}

JNIEXPORT jint JNICALL
Java_io_nekohasekai_sagernet_generator_warp_NativeByeDpi_nativeOpenWarpStream(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env;
    (void)clazz;
    if (!handle) return -1;
    return warp_dpi_open_stream((void *)(intptr_t)handle);
}

JNIEXPORT void JNICALL
Java_io_nekohasekai_sagernet_generator_warp_NativeByeDpi_nativeStop(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env;
    (void)clazz;
    if (handle) warp_dpi_stop((void *)(intptr_t)handle);
}

JNIEXPORT jboolean JNICALL
Java_io_nekohasekai_sagernet_generator_warp_NativeByeDpi_nativeSetReadTimeout(
    JNIEnv *env, jclass clazz, jint fd, jint timeout_ms)
{
    (void)env;
    (void)clazz;
    if (fd < 0 || timeout_ms < 0)
        return JNI_FALSE;
    struct timeval timeout = {
        .tv_sec = timeout_ms / 1000,
        .tv_usec = (timeout_ms % 1000) * 1000
    };
    return setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) == 0
        ? JNI_TRUE : JNI_FALSE;
}
