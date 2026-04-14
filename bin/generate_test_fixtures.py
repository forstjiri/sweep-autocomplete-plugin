#!/usr/bin/env python3
"""
Generate JSON test fixtures by running the Python NES functions.
These fixtures are used by Kotlin unit tests for parity verification.

Usage:
    uvx --no-env-file --with sweep-autocomplete python3 bin/generate_test_fixtures.py
"""
import json
import sys
sys.path.insert(0, "/var/folders/9b/jr54dbsj28128y6wjclqrs9h0000gn/T/sweep-pkg/sweep_src")

from sweep_autocomplete.autocomplete.next_edit_autocomplete_utils import (
    extract_diff_parts,
    filter_whitespace_only_hunks,
    split_into_hunks,
    get_line_number_from_position,
    get_lines_around_cursor,
    strip_leading_empty_newlines,
    parse_hunk,
    split_into_diff_hunks,
    is_large_diff_above_cursor,
    truncate_long_lines,
    should_disable_for_code_block,
    is_equal_ignoring_newlines,
)
from sweep_autocomplete.autocomplete.next_edit_autocomplete import (
    format_recent_changes_and_prev_section,
    get_block_at_cursor,
    select_best_hunk_from_completion,
    AutocompleteResult,
)
from sweep_autocomplete.utils.str_utils import pack_items_for_prompt

import regex
PRETOKENIZE_REGEX = r"""(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\r\n\p{L}\p{N}]?\p{L}+|\p{N}| ?[^\s\p{L}\p{N}]+[\r\n]*|\s*[\r\n]+|\s+(?!\S)|\s+"""
pretokenize_regex = regex.compile(PRETOKENIZE_REGEX)


def generate_fixtures():
    fixtures = {}

    # --- extract_diff_parts ---
    hunk1 = """@@ -28,7 +28,7 @@
 transformed = {
     "id": item["id"],
-    "score": round(item.get("score", 0) * 100, 2),
+    "value": round(item.get("score", 0) * 100, 2),
 }"""
    fixtures["extract_diff_parts_no_context"] = {
        "input": {"hunk": hunk1, "num_context_lines": 0},
        "output": list(extract_diff_parts(hunk1, 0)),
    }
    fixtures["extract_diff_parts_one_context"] = {
        "input": {"hunk": hunk1, "num_context_lines": 1},
        "output": list(extract_diff_parts(hunk1, 1)),
    }
    fixtures["extract_diff_parts_all"] = {
        "input": {"hunk": hunk1, "num_context_lines": -1},
        "output": list(extract_diff_parts(hunk1, -1)),
    }

    # --- split_into_hunks ---
    diff1 = """File: src/main.py
@@ -1,3 +1,3 @@
-old
+new
File: src/utils.py
@@ -5,2 +5,2 @@
-foo
+bar"""
    fixtures["split_into_hunks"] = {
        "input": diff1,
        "output": split_into_hunks(diff1),
    }

    # --- get_line_number_from_position ---
    text1 = "line0\nline1\nline2\nline3"
    fixtures["get_line_number_from_position"] = {
        "input": {"text": text1},
        "outputs": {
            "pos_0": get_line_number_from_position(text1, 0),
            "pos_6": get_line_number_from_position(text1, 6),
            "pos_12": get_line_number_from_position(text1, 12),
            "pos_end": get_line_number_from_position(text1, len(text1)),
            "pos_negative": get_line_number_from_position(text1, -1),
        }
    }

    # --- strip_leading_empty_newlines ---
    fixtures["strip_leading_empty_newlines"] = {
        "input": "\n\n\nhello\nworld",
        "output": strip_leading_empty_newlines("\n\n\nhello\nworld"),
    }

    # --- filter_whitespace_only_hunks ---
    hunks_ws = [
        "File: a.py\n@@ -1,1 +1,1 @@\n- foo\n+ foo",  # whitespace only
        "File: b.py\n@@ -1,1 +1,1 @@\n-old\n+new",  # real change
    ]
    fixtures["filter_whitespace_only_hunks"] = {
        "input": hunks_ws,
        "output": filter_whitespace_only_hunks(hunks_ws),
    }

    # --- split_into_diff_hunks ---
    input_code = "line1\nline2\nline3\nline4\n"
    output_code = "line1\nchanged2\nline3\nnew_line4\nextra_line\n"
    hunks_result = split_into_diff_hunks(input_code, output_code)
    fixtures["split_into_diff_hunks"] = {
        "input": {"input_content": input_code, "output_content": output_code},
        "output": [
            {
                "input_start": h[0],
                "input_lines": list(h[1]),
                "output_start": h[2],
                "output_lines": list(h[3]),
            }
            for h in hunks_result
        ],
    }

    # --- is_large_diff_above_cursor ---
    orig = "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\n"
    comp_large = "a\nb\nc\nd\ne\nf\ng\nh\nline7\nline8\n"
    comp_small = "line1\nchanged\nline3\nline4\nline5\nline6\nline7\nline8\n"
    fixtures["is_large_diff_above_cursor"] = {
        "large_diff": is_large_diff_above_cursor(orig, comp_large, 20),
        "small_diff": is_large_diff_above_cursor(orig, comp_small, 20),
        "same": is_large_diff_above_cursor(orig, orig, 20),
    }

    # --- truncate_long_lines ---
    long_line = "x" * 700
    fixtures["truncate_long_lines"] = {
        "input": f"short\n{long_line}\nshort2",
        "output": truncate_long_lines(f"short\n{long_line}\nshort2"),
    }

    # --- should_disable_for_code_block ---
    fixtures["should_disable_for_code_block"] = {
        "short_lines": should_disable_for_code_block("short\nlines\n"),
        "long_line": should_disable_for_code_block("short\n" + "x" * 1001 + "\nshort"),
    }

    # --- is_equal_ignoring_newlines ---
    fixtures["is_equal_ignoring_newlines"] = {
        "equal": is_equal_ignoring_newlines("a\n\nb", "a\nb"),
        "not_equal": is_equal_ignoring_newlines("a\nb", "a b"),
    }

    # --- pretokenize ---
    text_pt = "def hello_world(x: int) -> str:\n    return f'Hello {x}'"
    tokens_pt = pretokenize_regex.findall(text_pt)
    fixtures["pretokenize"] = {
        "input": text_pt,
        "output": tokens_pt,
    }

    # --- get_lines_around_cursor (small file) ---
    small_file = "\n".join(f"line{i}" for i in range(50))
    fixtures["get_lines_around_cursor_small"] = {
        "input": {"text": small_file, "cursor_position": 30},
        "output": get_lines_around_cursor(small_file, 30),
    }

    # --- get_block_at_cursor ---
    file_contents = """import json

def process(items):
    results = []
    for item in items:
        transformed = {"id": item["id"], "name": item["name"]}
        results.append(transformed)
    return results

def main():
    data = json.load(open("data.json"))
    print(process(data))
"""
    cursor_pos = file_contents.index("results.append") + len("results.append")
    code_block, prefix, suffix, block_start = get_block_at_cursor(file_contents, cursor_pos)
    fixtures["get_block_at_cursor"] = {
        "input": {"file_contents": file_contents, "cursor_position": cursor_pos},
        "output": {
            "code_block": code_block,
            "prefix": prefix,
            "suffix": suffix,
            "block_start_index": block_start,
        },
    }

    # --- pack_items_for_prompt ---
    items = ["short", "medium length text", "a very long string that takes up space"]
    packed = pack_items_for_prompt(items, str, token_limit=10, char_token_ratio=3.5)
    fixtures["pack_items_for_prompt"] = {
        "input": {"items": items, "token_limit": 10},
        "output": packed,
    }

    # --- parse_hunk ---
    hunk_str = "@@ -10,3 +10,4 @@\n \n context line\n-old line\n+new line1\n+new line2\n context after\n"
    result = parse_hunk(hunk_str)
    fixtures["parse_hunk"] = {
        "input": hunk_str,
        "output": {
            "input_start": result[0],
            "input_lines": list(result[1]),
            "output_start": result[2],
            "output_lines": list(result[3]),
        },
    }

    # Write all fixtures
    output_path = "src/test/resources/nes_fixtures/utils_fixtures.json"
    with open(output_path, "w") as f:
        json.dump(fixtures, f, indent=2, ensure_ascii=False)
    print(f"Wrote {len(fixtures)} fixtures to {output_path}")


if __name__ == "__main__":
    generate_fixtures()
