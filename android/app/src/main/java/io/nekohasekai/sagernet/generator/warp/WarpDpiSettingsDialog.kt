package io.nekohasekai.sagernet.generator.warp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
internal fun WarpDpiSettingsDialog(dismiss: () -> Unit) {
    val context = LocalContext.current
    val imported = remember(context) { DpiStrategyCatalog.imported(context) }
    var settings by remember(context) { mutableStateOf(DpiStrategyCatalog.settings(context)) }
    var timeout by remember { mutableStateOf(settings.timeout.toString()) }
    var search by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<DpiStrategyCatalog.Custom?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var forgetOnSave by remember { mutableStateOf(false) }
    val seconds = timeout.toIntOrNull()?.takeIf { it in 1..120 }

    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("ByeDPI settings") },
        text = {
            Column(
                Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("General", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    timeout, { timeout = it }, label = { Text("Probe timeout (seconds)") },
                    singleLine = true, isError = seconds == null,
                    supportingText = { Text("1–120 seconds. Default: 6.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                StrategyToggle("Remember successful strategy", settings.rememberSuccessful) {
                    settings = settings.copy(rememberSuccessful = it)
                }
                OutlinedTextField(
                    search, { search = it }, label = { Text("Search strategies") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                listOf("Built-in strategies" to DpiStrategyCatalog.strategies, "ByeDPIManager strategies" to imported)
                    .forEach { (title, group) ->
                        StrategyGroup(title, group.size, search, setAll = { enabled ->
                            val ids = group.map { it.id }.toSet()
                            settings = settings.copy(disabled =
                                if (enabled) settings.disabled - ids else settings.disabled + ids)
                        }) {
                            group.filter { matches(search, it.name, it.arguments.joinToString(" ")) }.forEach { entry ->
                                StrategyCard(entry.name, entry.arguments.joinToString(" "), entry.id !in settings.disabled, {
                                    settings = settings.copy(disabled =
                                        if (it) settings.disabled - entry.id else settings.disabled + entry.id)
                                })
                            }
                        }
                    }
                StrategyGroup("Custom strategies", settings.custom.size, search, initiallyExpanded = true, setAll = { enabled ->
                    settings = settings.copy(custom = settings.custom.map { it.copy(enabled = enabled) })
                }) {
                    settings.custom.filter { matches(search, it.name, it.args) }.forEach { item ->
                        StrategyCard(item.name, item.args, item.enabled, { enabled ->
                            settings = settings.copy(custom = settings.custom.map {
                                if (it.id == item.id) it.copy(enabled = enabled) else it
                            })
                        }) {
                            TextButton(onClick = { editing = item }) { Text("Edit") }
                            TextButton(onClick = {
                                settings = settings.copy(custom = settings.custom.filterNot { it.id == item.id })
                            }) { Text("Delete") }
                        }
                    }
                    OutlinedButton(onClick = {
                        editing = DpiStrategyCatalog.Custom("custom-" + UUID.randomUUID(), "", "")
                    }) { Text("Add strategy") }
                }
                TextButton(onClick = { confirmReset = true }) { Text("Reset to defaults") }
            }
        },
        confirmButton = {
            TextButton(enabled = seconds != null, onClick = {
                DpiStrategyCatalog.save(context, settings.copy(timeout = requireNotNull(seconds)), forgetOnSave)
                dismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }
    )
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset ByeDPI settings?") },
            text = { Text("This removes custom strategies and restores all defaults when you save.") },
            confirmButton = {
                TextButton(onClick = {
                    settings = DpiStrategyCatalog.Settings()
                    timeout = settings.timeout.toString()
                    forgetOnSave = true
                    confirmReset = false
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
        )
    }
    editing?.let { item ->
        key(item.id) {
            CustomStrategyDialog(item, { editing = null }) { updated ->
                val existing = settings.custom.any { it.id == updated.id }
                settings = settings.copy(custom = if (existing) {
                    settings.custom.map { if (it.id == updated.id) updated else it }
                } else settings.custom + updated)
                editing = null
            }
        }
    }
}

private fun matches(search: String, name: String, arguments: String) =
    name.contains(search.trim(), ignoreCase = true) || arguments.contains(search.trim(), ignoreCase = true)

@Composable
private fun StrategyGroup(
    title: String,
    count: Int,
    search: String,
    initiallyExpanded: Boolean = false,
    setAll: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    TextButton(onClick = { expanded = !expanded }) { Text("$title ($count)") }
    if (expanded || search.isNotBlank()) {
        if (count > 0) {
            Row {
                TextButton(onClick = { setAll(true) }) { Text("Enable all") }
                TextButton(onClick = { setAll(false) }) { Text("Disable all") }
            }
        }
        content()
    }
}

@Composable
private fun StrategyToggle(name: String, enabled: Boolean, toggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f))
        Switch(enabled, onCheckedChange = toggle)
    }
}

@Composable
private fun StrategyCard(
    name: String,
    arguments: String,
    enabled: Boolean,
    toggle: (Boolean) -> Unit,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            StrategyToggle(name, enabled, toggle)
            Text(arguments.ifBlank { "No arguments" }, style = MaterialTheme.typography.bodySmall)
            if (actions != null) Row(content = actions)
        }
    }
}

@Composable
private fun CustomStrategyDialog(
    item: DpiStrategyCatalog.Custom,
    dismiss: () -> Unit,
    save: (DpiStrategyCatalog.Custom) -> Unit
) {
    var name by remember { mutableStateOf(item.name) }
    var arguments by remember { mutableStateOf(item.args) }
    var error by remember { mutableStateOf<String?>(null) }
    var validating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Custom strategy") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, enabled = !validating, singleLine = true)
                OutlinedTextField(
                    arguments, { arguments = it; error = null },
                    label = { Text("ByeDPI arguments") }, enabled = !validating, isError = error != null,
                    supportingText = { error?.let { Text(it) } }
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !validating && name.isNotBlank() && arguments.isNotBlank(), onClick = {
                validating = true
                scope.launch {
                    try {
                        error = withContext(Dispatchers.Default) {
                            try {
                                NativeByeDpi.validate(DpiStrategyArguments.tokenize(arguments))
                            } catch (e: IllegalArgumentException) {
                                e.message ?: "Invalid ByeDPI arguments."
                            } catch (e: IllegalStateException) {
                                e.message ?: "ByeDPI validation is unavailable."
                            }
                        }
                        if (error == null) save(item.copy(name = name.trim(), args = arguments.trim()))
                    } finally {
                        validating = false
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }
    )
}
