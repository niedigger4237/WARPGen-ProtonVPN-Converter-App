package io.nekohasekai.sagernet.generator.warp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.horizontalScroll
import kotlin.random.Random
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings

@Composable
internal fun WarpScreen(model: WarpViewModel, onCopy: (String) -> Unit,
                        onSave: (WarpConfExporter.Output) -> Unit,
                        onImport: (String) -> Unit,
                        dpiSettings: (@Composable (() -> Unit) -> Unit)? = null) {
    val state by model.state.collectAsState()
    val s = state.settings
    val enabled = !state.busy
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(Icons.Filled.Cloud, null, Modifier.padding(14.dp).size(28.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                Text("WARP Generator", style = MaterialTheme.typography.headlineSmall)
                Text("Generate a Cloudflare WARP configuration", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (state.output == null || state.busy) {
        WarpTransportChoice(state.transport, enabled, model.availableTransports, model::transport, dpiSettings)
        state.strategyProgress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        WarpTileChoice("Config Format", Icons.Filled.Description, WarpGenerator.Format.values().toList(), s.format, enabled,
                { model.settings(s.copy(format = it)) }, { format -> when (format) {
                    WarpGenerator.Format.AWG -> "AmneziaWG (.conf)"
                    WarpGenerator.Format.WIRESOCK -> "WireSock (.conf)"
                    WarpGenerator.Format.CLASH -> "Clash / Mihomo (.yaml)"
                    WarpGenerator.Format.XRAY -> "Xray (.json)"
                } })
        WarpTileChoice("Endpoint", Icons.Filled.Dns, WarpPresets.EndpointMode.values().toList(), s.endpointMode, enabled,
                { model.settings(s.copy(endpointMode = it)) })
            if (s.endpointMode == WarpPresets.EndpointMode.CUSTOM)
                WarpField("Custom endpoint", s.customEndpoint, enabled) { model.settings(s.copy(customEndpoint = it)) }
        var advancedExpanded by remember { mutableStateOf(false) }
        WarpTile("Advanced settings", if (advancedExpanded) "Hide advanced settings" else "Show advanced settings", Icons.Filled.Tune, true, { advancedExpanded = !advancedExpanded }, advancedExpanded)
        if (advancedExpanded) {
        WarpCard("Configuration options") {
            if (s.format == WarpGenerator.Format.AWG)
                WarpChoice("Variant", WarpConfExporter.Variant.values().toList(), s.variant, enabled,
                    { model.settings(s.copy(variant = it)) })
            if (s.format == WarpGenerator.Format.WIRESOCK || s.format == WarpGenerator.Format.XRAY ||
                (s.format == WarpGenerator.Format.AWG && s.variant == WarpConfExporter.Variant.DOMAIN))
                WarpField("Domain", s.domain, enabled) { model.settings(s.copy(domain = it)) }
            if (s.format == WarpGenerator.Format.WIRESOCK) {
                WarpChoice("IP masking", listOf("QUIC", "DNS"), s.wireSockIp, enabled,
                    { model.settings(s.copy(wireSockIp = it)) })
                WarpChoice("Browser", listOf("Chrome", "Firefox", "cURL"), s.wireSockIb, enabled,
                    { model.settings(s.copy(wireSockIb = it)) })
            }
        }
        WarpCard("Network") {
            WarpChoice("DNS", model.presets.dns.map { it.id }, s.dnsId, enabled,
                { model.settings(s.copy(dnsId = it)) },
                { id -> model.presets.dns.first { it.id == id }.label })
            WarpField("MTU", s.mtu.toString(), enabled) { it.toIntOrNull()?.let { n -> model.settings(s.copy(mtu = n)) } }
            WarpToggle("IPv6", s.ipv6, enabled) { model.settings(s.copy(ipv6 = it)) }
            WarpToggle("Persistent keepalive", s.keepalive != null, enabled) {
                model.settings(s.copy(keepalive = if (it) 25 else null))
            }
            s.keepalive?.let { value ->
                WarpField("Keepalive (seconds)", value.toString(), enabled) {
                    it.toIntOrNull()?.let { n -> model.settings(s.copy(keepalive = n)) }
                }
            }
            WarpToggle("No junk packets", s.noJunk, enabled) { model.settings(s.copy(noJunk = it)) }
        }
        if (s.format == WarpGenerator.Format.AWG || s.format == WarpGenerator.Format.CLASH) {
            var expanded by remember { mutableStateOf(false) }
            WarpCard("Advanced obfuscation") {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide AWG options" else "Show AWG options") }
                if (expanded) {
                    val keys = listOf("ContentPaddingAddition", "MaxHandshakeAttempts", "KeepaliveTimeout",
                        "RejectAfterTime", "RekeyAfterTime", "RekeyTimeout")
                    WarpToggle("AWG 3.0", s.awg3Enabled, enabled) { model.settings(s.copy(awg3Enabled = it)) }
                    if (s.awg3Enabled) {
                    keys.forEach { key ->
                        WarpField(key, s.awg3[key].orEmpty(), enabled) {
                            model.settings(s.copy(awg3 = s.awg3 + (key to it)))
                        }
                    }
                    TextButton(enabled = enabled, onClick = {
                        fun range(a: Int, b: Int, c: Int, d: Int) = "${Random.nextInt(a, b + 1)}-${Random.nextInt(c, d + 1)}"
                        model.settings(s.copy(awg3 = mapOf(
                            "ContentPaddingAddition" to range(5,49,50,110),
                            "MaxHandshakeAttempts" to range(5,24,25,40),
                            "KeepaliveTimeout" to range(5,10,11,25),
                            "RejectAfterTime" to range(50,99,100,200),
                            "RekeyAfterTime" to range(50,99,100,150),
                            "RekeyTimeout" to range(3,9,10,15))))
                    }) { Text("Randomize") }
                    }
                    WarpToggle("AWG 3.1", s.awg31Enabled, enabled) { model.settings(s.copy(awg31Enabled = it)) }
                    if (s.awg31Enabled) {
                    WarpToggle("Random trailers", s.randomTrailers, enabled) { model.settings(s.copy(randomTrailers = it)) }
                    WarpToggle("Disable cookies", s.disableCookies, enabled) { model.settings(s.copy(disableCookies = it)) }
                    }
                }
            }
        }
        if (s.format == WarpGenerator.Format.AWG || s.format == WarpGenerator.Format.WIRESOCK) {
            var expanded by remember { mutableStateOf(false) }
            WarpCard("Split tunneling") {
                WarpToggle("Exclude LAN", s.excludeLan, enabled) {
                    model.settings(s.copy(excludeLan = it, sites = if (it) emptySet() else s.sites))
                }
                TextButton(onClick = { expanded = !expanded }) { Text("Select services (${s.sites.size})") }
                if (expanded) model.presets.siteNames.forEach { (id, name) ->
                    WarpToggle(name, id in s.sites, enabled && !s.excludeLan) {
                        model.settings(s.copy(sites = if (it) s.sites + id else s.sites - id))
                    }
                }
            }
        }
        }
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = model::cancel) { Text("Cancel") }
        } else Button(onClick = model::generate, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Generate Configuration") }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.output?.let { output ->
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null); Spacer(Modifier.width(12.dp)); Column { Text("Configuration generated", style = MaterialTheme.typography.titleMedium); Text("Ready to use") }
                }
            }
            WarpCard("Configuration") {
                Text(output.fileName)
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(output.text, Modifier.padding(12.dp).horizontalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), softWrap = false)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { onCopy(output.text) }) { Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Copy") }
                    FilledTonalButton(onClick = { onSave(output) }) { Icon(Icons.Filled.FileDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Save") }
                    if (s.format == WarpGenerator.Format.AWG || s.format == WarpGenerator.Format.WIRESOCK)
                        FilledTonalButton(onClick = { onImport(output.text) }) { Icon(Icons.Filled.Input, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Share") }
                }
            }
            OutlinedButton(onClick = model::resetAccount, enabled = enabled, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Reset configuration") }
        }
    }
}

@Composable private fun WarpCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
@Composable private fun WarpField(label: String, value: String, enabled: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true, label = { Text(label) })
}
@Composable private fun WarpToggle(label: String, value: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, change, enabled = enabled)
    }
}
@Composable private fun <T> WarpChoice(label: String, options: List<T>, value: T, enabled: Boolean,
                                       change: (T) -> Unit, display: (T) -> String = { it.toString() }) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { expanded = true }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(display(value), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.Filled.KeyboardArrowDown, null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(display(option)) }, onClick = { expanded = false; change(option) })
            }
        }
    }
}

@Composable
private fun WarpTransportChoice(value: WarpViewModel.Transport, enabled: Boolean, options: List<WarpViewModel.Transport>, change: (WarpViewModel.Transport) -> Unit, dpiSettings: (@Composable (() -> Unit) -> Unit)?) {
    var expanded by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    fun label(t: WarpViewModel.Transport) = if (t == WarpViewModel.Transport.BYEDPI) "ByeDPI (automatic)" else if (t == WarpViewModel.Transport.LOCAL_PROXY) "Local proxy" else "Direct"
    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { WarpTile("API Connection", label(value), Icons.Filled.AccountTree, enabled, { expanded = true }) }
            if (value == WarpViewModel.Transport.BYEDPI) IconButton(onClick = { settings = true }, enabled = enabled) {
                Icon(Icons.Filled.Settings, contentDescription = "ByeDPI settings")
            }
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(label(option)) },
                    onClick = { expanded = false; change(option) },
                    trailingIcon = if (option == WarpViewModel.Transport.BYEDPI) {
                        {
                            IconButton(onClick = { expanded = false; settings = true }) {
                                Icon(Icons.Filled.Settings, contentDescription = "ByeDPI settings")
                            }
                        }
                    } else null)
            }
        }
    }
    if (settings && enabled) dpiSettings?.invoke { settings = false }
}

@Composable private fun WarpTile(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit, expanded: Boolean = false) {
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
@Composable private fun <T> WarpTileChoice(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, options: List<T>, value: T, enabled: Boolean, change: (T) -> Unit, display: (T) -> String = { it.toString() }) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        WarpTile(title, display(value), icon, enabled, { expanded = true }, expanded)
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(display(option)) }, onClick = { expanded = false; change(option) }) }
        }
    }
}
