package dev.sweep.assistant.autocomplete.edit.engine

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tests for NesUtils, comparing outputs against Python-generated fixtures
 * to ensure parity between the Kotlin and Python implementations.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NesUtilsTest {

    private lateinit var fixtures: JsonObject
    private val gson = Gson()

    @BeforeAll
    fun loadFixtures() {
        val json = this::class.java.classLoader
            .getResourceAsStream("nes_fixtures/utils_fixtures.json")!!
            .bufferedReader().readText()
        fixtures = gson.fromJson(json, JsonObject::class.java)
    }

    // --- extract_diff_parts ---

    @Test
    fun `extractDiffParts no context matches Python`() {
        val fixture = fixtures["extract_diff_parts_no_context"].asJsonObject
        val hunk = fixture["input"].asJsonObject["hunk"].asString
        val expected = fixture["output"].asJsonArray.map { it.asString }

        val (oldCode, newCode) = NesUtils.extractDiffParts(hunk, 0)

        assertEquals(expected[0], oldCode)
        assertEquals(expected[1], newCode)
    }

    @Test
    fun `extractDiffParts one context line matches Python`() {
        val fixture = fixtures["extract_diff_parts_one_context"].asJsonObject
        val hunk = fixture["input"].asJsonObject["hunk"].asString
        val expected = fixture["output"].asJsonArray.map { it.asString }

        val (oldCode, newCode) = NesUtils.extractDiffParts(hunk, 1)

        assertEquals(expected[0], oldCode)
        assertEquals(expected[1], newCode)
    }

    @Test
    fun `extractDiffParts all lines matches Python`() {
        val fixture = fixtures["extract_diff_parts_all"].asJsonObject
        val hunk = fixture["input"].asJsonObject["hunk"].asString
        val expected = fixture["output"].asJsonArray.map { it.asString }

        val (oldCode, newCode) = NesUtils.extractDiffParts(hunk, -1)

        assertEquals(expected[0], oldCode)
        assertEquals(expected[1], newCode)
    }

    // --- split_into_hunks ---

    @Test
    fun `splitIntoHunks matches Python`() {
        val fixture = fixtures["split_into_hunks"].asJsonObject
        val input = fixture["input"].asString
        val expected = fixture["output"].asJsonArray.map { it.asString }

        val result = NesUtils.splitIntoHunks(input)

        assertEquals(expected, result)
    }

    // --- get_line_number_from_position ---

    @Test
    fun `getLineNumberFromPosition matches Python`() {
        val fixture = fixtures["get_line_number_from_position"].asJsonObject
        val text = fixture["input"].asJsonObject["text"].asString
        val outputs = fixture["outputs"].asJsonObject

        assertEquals(outputs["pos_0"].asInt, NesUtils.getLineNumberFromPosition(text, 0))
        assertEquals(outputs["pos_6"].asInt, NesUtils.getLineNumberFromPosition(text, 6))
        assertEquals(outputs["pos_12"].asInt, NesUtils.getLineNumberFromPosition(text, 12))
        assertEquals(outputs["pos_end"].asInt, NesUtils.getLineNumberFromPosition(text, text.length))
        assertEquals(outputs["pos_negative"].asInt, NesUtils.getLineNumberFromPosition(text, -1))
    }

    // --- strip_leading_empty_newlines ---

    @Test
    fun `stripLeadingEmptyNewlines matches Python`() {
        val fixture = fixtures["strip_leading_empty_newlines"].asJsonObject
        val input = fixture["input"].asString
        val expected = fixture["output"].asString

        assertEquals(expected, NesUtils.stripLeadingEmptyNewlines(input))
    }

    // --- filter_whitespace_only_hunks ---

    @Test
    fun `filterWhitespaceOnlyHunks matches Python`() {
        val fixture = fixtures["filter_whitespace_only_hunks"].asJsonObject
        val input = fixture["input"].asJsonArray.map { it.asString }
        val expected = fixture["output"].asJsonArray.map { it.asString }

        val result = NesUtils.filterWhitespaceOnlyHunks(input)

        assertEquals(expected, result)
    }

    // --- split_into_diff_hunks ---

    @Test
    fun `splitIntoDiffHunks matches Python semantically`() {
        val fixture = fixtures["split_into_diff_hunks"].asJsonObject
        val inputContent = fixture["input"].asJsonObject["input_content"].asString
        val outputContent = fixture["input"].asJsonObject["output_content"].asString
        val expected = fixture["output"].asJsonArray

        val result = NesUtils.splitIntoDiffHunks(inputContent, outputContent)

        // Compare the number of hunks
        assertEquals(expected.size(), result.size, "Number of hunks should match")

        // Compare each hunk semantically (line numbers and content)
        for (i in result.indices) {
            val expectedHunk = expected[i].asJsonObject
            val actualHunk = result[i]

            assertEquals(
                expectedHunk["input_start"].asInt, actualHunk.inputStart,
                "Hunk $i: input_start mismatch"
            )
            assertEquals(
                expectedHunk["output_start"].asInt, actualHunk.outputStart,
                "Hunk $i: output_start mismatch"
            )

            // Compare line content (stripping trailing newlines for robustness)
            val expectedInputLines = expectedHunk["input_lines"].asJsonArray.map { it.asString.trimEnd('\n') }
            val actualInputLines = actualHunk.inputLines.map { it.trimEnd('\n') }
            assertEquals(expectedInputLines, actualInputLines, "Hunk $i: input_lines mismatch")

            val expectedOutputLines = expectedHunk["output_lines"].asJsonArray.map { it.asString.trimEnd('\n') }
            val actualOutputLines = actualHunk.outputLines.map { it.trimEnd('\n') }
            assertEquals(expectedOutputLines, actualOutputLines, "Hunk $i: output_lines mismatch")
        }
    }

    // --- is_large_diff_above_cursor ---

    @Test
    fun `isLargeDiffAboveCursor matches Python`() {
        val fixture = fixtures["is_large_diff_above_cursor"].asJsonObject

        val orig = "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\n"
        val compLarge = "a\nb\nc\nd\ne\nf\ng\nh\nline7\nline8\n"
        val compSmall = "line1\nchanged\nline3\nline4\nline5\nline6\nline7\nline8\n"

        assertEquals(fixture["large_diff"].asBoolean, NesUtils.isLargeDiffAboveCursor(orig, compLarge, 20))
        assertEquals(fixture["small_diff"].asBoolean, NesUtils.isLargeDiffAboveCursor(orig, compSmall, 20))
        assertEquals(fixture["same"].asBoolean, NesUtils.isLargeDiffAboveCursor(orig, orig, 20))
    }

    // --- truncate_long_lines ---

    @Test
    fun `truncateLongLines matches Python`() {
        val fixture = fixtures["truncate_long_lines"].asJsonObject
        val input = fixture["input"].asString
        val expected = fixture["output"].asString

        assertEquals(expected, NesUtils.truncateLongLines(input))
    }

    // --- should_disable_for_code_block ---

    @Test
    fun `shouldDisableForCodeBlock matches Python`() {
        val fixture = fixtures["should_disable_for_code_block"].asJsonObject

        assertEquals(fixture["short_lines"].asBoolean, NesUtils.shouldDisableForCodeBlock("short\nlines\n"))
        assertEquals(
            fixture["long_line"].asBoolean,
            NesUtils.shouldDisableForCodeBlock("short\n" + "x".repeat(1001) + "\nshort")
        )
    }

    // --- is_equal_ignoring_newlines ---

    @Test
    fun `isEqualIgnoringNewlines matches Python`() {
        val fixture = fixtures["is_equal_ignoring_newlines"].asJsonObject

        assertEquals(fixture["equal"].asBoolean, NesUtils.isEqualIgnoringNewlines("a\n\nb", "a\nb"))
        assertEquals(fixture["not_equal"].asBoolean, NesUtils.isEqualIgnoringNewlines("a\nb", "a b"))
    }

    // --- pretokenize ---

    @Test
    fun `pretokenize matches Python`() {
        val fixture = fixtures["pretokenize"].asJsonObject
        val input = fixture["input"].asString
        val expected = fixture["output"].asJsonArray.map { it.asString }

        val result = NesUtils.pretokenize(input)

        assertEquals(expected, result)
    }

    // --- get_lines_around_cursor (small file returns full) ---

    @Test
    fun `getLinesAroundCursor small file returns full`() {
        val fixture = fixtures["get_lines_around_cursor_small"].asJsonObject
        val text = fixture["input"].asJsonObject["text"].asString
        val cursorPos = fixture["input"].asJsonObject["cursor_position"].asInt
        val expected = fixture["output"].asString

        assertEquals(expected, NesUtils.getLinesAroundCursor(text, cursorPos))
    }

    // --- pack_items_for_prompt ---

    @Test
    fun `packItemsForPrompt matches Python`() {
        val fixture = fixtures["pack_items_for_prompt"].asJsonObject
        val items = fixture["input"].asJsonObject["items"].asJsonArray.map { it.asString }
        val tokenLimit = fixture["input"].asJsonObject["token_limit"].asInt
        val expected = fixture["output"].asJsonArray.map { it.asString }

        val result = NesUtils.packItemsForPrompt(items, { it }, tokenLimit)

        assertEquals(expected, result)
    }

    // --- parse_hunk ---

    @Test
    fun `parseHunk matches Python`() {
        val fixture = fixtures["parse_hunk"].asJsonObject
        val input = fixture["input"].asString
        val expected = fixture["output"].asJsonObject

        val result = NesUtils.parseHunk(input)

        assertEquals(expected["input_start"].asInt, result.inputStart)
        assertEquals(expected["output_start"].asInt, result.outputStart)

        val expectedInputLines = expected["input_lines"].asJsonArray.map { it.asString.trimEnd('\n') }
        val actualInputLines = result.inputLines.map { it.trimEnd('\n') }
        assertEquals(expectedInputLines, actualInputLines)

        val expectedOutputLines = expected["output_lines"].asJsonArray.map { it.asString.trimEnd('\n') }
        val actualOutputLines = result.outputLines.map { it.trimEnd('\n') }
        assertEquals(expectedOutputLines, actualOutputLines)
    }

    // --- linesSplitKeepEnds ---

    @Test
    fun `linesSplitKeepEnds preserves line endings`() {
        assertEquals(listOf("a\n", "b\n", "c"), "a\nb\nc".linesSplitKeepEnds())
        assertEquals(listOf("a\n", "b\n"), "a\nb\n".linesSplitKeepEnds())
        assertEquals(listOf("single"), "single".linesSplitKeepEnds())
        assertEquals(listOf(""), "".linesSplitKeepEnds())
    }
}
