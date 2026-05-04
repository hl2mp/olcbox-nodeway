package org.turnbox.app.data.datasource

import kotlinx.coroutines.test.runTest
import org.turnbox.app.data.model.LocationBundleV4
import org.turnbox.app.data.model.LocationConfig
import org.turnbox.app.data.model.LocationEntry
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class JvmLocationsDataSourceImplTest {

    @Test
    fun storesLocationBundleInProvidedDirectory() = runTest {
        val dir = Files.createTempDirectory("turnbox-locations-test")
        val source = JvmLocationsDataSourceImpl(dir)
        val bundle = LocationBundleV4(
            activeLocationId = "desk",
            locations = listOf(
                LocationEntry.from(
                    "desk",
                    LocationConfig("Desktop", "room", "a".repeat(64), LocationConfig.PROVIDER_WB_STREAM)
                )
            )
        )

        source.saveLocationBundle(bundle)

        val loaded = source.loadLocationBundle()
        assertNotNull(loaded)
        assertEquals("desk", loaded.activeLocationId)
        assertEquals(LocationConfig.PROVIDER_WB_STREAM, loaded.locations.first().location.bypassProvider)
    }
}
