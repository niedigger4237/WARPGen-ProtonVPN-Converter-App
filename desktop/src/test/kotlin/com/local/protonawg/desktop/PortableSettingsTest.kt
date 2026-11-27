package com.local.protonawg.desktop

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PortableSettingsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun dataDirectoryBesideLauncherIndependentOfWorkingDirectory() {
        val root = temp.newFolder("portable").toPath()
        val other = temp.newFolder("working").toPath()
        val data = PortableSettings.dataDirectory(root.resolve("WPToolkit.exe").toString(), other)
        assertEquals(root.resolve("data"), data)
    }

    @Test fun developmentUsesWorkingDirectory() {
        val dir = temp.root.toPath()
        assertEquals(dir.resolve("data"), PortableSettings.dataDirectory(null, dir))
    }
}
