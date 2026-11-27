package io.nekohasekai.sagernet.generator.warp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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

    fun imported(context: Context): List<DpiStrategySelector.Strategy> =
        context.assets.open("generator/byedpi-manager/strategies.tsv").bufferedReader().useLines { lines ->
            lines.filter(String::isNotBlank).map { line ->
                val (number, args) = line.split('\t', limit = 2)
                DpiStrategySelector.Strategy("manager-$number", DpiStrategyArguments.tokenize(args), "ByeDPIManager #$number")
            }.toList()
        }

    private fun prefs(context: Context) =
        context.getSharedPreferences("warp_dpi", Context.MODE_PRIVATE)

    data class Custom(val id: String, val name: String, val args: String, val enabled: Boolean = true)
    data class Settings(
        val timeout: Int = 6,
        val custom: List<Custom> = emptyList(),
        val rememberSuccessful: Boolean = true,
        val disabled: Set<String> = emptySet()
    )

    fun settings(context: Context): Settings {
        val p = prefs(context)
        val array = runCatching { JSONArray(p.getString("custom", "[]") ?: "[]") }.getOrNull()
        val custom = (0 until (array?.length() ?: 0)).mapNotNull { index ->
            val item = array?.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            if (id.isEmpty()) return@mapNotNull null
            Custom(id, item.optString("name"), item.optString("args"), item.optBoolean("enabled", true))
        }
        return Settings(
            p.getInt("timeout", 6).coerceIn(1, 120),
            custom,
            p.getBoolean("remember_successful", true),
            p.getStringSet("disabled", emptySet()).orEmpty().toSet()
        )
    }

    @Synchronized
    fun save(context: Context, updated: Settings, forget: Boolean = false) {
        require(updated.timeout in 1..120)
        val prefs = prefs(context)
        val previous = settings(context)
        val preferred = prefs.getString("strategy", null)
        val array = JSONArray()
        updated.custom.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name)
                .put("args", it.args).put("enabled", it.enabled))
        }
        val edit = prefs.edit().putInt("timeout", updated.timeout).putString("custom", array.toString())
            .putBoolean("remember_successful", updated.rememberSuccessful)
            .putStringSet("disabled", updated.disabled)
        if (forget || !updated.rememberSuccessful ||
            !retainPreferred(preferred, previous, updated)
        ) edit.remove("strategy")
        edit.apply()
    }

    internal fun retainPreferred(id: String?, previous: Settings, current: Settings): Boolean {
        if (id == null || id in current.disabled) return false
        val prior = previous.custom.firstOrNull { it.id == id } ?: return true
        val updated = current.custom.firstOrNull { it.id == id } ?: return false
        return updated.enabled && updated.args == prior.args
    }

    fun available(context: Context, settings: Settings) =
        (strategies + imported(context)).filterNot { it.id in settings.disabled } +
            settings.custom.filter { it.enabled }.mapNotNull {
                runCatching { DpiStrategyArguments.tokenize(it.args) }.getOrNull()
                    ?.takeIf { args -> args.isNotEmpty() }
                    ?.let { args -> DpiStrategySelector.Strategy(it.id, args, it.name) }
            }

    fun preferred(context: Context, settings: Settings): String? =
        if (settings.rememberSuccessful) prefs(context).getString("strategy", null) else null

    @Synchronized
    fun remember(context: Context, match: DpiStrategySelector.Match) {
        val current = settings(context)
        if (!current.rememberSuccessful || available(context, current).none {
                it.id == match.strategy.id && it.arguments == match.strategy.arguments
            }) return
        prefs(context).edit().putString("strategy", match.strategy.id).apply()
    }
}
