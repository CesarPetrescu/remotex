package app.remotex.model

/**
 * Typed view of one row in the Codex event stream.
 *
 * The relay's `session-event` JSON gets translated into one of these
 * shapes by [RemotexViewModel.handleFrame] before the UI sees it.
 * Keeping the conversion at the boundary means the Compose layer
 * never touches raw JSON.
 */
sealed class UiEvent {
    abstract val id: String

    data class User(override val id: String, val text: String) : UiEvent()

    data class Reasoning(
        override val id: String,
        val text: String,
        val completed: Boolean,
    ) : UiEvent()

    data class Tool(
        override val id: String,
        val tool: String,
        val command: String,
        val output: String,
        val completed: Boolean,
    ) : UiEvent()

    data class Agent(
        override val id: String,
        val text: String,
        val completed: Boolean,
    ) : UiEvent()

    data class System(override val id: String, val label: String, val detail: String) : UiEvent()
}
