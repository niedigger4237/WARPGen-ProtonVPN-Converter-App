package io.nekohasekai.sagernet.generator.warp

import java.io.IOException
import java.util.concurrent.TimeUnit

internal object DpiStrategySelector {
    data class Strategy(val id: String, val arguments: List<String>, val name: String = id)
    data class Match(val strategy: Strategy, val latencyMs: Long)

    fun <Session : AutoCloseable, T> withWorkingStrategy(
        strategies: List<Strategy>,
        preferredId: String?,
        open: (Strategy) -> Session,
        checkCancelled: () -> Unit,
        progress: (Int, Int, Strategy) -> Unit,
        probe: (Session) -> Boolean,
        use: (Match, Session) -> T
    ): T {
        val ordered = strategies.distinctBy { it.id }.sortedBy { it.id != preferredId }
        for ((index, strategy) in ordered.withIndex()) {
            checkCancelled()
            progress(index + 1, ordered.size, strategy)
            val session = try {
                open(strategy)
            } catch (_: IOException) {
                continue
            }
            session.use {
                checkCancelled()
                val start = System.nanoTime()
                val working = try {
                    probe(it)
                } catch (_: IOException) {
                    false
                }
                checkCancelled()
                if (working) {
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                    return use(Match(strategy, elapsed), it)
                }
            }
        }
        checkCancelled()
        throw IOException("No working ByeDPI strategy for Cloudflare WARP")
    }
}
