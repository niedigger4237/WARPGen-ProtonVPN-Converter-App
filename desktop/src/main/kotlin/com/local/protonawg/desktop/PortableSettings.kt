package com.local.protonawg.desktop

import java.nio.file.Path

/** Resolves the portable data folder for non-secret application preferences. */
internal object PortableSettings {
    internal fun dataDirectory(appPath: String?, workingDir: Path): Path =
        (appPath?.takeIf { it.isNotBlank() }?.let { Path.of(it).toAbsolutePath().parent }
            ?: workingDir.toAbsolutePath()).resolve("data")
}
