# Compose Desktop preview

Run on Windows with JDK 17 or 21 from the repository root:
```bat
android\gradlew.bat -p desktop run
android\gradlew.bat -p desktop createDistributable
```

This preview shares the Kotlin generators and Material 3 screens with Android. Direct WARP and local SOCKS5 transport (127.0.0.1:10808) are available; native Windows ByeDPI is not yet implemented, and is deliberately hidden. The Share action copies text on desktop. File selection, clipboard, and export use desktop APIs. Existing Electron remains untouched until Windows parity testing is complete.
