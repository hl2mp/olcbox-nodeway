package org.olcbox.app

data class AppInfo(
    val name: String,
    val version: String
)

object CurrentAppInfo {
    val value: AppInfo = AppInfo(
        name = "olcbox",
        version = "1.0.0"
    )

    val userAgent: String = "${value.name}/${value.version}"
}
