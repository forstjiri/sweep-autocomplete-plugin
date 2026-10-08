package dev.sweep.assistant.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure command-rendering tests for the llama-server launch pipeline
 * (flag matrix + bash/PowerShell rendering). No IDE services involved.
 */
class LlamaServerCommandTest {

    private val modernHelp =
        "--parallel --flash-attn [on|off|auto] --cache-reuse --spec-type --spec-ngram-mod-n-match -c"
    private val oldBooleanFaHelp = "--parallel --flash-attn --spec-ngram-size-n"
    private val ancientHelp = "-ngl --port"

    // --- flag matrix ---

    @Test
    fun `modern build gets parallel flash attention cache-reuse and ngram spec`() {
        val flags = LocalAutocompleteServerManager.buildLlamaServerFlags(modernHelp)
        assertTrue("--parallel" in flags && "1" in flags)
        assertTrue("-fa" in flags && flags.indexOf("-fa") + 1 < flags.size && flags[flags.indexOf("-fa") + 1] == "on")
        assertTrue("--cache-reuse" in flags)
        assertTrue("--spec-type" in flags)
    }

    @Test
    fun `flash attention mode from settings is respected`() {
        val flags = LocalAutocompleteServerManager.buildLlamaServerFlags(modernHelp, flashAttentionMode = "off")
        assertEquals("off", flags[flags.indexOf("-fa") + 1])
        val auto = LocalAutocompleteServerManager.buildLlamaServerFlags(modernHelp, flashAttentionMode = "auto")
        assertEquals("auto", auto[auto.indexOf("-fa") + 1])
    }

    @Test
    fun `off disables flash attention on boolean-fa builds`() {
        val off = LocalAutocompleteServerManager.buildLlamaServerFlags(oldBooleanFaHelp, flashAttentionMode = "off")
        assertFalse(off.contains("-fa"))
        val on = LocalAutocompleteServerManager.buildLlamaServerFlags(oldBooleanFaHelp, flashAttentionMode = "on")
        assertTrue(on.contains("-fa"))
        // Boolean form must not be followed by a mode value.
        assertFalse(on.getOrNull(on.indexOf("-fa") + 1) in listOf("on", "auto", "off"))
    }

    @Test
    fun `context size zero omits the flag and positive values pass it through`() {
        assertFalse(LocalAutocompleteServerManager.buildLlamaServerFlags(modernHelp, contextSize = 0).contains("-c"))
        val flags = LocalAutocompleteServerManager.buildLlamaServerFlags(modernHelp, contextSize = 8192)
        assertEquals("8192", flags[flags.indexOf("-c") + 1])
    }

    @Test
    fun `ancient build gets only safe flags`() {
        val flags = LocalAutocompleteServerManager.buildLlamaServerFlags(ancientHelp)
        assertEquals(listOf("-ngl", "999"), flags)
    }

    @Test
    fun `old tuning flag generation gets draft parameters`() {
        val flags = LocalAutocompleteServerManager.buildLlamaServerFlags(oldBooleanFaHelp)
        assertTrue("--spec-ngram-size-n" in flags && "--draft-min" in flags)
    }

    // --- model resolution rendering ---

    @Test
    fun `bash model resolution uses find and head`() {
        val cmd = LocalAutocompleteServerManager.bashModelResolutionCommand("sweepai--model", "m.gguf", "/cache/m.gguf")
        assertTrue("find ~/.cache/huggingface/hub/models--sweepai--model" in cmd)
        assertTrue("\$MODEL_PATH" in cmd)
    }

    @Test
    fun `windows model resolution uses powershell and no bash-isms`() {
        val cmd = LocalAutocompleteServerManager.windowsModelResolutionCommand("sweepai--model", "m.gguf", "C:\\cache\\m.gguf")
        assertTrue("Get-ChildItem" in cmd && "-Recurse -Filter 'm.gguf'" in cmd)
        assertFalse("find " in cmd || "head -1" in cmd)
        assertFalse("&&" in cmd)
    }

    // --- model download rendering ---

    @Test
    fun `windows model download prefers hf and falls back to curl exe`() {
        val cmd = LocalAutocompleteServerManager.windowsModelDownloadCommand("sweepai/model", "m.gguf", "C:\\models")
        assertTrue(cmd.startsWith("if (Get-Command hf"))
        assertTrue("curl.exe -L -o 'C:\\models\\m.gguf'" in cmd)
        assertFalse(" && " in cmd && !cmd.contains("Write-Host"))
        assertFalse("mkdir" in cmd)
    }

    // --- llama-server download rendering ---

    @Test
    fun `windows llama download downloads the vulkan zip and extracts it`() {
        val cmd = LocalAutocompleteServerManager.windowsLlamaServerDownloadCommand("C:\\sweep\\llama.cpp", isArm = false)
        assertTrue("bin-win-vulkan-x64\\.zip" in cmd)
        assertTrue("Invoke-RestMethod" in cmd && "Expand-Archive" in cmd && "curl.exe" in cmd)
        assertFalse("| tar " in cmd)
    }

    @Test
    fun `windows arm uses the arm64 asset`() {
        val cmd = LocalAutocompleteServerManager.windowsLlamaServerDownloadCommand("dir", isArm = true)
        assertTrue("bin-win-vulkan-arm64\\.zip" in cmd)
    }
}
