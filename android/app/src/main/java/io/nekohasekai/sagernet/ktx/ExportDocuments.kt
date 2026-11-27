package io.nekohasekai.sagernet.ktx

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContract
import java.io.IOException
import java.util.Locale

/** Do not advertise arbitrary text-based formats as text/plain: SAF providers
 * may otherwise append .txt to the explicitly requested filename. */
internal fun exportMimeType(fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "json" -> "application/json"
        // No reliably mapped Android MIME type for .conf, .log or YAML.
        else -> "application/octet-stream"
    }

internal fun correctedExportName(actual: String, requested: String): String {
    val extension = requested.substringAfterLast('.', "")
    if (extension.isEmpty() || actual.endsWith(".$extension", ignoreCase = true)) return actual
    val stem = if (actual.endsWith(".txt", ignoreCase = true) ||
        actual.endsWith(".bin", ignoreCase = true)) actual.substringBeforeLast('.') else actual
    return if (stem.endsWith(".$extension", ignoreCase = true)) stem else "$stem.$extension"
}

internal class CreateExportDocument : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(exportMimeType(input))
            .putExtra(Intent.EXTRA_TITLE, input)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

private fun ContentResolver.exportDisplayName(uri: Uri): String =
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: throw IOException("Cannot verify exported filename")

/** Providers can ignore the suggested MIME/name. Correct an added suffix,
 * retaining the user's chosen basename and the returned URI after rename.
 * Fail explicitly rather than silently reporting an incorrectly named export. */
internal fun ContentResolver.ensureExportExtension(uri: Uri, requested: String): Uri {
    val actual = exportDisplayName(uri)
    val corrected = correctedExportName(actual, requested)
    if (actual == corrected) return uri
    val renamed = DocumentsContract.renameDocument(this, uri, corrected)
        ?: throw IOException("Document provider cannot preserve the requested file extension")
    val finalName = exportDisplayName(renamed)
    val extension = requested.substringAfterLast('.', "")
    if (!finalName.endsWith(".$extension", ignoreCase = true))
        throw IOException("Document provider changed the requested file extension")
    return renamed
}
