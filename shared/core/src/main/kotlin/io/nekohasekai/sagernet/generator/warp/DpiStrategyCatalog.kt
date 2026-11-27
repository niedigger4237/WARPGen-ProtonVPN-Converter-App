package io.nekohasekai.sagernet.generator.warp

internal object DpiStrategyCatalog {
    private fun preset(id: String, args: String) =
        DpiStrategySelector.Strategy(id, args.split(' ').filter { it.isNotEmpty() })

    val strategies = listOf(
        preset("none", ""),
        preset("s1", "--split 1+s"),
        preset("d1", "--disorder 1+s"),
        preset("tlsrec", "--tlsrec 1+s"),
        preset("sd", "--split 1+s --disorder 3+s"),
        preset("dtr", "--disorder 1 --tlsrec 1+s"),
        preset("multi", "--split 1 --split 3+s --tlsrec 1+s"),
        preset("oob", "--oob 1+s"),
        preset("doob", "--disoob 1+s"),
        preset("oobtr", "--oob 3 --tlsrec 3+s"),
        preset("f8", "--fake -1 --ttl 8"),
        preset("f5", "--fake -1 --ttl 5"),
        preset("f10tr", "--fake -1 --ttl 10 --tlsrec 1+s"),
        preset("df", "--disorder 1 --fake -1 --ttl 8"),
        preset("auto1", "--disorder 1 --auto=torst --tlsrec 1+s"),
        preset("auto2", "--fake -1 --ttl 10 --auto=ssl_err --fake -1 --ttl 5"),
        preset("md5", "--fake -1 --md5sig"),
        preset("md5oob", "--oob 1 --fake -1 --md5sig")
    )

    fun imported(text: String): List<DpiStrategySelector.Strategy> =
        text.lineSequence().filter(String::isNotBlank).map { line ->
            val (number, args) = line.split('\t', limit = 2)
            DpiStrategySelector.Strategy("manager-$number", DpiStrategyArguments.tokenize(args), "ByeDPIManager #$number")
        }.toList()

    data class Custom(val id: String, val name: String, val args: String, val enabled: Boolean = true)
    data class Settings(
        val timeout: Int = 6,
        val custom: List<Custom> = emptyList(),
        val rememberSuccessful: Boolean = true,
        val disabled: Set<String> = emptySet()
    )

    internal fun retainPreferred(id: String?, previous: Settings, current: Settings): Boolean {
        if (id == null || id in current.disabled) return false
        val prior = previous.custom.firstOrNull { it.id == id } ?: return true
        val updated = current.custom.firstOrNull { it.id == id } ?: return false
        return updated.enabled && updated.args == prior.args
    }

    fun available(imported: List<DpiStrategySelector.Strategy>, settings: Settings) =
        (strategies + imported).filterNot { it.id in settings.disabled } +
            settings.custom.filter { it.enabled }.mapNotNull {
                runCatching { DpiStrategyArguments.tokenize(it.args) }.getOrNull()
                    ?.takeIf { args -> args.isNotEmpty() }
                    ?.let { args -> DpiStrategySelector.Strategy(it.id, args, it.name) }
            }

}
