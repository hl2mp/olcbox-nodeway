package org.olcbox.app.ui.features.locations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.VlessConfig
import org.olcbox.app.data.repository.LocationsRepository

data class LocationItem(
    val storageId: String,
    val fullName: String,
    val config: LocationConfig? = null,
    val vless: VlessConfig? = null,
    val isVless: Boolean = vless != null,
    val subscriptionUrl: String? = null,
    val metadata: LocationMetadata? = null
)

sealed class PingsState {
    object Idle : PingsState()

    data class Loading(
        val lastPings: Map<String, Int?>? = null,
        val currentPings: Map<String, Int?> = emptyMap(),
        val pendingLocationIds: Set<String> = emptySet(),
        val completed: Int = 0,
        val total: Int = 0
    ) : PingsState()

    data class Success(
        val pings: Map<String, Int?>
    ) : PingsState()

    data class Error(
        val message: String,
        val lastPings: Map<String, Int?>? = null
    ) : PingsState()
}

class LocationViewModel(
    private val locationsRepository: LocationsRepository,
) : ViewModel() {

    var locations = mutableStateListOf<LocationItem>()
        private set

    var selectedLocationId by mutableStateOf<String?>(null)
        private set

    var pingsState by mutableStateOf<PingsState>(PingsState.Idle)
        private set

    private val activePingJobs = mutableMapOf<String, Job>()
    private val pingSemaphore = Semaphore(LOCATION_PING_PARALLELISM)
    private var loadLocationsJob: Job? = null
    private var loadLocationsRequest = 0
    private val providerDrafts = mutableMapOf<String, ProviderDraft>()

    var editingConfig by mutableStateOf(LocationConfig())
    var editingName by mutableStateOf("")
    var editingId by mutableStateOf<String?>(null)
    var editingServiceProvider by mutableStateOf(LocationConfig.DEFAULT_BYPASS_PROVIDER)
        private set

    var isSaving by mutableStateOf(false)
        private set

    var nameError by mutableStateOf<String?>(null)
        private set

    var serverError by mutableStateOf<String?>(null)
        private set

    var keyError by mutableStateOf<String?>(null)
        private set

    var dnsError by mutableStateOf<String?>(null)
        private set

    var editingVless by mutableStateOf(VlessConfig())
        private set

    var isVlessEdit by mutableStateOf(false)
        private set

    var vlessServerError by mutableStateOf<String?>(null)
    var vlessPortError by mutableStateOf<String?>(null)
    var vlessUuidError by mutableStateOf<String?>(null)
    var vlessTlsError by mutableStateOf<String?>(null)

    val isFormValid: Boolean
        get() = nameError == null &&
                serverError == null &&
                keyError == null &&
                dnsError == null &&
                editingName.isNotBlank() &&
                editingConfig.id.isNotBlank() &&
                editingConfig.key.isNotBlank()

    val isVlessFormValid: Boolean
        get() = vlessServerError == null &&
                vlessPortError == null &&
                vlessUuidError == null &&
                vlessTlsError == null &&
                (editingName.isNotBlank() || editingVless.server.isNotBlank()) &&
                editingVless.server.isNotBlank() &&
                editingVless.port in 1..65535 &&
                VlessConfig.isValidUuid(editingVless.uuid)

    init {
        loadLocations()
        viewModelScope.launch {
            locationsRepository.changes
                .drop(1)
                .collect {
                    loadLocations()
                }
        }
    }

    fun loadLocations(onComplete: () -> Unit = {}) {
        val requestId = ++loadLocationsRequest
        loadLocationsJob?.cancel()
        loadLocationsJob = viewModelScope.launch {
            val bundle = locationsRepository.getBundle()
            val savedConfigs = bundle.locations
            val currentSelectedId = bundle.activeLocationId

            val nextLocations = savedConfigs.map { entry ->
                val normalized = entry.location
                LocationItem(
                    storageId = entry.storageId,
                    fullName = entry.isVless
                        .takeIf { it }?.let { entry.vless?.normalized()?.displayName() }
                        ?: normalized.displayName(),
                    config = normalized,
                    vless = entry.vless?.normalized(),
                    isVless = entry.isVless,
                    subscriptionUrl = entry.subscriptionUrl,
                    metadata = entry.metadata
                )
            }

            if (requestId != loadLocationsRequest) return@launch

            locations.clear()
            locations.addAll(nextLocations)

            val nextSelectedId = if (
                nextLocations.isNotEmpty() &&
                (
                        currentSelectedId.isNullOrBlank() ||
                                nextLocations.none { it.storageId == currentSelectedId }
                        )
            ) {
                nextLocations.firstOrNull()?.storageId
            } else {
                currentSelectedId
            }
            if (
                nextSelectedId != currentSelectedId &&
                nextLocations.any { it.storageId == nextSelectedId }
            ) {
                locationsRepository.setActiveLocationId(nextSelectedId)
            }

            if (requestId != loadLocationsRequest) return@launch

            selectedLocationId = nextSelectedId
            onComplete()
        }
    }

    fun selectLocation(id: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            locationsRepository.setActiveLocationId(id)
            onComplete()
        }
    }

    fun refreshPings(
        targetLocationIds: List<String>? = null,
        performPing: suspend (LocationConfig) -> Long?,
        performPingVless: suspend (VlessConfig, String, String) -> Long? = { _, _, _ -> null },
        socksUsername: String = "",
        socksPassword: String = "",
        onComplete: (onlineCount: Int, totalCount: Int) -> Unit = { _, _ -> Unit },
        onError: (String) -> Unit = {}
    ) {
        val previousPings = currentPingsSnapshot()
        val locationsSnapshot = locations.toList()

        val pingableLocations = locationsSnapshot
            .filter { location ->
                val isPingable = if (location.isVless) {
                    location.vless?.isComplete() == true
                } else {
                    location.config?.isComplete() == true
                }
                isPingable &&
                (targetLocationIds == null || targetLocationIds.contains(location.storageId))
            }
            .filterNot { location ->
                activePingJobs.containsKey(location.storageId)
            }

        if (locationsSnapshot.isEmpty()) {
            if (activePingJobs.isEmpty()) {
                pingsState = PingsState.Success(emptyMap())
            }
            onComplete(0, 0)
            return
        }

        if (pingableLocations.isEmpty()) {
            emitPingState(previousPings)
            onComplete(0, 0)
            return
        }

        var completedForThisRequest = 0
        var onlineForThisRequest = 0
        val totalForThisRequest = pingableLocations.size
        val jobsToStart = mutableListOf<Job>()

        pingableLocations.forEach { location ->
            val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val ping = try {
                        pingSemaphore.withPermit {
                            if (location.isVless) {
                                checkLocationPingVless(
                                    location = location,
                                    performPingVless = performPingVless,
                                    socksUsername = socksUsername,
                                    socksPassword = socksPassword
                                )
                            } else {
                                checkLocationPing(location, performPing)
                            }
                        }?.toInt()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }

                    val updatedPings = currentPingsSnapshot().toMutableMap()
                    updatedPings[location.storageId] = ping

                    activePingJobs.remove(location.storageId)

                    if (ping != null) {
                        onlineForThisRequest++
                    }

                    completedForThisRequest++

                    emitPingState(updatedPings.toMap())

                    if (completedForThisRequest == totalForThisRequest) {
                        onComplete(onlineForThisRequest, totalForThisRequest)
                    }
                } catch (e: CancellationException) {
                    activePingJobs.remove(location.storageId)
                    emitPingState()
                    throw e
                } catch (e: Exception) {
                    activePingJobs.remove(location.storageId)

                    val message = e.message ?: "HTTP ping failed"
                    onError(message)

                    emitPingState()
                }
            }

            activePingJobs[location.storageId] = job
            jobsToStart.add(job)
        }

        emitPingState(previousPings)
        jobsToStart.forEach { it.start() }
    }

    private fun currentPingsSnapshot(): Map<String, Int?> {
        return when (val state = pingsState) {
            PingsState.Idle -> emptyMap()

            is PingsState.Loading -> {
                state.currentPings.ifEmpty {
                    state.lastPings.orEmpty()
                }
            }

            is PingsState.Success -> {
                state.pings
            }

            is PingsState.Error -> {
                state.lastPings.orEmpty()
            }
        }
    }

    private fun emitPingState(
        pings: Map<String, Int?> = currentPingsSnapshot()
    ) {
        val pendingIds = activePingJobs.keys.toSet()

        pingsState = if (pendingIds.isEmpty()) {
            PingsState.Success(pings)
        } else {
            PingsState.Loading(
                lastPings = pings,
                currentPings = pings,
                pendingLocationIds = pendingIds,
                completed = 0,
                total = pendingIds.size
            )
        }
    }

    private suspend fun checkLocationPing(
        location: LocationItem,
        performPing: suspend (LocationConfig) -> Long?
    ): Long? {
        val config = location.config?.takeIf { it.isComplete() } ?: return null

        return withTimeoutOrNull(LOCATION_PING_TIMEOUT_MS) {
            repeat(LOCATION_PING_ATTEMPTS) { attempt ->
                val result = try {
                    performPing(config)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }

                if (result != null) {
                    return@withTimeoutOrNull result
                }

                if (attempt < LOCATION_PING_ATTEMPTS - 1) {
                    delay(LOCATION_PING_RETRY_DELAY_MS)
                }
            }

            null
        }
    }

    private suspend fun checkLocationPingVless(
        location: LocationItem,
        performPingVless: suspend (VlessConfig, String, String) -> Long?,
        socksUsername: String,
        socksPassword: String
    ): Long? {
        val config = location.vless ?: return null

        return withTimeoutOrNull(LOCATION_PING_TIMEOUT_MS) {
            repeat(LOCATION_PING_ATTEMPTS) { attempt ->
                val result = try {
                    performPingVless(config, socksUsername, socksPassword)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }

                if (result != null) {
                    return@withTimeoutOrNull result
                }

                if (attempt < LOCATION_PING_ATTEMPTS - 1) {
                    delay(LOCATION_PING_RETRY_DELAY_MS)
                }
            }

            null
        }
    }

    fun startEditing(id: String?) {
        nameError = null
        serverError = null
        keyError = null
        dnsError = null
        vlessServerError = null
        vlessPortError = null
        vlessUuidError = null
        vlessTlsError = null
        isSaving = false
        providerDrafts.clear()

        val location = id?.let { locations.find { entry -> entry.storageId == id } }

        if (location != null && location.isVless) {
            isVlessEdit = true
            editingVless = location.vless?.normalized() ?: VlessConfig()
            editingName = editingVless.name.takeIf { it.isNotBlank() } ?: editingVless.displayName()
            editingConfig = LocationConfig()
            editingId = id
            editingServiceProvider = LocationConfig.DEFAULT_BYPASS_PROVIDER
        } else {
            isVlessEdit = false
            editingVless = VlessConfig()
            editingId = id
            editingConfig = location?.config?.normalized() ?: LocationConfig()
            editingName = editingConfig.displayName()
            val provider = LocationConfig.normalizeProvider(editingConfig.bypassProvider)
            editingServiceProvider = if (provider == LocationConfig.PROVIDER_JITSI) {
                LocationConfig.DEFAULT_BYPASS_PROVIDER
            } else {
                provider
            }
            providerDrafts[provider] = ProviderDraft(
                room = editingConfig.id,
                key = editingConfig.key
            )
        }
    }

    fun switchToVlessEdit() {
        isVlessEdit = true
        if (editingName.isBlank() && editingVless.server.isNotBlank()) {
            editingName = editingVless.displayName()
        }
        vlessServerError = null
        vlessPortError = null
        vlessUuidError = null
        validateVlessTls(editingVless)
    }

    fun switchToOlcrtcEdit() {
        isVlessEdit = false
        nameError = null
        serverError = null
        keyError = null
        dnsError = null
    }

    fun onVlessNameChanged(value: String) {
        editingVless = editingVless.copy(name = value)
    }

    fun onVlessServerChanged(value: String) {
        editingVless = editingVless.copy(server = value)
        validateVlessServer(value)
    }

    fun onVlessPortChanged(value: String) {
        val digits = value.filter { it.isDigit() }
        val parsed = digits.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        editingVless = editingVless.copy(port = parsed)
        validateVlessPort(digits)
    }

    fun onVlessUuidChanged(value: String) {
        editingVless = editingVless.copy(uuid = value)
        validateVlessUuid(value)
    }

    fun onVlessNetworkChanged(value: String) {
        editingVless = editingVless.copy(network = VlessConfig.normalizeNetwork(value))
    }

    fun onVlessSecurityChanged(value: String) {
        editingVless = editingVless.copy(security = VlessConfig.normalizeSecurity(value))
        validateVlessTls(editingVless)
    }

    fun onVlessSniChanged(value: String) {
        editingVless = editingVless.copy(sni = value.takeIf { it.isNotBlank() })
        validateVlessTls(editingVless)
    }

    fun onVlessHostChanged(value: String) {
        editingVless = editingVless.copy(host = value.takeIf { it.isNotBlank() })
    }

    fun onVlessPathChanged(value: String) {
        editingVless = editingVless.copy(path = value.takeIf { it.isNotBlank() })
    }

    fun onVlessGrpcServiceChanged(value: String) {
        editingVless = editingVless.copy(grpcServiceName = value.takeIf { it.isNotBlank() })
    }

    fun onVlessAllowInsecureChanged(value: Boolean) {
        editingVless = editingVless.copy(allowInsecure = value)
    }

    fun onVlessFlowChanged(value: String) {
        editingVless = editingVless.copy(flow = value.takeIf { it.isNotBlank() })
    }

    fun onVlessFingerprintChanged(value: String) {
        editingVless = editingVless.copy(fingerprint = value.takeIf { it.isNotBlank() })
    }

    fun onNameChanged(value: String) {
        editingName = value
        validateName(value)
    }

    fun onServerChanged(value: String) {
        editingConfig = editingConfig.copy(id = value)
        validateServer(value)
    }

    fun onPasswordChanged(value: String) {
        editingConfig = editingConfig.copy(key = value)
        validateKey(value)
    }

    fun onBypassProviderChanged(value: String) {
        val provider = LocationConfig.normalizeProvider(value)
        val currentProvider = LocationConfig.normalizeProvider(editingConfig.bypassProvider)
        if (provider == currentProvider) return

        providerDrafts[currentProvider] = ProviderDraft(
            room = editingConfig.id,
            key = editingConfig.key
        )

        if (provider != LocationConfig.PROVIDER_JITSI) {
            editingServiceProvider = provider
        }

        val restored = providerDrafts[provider] ?: ProviderDraft()

        editingConfig = editingConfig.copy(
            bypassProvider = provider,
            transport = if (provider == LocationConfig.PROVIDER_JITSI) {
                LocationConfig.TRANSPORT_DATACHANNEL
            } else {
                LocationConfig.normalizeTransport(editingConfig.transport, provider)
            },
            id = restored.room,
            key = restored.key
        )
        serverError = null
        keyError = null
    }

    fun onTransportChanged(value: String) {
        editingConfig = editingConfig.copy(
            transport = LocationConfig.normalizeTransport(value, editingConfig.bypassProvider)
        )
    }

    fun onVp8FpsChanged(value: String) {
        editingConfig = editingConfig.copy(
            vp8Fps = value.filter { it.isDigit() }.toIntOrNull() ?: 0
        )
    }

    fun onVp8BatchChanged(value: String) {
        editingConfig = editingConfig.copy(
            vp8Batch = value.filter { it.isDigit() }.toIntOrNull() ?: 0
        )
    }

    fun onDnsServerChanged(value: String) {
        editingConfig = editingConfig.copy(
            dnsServer = value
                .replace("\r", "")
                .replace("\n", "")
                .take(LocationConfig.MAX_DNS_SERVER_LENGTH)
        )
        validateDnsServer(editingConfig.dnsServer)
    }

    private fun validateName(name: String) {
        nameError = when {
            name.isBlank() -> "Name cannot be empty"
            name.length > 30 -> "Name is too long (max 30 chars)"
            else -> null
        }
    }

    private fun validateServer(server: String) {
        val roomLabel = if (editingConfig.bypassProvider == LocationConfig.PROVIDER_JITSI) {
            "Room URL"
        } else {
            "Room ID"
        }
        serverError = when {
            server.isBlank() -> "$roomLabel cannot be empty"
            server.length > 256 -> "$roomLabel is too long"
            else -> null
        }
    }

    private fun validateKey(key: String) {
        keyError = when {
            key.isBlank() -> "Key cannot be empty"
            !key.matches(Regex("^[a-fA-F0-9]{64}$")) -> "Key must be 64 hex characters"
            else -> null
        }
    }

    private fun validateDnsServer(dnsServer: String) {
        dnsError = if (LocationConfig.isValidDnsServer(dnsServer)) {
            null
        } else {
            "Use host:port or [IPv6]:port; leave empty for Auto"
        }
    }

    private fun validateVlessServer(server: String) {
        vlessServerError = when {
            server.isBlank() -> "Server cannot be empty"
            server.length > 256 -> "Server is too long"
            else -> null
        }
    }

    private fun validateVlessPort(raw: String) {
        vlessPortError = when {
            raw.isBlank() -> "Port cannot be empty"
            raw.toIntOrNull() !in 1..65535 -> "Port must be 1–65535"
            else -> null
        }
    }

    private fun validateVlessUuid(uuid: String) {
        vlessUuidError = when {
            uuid.isBlank() -> "UUID cannot be empty"
            !VlessConfig.isValidUuid(uuid) -> "Enter a valid UUID (xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx)"
            else -> null
        }
    }

    private fun validateVlessTls(config: VlessConfig) {
        vlessTlsError = when {
            config.security == VlessConfig.SECURITY_NONE -> null
            config.sni != null || config.host != null -> null
            else -> "Add SNI or Host for TLS"
        }
    }

    fun saveEditing(onComplete: () -> Unit) {
        if (isVlessEdit) {
            saveVlessEditing(onComplete)
            return
        }

        validateName(editingName)
        validateServer(editingConfig.id)
        validateKey(editingConfig.key)
        validateDnsServer(editingConfig.dnsServer)

        if (!isFormValid || isSaving) return

        viewModelScope.launch {
            isSaving = true
            try {
                val id = editingId ?: "custom_${(100..999).random()}"
                val finalConfig = editingConfig.copy(name = editingName).normalized()

                locationsRepository.saveLocation(id, finalConfig)
                locationsRepository.setActiveLocationId(id)

                loadLocations()

                delay(600)

                onComplete()
            } finally {
                isSaving = false
            }
        }
    }

    private fun saveVlessEditing(onComplete: () -> Unit) {
        validateName(editingName)
        validateVlessServer(editingVless.server)
        validateVlessPort(editingVless.port.toString())
        validateVlessUuid(editingVless.uuid)
        validateVlessTls(editingVless)

        if (!isVlessFormValid || isSaving) {
            onComplete()
            return
        }

        viewModelScope.launch {
            isSaving = true
            try {
                val id = editingId ?: "vless_${(100..999).random()}"
                val name = if (editingName.isNotBlank()) editingName else editingVless.displayName()
                val finalConfig = editingVless.copy(name = name).normalized()

                locationsRepository.saveVlessLocation(id, finalConfig)
                locationsRepository.setActiveLocationId(id)

                loadLocations()

                delay(600)

                onComplete()
            } finally {
                isSaving = false
            }
        }
    }

    fun deleteLocation(id: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            locationsRepository.deleteLocation(id)
            loadLocations(onComplete)
        }
    }

    private companion object {
        const val LOCATION_PING_ATTEMPTS = 2
        const val LOCATION_PING_TIMEOUT_MS = 25_000L
        const val LOCATION_PING_RETRY_DELAY_MS = 500L
        // Finish each location check before starting the next one.
        const val LOCATION_PING_PARALLELISM = 1
    }

    private data class ProviderDraft(
        val room: String = "",
        val key: String = ""
    )
}
