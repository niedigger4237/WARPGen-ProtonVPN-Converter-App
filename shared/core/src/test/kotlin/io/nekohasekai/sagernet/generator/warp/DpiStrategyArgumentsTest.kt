package io.nekohasekai.sagernet.generator.warp

import org.junit.Assert.*
import org.junit.Test

class DpiStrategyArgumentsTest {
    @Test
    fun preservesNativeOffsetsModifiersAndEscapes() {
        assertEquals(
            listOf("-SZ", "-s1:5:2+sm", "--fake-offset=-1", "-A", "torst,ssl_err",
                "--fake-sni={sni}", "-Q", "r,o,m=-100", "--oob-data", "\\x20"),
            DpiStrategyArguments.tokenize(
                """-SZ -s1:5:2+sm --fake-offset=-1 -A "torst,ssl_err" --fake-sni={sni}
                    -Q r,o,m=-100 --oob-data '\x20'"""
            )
        )
        assertEquals(listOf("-e", " "), DpiStrategyArguments.tokenize("-e \" \""))
        assertEquals(24, DpiStrategyArguments.tokenize(List(24) { "-s1" }.joinToString(" ")).size)
    }

    @Test
    fun rejectsInvalidInputWithoutSilentlyDroppingTokens() {
        assertThrows(IllegalArgumentException::class.java) { DpiStrategyArguments.tokenize("-e '") }
        assertThrows(IllegalArgumentException::class.java) { DpiStrategyArguments.tokenize("-n пример") }
        assertThrows(IllegalArgumentException::class.java) { DpiStrategyArguments.tokenize("x".repeat(16385)) }
        assertEquals(listOf("-n", ""), DpiStrategyArguments.tokenize("-n \"\""))
    }

    @Test
    fun remembersOnlyTheSameEnabledCustomStrategy() {
        val item = DpiStrategyCatalog.Custom("custom-1", "Original", "-s1")
        val previous = DpiStrategyCatalog.Settings(custom = listOf(item))
        fun current(update: DpiStrategyCatalog.Custom) = previous.copy(custom = listOf(update))
        assertTrue(DpiStrategyCatalog.retainPreferred(item.id, previous, current(item.copy(name = "Renamed"))))
        assertFalse(DpiStrategyCatalog.retainPreferred(item.id, previous, current(item.copy(args = "-s2"))))
        assertFalse(DpiStrategyCatalog.retainPreferred(item.id, previous, current(item.copy(enabled = false))))
        assertFalse(DpiStrategyCatalog.retainPreferred(item.id, previous, previous.copy(custom = emptyList())))
        assertFalse(DpiStrategyCatalog.retainPreferred("manager-11", previous, previous.copy(disabled = setOf("manager-11"))))
    }
}
