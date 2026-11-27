package io.nekohasekai.sagernet.generator.proton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.generator.proton.ProtonConfExporter as Conf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

@Composable
internal fun ProtonScreen(
    presets: ProtonPresets,
    sources: List<ProtonParser.Source>,
    onSources: (List<ProtonParser.Source>) -> Unit,
    onPick: () -> Unit,
    onCopy: (String) -> Unit,
    onSave: (List<Conf.Output>) -> Unit,
    onImport: (String) -> Unit
) {
    var options by remember(presets) { mutableStateOf(presets.defaults()) }
    var advanced by remember { mutableStateOf(false) }
    var paste by remember { mutableStateOf(false) }
    var domainDialog by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ProtonStructuredExporter.Batch?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val s = options
    fun change(next: Conf.Options) { options = next; result = null; error = null }
    LaunchedEffect(sources) { result = null; error = null }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(Icons.Filled.Security, null, Modifier.padding(14.dp).size(28.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                Text("Proton VPN Converter", style = MaterialTheme.typography.headlineSmall)
                Text("Convert Proton WireGuard configurations", style = MaterialTheme.typography.bodyMedium)
            }
        }
        val hasResult = result?.outputs?.isNotEmpty() == true
        if (!hasResult) {
        ProtonCard("Source configuration", Icons.Filled.FolderOpen) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onPick, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Select files") }
                OutlinedButton(onClick = { paste = true }, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Paste text") }
            }
            if (sources.isEmpty()) Text("No configuration files selected", style = MaterialTheme.typography.bodySmall)
            sources.forEachIndexed { index, source ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(source.name, Modifier.weight(1f))
                    TextButton(onClick = { onSources(sources.filterIndexed { i, _ -> i != index }) }, enabled = !busy) { Text("Remove") }
                }
            }
        }
        ProtonChoice("Config Format", Conf.Format.values().toList(), s.format, !busy, { change(presets.selectFormat(s, it)) }) {
                when (it) {
                    Conf.Format.AWG -> "AmneziaWG (.conf)"
                    Conf.Format.WIRESOCK -> "WireSock (.conf)"
                    Conf.Format.CLASH -> "Clash / Mihomo (.yaml)"
                    Conf.Format.XRAY -> "Xray (.json)"
                }
            }
        ProtonChoice("Endpoint", Conf.ports, s.port, !busy, { change(s.copy(port = it)) }) { "Port: $it" }
        ProtonTile("Advanced settings", if (advanced) "Hide advanced settings" else "Show advanced settings", Icons.Filled.Tune, !busy, advanced) { advanced = !advanced }
        if (advanced) {
            ProtonCard("Junk packets") {
                ProtonChoice("Preset", Conf.Junk.values().toList(), s.junk, !busy, { change(s.copy(junk = it)) }) {
                    when (it) { Conf.Junk.LIGHT -> "Jc 3 · 1–3"; Conf.Junk.HEAVY -> "Jc 30 · 10–30"; Conf.Junk.CUSTOM -> "Custom" }
                }
                if (s.junk == Conf.Junk.CUSTOM) {
                    ProtonNumber("Jc", s.jc, !busy) { change(s.copy(jc = it)) }
                    ProtonNumber("Jmin", s.jmin, !busy) { change(s.copy(jmin = it)) }
                    ProtonNumber("Jmax", s.jmax, !busy) { change(s.copy(jmax = it)) }
                }
                TextButton(onClick = { change(presets.randomJunk(s)) }, enabled = !busy) { Text("Randomize") }
                ProtonToggle("No junk packets", s.noJunk, !busy) { change(s.copy(noJunk = it)) }
            }
            if (s.format == Conf.Format.WIRESOCK) {
                ProtonCard("Protocol masking") {
                    ProtonField("Id (domain)", s.wsDomain, !busy) { change(s.copy(wsDomain = it)) }
                    ProtonChoice("Ip", listOf("quic", "dns"), s.wsIp, !busy, { change(s.copy(wsIp = it)) })
                    ProtonChoice("Ib", listOf("chrome", "firefox", "curl"), s.wsBrowser, !busy, { change(s.copy(wsBrowser = it)) })
                    TextButton(onClick = { change(presets.randomWireSock(s)) }, enabled = !busy) { Text("Random domain") }
                }
            } else {
                ProtonCard("Protocol masking (I1–I5)") {
                    s.i.take(if (s.format == Conf.Format.XRAY) 1 else 5).forEachIndexed { index, value ->
                        ProtonField("I${index + 1}", value, !busy, false) { v ->
                            change(s.copy(i = s.i.toMutableList().apply { set(index, v) }))
                        }
                    }
                    Text("Empty I1 uses the bundled QUIC packet.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { change(presets.randomI1(s)) }, enabled = !busy) { Text("Randomize") }
                        TextButton(onClick = { domainDialog = true }, enabled = !busy) { Text("From domain") }
                    }
                }
            }
            if (s.format != Conf.Format.XRAY) {
                ProtonCard("Advanced obfuscation") {
                    ProtonToggle("AWG 3.0", s.awg3, !busy) { change(s.copy(awg3 = it)) }
                    if (s.awg3) {
                        Conf.rangeKeys.forEach { key ->
                            ProtonField(key, s.ranges[key].orEmpty(), !busy) { change(s.copy(ranges = s.ranges + (key to it))) }
                        }
                        TextButton(onClick = { change(presets.randomAwg3(s)) }, enabled = !busy) { Text("Randomize") }
                    }
                    ProtonToggle("AWG 3.1 — Disable cookies", s.awg31, !busy) { change(s.copy(awg31 = it)) }
                }
            }
            ProtonCard("Network") {
                ProtonNumber("MTU", s.mtu, !busy) { change(s.copy(mtu = it)) }
                ProtonToggle("Persistent keepalive", s.keepalive != null, !busy) { change(s.copy(keepalive = if (it) 25 else null)) }
                s.keepalive?.let { ProtonNumber("Keepalive (seconds)", it, !busy) { n -> change(s.copy(keepalive = n)) } }
                if (s.format == Conf.Format.AWG || s.format == Conf.Format.WIRESOCK)
                    ProtonToggle("Exclude LAN", s.splitAllowedIps != null, !busy) { change(presets.excludeLan(s, it)) }
            }
        }
        Button(enabled = !busy && sources.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), onClick = {
            busy = true; result = null; error = null
            scope.launch {
                try { result = withContext(Dispatchers.Default) { ProtonStructuredExporter.convert(sources, s) } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "Conversion failed" }
                finally { busy = false }
            }
        }) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Convert Configuration") }
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, null)
                Text("Select or paste Proton WireGuard files and choose the output format.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
        }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        result?.let { batch ->
            batch.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            if (batch.outputs.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, null, Modifier.size(30.dp))
                        Column { Text("Configuration generated", style = MaterialTheme.typography.titleMedium); Text(if (batch.errors.isEmpty()) "Ready to use" else "Some files failed") }
                    }
                }
                ProtonCard("Generated configurations (${batch.outputs.size})") {
                    if (batch.outputs.size > 1) {
                        Row {
                            TextButton(onClick = { onCopy(batch.outputs.joinToString("\n") { "### ${it.fileName}\n${it.text}" }) }) { Text("Copy all") }
                            TextButton(onClick = { onSave(batch.outputs) }) { Text("Save all") }
                        }
                    }
                    batch.outputs.forEach { output ->
                        Text(output.fileName, style = MaterialTheme.typography.titleSmall)
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(output.text, Modifier.padding(12.dp).horizontalScroll(rememberScrollState()),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), softWrap = false)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { onCopy(output.text) }) { Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Copy") }
                            FilledTonalButton(onClick = { onSave(listOf(output)) }) { Icon(Icons.Filled.FileDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Save") }
                            if (s.format == Conf.Format.AWG || s.format == Conf.Format.WIRESOCK)
                                FilledTonalButton(onClick = { onImport(output.text) }) { Icon(Icons.Filled.Input, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Share") }
                        }
                    }
                }
            }
        }
        OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            onClick = { onSources(emptyList()); result = null; error = null }) { Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Reset Configuration") }
    }
    if (paste) ProtonTextDialog("Paste configuration", "WireGuard configuration", true, { paste = false }) { value ->
        onSources(sources + ProtonParser.Source("pasted_${sources.size + 1}.conf", value)); paste = false
    }
    if (domainDialog) ProtonTextDialog("Generate QUIC I1", "Domain", false, { domainDialog = false }) { value ->
        domainDialog = false
        busy = true
        scope.launch {
            try { change(withContext(Dispatchers.Default) { presets.domainI1(s, value) }) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "I1 generation failed" }
            finally { busy = false }
        }
    }
}

@Composable private fun ProtonCard(title: String, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                icon?.let { Icon(it, null, tint = MaterialTheme.colorScheme.primary) }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}
@Composable private fun ProtonField(label: String, value: String, enabled: Boolean, singleLine: Boolean = true, change: (String) -> Unit) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), enabled = enabled, singleLine = singleLine, label = { Text(label) })
}
@Composable private fun ProtonNumber(label: String, value: Int, enabled: Boolean, change: (Int) -> Unit) {
    var draft by remember(value) { mutableStateOf(value.toString()) }
    ProtonField(label, draft, enabled) { text -> if (text.all { it.isDigit() }) { draft = text; text.toIntOrNull()?.let(change) } }
}
@Composable private fun ProtonToggle(label: String, value: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, change, enabled = enabled)
    }
}
@Composable private fun <T> ProtonChoice(label: String, options: List<T>, value: T, enabled: Boolean, change: (T) -> Unit, display: (T) -> String = { it.toString() }) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ProtonTile(label, display(value), if (label == "Endpoint") Icons.Filled.Dns else Icons.Filled.Description, enabled, expanded) { expanded = true }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(display(option)) }, onClick = { expanded = false; change(option) }) }
        }
    }
}
@Composable private fun ProtonTextDialog(title: String, label: String, multiline: Boolean, dismiss: () -> Unit, submit: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = !multiline, minLines = if (multiline) 5 else 1, maxLines = if (multiline) 10 else 1)
    }, confirmButton = { TextButton(onClick = { submit(value.trim()) }, enabled = value.isNotBlank()) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable private fun ProtonTile(title: String, subtitle: String, icon: ImageVector, enabled: Boolean, expanded: Boolean, onClick: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
        }
    }
}
