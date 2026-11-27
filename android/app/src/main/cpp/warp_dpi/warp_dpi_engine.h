#ifndef WARP_DPI_ENGINE_H
#define WARP_DPI_ENGINE_H

void *warp_dpi_start_args(int count, char **argv);
int warp_dpi_validate_args(int count, char **tokens, char *error);
int warp_dpi_open_stream(void *engine);
void warp_dpi_stop(void *engine);

#endif
