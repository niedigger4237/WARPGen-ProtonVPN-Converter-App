// Android 6 has no getifaddrs API. ByeDPI only needs it for addresses
// with an explicit interface scope; the app binds to numeric loopback.
#pragma once

#if defined(__ANDROID__) && __ANDROID_API__ < 24
#include <errno.h>
#include <ifaddrs.h>

extern int getifaddrs(struct ifaddrs **) __attribute__((weak));
extern void freeifaddrs(struct ifaddrs *) __attribute__((weak));

static inline int byedpi_getifaddrs(struct ifaddrs **interfaces) {
    if (getifaddrs && freeifaddrs) {
        return getifaddrs(interfaces);
    }
    *interfaces = 0;
    errno = ENOSYS;
    return -1;
}

#define getifaddrs byedpi_getifaddrs
#endif
