package com.local.protonawg.desktop

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PortableSettingsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun savesThemeBesideLauncherIndependentOfWorkingDirectory() {
        val root = temp.newFolder("portable").toPath()
        val other = temp.newFolder("working").toPath()
        val data = PortableSettings.dataDirectory(root.resolve("WarpProtonAWG.exe").toString(), other)
        assertEquals(root.resolve("data"), data)
        assertEquals("System", PortableSettings(data).loadTheme())
        assertTrue(PortableSettings(data).saveTheme("Dark"))
        assertEquals("Dark", PortableSettings(data).loadTheme())
        assertFalse(PortableSettings(data).saveTheme("Invalid"))
        assertEquals("Dark", PortableSettings(data).loadTheme())
    }

    @Test fun developmentUsesWorkingDirectory() {
        val dir = temp.root.toPath()
        assertEquals(dir.resolve("data"), PortableSettings.dataDirectory(null, dir))
    }
}
