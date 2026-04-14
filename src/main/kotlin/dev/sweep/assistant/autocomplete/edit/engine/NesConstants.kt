package dev.sweep.assistant.autocomplete.edit.engine

/**
 * Constants for the Next Edit Suggestion (NES) engine.
 * Ported from Python sweep_autocomplete.
 */
object NesConstants {
    // Block extraction around cursor
    const val NUM_LINES_BEFORE = 2
    const val NUM_LINES_AFTER = 5

    // Token estimation
    const val CHARS_PER_TOKEN = 3.5

    // Prompt truncation limits
    val MAX_INPUT_TOKENS_COUNT = (8192 * 4) - 256   // ~8K tokens at 3.5 chars/token
    val CHARACTER_BOUND_TO_CHECK_TOKENIZATION = (8192 * 2) - 256
    val CHARACTER_BOUND_TO_SKIP_TOKENIZATION = (8192 * 4) * 2

    // Retrieval
    const val MAX_RETRIEVAL_CHUNK_SIZE_LINES = 25
    const val MAX_RETRIEVAL_CHUNKS = 3
    const val MAX_RETRIEVAL_TOKENS_COUNT = 2048

    // Generation
    const val AUTOCOMPLETE_OUTPUT_MAX_TOKENS = 1024

    // Line length limits
    const val AUTOCOMPLETE_TRUNCATION_LINE_LENGTH = 600
    const val AUTOCOMPLETE_MAXIMUM_LINE_LENGTH = 1000

    // User actions
    const val NUM_RECENT_ACTIONS_TO_PRESERVE = 20

    // Stop tokens
    val STOP_TOKENS = listOf("<|endoftext|>", "<|file_sep|>")

    // Prompt templates — must match Python exactly
    val PROMPT_TEMPLATE = """<|file_sep|>{file_path}
{initial_file}{retrieval_results}
{recent_changes}
<|file_sep|>original/{file_path}:{start_line}:{end_line}
{prev_section}
<|file_sep|>current/{file_path}:{start_line}:{end_line}
{code_block}
<|file_sep|>updated/{file_path}:{start_line}:{end_line}"""

    val DIFF_FORMAT = """<|file_sep|>{file_path}:{start_line}:{end_line}
original:
{old_code}
updated:
{new_code}"""

    // Qwen2 pretokenizer regex (used for prefill computation)
    // Source: https://github.com/huggingface/transformers/blob/main/src/transformers/models/qwen2/tokenization_qwen2.py
    const val PRETOKENIZE_REGEX =
        """(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\r\n\p{L}\p{N}]?\p{L}+|\p{N}| ?[^\s\p{L}\p{N}]+[\r\n]*|\s*[\r\n]+|\s+(?!\S)|\s+"""

    // Chunk size for getLinesAroundCursor
    const val CHUNK_SIZE = 300
    const val CHUNK_STRIDE = CHUNK_SIZE / 2
    const val LIMIT_TO_CHUNK = 800
}
