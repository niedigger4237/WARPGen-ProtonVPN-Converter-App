# Compose Desktop preview

Run on Windows with JDK 17 or 21 from the repository root:
```bat
android\gradlew.bat -p desktop run
android\gradlew.bat -p desktop portableZip
```

This preview shares the Kotlin generators and Material 3 screens with Android. Direct WARP and local SOCKS5 transport (127.0.0.1:10808) are available; native Windows ByeDPI is not yet implemented, and is deliberately hidden. The Share action copies text on desktop. File selection, clipboard, and export use desktop APIs. The previous Electron implementation is isolated in `legacy/electron/` until Windows parity testing is complete.

## Portable distribution

The archive in `desktop/build/portable/` contains the launcher, application libraries and bundled Java runtime. Extract the entire folder and start `WarpProtonAWG.exe`. No installer, separate Java installation or administrator privileges are normally needed. Keep the folder somewhere writable. Theme preferences are stored in `data/settings.properties` beside the launcher; no generated keys or configurations are saved there automatically. In development, the data folder is relative to the working directory. To update, extract the new package and retain your data folder. Preview CI uploads the portable ZIP inside the GitHub Actions artifact. The legacy Electron build remains available during migration.

Both targets compile the same generator and UI sources from `shared/core` and `shared/ui`. The shared core test suite runs in both Gradle builds.
