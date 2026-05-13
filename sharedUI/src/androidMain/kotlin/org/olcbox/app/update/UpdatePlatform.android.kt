package org.olcbox.app.update

import android.os.Build

actual fun currentUpdatePlatform(): UpdatePlatform {
    val arch = when (Build.SUPPORTED_ABIS.firstOrNull().orEmpty()) {
        "arm64-v8a" -> "arm64"
        "x86_64" -> "amd64"
        else -> Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" }
    }
    return UpdatePlatform("android", arch)
}
