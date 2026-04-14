package dev.sweep.assistant.autocomplete.edit.engine

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tests for NesCompletionParser, comparing outputs against Python-generated fixtures.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NesCompletionParserTest {

    private lateinit var fixtures: JsonObject
    private val gson = Gson()

    @BeforeAll
    fun loadFixtures() {
        val json = this::class.java.classLoader
            .getResourceAsStream("nes_fixtures/parser_fixtures.json")!!
            .bufferedReader().readText()
        fixtures = gson.fromJson(json, JsonObject::class.java)
    }

    @Test
    fun `getGhostTextWithLocation matches Python`() {
        val f = fixtures["ghost_text_with_location"].asJsonObject
        val input = f["input"].asJsonObject
        val expected = f["output"].asString

        val result = NesCompletionParser.getGhostTextWithLocation(
            input["completion"].asString,
            input["code_block"].asString,
            input["cursor_pos"].asInt,
        )
        assertEquals(expected, result)
    }

    @Test
    fun `findGhostTextNonLocal matches Python`() {
        val f = fixtures["find_ghost_text_non_local"].asJsonObject
        val input = f["input"].asJsonObject
        val expectedArray = f["output"].asJsonArray
        val expectedText = expectedArray[0].asString
        val expectedPos = expectedArray[1].asInt

        val (text, pos) = NesCompletionParser.findGhostTextNonLocal(
            input["completion"].asString,
            input["code_block"].asString,
            input["cursor_pos"].asInt,
        )
        assertEquals(expectedText, text)
        assertEquals(expectedPos, pos)
    }

    @Test
    fun `isSingleLineGhostText matches Python`() {
        val f = fixtures["single_line_ghost_text"].asJsonObject
        val input = f["input"].asJsonObject
        val expected = f["output"].asString

        val result = NesCompletionParser.isSingleLineGhostText(
            input["completion"].asString,
            input["code_block"].asString,
            input["cursor_pos"].asInt,
        )
        assertEquals(expected, result)
    }

    @Test
    fun `isSingleLineGhostText empty when no match`() {
        val f = fixtures["single_line_ghost_text_empty"].asJsonObject
        val input = f["input"].asJsonObject
        val expected = f["output"].asString

        val result = NesCompletionParser.isSingleLineGhostText(
            input["completion"].asString,
            input["code_block"].asString,
            input["cursor_pos"].asInt,
        )
        assertEquals(expected, result)
    }

    @Test
    fun `isPureInsertionAboveCursor matches Python`() {
        val f = fixtures["is_pure_insertion_above_cursor_true"].asJsonObject
        val input = f["input"].asJsonObject
        val expected = f["output"].asBoolean

        val result = NesCompletionParser.isPureInsertionAboveCursor(
            input["code_block"].asString,
            input["completion"].asString,
            input["cursor_pos"].asInt,
        )
        assertEquals(expected, result)
    }

    @Test
    fun `applyCompletionsToCodeBlock matches Python`() {
        val f = fixtures["apply_completions_to_code_block"].asJsonObject
        val input = f["input"].asJsonObject
        val expected = f["output"].asString

        val comps = input["completions"].asJsonArray.map { it.asJsonObject }.map {
            NesCompletionParser.AutocompleteResult(
                it["start_index"].asInt,
                it["end_index"].asInt,
                it["completion"].asString,
                1.0f,
                "test",
            )
        }

        val result = NesCompletionParser.applyCompletionsToCodeBlock(
            comps,
            input["file_contents"].asString,
            input["cleaned_code_block"].asString,
        )
        assertEquals(expected, result)
    }

    @Test
    fun `selectBestHunkFromCompletion matches Python`() {
        val f = fixtures["select_best_hunk_simple_change"].asJsonObject
        val input = f["input"].asJsonObject
        val expectedArray = f["output"].asJsonArray

        val results = NesCompletionParser.selectBestHunkFromCompletion(
            input["completion"].asString,
            input["cleaned_code_block"].asString,
            input["file_contents"].asString,
            input["cursor_position"].asInt,
            input["autocomplete_id"].asString,
        )

        assertEquals(expectedArray.size(), results.size, "Number of results should match")

        for (i in results.indices) {
            val expected = expectedArray[i].asJsonObject
            val actual = results[i]
            assertEquals(expected["start_index"].asInt, actual.startIndex, "Result $i: start_index")
            assertEquals(expected["end_index"].asInt, actual.endIndex, "Result $i: end_index")
            assertEquals(expected["completion"].asString, actual.completion, "Result $i: completion")
            assertEquals(expected["autocomplete_id"].asString, actual.autocompleteId, "Result $i: autocomplete_id")
        }
    }
}
