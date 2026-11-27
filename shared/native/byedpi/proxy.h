#ifndef PROXY_H
#define PROXY_H

#include <stdint.h>

#ifdef _WIN32
    #include <ws2tcpip.h>
#else
    #include <arpa/inet.h>
    #include <sys/socket.h>
#endif

#include "conev.h"

#define SA_SIZE(s) \
    (((const struct sockaddr *)s)->sa_family == AF_INET6) ? \
        sizeof(struct sockaddr_in6) : sizeof(struct sockaddr_in)

void map_fix(union sockaddr_u *addr, char f6);
int create_conn(struct poolhd *pool,
        struct eval *val, const union sockaddr_u *dst, evcb_t next);
int on_private_connect(struct poolhd *pool, struct eval *val, int et);
int on_tunnel(struct poolhd *pool, struct eval *val, int etype);
int on_ignore(struct poolhd *pool, struct eval *val, int etype);
void byedpi_private_loop(int stream, int stop, const union sockaddr_u *dst);

#endif
