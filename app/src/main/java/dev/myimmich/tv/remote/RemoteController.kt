package dev.myimmich.tv.remote

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom

class RemoteController {

    val commands: SharedFlow<RemoteCommand> get() = _commands
    val viewerState: StateFlow<RemoteViewerState?> get() = _viewerState
    val setupState: StateFlow<SetupState> get() = _setupState

    private val _commands = MutableSharedFlow<RemoteCommand>(extraBufferCapacity = 32)
    private val _viewerState = MutableStateFlow<RemoteViewerState?>(null)
    private val _setupState = MutableStateFlow(SetupState())

    private val sessions = HashMap<String, Long>()
    private val random = SecureRandom()

    @Volatile
    var pin: String = generatePin()
        private set

    @Volatile
    var serverPort: Int = 8321

    fun generatePin(): String {
        val n = random.nextInt(1000000)
        pin = "%06d".format(n)
        return pin
    }

    fun regeneratePin() {
        generatePin()
    }

    fun checkPin(submitted: String?): Boolean {
        return submitted != null && submitted.trim() == pin
    }

    @Synchronized
    fun tryPair(submitted: String): String? {
        if (checkPin(submitted)) {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            val token = bytes.joinToString("") { "%02x".format(it) }
            sessions[token] = System.currentTimeMillis()
            return token
        }
        return null
    }

    @Synchronized
    fun isAuthorized(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        return sessions.containsKey(token)
    }

    @Synchronized
    fun revokeAll() {
        sessions.clear()
    }

    fun updateSetup(state: SetupState) {
        _setupState.value = state
    }

    fun send(command: RemoteCommand) {
        _commands.tryEmit(command)
    }

    fun publish(state: RemoteViewerState) {
        _viewerState.value = state
    }
}
