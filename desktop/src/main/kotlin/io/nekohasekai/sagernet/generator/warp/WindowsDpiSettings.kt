package io.nekohasekai.sagernet.generator.warp

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import org.json.JSONArray
import org.json.JSONObject

internal class WindowsDpiSettings(private val directory: Path, readAsset: (String) -> String) {
    val imported = DpiStrategyCatalog.imported(readAsset("generator/byedpi-manager/strategies.tsv"))
    private val file = directory.resolve("warp-dpi.json")
    private fun read(): JSONObject = runCatching {
        if (Files.isRegularFile(file)) JSONObject(Files.readString(file)) else JSONObject()
    }.getOrElse { JSONObject() }
    private fun write(json: JSONObject) {
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, "warp-dpi-", ".tmp")
        try {
            Files.writeString(temp, json.toString())
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temp) }
    }
    private fun decode(p: JSONObject): DpiStrategyCatalog.Settings {
        val array = p.optJSONArray("custom") ?: JSONArray()
        val custom = (0 until array.length()).mapNotNull {
            val item = array.optJSONObject(it) ?: return@mapNotNull null
            val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            DpiStrategyCatalog.Custom(id, item.optString("name"), item.optString("args"), item.optBoolean("enabled", true))
        }
        val disabled = p.optJSONArray("disabled") ?: JSONArray()
        return DpiStrategyCatalog.Settings(p.optInt("timeout", 6).coerceIn(1, 120), custom,
            p.optBoolean("remember_successful", true),
            (0 until disabled.length()).map { disabled.optString(it) }.toSet())
    }
    @Synchronized fun settings() = decode(read())
    fun available(settings: DpiStrategyCatalog.Settings) = DpiStrategyCatalog.available(imported, settings)
    @Synchronized fun preferred(settings: DpiStrategyCatalog.Settings): String? =
        if (settings.rememberSuccessful) read().optString("strategy").takeIf(String::isNotBlank) else null
    @Synchronized fun save(updated: DpiStrategyCatalog.Settings, forget: Boolean = false) {
        require(updated.timeout in 1..120)
        val old = read()
        val previous = decode(old)
        val preferred = old.optString("strategy").takeIf(String::isNotBlank)
        val array = JSONArray()
        updated.custom.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("args", it.args).put("enabled", it.enabled)) }
        val json = JSONObject().put("timeout", updated.timeout).put("custom", array)
            .put("remember_successful", updated.rememberSuccessful).put("disabled", JSONArray(updated.disabled.toList()))
        if (!forget && updated.rememberSuccessful && DpiStrategyCatalog.retainPreferred(preferred, previous, updated))
            json.put("strategy", preferred)
        write(json)
    }
    @Synchronized fun remember(match: DpiStrategySelector.Match) {
        val p = read()
        val current = decode(p)
        if (current.rememberSuccessful && available(current).any { it.id == match.strategy.id && it.arguments == match.strategy.arguments })
            runCatching { write(p.put("strategy", match.strategy.id)) }
    }
}
