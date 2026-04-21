package app.remotex.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.remotex.model.Host
import app.remotex.net.RelayClient
import app.remotex.net.SocketEvent
import app.remotex.net.openSessionSocket
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val userToken: String = "demo-user-token",
    val hosts: List<Host> = emptyList(),
    val selectedHostId: String? = null,
    val loading: Boolean = false,
    val status: String = "idle",
    val lastSessionId: String? = null,
    val error: String? = null,
    val eventsLog: List<String> = emptyList(),
) {
    val canOpen: Boolean
        get() = hosts.firstOrNull { it.id == selectedHostId }?.online == true && status == "idle"
}

class RemotexViewModel(private val relayUrl: String) : ViewModel() {
    private val client = RelayClient(baseUrl = relayUrl)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var socketJob: Job? = null

    fun setToken(token: String) {
        _state.update { it.copy(userToken = token) }
    }

    fun selectHost(id: String) {
        _state.update { it.copy(selectedHostId = id) }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val hosts = client.listHosts(_state.value.userToken)
                _state.update { it.copy(hosts = hosts, loading = false) }
            } catch (t: Throwable) {
                _state.update { it.copy(loading = false, error = t.message ?: "refresh failed") }
            }
        }
    }

    fun openSession() {
        val target = _state.value.selectedHostId ?: return
        socketJob?.cancel()
        viewModelScope.launch {
            _state.update { it.copy(status = "opening", error = null, eventsLog = emptyList()) }
            val sid = try {
                client.openSession(_state.value.userToken, target)
            } catch (t: Throwable) {
                _state.update { it.copy(status = "idle", error = t.message ?: "open failed") }
                return@launch
            }
            _state.update { it.copy(lastSessionId = sid, status = "connecting") }
            socketJob = launch {
                openSessionSocket(
                    baseUrl = relayUrl,
                    userToken = _state.value.userToken,
                    sessionId = sid,
                ).collect { ev ->
                    when (ev) {
                        is SocketEvent.Frame -> appendLog(ev.text)
                        is SocketEvent.Closed -> _state.update { it.copy(status = "idle") }
                        is SocketEvent.Failure -> _state.update {
                            it.copy(status = "idle", error = ev.throwable.message ?: "socket error")
                        }
                    }
                }
            }
        }
    }

    private fun appendLog(line: String) {
        _state.update {
            val next = it.eventsLog + line
            val statusNext = if (it.status == "connecting") "connected" else it.status
            it.copy(eventsLog = next.takeLast(200), status = statusNext)
        }
    }

    companion object {
        fun factory(relayUrl: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RemotexViewModel(relayUrl) as T
            }
    }
}
