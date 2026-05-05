package org.olcbox.app.data.exporter

import android.content.Context
import android.net.Uri

class AndroidLogExporter(private val context: Context) : LogExporter {
    override suspend fun writeLogs(target: Any, content: String): Result<String> {
        val uri = target as? Uri
            ?: return Result.failure(IllegalArgumentException("Android log export target must be a Uri"))

        return runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            } ?: error("Cannot open selected file")
            "Logs saved"
        }
    }
}
