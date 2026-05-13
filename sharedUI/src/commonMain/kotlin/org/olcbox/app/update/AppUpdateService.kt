package org.olcbox.app.update

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.data.identity.DeviceIdentityProvider

@Serializable
enum class ReleaseChannel {
    Stable,
    Nightly
}

data class ReleaseMirror(
    val name: String,
    val repositoryUrl: String
) {
    val ownerRepo: String
        get() = repositoryUrl
            .removePrefix("https://github.com/")
            .removeSuffix("/")

    companion object {
        val GitHub = ReleaseMirror(
            name = "GitHub",
            repositoryUrl = "https://github.com/alananisimov/olcbox"
        )
    }
}

data class AppUpdateAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long?
)

data class AppUpdateInfo(
    val channel: ReleaseChannel,
    val version: String,
    val htmlUrl: String,
    val publishedAt: String?,
    val asset: AppUpdateAsset,
    val isUpdateAvailable: Boolean
)

class AppUpdateService(
    private val httpClient: HttpClient = createUpdateHttpClient(),
    private val deviceIdentityProvider: DeviceIdentityProvider,
    private val mirror: ReleaseMirror = ReleaseMirror.GitHub,
    private val currentVersion: String = CurrentAppInfo.value.version,
    private val platform: UpdatePlatform = UpdatePlatform.current()
) {
    suspend fun check(channel: ReleaseChannel): Result<AppUpdateInfo> = runCatching {
        val release = fetchRelease(channel)
        val asset = selectAsset(release.assets, platform)
            ?: error(
                "No ${platform.assetToken.joinToString(" + ")} update asset in ${release.tagName}. " +
                        "Expected asset name containing ${platform.assetToken.joinToString(", ")}" +
                        platform.preferredExtensions.takeIf { it.isNotEmpty() }
                            ?.joinToString(prefix = " and ending with one of: ")
                            .orEmpty()
            )

        AppUpdateInfo(
            channel = channel,
            version = release.tagName.removePrefix("v"),
            htmlUrl = release.htmlUrl,
            publishedAt = release.publishedAt,
            asset = asset,
            isUpdateAvailable = isUpdateAvailable(channel, release.tagName, currentVersion)
        )
    }

    suspend fun fetchRelease(channel: ReleaseChannel): GithubRelease {
        val endpoint = when (channel) {
            ReleaseChannel.Stable -> "https://api.github.com/repos/${mirror.ownerRepo}/releases/latest"
            ReleaseChannel.Nightly -> "https://api.github.com/repos/${mirror.ownerRepo}/releases/tags/nightly"
        }

        val hwid = deviceIdentityProvider.hwid()
        val response = httpClient.get(endpoint) {
            headers {
                append(HttpHeaders.Accept, "application/vnd.github+json")
                append(HttpHeaders.UserAgent, CurrentAppInfo.userAgent)
                append("x-hwid", hwid)
            }
        }

        if (response.status.value !in 200..299) {
            error("GitHub release request failed with HTTP ${response.status.value}")
        }

        return json.decodeFromString(GithubRelease.serializer(), response.bodyAsText())
    }

    companion object {
        fun selectAsset(assets: List<GithubReleaseAsset>, platform: UpdatePlatform): AppUpdateAsset? {
            val token = platform.assetToken
            val candidates = assets.filter { asset ->
                val name = asset.name.lowercase()
                token.all { it in name }
            }
            val asset = platform.preferredExtensions
                .firstNotNullOfOrNull { extension ->
                    candidates.firstOrNull { it.name.lowercase().endsWith(extension) }
                }
                ?: candidates.firstOrNull()

            return asset?.let {
                AppUpdateAsset(
                    name = it.name,
                    downloadUrl = it.browserDownloadUrl,
                    sizeBytes = it.size
                )
            }
        }

        fun isUpdateAvailable(
            channel: ReleaseChannel,
            releaseTag: String,
            currentVersion: String
        ): Boolean {
            if (channel == ReleaseChannel.Nightly) return true

            val release = releaseTag.removePrefix("v")
            return compareVersions(release, currentVersion) > 0
        }

        fun compareVersions(left: String, right: String): Int {
            val leftParts = left.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
            val rightParts = right.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
            val size = maxOf(leftParts.size, rightParts.size)
            for (index in 0 until size) {
                val diff = (leftParts.getOrNull(index) ?: 0) - (rightParts.getOrNull(index) ?: 0)
                if (diff != 0) return diff
            }
            return 0
        }
    }
}

data class UpdatePlatform(
    val os: String,
    val arch: String
) {
    val assetToken: List<String>
        get() = when (os) {
            "windows" -> listOf("windows", "amd64")
            "macos" -> listOf("macos", arch)
            "linux" -> listOf("linux", arch)
            "android" -> listOf("android")
            else -> listOf(os, arch)
        }

    val preferredExtensions: List<String>
        get() = when (os) {
            "windows" -> listOf(".msi", ".exe", ".zip")
            "macos" -> listOf(".dmg")
            "linux" -> listOf(".appimage")
            "android" -> listOf(".apk")
            else -> emptyList()
        }

    companion object {
        fun current(): UpdatePlatform = currentUpdatePlatform()
    }
}

expect fun currentUpdatePlatform(): UpdatePlatform

@Serializable
data class GithubRelease(
    @SerialName("tag_name")
    val tagName: String,
    @SerialName("html_url")
    val htmlUrl: String,
    @SerialName("published_at")
    val publishedAt: String? = null,
    val assets: List<GithubReleaseAsset> = emptyList()
)

@Serializable
data class GithubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String,
    val size: Long? = null
)

private val json = Json {
    ignoreUnknownKeys = true
}

private fun createUpdateHttpClient(): HttpClient {
    return HttpClient {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
    }
}
