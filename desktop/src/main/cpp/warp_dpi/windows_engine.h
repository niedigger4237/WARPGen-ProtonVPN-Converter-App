#ifndef WARP_WINDOWS_ENGINE_H
#define WARP_WINDOWS_ENGINE_H
int win_dpi_validate(int count, char **tokens, char *error);
void *win_dpi_start(int count, char **tokens);
int win_dpi_prepare(void *handle);
int win_dpi_connect(void *handle, int client_port);
void win_dpi_stop(void *handle);
#endif
