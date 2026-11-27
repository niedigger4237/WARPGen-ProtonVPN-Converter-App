package com.local.protonawg.desktop

import io.nekohasekai.sagernet.generator.proton.ProtonParser
import java.awt.Component
import kotlinx.coroutines.CoroutineScope
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

internal class DesktopFiles(
    private val window: Component,
    private val scope: CoroutineScope,
    private val onSources: (List<ProtonParser.Source>) -> Unit,
    private val notify: (String) -> Unit
) {
    private var message: String
        get() = ""
        set(value) = notify(value)

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
            if (loaded.isNotEmpty()) onSources(loaded)
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
}
