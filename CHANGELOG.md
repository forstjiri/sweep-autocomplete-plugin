# Changelog

## 1.32.14

### Added
- Automatic Windows setup for llama.cpp: downloads and extracts the official Vulkan build, downloads the selected model, resolves its cache path, and starts the server in PowerShell.
- Settings for the llama-server executable path, extra launch arguments, flash-attention mode, and context size.
- Windows and server-tuning setup documentation.

## 1.32.13

### Changed
- **Faster autocomplete server startup flags** — the plugin now launches `llama-server` with a single decoding slot, flash attention, and `--cache-reuse`, so prompts reuse the KV cache across keystrokes instead of paying a cold ~3k-token prefill on nearly every request (measured: 2590 ms → 1868 ms cold, ~25 ms warm).
- **Deadline-aware request pipeline** — prompt building, context collection, and inference share one deadline; requests are cancelled promptly and never block behind stale work.
- **Reworked prompt budget and context packing** — retrieval chunks, recent changes, and structural outline are packed under explicit token budgets with per-section diagnostics.
- **PHP structural outline** — large PHP files fall back to a conservative declaration outline (comments and strings masked) when the full file does not fit the window.
- **Bounded recent-change diffs** — recent diffs are rendered under a shared budget, trimming whole lines and skipping non-fitting chunks.

### Fixed
- Pure-insertion suggestions no longer anchor one line below the model's intended position (`1.32.11`).
- The plugin installs in all current and future IDE versions — no `until-build` cap (`1.32.12`).
- Caret placement after accepting an edit, and definition offsets in jump-to-edit suggestions.
- Disabled-state autocomplete server startup (`1.32.13` early commit `0ed1fa8`).

### Added
- Live replay tests: latency, retry, typing prefill, context budgets, and the `wire(` signature-split scenario.
- Latency validation notes in `docs/nes-latency-validation.md`.

## 1.32.12

### Changed
- Dropped the `until-build` compatibility cap — installs in all current and future JetBrains IDEs (2025.1+).

## 1.32.11

### Fixed
- Pure-insertion hunks anchored one line below their intended position: the Python difflib off-by-one compensation was wrongly applied to java-diff-utils positions in `splitIntoDiffHunks`.

## 1.32.10

### Changed
- Conservative complete-window stream termination: inference stops once a changed rewrite covers at least 75% of the window and ends with two unchanged final lines.

## 1.32.9 and earlier

See [GitHub releases](https://github.com/forstjiri/sweep-autocomplete-plugin/releases).
