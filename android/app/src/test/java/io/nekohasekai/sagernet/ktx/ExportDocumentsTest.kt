package io.nekohasekai.sagernet.ktx

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportDocumentsTest {
    @Test fun preservesRequestedExtension() {
        for (ext in listOf("conf", "log", "json", "yaml", "yml")) {
            val name = "export.$ext"
            assertEquals(name, correctedExportName(name, name))
            assertEquals(name, correctedExportName("$name.txt", name))
            assertEquals(name, correctedExportName("$name.bin", name))
            assertEquals("renamed.$ext", correctedExportName("renamed.txt", name))
            assertEquals("renamed.$ext", correctedExportName("renamed", name))
            assertEquals("renamed (1).$ext", correctedExportName("renamed (1).$ext.txt", name))
        }
        assertEquals("WARP.CONF", correctedExportName("WARP.CONF", "export.conf"))
    }

    @Test fun avoidsGenericTextMime() {
        for (ext in listOf("conf", "log", "yaml", "yml"))
            assertEquals("application/octet-stream", exportMimeType("export.$ext"))
        assertEquals("application/json", exportMimeType("export.json"))
    }
}
