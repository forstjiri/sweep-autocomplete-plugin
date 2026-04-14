#!/usr/bin/env python3
"""
Benchmark for local next-edit autocomplete: Python server vs llama-server direct.

Usage:
    # Python path (sweep-autocomplete on port 8081):
    uvx sweep-autocomplete --port 8081
    python bin/benchmark_autocomplete.py --port 8081 --mode python

    # llama-server direct (llama-server on port 8081):
    llama-server -m model.gguf --port 8081 -ngl -1 --flash-attn
    python bin/benchmark_autocomplete.py --port 8081 --mode llama-server

    # Both (Python on 8081, llama-server on 8082):
    python bin/benchmark_autocomplete.py --python-port 8081 --llama-port 8082
"""

import argparse
import json
import time
import requests
import statistics

# --- Test fixtures ---

FILE_PATH = "src/utils/data_processor.py"

FILE_CONTENTS = '''\
import json
import os
from dataclasses import dataclass
from typing import Optional


@dataclass
class Config:
    input_path: str
    output_path: str
    batch_size: int = 32
    verbose: bool = False


def load_config(path: str) -> Config:
    with open(path) as f:
        data = json.load(f)
    return Config(**data)


def process_batch(items: list[dict], config: Config) -> list[dict]:
    results = []
    for item in items:
        transformed = {
            "id": item["id"],
            "name": item["name"].strip().lower(),
            "score": round(item.get("score", 0) * 100, 2),
        }
        if config.verbose:
            print(f"Processing {transformed['id']}")
        results.append(transformed)
    return results


def save_results(results: list[dict], path: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(results, f, indent=2)


def main():
    config = load_config("config.json")
    data = json.load(open(config.input_path))

    all_results = []
    for i in range(0, len(data), config.batch_size):
        batch = data[i:i + config.batch_size]
        results = process_batch(batch, config)
        all_results.extend(results)

    save_results(all_results, config.output_path)
    print(f"Processed {len(all_results)} items")


if __name__ == "__main__":
    main()
'''

CURSOR_POSITION = FILE_CONTENTS.index('results.append(transformed)') + len('results.append(transformed)')

RECENT_CHANGES = """\
File: src/utils/data_processor.py
@@ -28,7 +28,7 @@
         transformed = {
             "id": item["id"],
             "name": item["name"].strip().lower(),
-            "score": round(item.get("score", 0) * 100, 2),
+            "value": round(item.get("score", 0) * 100, 2),
         }
"""

CURSOR_POSITION_2 = len(FILE_CONTENTS)

RECENT_CHANGES_2 = """\
File: src/utils/data_processor.py
@@ -48,3 +48,6 @@
     data = json.load(open(config.input_path))

     all_results = []
+    for i in range(0, len(data), config.batch_size):
+        batch = data[i:i + config.batch_size]
+        results = process_batch(batch, config)
"""

# Pre-built prompt matching NES format (for llama-server direct testing)
# This is what the Kotlin engine would construct
LLAMA_PROMPT_SCENARIO_1 = f"""<|file_sep|>{FILE_PATH}
{FILE_CONTENTS}

<|file_sep|>{FILE_PATH}:1:1
original:
            "score": round(item.get("score", 0) * 100, 2),
updated:
            "value": round(item.get("score", 0) * 100, 2),
<|file_sep|>original/{FILE_PATH}:6:14
    for item in items:
        transformed = {{
            "id": item["id"],
            "name": item["name"].strip().lower(),
            "score": round(item.get("score", 0) * 100, 2),
        }}
        if config.verbose:
            print(f"Processing {{transformed['id']}}")
        results.append(transformed)
<|file_sep|>current/{FILE_PATH}:6:14
    for item in items:
        transformed = {{
            "id": item["id"],
            "name": item["name"].strip().lower(),
            "value": round(item.get("score", 0) * 100, 2),
        }}
        if config.verbose:
            print(f"Processing {{transformed['id']}}")
        results.append(transformed)<|cursor|>
<|file_sep|>updated/{FILE_PATH}:6:14
"""

LLAMA_PROMPT_SCENARIO_3 = f"""<|file_sep|>{FILE_PATH}
{FILE_CONTENTS}

<|file_sep|>original/{FILE_PATH}:12:20
    save_results(all_results, config.output_path)
    print(f"Processed {{len(all_results)}} items")


if __name__ == "__main__":
    main()
<|file_sep|>current/{FILE_PATH}:12:20
    save_results(all_results, config.output_path)
    print(f"Processed {{len(all_results)}} items")


if __name__ == "__main__":
    main()
<|file_sep|>updated/{FILE_PATH}:12:20
"""


def make_python_request(port, cursor_position, recent_changes):
    """Send a request to the Python sweep-autocomplete server."""
    url = f"http://localhost:{port}/backend/next_edit_autocomplete"
    payload = {
        "file_path": FILE_PATH,
        "file_contents": FILE_CONTENTS,
        "original_file_contents": FILE_CONTENTS,
        "recent_changes": recent_changes,
        "cursor_position": cursor_position,
        "file_chunks": [],
        "retrieval_chunks": [],
        "recent_user_actions": [],
        "multiple_suggestions": True,
        "recent_changes_high_res": "",
        "changes_above_cursor": True,
        "editor_diagnostics": [],
    }

    start = time.perf_counter()
    resp = requests.post(url, json=payload, timeout=30)
    elapsed_ms = (time.perf_counter() - start) * 1000
    resp.raise_for_status()

    result = None
    for line in resp.text.strip().split("\n"):
        if line.strip():
            try:
                result = json.loads(line)
            except json.JSONDecodeError:
                pass

    completions = result.get("completions", []) if result else []
    completion_text = completions[0]["completion"] if completions else ""
    server_elapsed = result.get("elapsed_time_ms", -1) if result else -1

    return {
        "round_trip_ms": round(elapsed_ms, 1),
        "server_ms": server_elapsed,
        "completion_length": len(completion_text),
        "completion_preview": completion_text[:80].replace("\n", "\\n") if completion_text else "(empty)",
        "num_completions": len(completions),
    }


def make_llama_request(port, prompt):
    """Send a request directly to llama-server /v1/completions."""
    url = f"http://localhost:{port}/v1/completions"
    payload = {
        "prompt": prompt,
        "stop": ["<|endoftext|>", "<|file_sep|>"],
        "max_tokens": 1024,
        "temperature": 0.0,
        "n_predict": 1024,
    }

    start = time.perf_counter()
    resp = requests.post(url, json=payload, timeout=30)
    elapsed_ms = (time.perf_counter() - start) * 1000
    resp.raise_for_status()

    obj = resp.json()
    text = ""
    finish_reason = None
    if "choices" in obj and len(obj["choices"]) > 0:
        text = obj["choices"][0].get("text", "")
        finish_reason = obj["choices"][0].get("finish_reason")

    return {
        "round_trip_ms": round(elapsed_ms, 1),
        "server_ms": round(elapsed_ms, 1),  # no separate server timing for llama-server
        "completion_length": len(text),
        "completion_preview": text[:80].replace("\n", "\\n") if text else "(empty)",
        "num_completions": 1 if text else 0,
        "finish_reason": finish_reason,
    }


def run_scenario(name, request_fn, runs, warmup):
    """Run a benchmark scenario multiple times and print stats."""
    print(f"\n{'=' * 60}")
    print(f"Scenario: {name}")
    print(f"{'=' * 60}")

    for i in range(warmup):
        r = request_fn()
        print(f"  warmup {i+1}: {r['round_trip_ms']:.0f}ms (server: {r['server_ms']}ms)")

    results = []
    for i in range(runs):
        r = request_fn()
        results.append(r)
        preview = r['completion_preview']
        extra = f" finish={r.get('finish_reason', 'n/a')}" if 'finish_reason' in r else ""
        print(f"  run {i+1}: {r['round_trip_ms']:.0f}ms (server: {r['server_ms']}ms) "
              f"completions={r['num_completions']}{extra} [{preview}]")

    round_trips = [r["round_trip_ms"] for r in results]
    server_times = [r["server_ms"] for r in results if r["server_ms"] > 0]

    print(f"\n  Round-trip: median={statistics.median(round_trips):.0f}ms "
          f"mean={statistics.mean(round_trips):.0f}ms "
          f"min={min(round_trips):.0f}ms max={max(round_trips):.0f}ms")
    if server_times:
        print(f"  Server:     median={statistics.median(server_times):.0f}ms "
              f"mean={statistics.mean(server_times):.0f}ms "
              f"min={min(server_times):.0f}ms max={max(server_times):.0f}ms")

    return results


def main():
    parser = argparse.ArgumentParser(description="Benchmark local autocomplete: Python vs llama-server direct")
    parser.add_argument("--port", type=int, default=None, help="Server port (for single-mode testing)")
    parser.add_argument("--python-port", type=int, default=None, help="Python sweep-autocomplete port")
    parser.add_argument("--llama-port", type=int, default=None, help="llama-server port")
    parser.add_argument("--mode", choices=["python", "llama-server", "both"], default="both",
                        help="Which server to benchmark")
    parser.add_argument("--runs", type=int, default=5, help="Timed runs per scenario (default: 5)")
    parser.add_argument("--warmup", type=int, default=2, help="Warmup runs per scenario (default: 2)")
    args = parser.parse_args()

    # Resolve ports
    python_port = args.python_port or args.port
    llama_port = args.llama_port or args.port

    if args.mode in ("python", "both") and python_port:
        try:
            resp = requests.get(f"http://localhost:{python_port}/health", timeout=5)
            resp.raise_for_status()
            print(f"Python server healthy at port {python_port}")
        except Exception as e:
            print(f"Warning: Python server not reachable at port {python_port}: {e}")
            if args.mode == "python":
                return
            args.mode = "llama-server"

    if args.mode in ("llama-server", "both") and llama_port:
        try:
            resp = requests.get(f"http://localhost:{llama_port}/health", timeout=5)
            resp.raise_for_status()
            print(f"llama-server healthy at port {llama_port}")
        except Exception as e:
            print(f"Warning: llama-server not reachable at port {llama_port}: {e}")
            if args.mode == "llama-server":
                return
            args.mode = "python"

    # =====================
    # Python server benchmarks
    # =====================
    if args.mode in ("python", "both") and python_port:
        print(f"\n{'#' * 60}")
        print(f"# PYTHON SERVER (port {python_port})")
        print(f"# Full stack: plugin → HTTP → FastAPI → prompt → llama-cpp-python → Metal")
        print(f"{'#' * 60}")

        run_scenario(
            "Python: Rename variable",
            lambda: make_python_request(python_port, CURSOR_POSITION, RECENT_CHANGES),
            args.runs, args.warmup,
        )

        run_scenario(
            "Python: Repeated identical request",
            lambda: make_python_request(python_port, CURSOR_POSITION, RECENT_CHANGES),
            args.runs, 0,
        )

        run_scenario(
            "Python: New function at EOF",
            lambda: make_python_request(python_port, CURSOR_POSITION_2, RECENT_CHANGES_2),
            args.runs, args.warmup,
        )

    # =====================
    # llama-server direct benchmarks
    # =====================
    if args.mode in ("llama-server", "both") and llama_port:
        print(f"\n{'#' * 60}")
        print(f"# LLAMA-SERVER DIRECT (port {llama_port})")
        print(f"# Inference only: plugin → HTTP → llama-server (C++) → Metal")
        print(f"{'#' * 60}")

        run_scenario(
            "llama-server: Rename variable (prompt constructed in plugin)",
            lambda: make_llama_request(llama_port, LLAMA_PROMPT_SCENARIO_1),
            args.runs, args.warmup,
        )

        run_scenario(
            "llama-server: Repeated identical prompt (KV cache hit)",
            lambda: make_llama_request(llama_port, LLAMA_PROMPT_SCENARIO_1),
            args.runs, 0,
        )

        run_scenario(
            "llama-server: New function at EOF",
            lambda: make_llama_request(llama_port, LLAMA_PROMPT_SCENARIO_3),
            args.runs, args.warmup,
        )

    # =====================
    # Iterative editing benchmark (ngram-mod cross-request learning)
    # =====================
    if args.mode in ("llama-server", "both") and llama_port:
        print(f"\n{'#' * 60}")
        print(f"# ITERATIVE EDITING (llama-server port {llama_port})")
        print(f"# Simulates adding lines one at a time — tests ngram-mod learning")
        print(f"{'#' * 60}")

        run_iterative_scenario(llama_port, args.runs)

    if args.mode in ("python", "both") and python_port:
        print(f"\n{'#' * 60}")
        print(f"# ITERATIVE EDITING (Python port {python_port})")
        print(f"# Same scenario through Python server for comparison")
        print(f"{'#' * 60}")

        run_iterative_scenario_python(python_port, args.runs)

    print(f"\n{'=' * 60}")
    print("Done.")


# --- Iterative editing scenarios ---

ITERATIVE_BASE = '''\
import json
import os

def process_items(items: list[dict]) -> list[dict]:
    results = []
    for item in items:
        results.append(item)
    return results
'''

# Simulate adding validation lines one at a time
ITERATIVE_ADDITIONS = [
    ('        if not item.get("id"):\n            continue\n', "skip items without id"),
    ('        if not item.get("name"):\n            continue\n', "skip items without name"),
    ('        if item.get("score", 0) < 0:\n            continue\n', "skip negative scores"),
    ('        if len(item.get("name", "")) > 100:\n            continue\n', "skip long names"),
    ('        if item.get("deleted", False):\n            continue\n', "skip deleted items"),
    ('        if not isinstance(item.get("score", 0), (int, float)):\n            continue\n', "skip non-numeric scores"),
    ('        if item.get("name", "").strip() == "":\n            continue\n', "skip empty names"),
    ('        if item.get("id") in seen_ids:\n            continue\n        seen_ids.add(item.get("id"))\n', "skip duplicates"),
]


def build_iterative_prompt(file_contents, cursor_position, recent_change, prev_file):
    """Build a NES prompt for iterative editing."""
    lines = file_contents.split("\n")
    cursor_line = file_contents[:cursor_position].count("\n")

    # Build code block around cursor (simplified)
    start = max(0, cursor_line - 2)
    end = min(len(lines), cursor_line + 6)
    code_block_lines = lines[start:end]
    code_block = "\n".join(code_block_lines)

    prev_block = prev_file.split("\n")
    prev_start = max(0, cursor_line - 2)
    prev_end = min(len(prev_block), cursor_line + 6)
    prev_section = "\n".join(prev_block[prev_start:prev_end])

    return f"""<|file_sep|>process_items.py
{prev_file}
{recent_change}
<|file_sep|>original/process_items.py:{start+1}:{end+1}
{prev_section}
<|file_sep|>current/process_items.py:{start+1}:{end+1}
{code_block}
<|file_sep|>updated/process_items.py:{start+1}:{end+1}
"""


def run_iterative_scenario(llama_port, runs):
    """Simulate iterative line-by-line additions to test ngram-mod learning."""
    print(f"\n{'=' * 60}")
    print(f"Scenario: Iterative additions ({len(ITERATIVE_ADDITIONS)} steps x {runs} runs)")
    print(f"{'=' * 60}")

    insert_point = ITERATIVE_BASE.index("        results.append(item)")

    for run in range(runs):
        current_file = ITERATIVE_BASE
        prev_file = ITERATIVE_BASE
        run_times = []

        for i, (addition, desc) in enumerate(ITERATIVE_ADDITIONS):
            # Insert the new line before results.append
            current_insert = current_file.index("        results.append(item)")
            new_file = current_file[:current_insert] + addition + current_file[current_insert:]

            # Build diff for recent_changes
            recent_change = f"<|file_sep|>process_items.py:1:1\noriginal:\n        results.append(item)\nupdated:\n{addition}        results.append(item)"

            cursor_pos = current_insert + len(addition)
            prompt = build_iterative_prompt(new_file, cursor_pos, recent_change, prev_file)

            result = make_llama_request(llama_port, prompt)
            run_times.append(result["round_trip_ms"])

            prev_file = current_file
            current_file = new_file

        times_str = " ".join(f"{t:.0f}" for t in run_times)
        avg = statistics.mean(run_times)
        print(f"  run {run+1}: [{times_str}] ms  avg={avg:.0f}ms  trend={'↓' if run_times[-1] < run_times[0] else '→'}")

    print(f"\n  If ngram-mod is learning, later additions should be faster than earlier ones.")


def run_iterative_scenario_python(python_port, runs):
    """Same iterative scenario through Python server."""
    print(f"\n{'=' * 60}")
    print(f"Scenario: Iterative additions ({len(ITERATIVE_ADDITIONS)} steps x {runs} runs)")
    print(f"{'=' * 60}")

    for run in range(runs):
        current_file = ITERATIVE_BASE
        prev_file = ITERATIVE_BASE
        run_times = []

        for i, (addition, desc) in enumerate(ITERATIVE_ADDITIONS):
            current_insert = current_file.index("        results.append(item)")
            new_file = current_file[:current_insert] + addition + current_file[current_insert:]

            recent_change = f"""File: process_items.py
@@ -{6+i},{1} +{6+i},{addition.count(chr(10))+1} @@
 results.append(item)
+{addition.rstrip()}"""

            cursor_pos = current_insert + len(addition)

            result = make_python_request(python_port, cursor_pos, recent_change)
            run_times.append(result["round_trip_ms"])

            prev_file = current_file
            current_file = new_file

        times_str = " ".join(f"{t:.0f}" for t in run_times)
        avg = statistics.mean(run_times)
        print(f"  run {run+1}: [{times_str}] ms  avg={avg:.0f}ms")


if __name__ == "__main__":
    main()
