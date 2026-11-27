# Shared JVM architecture

- `core/src/main/kotlin`: generators, parsers, exporters, registration, cryptography, HTTP and strategy selection. No Compose or Android dependencies.
- `core/src/main/resources`: canonical generator presets, packaged as Android assets and desktop JVM resources.
- `core/src/test/kotlin`: JVM regression tests compiled and run on both targets.
- `ui/src/main/kotlin`: shared Material 3 screens and coroutine-based presentation state.
- `android/app`: Android lifecycle, SAF, clipboard, preferences, and JNI adapter.
- `desktop`: desktop window, platform file and preferences adapters, bundled runtime.

These are shared source sets rather than separately published libraries; keep core independent of UI/platform APIs. The canonical C sources and strategy argument parser live in `shared/native`. POSIX engine/stream lifecycle and Android JNI remain under `android/app/src/main/cpp/warp_dpi`; the Winsock adapter is not implemented yet. Electron remains until Windows parity is proven.
