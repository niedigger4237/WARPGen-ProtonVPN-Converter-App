package com.local.protonawg.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.Properties

/** Only non-secret UI preferences belong here. Never persist generated private keys. */
internal class PortableSettings(private val dataDir: Path) {
    private val file = dataDir.resolve("settings.properties")
    fun loadTheme(): String = runCatching {
        val props = Properties()
        if (Files.isRegularFile(file)) Files.newInputStream(file).use { props.load(it) }
        props.getProperty("theme", "System").takeIf { it in themes } ?: "System"
    }.getOrDefault("System")

    fun saveTheme(theme: String): Boolean {
        if (theme !in themes) return false
        return runCatching {
            Files.createDirectories(dataDir)
            val temp = Files.createTempFile(dataDir, "settings-", ".tmp")
            try {
                val props = Properties()
                if (Files.isRegularFile(file)) Files.newInputStream(file).use { props.load(it) }
                props.setProperty("theme", theme)
                Files.newOutputStream(temp).use { props.store(it, "Portable application preferences") }
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temp)
            }
            true
        }.getOrDefault(false)
    }

    companion object {
        private val themes = setOf("System", "Light", "Dark")
        internal fun dataDirectory(appPath: String?, workingDir: Path): Path =
            (appPath?.takeIf { it.isNotBlank() }?.let { Path.of(it).toAbsolutePath().parent }
                ?: workingDir.toAbsolutePath()).resolve("data")

        fun open() = PortableSettings(dataDirectory(System.getProperty("jpackage.app-path"),
            Path.of(System.getProperty("user.dir"))))
    }
}
