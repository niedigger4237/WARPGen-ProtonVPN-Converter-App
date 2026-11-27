package io.nekohasekai.sagernet.generator.warp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import io.nekohasekai.sagernet.generator.warp.DpiStrategyCatalog.Custom
import io.nekohasekai.sagernet.generator.warp.DpiStrategyCatalog.Settings

internal object AndroidDpiSettings {
    fun imported(context: Context) =
        DpiStrategyCatalog.imported(context.assets.open("generator/byedpi-manager/strategies.tsv").bufferedReader().use { it.readText() })

    private fun prefs(context: Context) =
        context.getSharedPreferences("warp_dpi", Context.MODE_PRIVATE)

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
            !DpiStrategyCatalog.retainPreferred(preferred, previous, updated)
        ) edit.remove("strategy")
        edit.apply()
    }

    fun available(context: Context, settings: Settings) =
        DpiStrategyCatalog.available(imported(context), settings)

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
