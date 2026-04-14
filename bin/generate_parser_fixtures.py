#!/usr/bin/env python3
"""Generate test fixtures for NesCompletionParser."""
import json
import sys
sys.path.insert(0, "/var/folders/9b/jr54dbsj28128y6wjclqrs9h0000gn/T/sweep-pkg/sweep_src")

from sweep_autocomplete.autocomplete.next_edit_autocomplete import (
    get_ghost_text_with_location,
    find_ghost_text_non_local,
    is_single_line_ghost_text,
    is_pure_insertion_above_cursor,
    apply_completions_to_code_block,
    select_best_hunk_from_completion,
    AutocompleteResult,
)


def generate_fixtures():
    fixtures = {}

    # --- ghost text tests ---
    code_block = "def hello():\n    print('hi')\n    return True\n"
    # Insertion at cursor position 25 (after "print('hi')")
    completion_ghost = "def hello():\n    print('hi')\n    print('bye')\n    return True\n"
    cursor_pos = len("def hello():\n    print('hi')\n")

    fixtures["ghost_text_with_location"] = {
        "input": {"completion": completion_ghost, "code_block": code_block, "cursor_pos": cursor_pos},
        "output": get_ghost_text_with_location(completion_ghost, code_block, cursor_pos),
    }

    fixtures["find_ghost_text_non_local"] = {
        "input": {"completion": completion_ghost, "code_block": code_block, "cursor_pos": cursor_pos},
        "output": list(find_ghost_text_non_local(completion_ghost, code_block, cursor_pos)),
    }

    # Single line ghost text
    code_single = "x = 1\ny = \nz = 3\n"
    comp_single = "x = 1\ny = 2\nz = 3\n"
    cursor_single = len("x = 1\ny = ")
    fixtures["single_line_ghost_text"] = {
        "input": {"completion": comp_single, "code_block": code_single, "cursor_pos": cursor_single},
        "output": is_single_line_ghost_text(comp_single, code_single, cursor_single),
    }

    # No ghost text (different text)
    fixtures["single_line_ghost_text_empty"] = {
        "input": {"completion": "totally different", "code_block": code_single, "cursor_pos": cursor_single},
        "output": is_single_line_ghost_text("totally different", code_single, cursor_single),
    }

    # --- pure insertion above cursor ---
    code_pure = "line1\nline2\nline3\n"
    comp_pure = "line1\nnew_line\nline2\nline3\n"
    fixtures["is_pure_insertion_above_cursor_true"] = {
        "input": {"code_block": code_pure, "completion": comp_pure, "cursor_pos": len("line1\nline2\n")},
        "output": is_pure_insertion_above_cursor(code_pure, comp_pure, len("line1\nline2\n")),
    }

    # --- select_best_hunk_from_completion ---
    file_contents = "import os\n\ndef process():\n    x = 1\n    y = 2\n    z = x + y\n    return z\n"
    code_block_2 = "    x = 1\n    y = 2\n    z = x + y\n    return z\n"
    completion_2 = "    x = 10\n    y = 2\n    z = x + y\n    return z\n"
    cursor_2 = file_contents.index("    x = 1") + len("    x = 1")
    block_start = file_contents.index(code_block_2)

    results = select_best_hunk_from_completion(
        completion_2, code_block_2, file_contents, cursor_2, "test-id"
    )
    fixtures["select_best_hunk_simple_change"] = {
        "input": {
            "completion": completion_2,
            "cleaned_code_block": code_block_2,
            "file_contents": file_contents,
            "cursor_position": cursor_2,
            "autocomplete_id": "test-id",
        },
        "output": [
            {"start_index": r.start_index, "end_index": r.end_index,
             "completion": r.completion, "confidence": r.confidence,
             "autocomplete_id": r.autocomplete_id}
            for r in results
        ],
    }

    # --- apply_completions_to_code_block ---
    completions = [AutocompleteResult(
        start_index=block_start,
        end_index=block_start + len("    x = 1"),
        completion="    x = 10",
        confidence=1.0,
        autocomplete_id="test-0",
    )]
    applied = apply_completions_to_code_block(completions, file_contents, code_block_2)
    fixtures["apply_completions_to_code_block"] = {
        "input": {
            "file_contents": file_contents,
            "cleaned_code_block": code_block_2,
            "completions": [{"start_index": c.start_index, "end_index": c.end_index,
                           "completion": c.completion} for c in completions],
        },
        "output": applied,
    }

    output_path = "src/test/resources/nes_fixtures/parser_fixtures.json"
    with open(output_path, "w") as f:
        json.dump(fixtures, f, indent=2, ensure_ascii=False)
    print(f"Wrote {len(fixtures)} fixtures to {output_path}")


if __name__ == "__main__":
    generate_fixtures()
