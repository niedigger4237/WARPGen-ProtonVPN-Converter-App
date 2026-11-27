# Native ByeDPI source of truth

The vendored C sources and WARP strategy argument parser are shared here, without modifications. The Android CMake build and native lifecycle tests refer to this directory. The Android JNI, POSIX stream pair, pthread/eventfd engine remain platform-specific. Windows needs a separate Winsock stream and cancellation implementation, pointer-width-safe SOCKET handling and JVM TLS integration; do not compile the POSIX adapter on Windows or ship a placeholder DLL.
