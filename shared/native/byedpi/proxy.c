#include "proxy.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>

#include "params.h"
#include "conev.h"
#include "extend.h"
#include "error.h"

#ifdef _WIN32
    #include <winsock2.h>
    #include <ws2tcpip.h>

    #define close(fd) closesocket(fd)
    #define SHUT_RDWR SD_BOTH
#else
    #include <errno.h>
    #include <unistd.h>
    #include <fcntl.h>

    #include <sys/socket.h>
    #include <arpa/inet.h>
    #include <netinet/tcp.h>


#endif


void map_fix(union sockaddr_u *addr, char f6)
{
    struct {
        uint64_t o64;
        uint16_t o16;
        uint16_t t16;
        uint32_t o32;
    } *ipv6m = (void *)&addr->in6.sin6_addr;

    if (addr->sa.sa_family == AF_INET && f6) {
        addr->sa.sa_family = AF_INET6;
        ipv6m->o32 = *(uint32_t *)(&addr->in.sin_addr);
        ipv6m->o64 = 0;
        ipv6m->o16 = 0;
        ipv6m->t16 = 0xffff;
    }
    else if (!ipv6m->o64 && !ipv6m->o16 &&
            ipv6m->t16 == 0xffff && !f6) {
        addr->sa.sa_family = AF_INET;
        const struct in_addr *sin_addr_ptr = (struct in_addr *) &ipv6m->o32;
        addr->in.sin_addr = *sin_addr_ptr;
    }
}


static inline int nb_socket(int domain, int type)
{
    #ifdef __linux__
    int fd = socket(domain, type | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    #else
    int fd = socket(domain, type, 0);
    #endif
    if (fd < 0) {
        uniperror("socket");
        return -1;
    }
    #ifdef _WIN32
    unsigned long mode = 1;
    if (ioctlsocket(fd, FIONBIO, &mode) < 0) {
        uniperror("ioctlsocket");
        close(fd);
        return -1;
    }
    #else
    #ifndef __linux__
    if (fcntl(fd, F_SETFL, O_NONBLOCK) < 0) {
        uniperror("fcntl");
        close(fd);
        return -1;
    }
    #endif
    #endif
    return fd;
}


static int remote_sock(union sockaddr_u *dst, int type)
{
    if (params.baddr.sa.sa_family == AF_INET6) {
        map_fix(dst, 6);
    } else {
        map_fix(dst, 0);
    }
    if (dst->sa.sa_family != params.baddr.sa.sa_family) {
        LOG(LOG_E, "different addresses family\n");
        return -1;
    }
    int sfd = nb_socket(dst->sa.sa_family, type);
    if (sfd < 0) {
        uniperror("socket");
        return -1;
    }
    if (socket_mod(sfd) < 0) {
        close(sfd);
        return -1;
    }
    if (dst->sa.sa_family == AF_INET6) {
        int no = 0;
        if (setsockopt(sfd, IPPROTO_IPV6,
                IPV6_V6ONLY, (char *)&no, sizeof(no))) {
            uniperror("setsockopt IPV6_V6ONLY");
            close(sfd);
            return -1;
        }
    }
    if (bind(sfd, (struct sockaddr *)&params.baddr,
            SA_SIZE(&params.baddr)) < 0) {
        uniperror("bind");
        close(sfd);
        return -1;
    }
    return sfd;
}


int create_conn(struct poolhd *pool,
        struct eval *val, const union sockaddr_u *dst, evcb_t next)
{
    union sockaddr_u addr = *dst;

    int sfd = remote_sock(&addr, SOCK_STREAM);
    if (sfd < 0) {
        return -1;
    }
    #ifdef __linux__
    int syn_count = 1;
    if (setsockopt(sfd, IPPROTO_TCP,
            TCP_SYNCNT, (char *)&syn_count, sizeof(syn_count))) {
        uniperror("setsockopt TCP_SYNCNT");
        close(sfd);
        return -1;
    }
    #ifdef TCP_FASTOPEN_CONNECT
    int yes = 1;
    if (params.tfo && setsockopt(sfd, IPPROTO_TCP,
            TCP_FASTOPEN_CONNECT, (char *)&yes, sizeof(yes))) {
        uniperror("setsockopt TCP_FASTOPEN_CONNECT");
        close(sfd);
        return -1;
    }
    #endif
    #endif
    int one = 1;
    if (setsockopt(sfd, IPPROTO_TCP,
            TCP_NODELAY, (char *)&one, sizeof(one))) {
        uniperror("setsockopt TCP_NODELAY");
        close(sfd);
        return -1;
    }
    if (LOG_ENABLED) {
        INIT_ADDR_STR((*dst));
        LOG(LOG_S, "new conn: fd=%d, pair=%d, addr=%s:%d\n",
            sfd, val->fd, ADDR_STR, ntohs(dst->in.sin_port));
    }
    int status = connect(sfd, &addr.sa, SA_SIZE(&addr));
    if (status == 0 && params.tfo) {
        LOG(LOG_S, "TFO supported!\n");
    }
    int error = status < 0 ? get_e() : 0;
    if (error == EINPROGRESS || error == EAGAIN)
        error = 0;
    // Link the pair before dispatching a synchronous connect failure.
    struct eval *pair = add_event(pool, next, sfd, error ? 0 : POLLOUT);
    if (!pair) {
        close(sfd);
        return -1;
    }
    if (mod_etype(pool, val, 0) < 0) {
        uniperror("mod_etype");
        del_event(pool, pair);
        return -1;
    }
    val->pair = pair;
    pair->pair = val;
    #ifdef __NetBSD__
    pair->addr = addr;
    #else
    pair->addr = *dst;
    #endif
    pair->flag = FLAG_CONN;
    pair->connect_error = error;
    if (error)
        set_timer(pool, pair, 0);
    val->cb = &on_ignore;
    return 0;
}


int on_tunnel(struct poolhd *pool, struct eval *val, int etype)
{
    ssize_t n = 0;
    struct eval *pair = val->pair;

    if (etype & POLLOUT || etype == POLLTIMEOUT) {
        LOG(LOG_S, "pollout (fd=%d)\n", val->fd);
        val = pair;
        pair = val->pair;
    }
    if (val->buff) {
        if (etype & POLLHUP) {
            return -1;
        }
        bool wait = false;
        ssize_t sn = tcp_send_hook(pool, pair, val->buff, &val->buff->lock, &wait);
        if (sn < 0) {
            uniperror("send");
            return -1;
        }
        if (sn < val->buff->lock - val->buff->offset || wait) {
            val->buff->offset += sn;
            return mod_etype(pool, pair, wait ? 0 : POLLOUT);
        }
        buff_push(pool, val->buff);
        val->buff = 0;

        if (mod_etype(pool, val, POLLIN) ||
                mod_etype(pool, pair, POLLIN)) {
            uniperror("mod_etype");
            return -1;
        }
    }
    struct buffer *buff = buff_pop(pool, params.bfsize);
    if (!buff) {
        return -1;
    }
    val->buff = buff;
    do {
        n = tcp_recv_hook(pool, val, buff);
        //if (n < 0 && get_e() == EAGAIN) {
        if (n == 0) {
            break;
        }
        if (n < 0) {
            return -1;
        }

        bool wait = false;
        ssize_t sn = tcp_send_hook(pool, pair, buff, &n, &wait);
        if (sn < 0) {
            uniperror("send");
            return -1;
        }
        if (sn < n || wait) {
            if (sn < n) {
                LOG(LOG_S, "send: %zd != %zd (fd=%d)\n", sn, n, pair->fd);
            }
            else {
                LOG(LOG_S, "send: %zd, but not done yet (fd=%d)\n", sn, pair->fd);
            }

            buff->lock = n;
            buff->offset = sn;

            if (mod_etype(pool, val, 0) ||
                    mod_etype(pool, pair, !wait ? POLLOUT : 0)) {
                uniperror("mod_etype");
                return -1;
            }
            return 0;
        }
    } while (n == (ssize_t )buff->size);
    buff_push(pool, val->buff);
    val->buff = 0;
    return 0;
}


int on_ignore(struct poolhd *pool, struct eval *val, int etype)
{
    return (etype & (POLLHUP | POLLERR | POLLRDHUP)) ? -1 : 0;
}


int on_private_connect(struct poolhd *pool, struct eval *val, int et)
{
    int error = val->connect_error;
    socklen_t len = sizeof(error);
    if (!error && getsockopt(val->fd, SOL_SOCKET, SO_ERROR, &error, &len))
        return -1;
    if (!error && (et & POLLERR))
        error = ECONNREFUSED;
    if (error == ECONNRESET || error == ECONNREFUSED || error == ETIMEDOUT)
        return on_connerr(pool, val);
    if (error || !(et & POLLOUT))
        return -1;
    val->cb = &on_tunnel;
    val->pair->cb = &on_tunnel;
    // Keep write interest until on_tunnel has flushed a replay buffer.
    if (val->pair->buff)
        return on_tunnel(pool, val, POLLOUT);
    return mod_etype(pool, val, POLLIN) || mod_etype(pool, val->pair, POLLIN) ? -1 : 0;
}

static int on_private_stop(struct poolhd *pool, struct eval *val, int et)
{
    pool->brk = true;
    return 0;
}

void byedpi_private_loop(int stream, int stop, const union sockaddr_u *dst)
{
    struct poolhd *pool = init_pool(4);
    if (!pool) {
        close(stream);
        close(stop);
        return;
    }
    if (!add_event(pool, &on_private_stop, stop, POLLIN)) {
        close(stop);
        close(stream);
        destroy_pool(pool);
        return;
    }
    struct eval *client = add_event(pool, &on_ignore, stream, 0);
    if (!client)
        close(stream);
    else if (!connect_hook(pool, client, dst, &on_private_connect))
        loop_event(pool);
    destroy_pool(pool);
}
