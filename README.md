# JetBrains Extension for Sweep - VULCAN

Installable releases are published at
https://github.com/forstjiri/sweep-autocomplete-plugin/releases.

This project is a fork of [Sweep Autocomplete](https://blog.sweep.dev/posts/oss-next-edit).
It provides a local alternative to JetBrains Next Edit Suggestions, using the
Sweep 0.5B or 1.5B model to predict and apply edits anywhere in the file.

The plugin supports:

- Next-edit suggestions at the current cursor position
- Jump-to-edit suggestions elsewhere in the file
- Requesting another suggestion with a steering prompt via configurable shortcut
- Safe offset validation against the current document
- Local inference through an AMD/Vulkan GPU

On supported local GPU backends, suggestions can typically be generated in
about one second, depending on the model, prompt size, hardware, and system
load. AMD integrated graphics with Vulkan are one tested configuration; other
GPU backends may require different llama.cpp packages or runtime settings.

## Local Server

The patched server is published at
https://github.com/forstjiri/sweep-autocomplete. The plugin downloads the
pinned `v0.1.2` wheel from that repository when no local development wheel is
available.

The server runs exclusively in the visible PhpStorm/IntelliJ terminal. The
plugin never starts a hidden background server. It checks the server health
periodically and skips autocomplete requests while the server is unavailable.

To test, start the server manually with the required model:

```bash
MODEL_REPO='sweepai/sweep-next-edit-0.5B' \
MODEL_FILENAME='sweep-next-edit-0.5b.q8_0.gguf' \
sweep-autocomplete --gpu-profile safe --port 8081
```

For the 1.5B model:

```bash
MODEL_REPO='sweepai/sweep-next-edit-1.5B' \
MODEL_FILENAME='sweep-next-edit-1.5b.q8_0.v2.gguf' \
sweep-autocomplete --gpu-profile safe --port 8081
```

During development, a wheel under `~/test/sweep-autocomplete/*/dist/` takes
precedence as an explicit local override.

### GPU Profiles

The `safe` profile reduces context and batch sizes while keeping all model
layers on the GPU. If Vulkan or AMDGPU still crashes, use `conservative`, which
offloads fewer layers and disables flash attention:

```bash
sweep-autocomplete --gpu-profile conservative --port 8081
```

The individual settings can also be overridden with `SWEEP_N_CTX`,
`SWEEP_N_BATCH`, `SWEEP_N_GPU_LAYERS`, and `SWEEP_FLASH_ATTN`. Retrying the
default profile after a native Vulkan crash is unlikely to help; reduce the
memory settings or switch to the conservative profile.

If the server exits unexpectedly, its stderr remains visible in the terminal.
The plugin also prints the exit code and points to Vulkan/AMDGPU logs. On Linux,
check crashes with:

```bash
journalctl -k -b --no-pager | grep -Ei 'segfault|amdgpu|vulkan|oom|killed'
```

## Building

Open the project in IntelliJ and click **Run Plugin** in the top right corner.
To build an installable ZIP:

```bash
./gradlew buildPlugin
```

The resulting archive is written to `build/distributions/`.

## Customizing Autocomplete Keystrokes

The autocomplete accept and reject keystrokes are fully customizable via
IntelliJ's keymap settings.

### Default Keystrokes

- **Accept Completion**: `Tab`
- **Reject Completion**: `Escape`
- **Show Next Suggestion**: no default shortcut

### How to Customize

1. Open **Settings/Preferences** -> **Keymap**
2. Search for **Accept Edit Completion**, **Reject Edit Completion**, or
   **Show Next Autocomplete Suggestion**
3. Right-click on the action and select **Add Keyboard Shortcut**
4. Assign your preferred keystroke
5. The plugin will automatically adapt to your custom keystrokes without
   requiring a restart

The next-suggestion action first cycles through alternatives already returned by
the server. When those are exhausted, it sends a new request from the current
cursor with a steering prompt requesting a different edit.

The keystroke must map to a standard editor action to be intercepted reliably.
