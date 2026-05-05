package org.olcbox.app.data.datasource

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.repository.LocationsRepository

interface LocationsDataSource {
    suspend fun loadLocationBundle(): LocationBundleV4?
    suspend fun saveLocationBundle(bundle: LocationBundleV4)
    suspend fun loadLegacyLocations(): List<Pair<String, String>>
    suspend fun loadLegacyActiveLocationId(): String?
}

class LocationsRepositoryImpl(
    private val dataSource: LocationsDataSource
) : LocationsRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    override suspend fun getBundle(): LocationBundleV4 {
        val stored = dataSource.loadLocationBundle()?.normalized()
        if (stored != null && stored.locations.isNotEmpty()) return stored

        val legacy = migrateLegacyBundle()
        if (legacy.locations.isNotEmpty()) {
            dataSource.saveLocationBundle(legacy)
        }
        return legacy
    }

    override suspend fun saveBundle(bundle: LocationBundleV4) {
        dataSource.saveLocationBundle(bundle.normalized())
    }

    override suspend fun exportBundle(): String {
        return json.encodeToString(LocationBundleV4.serializer(), getBundle())
    }

    override suspend fun importText(text: String) {
        val parsed = parseImport(text.trim()) ?: return
        dataSource.saveLocationBundle(parsed.normalized())
    }

    override suspend fun saveLocation(storageId: String, location: LocationConfig) {
        val entry = LocationEntry.from(storageId.ifBlank { location.storageSlug() }, location)
        val bundle = getBundle()
        val locations = bundle.locations
            .filterNot { it.storageId == entry.storageId } + entry
        dataSource.saveLocationBundle(
            bundle.copy(
                activeLocationId = entry.storageId,
                locations = locations
            ).normalized()
        )
    }

    override suspend fun loadLocation(storageId: String): LocationConfig? {
        return getBundle().locations.firstOrNull { it.storageId == storageId }?.location
    }

    override suspend fun deleteLocation(storageId: String) {
        val bundle = getBundle()
        dataSource.saveLocationBundle(
            bundle.copy(
                activeLocationId = bundle.activeLocationId?.takeUnless { it == storageId },
                locations = bundle.locations.filterNot { it.storageId == storageId }
            ).normalized()
        )
    }

    override suspend fun getAllLocations(): List<LocationEntry> {
        return getBundle().locations
    }

    override suspend fun getActiveLocationId(): String? {
        return getBundle().activeLocationId
    }

    override suspend fun setActiveLocationId(storageId: String?) {
        val bundle = getBundle()
        val nextActive = storageId?.takeIf { id -> bundle.locations.any { it.storageId == id } }
        dataSource.saveLocationBundle(bundle.copy(activeLocationId = nextActive).normalized())
    }

    override suspend fun getActiveLocation(): LocationEntry? {
        val bundle = getBundle()
        return bundle.locations.firstOrNull { it.storageId == bundle.activeLocationId }
    }

    private suspend fun migrateLegacyBundle(): LocationBundleV4 {
        val legacy = dataSource.loadLegacyLocations().mapNotNull { (storageId, text) ->
            parseSingleLocation(text, storageId)
        }
        val active = dataSource.loadLegacyActiveLocationId()?.takeIf { id ->
            legacy.any { it.storageId == id }
        }
        return LocationBundleV4(
            activeLocationId = active,
            locations = legacy
        ).normalized()
    }

    private fun parseImport(text: String): LocationBundleV4? {
        if (!text.startsWith("{") || !text.endsWith("}")) return null
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        parseBundle(root)?.let { return it }
        return parseSingleLocation(root, null)?.let {
            LocationBundleV4(
                activeLocationId = it.storageId,
                locations = listOf(it)
            )
        }
    }

    private fun parseBundle(root: JsonObject): LocationBundleV4? {
        val locationsElement = root["locations"] ?: return null
        val locations = runCatching { locationsElement.jsonArray }.getOrNull()?.mapNotNull { element ->
            val item = element.jsonObjectOrNull() ?: return@mapNotNull null
            decodeLocationEntry(item)?.let { return@mapNotNull it }
            val storageId = item.string("storage_id")
                ?: item.string("storageId")
                ?: item.string("id")?.let { "imported_${it.storageSlug()}" }
            parseSingleLocation(item, storageId)
        } ?: return null

        val version = root["version"]?.jsonPrimitive?.intOrNull ?: 3
        if (version < 3 && locations.isEmpty()) return null

        return LocationBundleV4(
            activeLocationId = root.string("active_location_id") ?: root.string("activeLocationId"),
            locations = locations
        )
    }

    private fun parseSingleLocation(text: String, fallbackStorageId: String?): LocationEntry? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        parseBundle(root)?.let { bundle ->
            return bundle.normalized().locations.firstOrNull()
        }
        return parseSingleLocation(root, fallbackStorageId)
    }

    private fun parseSingleLocation(root: JsonObject, fallbackStorageId: String?): LocationEntry? {
        decodeLocationEntry(root)?.let { return it }

        val source = root["location"]?.jsonObjectOrNull()
            ?: root["hysteria"]?.jsonObjectOrNull()
            ?: root

        val provider = firstNotBlank(
            source.string("bypass_provider"),
            source.string("bypassProvider"),
            source.string("provider"),
            root["turn"]?.jsonObjectOrNull()?.string("type"),
            root.string("bypass_provider"),
            root.string("bypassProvider"),
            root.string("provider")
        )
        val transportArgs = firstNotBlank(
            source.string("transport_args"),
            source.string("transportArgs"),
            source.string("args"),
            root.string("transport_args"),
            root.string("transportArgs"),
            root.string("args")
        )
        val vp8Fps = firstInt(
            source.int("vp8_fps"),
            source.int("vp8Fps"),
            root.int("vp8_fps"),
            root.int("vp8Fps"),
            transportArgInt(transportArgs, "-vp8-fps")
        ) ?: LocationConfig.DEFAULT_VP8_FPS
        val vp8Batch = firstInt(
            source.int("vp8_batch"),
            source.int("vp8Batch"),
            root.int("vp8_batch"),
            root.int("vp8Batch"),
            transportArgInt(transportArgs, "-vp8-batch")
        ) ?: LocationConfig.DEFAULT_VP8_BATCH

        val location = LocationConfig(
            name = firstNotBlank(source.string("name"), root.string("name")),
            id = firstNotBlank(
                source.string("id"),
                source.string("room_id"),
                source.string("server"),
                root.string("id")
            ),
            key = firstNotBlank(
                source.string("key"),
                source.string("encryption_key"),
                source.string("password"),
                root.string("key")
            ),
            bypassProvider = provider,
            transport = firstNotBlank(
                source.string("transport"),
                root.string("transport"),
                if (transportArgs.isNotBlank()) LocationConfig.TRANSPORT_VP8CHANNEL else null
            ),
            vp8Fps = vp8Fps,
            vp8Batch = vp8Batch
        ).normalized()

        if (!location.isComplete()) return null

        val storageId = firstNotBlank(
            fallbackStorageId,
            root.string("storage_id"),
            root.string("storageId"),
            source.string("storage_id"),
            source.string("storageId"),
            "imported_${location.storageSlug()}"
        )
        return LocationEntry.from(storageId, location)
    }

    private fun decodeLocationEntry(root: JsonObject): LocationEntry? {
        return runCatching {
            json.decodeFromJsonElement(LocationEntry.serializer(), root)
                .normalized()
                .takeIf { it.location.isComplete() }
        }.getOrNull()
    }

    private fun LocationConfig.storageSlug(): String {
        return displayName().ifBlank { id }.storageSlug()
    }

    private fun String.storageSlug(): String {
        return lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "_")
            .trim('_')
            .take(32)
            .ifBlank { "location" }
    }

    private fun JsonObject.string(name: String): String? {
        return (this[name] as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.int(name: String): Int? {
        return (this[name] as? JsonPrimitive)?.intOrNull
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? {
        return runCatching { jsonObject }.getOrNull()
    }

    private fun firstNotBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() } ?: ""
    }

    private fun firstInt(vararg values: Int?): Int? {
        return values.firstOrNull { it != null }
    }

    private fun transportArgInt(args: String, name: String): Int? {
        if (args.isBlank()) return null
        val parts = args.split(Regex("\\s+")).filter { it.isNotBlank() }
        val index = parts.indexOf(name)
        return parts.getOrNull(index + 1)?.toIntOrNull()
    }
}
