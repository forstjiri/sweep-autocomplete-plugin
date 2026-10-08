# NES latency policy and validation

Automatic requests use 4,096 estimated input tokens, a 150-line original-file
window, 768 outline tokens, one 512-token retrieval chunk, one 512-token auxiliary
file, and a three-second total deadline. Manual requests use 8,192 input tokens,
300 lines, 1,536 outline tokens, three retrieval chunks totaling 2,048 tokens,
one 1,024-token auxiliary file, and twenty seconds total. Full files are retained
when affordable. The model, speculation, debounce, and 10-line edit window are unchanged.

## Typing continuation

Automatic `INSERT_CHAR` requests prefill unchanged code before the caret, backing
off the last Qwen pretoken to allow completion inside identifiers. Prefill follows
steering and counts toward the mandatory prompt budget. Parsing, unchanged
filtering, and complete-window stopping reconstruct the prefix plus continuation.
Line-based hunks trim an identical prefix before the actual caret; edits that
change earlier text are rejected, including retrieval fallback results.
Manual requests and other actions retain full rewrites. Automatic fallback remains
limited to a distinct context after valid empty/unchanged/no-hunk output, with
one second remaining; it does not prefill a fictitious retrieval cursor.

The optional IDE structural adapter is removed. PHP declarations come from the
current captured source through the conservative text extractor, including distant
properties, constructor promotion, and accessor signatures. Comments/strings are
masked, bodies omitted, uncertain heredoc syntax discarded, and cancellation checked.
Mandatory prompt budgeting, retrieval deadlines, HTTP cancellation and stream cleanup remain.
Prefill removed 96 production Kotlin lines. Subsequent consolidation of definition
walking, dropdown strings and tracked/unversioned diff rendering removed another
203 lines. The combined reduction is 299 lines against the saved implementation,
including untracked files. Tests and documentation are counted separately.

## Controlled replay, 2026-10-02

Baseline is the complete working-tree snapshot saved immediately before prefill,
not HEAD. Baseline and candidate ran sequentially on fresh isolated llama-b10655
servers with the same sweep-next-edit-1.5B Q8_0 model, Vulkan offload, ngram-mod,
and default slots/context. The user's running server was left unchanged.
Each warm mode has 30 measured requests following scenario-specific priming:
PHP getters, distant properties/existing accessors, variable extraction,
post-accept baselines, and auxiliary context. Cold replay has six requests per
mode with `cache_prompt=false`, starting with a fresh server. These are engine
request-to-result times; they exclude debounce, IDE retrieval and display.

| Mode/cache | Baseline p50 / p95 | Candidate p50 / p95 | Valid baseline / candidate |
|---|---:|---:|---:|
| Automatic warm | 616.5 / 1,002 ms | 602.5 / 771 ms | 30/30 / 30/30 |
| Manual warm | 624 / 1,027 ms | 622.5 / 1,019 ms | 30/30 / 30/30 |
| Automatic cold | 1,498.5 / 2,813 ms | 1,339.5 / 2,528 ms | 6/6 / 6/6 |
| Manual cold | 1,610.5 / 4,157 ms | 1,629.5 / 4,518 ms | 6/6 / 6/6 |

Warm automatic p95 improved 23.1%; cold automatic p95 improved 10.1%.
The corrected cold replay observed no timeouts, inference errors or empty results.
The first cold run had a proxy connection-reuse error and was superseded by
fresh-connection cold replay; its failure is not counted as a model quality result.
Warm validation checks getter bodies and duplicate accessors; cold validation
checks the expected suggested symbol. Neither proves general semantic quality.
Raw replay records are generated under `build/reports/nes-latency-replay.json`.

## Verification and remaining release gate

Run with JDK 21 (this Gradle setup cannot use the system JDK 25):

```sh
JAVA_HOME=/home/jirka/.jdks/temurin-21.0.11 ./gradlew test buildPlugin --continue --offline
NES_REPLAY_LABEL=candidate NES_REPLAY_ENDPOINT=http://127.0.0.1:18082 \
JAVA_HOME=/home/jirka/.jdks/temurin-21.0.11 ./gradlew test \
  --tests 'dev.sweep.assistant.autocomplete.edit.engine.NesLatencyReplayTest' --offline
```

Replay is opt-in; set `NES_REPLAY_COLD_ONLY=true` to run only cold samples.
Final autocomplete checks: 110 passed, zero failed, one opt-in replay skipped.
Four additional bounded-diff regression tests pass.
Existing live simulations pass all five scenarios. Full-suite results retain the
two unrelated failures in untouched `StringUtilsTest`: `test happy path no new line`
and `test very long word that exceeds width`. Plugin packaging succeeds. PhpStorm 2026.3 EAP (263.4732.41) lists the built
plugin in its isolated startup log. Startup also logs an existing EDT warning
from `PluginConflictUtils.disableFullLineCompletion`; that path was not changed.
The smoke test checks startup loading, not interactive displayed suggestions.

The 30% warm automatic p95 target is **not met**. Cold regression and sampled
quality gates pass, but release validation still needs displayed-suggestion timing
including debounce/retrieval, realistic cursor/auxiliary changes and rapid
supersession. Do not count deadline expiry or cancellation as a latency win.
Preserve distant-property/accessor coverage and reject a valid-suggestion decline
over ten percentage points. No release is implied by the plugin build.
