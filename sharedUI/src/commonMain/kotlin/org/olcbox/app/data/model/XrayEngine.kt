package org.olcbox.app.data.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class XrayConfig(
    val vlessLink: String,
    val listenPort: Int = 1081,
    val dnsServer: String? = null,
    val networkHandle: Long? = null,
    val onLogEntry: ((String) -> Unit)? = null,
    val socksUsername: String = "",
    val socksPassword: String = ""
)

interface XrayEngine {
    val state: StateFlow<XrayEngineState>

    suspend fun startXray(config: XrayConfig): Result<Unit>
    suspend fun stopXray(): Result<Unit>
    val isRunning: Boolean

    companion object {
        const val STATE_IDLE = "idle"
        const val STATE_STARTING = "starting"
        const val STATE_RUNNING = "running"
        const val STATE_STOPPING = "stopping"
        const val STATE_ERROR = "error"
    }
}

data class XrayEngineState(
    val status: String = XrayEngine.STATE_IDLE,
    val error: String? = null
)

class StubXrayEngine : XrayEngine {
    private val _state = MutableStateFlow(XrayEngineState())
    override val state: StateFlow<XrayEngineState> = _state.asStateFlow()
    override val isRunning: Boolean = false

    override suspend fun startXray(config: XrayConfig): Result<Unit> {
        return Result.failure(UnsupportedOperationException("xray not available on this platform"))
    }

    override suspend fun stopXray(): Result<Unit> {
        return Result.success(Unit)
    }
}
