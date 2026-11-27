package com.local.protonawg.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.nekohasekai.sagernet.generator.proton.ProtonParser
import io.nekohasekai.sagernet.generator.proton.ProtonPresets
import io.nekohasekai.sagernet.generator.proton.ProtonScreen
import io.nekohasekai.sagernet.generator.warp.WarpScreen
import io.nekohasekai.sagernet.generator.warp.WarpViewModel
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object Assets
private fun asset(name: String): String =
    requireNotNull(Assets::class.java.classLoader.getResourceAsStream(name)) { "Missing resource: $name" }
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "WARP + Proton AWG",
        state = rememberWindowState(width = 1000.dp, height = 760.dp)) {
        val scope = rememberCoroutineScope()
        val model = remember(scope) { WarpViewModel(::asset, scope) }
        val presets = remember { ProtonPresets(::asset) }
        var sources by remember { mutableStateOf<List<ProtonParser.Source>>(emptyList()) }
        var tab by remember { mutableStateOf(0) }
        val preferences = remember { PortableSettings.open() }
        var theme by remember { mutableStateOf(preferences.loadTheme()) }
        var message by remember { mutableStateOf("") }
        val dark = when(theme) { "Dark" -> true; "Light" -> false; else -> isSystemInDarkTheme() }
        fun copy(text: String) {
            message = runCatching {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                "Configuration copied"
            }.getOrElse { "Unable to access clipboard" }
        }
        fun pick() {
            val chooser = JFileChooser().apply {
                isMultiSelectionEnabled = true
                fileFilter = FileNameExtensionFilter("WireGuard configurations", "conf")
            }
            if (chooser.showOpenDialog(window) != JFileChooser.APPROVE_OPTION) return
            val files = chooser.selectedFiles.take(100)
            scope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    files.mapNotNull { file -> runCatching {
                        require(file.isFile && file.length() <= 1024 * 1024)
                        ProtonParser.Source(file.name, file.readText(Charsets.UTF_8))
                    }.getOrNull() }
                }
                if (loaded.isNotEmpty()) sources = loaded
                message = "Loaded ${loaded.size} of ${files.size} files"
            }
        }
        fun save(outputs: List<Pair<String, String>>) {
            if (outputs.isEmpty()) return
            val chooser = JFileChooser()
            val targets: List<Pair<File, String>>
            if (outputs.size == 1) {
                val (name, content) = outputs.single()
                chooser.selectedFile = File(name)
                if (chooser.showSaveDialog(window) != JFileChooser.APPROVE_OPTION) return
                val chosen = chooser.selectedFile
                val suffix = "." + name.substringAfterLast('.')
                val target = if (chosen.name.endsWith(suffix, ignoreCase = true)) chosen else File(chosen.path + suffix)
                if (target.exists() && JOptionPane.showConfirmDialog(window, "Replace ${target.name}?", "Save", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return
                targets = listOf(target to content)
            } else {
                chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                if (chooser.showSaveDialog(window) != JFileChooser.APPROVE_OPTION) return
                val dir = chooser.selectedFile
                targets = outputs.mapNotNull { (name, content) ->
                    if (name != File(name).name || name == "." || name == ".." || name.contains(':')) null else File(dir, name) to content
                }
            }
            scope.launch {
                val count = withContext(Dispatchers.IO) {
                    targets.count { (file, content) -> runCatching {
                        if (outputs.size == 1) Files.writeString(file.toPath(), content, Charsets.UTF_8)
                        else Files.writeString(file.toPath(), content, Charsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    }.isSuccess }
                }
                message = "Saved $count of ${outputs.size} configurations (existing batch files skipped)"
            }
        }
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.width(190.dp).fillMaxHeight().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("WARP + Proton", style = MaterialTheme.typography.titleLarge)
                        NavigationDrawerItem(label = { Text("WARP") }, selected = tab == 0, onClick = { tab = 0 })
                        NavigationDrawerItem(label = { Text("Proton VPN") }, selected = tab == 1, onClick = { tab = 1 })
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            theme = when(theme) { "System" -> "Light"; "Light" -> "Dark"; else -> "System" }
                            if (!preferences.saveTheme(theme)) message = "Unable to save settings in the portable data folder"
                        }) { Text("Theme: $theme") }
                    }
                    VerticalDivider()
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (message.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, Modifier.weight(1f))
                            TextButton(onClick = { message = "" }) { Text("Dismiss") }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (tab == 0) WarpScreen(model, ::copy, { save(listOf(it.fileName to it.text)) }, ::copy)
                            else ProtonScreen(presets, sources, { sources = it }, ::pick, ::copy,
                                { outputs -> save(outputs.map { it.fileName to it.text }) }, ::copy)
                        }
                    }
                }
            }
        }
    }
}
