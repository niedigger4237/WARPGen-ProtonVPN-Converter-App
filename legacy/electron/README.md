# Legacy Electron implementation

This directory preserves the previous Windows application during the Compose Desktop migration. It is not used by Android or the new desktop application.

From this directory, run `npm ci`, `python tools/build.py` and `npm run dist:win` (with the compatible ciadpi.exe in byedpi-bin/). CI downloads the legacy engine and builds this package only when legacy_windows is selected in Preview All.

Do not edit the legacy generator presets to add features to the new app. The canonical native Kotlin generator assets live in shared/core at the repository root.
