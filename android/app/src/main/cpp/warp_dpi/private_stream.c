#include <sys/socket.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>

/* Both descriptors are owned by the caller on success.
 * The engine end is nonblocking; the client end remains blocking.
 * Neither endpoint has a filesystem name or listening port.
 */
int warp_private_stream_pair(int *client, int *engine)
{
    if (!client || !engine || client == engine) {
        errno = EINVAL;
        return -1;
    }
    *client = -1;
    *engine = -1;
    int fd[2];
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, fd) < 0)
        return -1;
    int flags = fcntl(fd[1], F_GETFL);
    if (flags < 0 || fcntl(fd[1], F_SETFL, flags | O_NONBLOCK) < 0) {
        int saved = errno;
        close(fd[0]);
        close(fd[1]);
        errno = saved;
        return -1;
    }
    *client = fd[0];
    *engine = fd[1];
    return 0;
}
