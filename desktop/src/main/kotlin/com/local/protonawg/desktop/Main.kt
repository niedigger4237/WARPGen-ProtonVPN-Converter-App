package com.local.protonawg.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.nekohasekai.sagernet.generator.proton.ProtonParser
import io.nekohasekai.sagernet.generator.proton.ProtonPresets
import io.nekohasekai.sagernet.generator.proton.ProtonScreen
import io.nekohasekai.sagernet.generator.warp.WarpScreen
import io.nekohasekai.sagernet.generator.warp.WarpViewModel
import io.nekohasekai.sagernet.generator.warp.WindowsDpiSettings
import io.nekohasekai.sagernet.generator.warp.WindowsDpiTransport
import io.nekohasekai.sagernet.generator.warp.WindowsDpiSettingsDialog
import java.nio.file.Path

private object Assets
private fun asset(name: String): String =
    requireNotNull(Assets::class.java.classLoader.getResourceAsStream(name)) { "Missing resource: $name" }
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "WP Toolkit",
        icon = painterResource("icon.png"),
        state = rememberWindowState(width = 1000.dp, height = 760.dp)) {
        val scope = rememberCoroutineScope()
        val dpiSettings = remember { WindowsDpiSettings(PortableSettings.dataDirectory(System.getProperty("jpackage.app-path"), Path.of(System.getProperty("user.dir"))), ::asset) }
        val dpi = remember(dpiSettings) { WindowsDpiTransport(dpiSettings) }
        val model = remember(scope, dpi) { WarpViewModel(::asset, scope, dpi::register) }
        val presets = remember { ProtonPresets(::asset) }
        var sources by remember { mutableStateOf<List<ProtonParser.Source>>(emptyList()) }
        var tab by remember { mutableStateOf(0) }
        var message by remember { mutableStateOf("") }
        val dark = isSystemInDarkTheme()
        val files = DesktopFiles(window, scope, { sources = it }, { message = it })
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.width(230.dp).fillMaxHeight().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        NavigationDrawerItem(label = { Text("WARP Generator") }, selected = tab == 0, onClick = { tab = 0 })
                        NavigationDrawerItem(label = { Text("ProtonVPN Converter") }, selected = tab == 1, onClick = { tab = 1 })
                    }
                    VerticalDivider()
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (message.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, Modifier.weight(1f))
                            TextButton(onClick = { message = "" }) { Text("Dismiss") }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (tab == 0) WarpScreen(model, files::copy, { files.save(listOf(it.fileName to it.text)) }, files::copy, dpiSettings = { dismiss -> WindowsDpiSettingsDialog(dpiSettings, dismiss) })
                            else ProtonScreen(presets, sources, { sources = it }, files::pick, files::copy,
                                { outputs -> files.save(outputs.map { it.fileName to it.text }) }, files::copy)
                        }
                    }
                }
            }
        }
    }
}
