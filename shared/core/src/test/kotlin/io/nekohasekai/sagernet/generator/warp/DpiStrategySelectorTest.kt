package io.nekohasekai.sagernet.generator.warp

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DpiStrategySelectorTest {
    private class Session(val id: String) : AutoCloseable {
        var closeCount = 0
            private set
        override fun close() {
            closeCount++
        }
    }

    private val first = DpiStrategySelector.Strategy("first", emptyList())
    private val preferred = DpiStrategySelector.Strategy("preferred", emptyList())

    @Test
    fun closesFailedProbeBeforeStartingNextAndClosesSuccessfulSession() {
        val opened = mutableListOf<Session>()
        val selected = DpiStrategySelector.withWorkingStrategy(
            listOf(first, preferred), preferred.id,
            open = { strategy ->
                assertTrue(opened.all { it.closeCount == 1 })
                Session(strategy.id).also(opened::add)
            },
            checkCancelled = {},
            progress = { _, _, _ -> },
            probe = { if (it.id == preferred.id) throw IOException("probe failed") else true }
        ) { match, session ->
            assertEquals(0, session.closeCount)
            match.strategy
        }
        assertEquals(first, selected)
        assertEquals(listOf(preferred.id, first.id), opened.map { it.id })
        assertTrue(opened.all { it.closeCount == 1 })
    }

    @Test
    fun closesSessionOnRegistrationError() {
        val session = Session(first.id)
        assertThrows(IOException::class.java) {
            DpiStrategySelector.withWorkingStrategy(
                listOf(first), null, { session }, {}, { _, _, _ -> }, { true }
            ) { _, _ -> throw IOException("registration failed") }
        }
        assertEquals(1, session.closeCount)
    }

    @Test
    fun closesSessionOnCancellationWithoutTryingAnotherStrategy() {
        val opened = mutableListOf<Session>()
        var cancelled = false
        assertThrows(CancellationException::class.java) {
            DpiStrategySelector.withWorkingStrategy(
                listOf(first, preferred), null,
                open = { Session(it.id).also(opened::add) },
                checkCancelled = { if (cancelled) throw CancellationException() },
                progress = { _, _, _ -> },
                probe = {
                    cancelled = true
                    false
                }
            ) { _, _ -> error("Registration attempted after cancellation") }
        }
        assertEquals(listOf(first.id), opened.map { it.id })
        assertEquals(1, opened.single().closeCount)
    }
    @Test
    fun skipsInvalidStrategiesAndReportsTheNameAndPosition() {
        val bad = DpiStrategySelector.Strategy("custom-invalid", listOf("-p1080"), "Invalid custom entry")
        val progress = mutableListOf<Triple<Int, Int, String>>()
        val chosen = DpiStrategySelector.withWorkingStrategy(
            listOf(first, bad), bad.id,
            open = {
                if (it == bad) throw IOException("Unsupported option")
                Session(it.id)
            },
            checkCancelled = {},
            progress = { index, count, strategy -> progress += Triple(index, count, strategy.name) },
            probe = { true }
        ) { match, _ -> match.strategy }
        assertEquals(first, chosen)
        assertEquals(listOf(Triple(1, 2, bad.name), Triple(2, 2, first.name)), progress)
    }
}
