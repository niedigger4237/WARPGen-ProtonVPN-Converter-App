package io.nekohasekai.sagernet.generator.warp

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class WindowsByeDpiTest {
    @Test fun validationAndRepeatedNativeSessionLifecycle() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        WindowsByeDpi.ensureLoaded()
        assertNull(WindowsByeDpi.validate(listOf("--split", "1+s")))
        assertNotNull(WindowsByeDpi.validate(listOf("--ip", "0.0.0.0")))
        assertNotNull(WindowsByeDpi.validate(listOf("--md5sig")))
        assertNotNull(WindowsByeDpi.validate(listOf("--drop-sack")))
        assertNotNull(WindowsByeDpi.validate(listOf("--wait-send")))
        repeat(20) {
            WindowsByeDpi.open(DpiStrategySelector.Strategy("test", emptyList())).use { engine ->
                assertTrue(engine.prepare() in 1..65535)
                assertTrue(engine.prepare() in 1..65535)
            }
        }
    }
    @Test fun bundledStrategiesAreReadable() {
        val resource = requireNotNull(javaClass.classLoader.getResourceAsStream("generator/byedpi-manager/strategies.tsv"))
        val imported = resource.bufferedReader().use { DpiStrategyCatalog.imported(it.readText()) }
        assertEquals(78, DpiStrategyCatalog.strategies.size + imported.size)
    }
}
