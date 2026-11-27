# Shared JVM generator sources

Android and the forthcoming Compose Desktop application compile these same sources. The Android app imports the shared Kotlin and asset directories through Gradle source sets. Presets accept a platform-supplied asset reader; cryptography uses Bouncy Castle on both platforms. Keep native socket handling, UI state and platform file pickers outside this directory.

The existing Electron application remains available during migration. Do not remove it until Windows parity and native transport are tested.
