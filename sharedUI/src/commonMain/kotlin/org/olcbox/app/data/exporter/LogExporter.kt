package org.olcbox.app.data.exporter

interface LogExporter {
    suspend fun writeLogs(target: Any, content: String): Result<String>
}
