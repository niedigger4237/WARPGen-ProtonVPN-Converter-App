#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "windows_engine.h"
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
Java_io_nekohasekai_sagernet_generator_warp_WindowsByeDpi_nativeValidate(
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
    int result = win_dpi_validate(count, tokens, error);
    free_tokens(tokens, count);
    return result ? (*env)->NewStringUTF(env, error) : NULL;
}

JNIEXPORT jlong JNICALL
Java_io_nekohasekai_sagernet_generator_warp_WindowsByeDpi_nativeStart(
    JNIEnv *env, jclass clazz, jobjectArray arguments)
{
    (void)clazz;
    char error[WARP_DPI_ERROR_SIZE];
    jsize count = (*env)->GetArrayLength(env, arguments);
    char **tokens = copy_tokens(env, arguments, count, error);
    if (!tokens)
        return 0;
    void *engine = win_dpi_start(count, tokens);
    free_tokens(tokens, count);
    return (jlong)(intptr_t)engine;
}

JNIEXPORT jint JNICALL
Java_io_nekohasekai_sagernet_generator_warp_WindowsByeDpi_nativePrepare(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    return handle ? win_dpi_prepare((void *)(intptr_t)handle) : -1;
}
JNIEXPORT jboolean JNICALL
Java_io_nekohasekai_sagernet_generator_warp_WindowsByeDpi_nativeConnect(JNIEnv *env, jclass clazz, jlong handle, jint port)
{
    (void)env; (void)clazz;
    return handle && !win_dpi_connect((void *)(intptr_t)handle, port) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL
Java_io_nekohasekai_sagernet_generator_warp_WindowsByeDpi_nativeStop(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    if (handle) win_dpi_stop((void *)(intptr_t)handle);
}
