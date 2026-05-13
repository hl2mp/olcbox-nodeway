package org.olcbox.app.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdateServiceTest {
    @Test
    fun stableVersionComparisonUsesSemverParts() {
        assertTrue(AppUpdateService.isUpdateAvailable(ReleaseChannel.Stable, "v1.2.0", "1.1.9"))
        assertFalse(AppUpdateService.isUpdateAvailable(ReleaseChannel.Stable, "v1.0.0", "1.0.0"))
        assertTrue(AppUpdateService.isUpdateAvailable(ReleaseChannel.Nightly, "nightly", "9.9.9"))
    }

    @Test
    fun selectsPreferredAssetForPlatform() {
        val assets = listOf(
            GithubReleaseAsset("Olcbox-1.0.0-windows-amd64-portable.zip", "https://example/zip"),
            GithubReleaseAsset("Olcbox-1.0.0-windows-amd64.msi", "https://example/msi")
        )

        val selected = AppUpdateService.selectAsset(
            assets = assets,
            platform = UpdatePlatform("windows", "amd64")
        )

        assertEquals("Olcbox-1.0.0-windows-amd64.msi", selected?.name)
    }

    @Test
    fun selectsNightlyAndroidApk() {
        val selected = AppUpdateService.selectAsset(
            assets = listOf(
                GithubReleaseAsset("olcbox-nightly-android-arm64.apk", "https://example/app.apk")
            ),
            platform = UpdatePlatform("android", "arm64")
        )

        assertEquals("https://example/app.apk", selected?.downloadUrl)
    }

    @Test
    fun updateSettingsPersistAndDueCheckUsesInterval() {
        val settings = AppUpdateSettings(
            channel = ReleaseChannel.Nightly,
            intervalHours = 6,
            lastCheckAtEpochMs = 1_000L,
            lastSeenUpdateVersion = "Nightly:nightly:apk"
        )
        val store = InMemoryAppUpdateSettingsStore()

        kotlinx.coroutines.test.runTest {
            store.save(settings)

            assertEquals(settings, store.load())
            assertFalse(store.load().isUpdateCheckDue(1_000L + 5L * 60L * 60L * 1_000L))
            assertTrue(store.load().isUpdateCheckDue(1_000L + 6L * 60L * 60L * 1_000L))
        }
    }

    @Test
    fun laterSuppressesSameUpdateUntilNextIntervalOrNewVersion() {
        val asset = AppUpdateAsset("olcbox-android.apk", "https://example/app.apk", 100)
        val info = AppUpdateInfo(
            channel = ReleaseChannel.Nightly,
            version = "nightly",
            htmlUrl = "https://example/release",
            publishedAt = null,
            asset = asset,
            isUpdateAvailable = true
        )
        val settings = AppUpdateSettings(
            channel = ReleaseChannel.Nightly,
            intervalHours = 6,
            lastCheckAtEpochMs = 10_000L,
            lastSeenUpdateVersion = info.identity()
        )

        assertFalse(info.shouldShowOffer(settings, 10_000L + 2L * 60L * 60L * 1_000L))
        assertTrue(info.shouldShowOffer(settings, 10_000L + 6L * 60L * 60L * 1_000L))
        assertTrue(info.copy(asset = asset.copy(name = "olcbox-android-new.apk")).shouldShowOffer(settings, 10_001L))
    }
}
