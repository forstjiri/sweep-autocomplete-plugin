package dev.sweep.assistant.autocomplete.edit.engine

/**
 * Available NES model configurations for llama-server.
 */
data class NesModel(
    val id: String,
    val displayName: String,
    val repo: String,
    val filename: String,
    val description: String,
)

object NesModelConfig {
    val MODELS = listOf(
        NesModel(
            id = "sweep-0.5B",
            displayName = "Sweep 0.5B (Q8, fastest)",
            repo = "sweepai/sweep-next-edit-0.5B",
            filename = "sweep-next-edit-0.5b.q8_0.gguf",
            description = "Smallest and fastest model, good for most edits",
        ),
        NesModel(
            id = "sweep-1.5B",
            displayName = "Sweep 1.5B (Q8)",
            repo = "sweepai/sweep-next-edit-1.5B",
            filename = "sweep-next-edit-1.5b.q8_0.v2.gguf",
            description = "Better quality, slightly slower",
        ),
        NesModel(
            id = "sweep-7B-q5",
            displayName = "Sweep 7B v2 (Q5_K_M)",
            repo = "henrik3/sweep-next-edit-v2-7B-GGUF",
            filename = "q5_k_m.gguf",
            description = "Highest quality, requires more RAM",
        ),
        NesModel(
            id = "sweep-7B-q4",
            displayName = "Sweep 7B v2 (Q4_K_M)",
            repo = "henrik3/sweep-next-edit-v2-7B-GGUF",
            filename = "q4_k_m.gguf",
            description = "High quality, less RAM than Q5",
        ),
    )

    val DEFAULT_MODEL_ID = "sweep-0.5B"

    fun getModel(id: String): NesModel = MODELS.find { it.id == id } ?: MODELS.first()
}
