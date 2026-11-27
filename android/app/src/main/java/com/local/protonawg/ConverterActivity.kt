package com.local.protonawg

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.generator.proton.ProtonParser
import io.nekohasekai.sagernet.generator.proton.ProtonPresets
import io.nekohasekai.sagernet.generator.proton.ProtonScreen
import io.nekohasekai.sagernet.generator.warp.WarpScreen
import io.nekohasekai.sagernet.generator.warp.AndroidWarpViewModel
import io.nekohasekai.sagernet.generator.warp.WarpDpiSettingsDialog
import io.nekohasekai.sagernet.ktx.CreateExportDocument
import io.nekohasekai.sagernet.ktx.ensureExportExtension
import io.nekohasekai.sagernet.ktx.exportMimeType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConverterActivity : ComponentActivity() {
    class FileState : ViewModel() {
        internal val sources = mutableStateOf<List<ProtonParser.Source>>(emptyList())
        internal var pending: List<Pair<String, String>> = emptyList()
    }
    private val files by lazy { ViewModelProvider(this)[FileState::class.java] }
    private val warp by lazy { ViewModelProvider(this)[AndroidWarpViewModel::class.java].model }
    private val presets by lazy { ProtonPresets { name -> applicationContext.assets.open(name).bufferedReader().use { it.readText() } } }
    private fun notify(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("config", text))
    }
    private fun share(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share configuration"))
    }
    private val pick = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                uris.take(100).mapNotNull { uri ->
                    try {
                        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        } ?: "proton.conf"
                        val text = requireNotNull(contentResolver.openInputStream(uri)).bufferedReader(Charsets.UTF_8).use { reader ->
                            val out = StringBuilder()
                            val buffer = CharArray(4096)
                            while (true) {
                                val n = reader.read(buffer)
                                if (n < 0) break
                                require(out.length + n <= 1024 * 1024) { "File too large" }
                                out.append(buffer, 0, n)
                            }
                            out.toString()
                        }
                        ProtonParser.Source(name, text)
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                }
            }
            if (loaded.isNotEmpty()) files.sources.value = loaded
            notify("Loaded ${loaded.size} configuration(s), failed: ${uris.size - loaded.size}")
        }
    }
    private fun write(uri: Uri, name: String, text: String) {
        val destination = contentResolver.ensureExportExtension(uri, name)
        requireNotNull(contentResolver.openOutputStream(destination, "wt")).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
    private val save = registerForActivityResult(CreateExportDocument()) { uri ->
        val output = files.pending.singleOrNull()
        files.pending = emptyList()
        if (uri != null && output != null) lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { write(uri, output.first, output.second) }.isSuccess }
            notify(if (ok) "Configuration saved" else "Unable to save configuration")
        }
    }
    private val folder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val outputs = files.pending
        files.pending = emptyList()
        if (uri != null && outputs.isNotEmpty()) lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                val dir = DocumentFile.fromTreeUri(this@ConverterActivity, uri)
                outputs.count { (name, text) ->
                    runCatching {
                        val file = requireNotNull(dir?.createFile(exportMimeType(name), name))
                        try { write(file.uri, name, text) } catch (e: Exception) { runCatching { file.delete() }; throw e }
                    }.isSuccess
                }
            }
            notify("Saved $count of ${outputs.size} configuration(s)")
        }
    }
    private fun saveOutputs(outputs: List<Pair<String, String>>) {
        if (outputs.isEmpty()) return
        files.pending = outputs
        if (outputs.size == 1) save.launch(outputs.first().first) else folder.launch(null)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var tab by rememberSaveable { mutableStateOf(0) }
            val dark = isSystemInDarkTheme()
            val context = LocalContext.current
            val colors = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                        TabRow(selectedTabIndex = tab) {
                            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("WARP Generator") })
                            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("ProtonVPN Converter") })
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            val stateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                            stateHolder.SaveableStateProvider(tab) {
                                if (tab == 0) WarpScreen(warp, ::copy, { saveOutputs(listOf(it.fileName to it.text)) }, ::share, dpiSettings = { dismiss -> WarpDpiSettingsDialog(dismiss) })
                                else ProtonScreen(presets, files.sources.value, { files.sources.value = it },
                                    onPick = { pick.launch(arrayOf("*/*")) }, onCopy = ::copy,
                                    onSave = { outputs -> saveOutputs(outputs.map { it.fileName to it.text }) }, onImport = ::share)
                            }
                        }
                    }
                }
            }
        }
    }
}
