# HWND Surface zero-copy map (2026-09-14)

## Verdict
knife13 hot path is already HWND Surface AHB zero-copy. Present does **not** go through ImageReader or HostVk blit. No further Present code change this knife.

## Hot path
1. Kotlin `WineAndroidSessionActivity` → per-HWND `SurfaceView` → `HOST_SURFACE_CHANGED` + SCM_RIGHTS
2. Host `wineandroid_host_anw.c` serves ANW (DEQUEUE/QUEUE) — BufferQueue forward, not blit
3. Wine `ANDROID_vulkan_surface_create` → `amphora_wsi_create_android_surface` → log `DIRECT hwnd-ANW (no ImageReader)`
4. CreateSwapchain: AHB import + create-time `win_queue` all (do not regress)
5. Present: IPC `AHB_SC_OP_PRESENT` → `amphora_guest_queue_knife` + FREE only (no HostVk, no re-queue)

## Dead / unused (cleaned 2026-09-14)
- Removed `wineandroid_host_vk.c` from `libwinlator` build; `CMD_VK_PRESENT` host handler is a stub reply (`-ENOENT`)
- Removed unused `amphora_parent_vk_present` / `AMPHORA_BUF_VK_PRESENT` from proton-wine `wineandroid.drv`
- Kotlin `ImageReader` — none in wineandroid session path
- Guard: `win32u` `surface_get_fshack_dpi` returns 0 when `amphora_wsi_wanted()` so fs_hack compute blit cannot engage

## HA262AAH proof (logcat 2026-09-14 ~22:28, dxvk-smoke)
- HAS: `DIRECT hwnd-ANW (no ImageReader)`, `AHB_SC create images=3 import=ok`, `guest-readback CLASS=MAGENTA` @50 and @100
- MISSING: `WineAndroidHostVk`, `GUEST_CPU_FILL`, real ImageReader usage (only the "no ImageReader" string)
- Artifact: `hwnd-zero-copy-proof/ha262-zc-proof.txt`

## Cleanup done (2026-09-14)
- HostVk Present dead path removed; fs_hack guard landed in proton-wine
- Do **not** touch AHB CreateSwapchain / import / create-time win_queue
- Do **not** GB / VkLayer / table scan

## CI Present smoke — DONE on public proton_11.0 (2026-09-16)

**Goal met:** smoke release Amphora APK + Proton WCP on HA262AAH with
`AHB_SC … import=ok` / Present≥50. **Not** knife-manual `.so` sideload.

**Status (2026-09-16 ~13:08 Asia/Shanghai):**

- **v9c PASSED** (~13:08): amphora `034b38e` + public WCP
  `Proton-11.0-0a64ebc8d-x86_64.wcp` (sha256 `c6b42624…`). DIRECT hwnd-ANW,
  `SET_BUFFER_COUNT want=5 ret=0`, `import=ok`, Present≥50, guest-readback
  CLASS=MAGENTA @50/@100, screen center magenta.
  Mac ART: `/Users/sky/co/src/amphora-dev/smoke-artifacts/ci-present-20260916-130751-v9c/`
  Box note: `/workspace/ha262-ci-present-v9c-0a64ebc-pass.md`.
- **v8b** (~11:26) was the first PASS on intermediate pin `df643f5db` + APK
  `21034ee` (same criteria).
- `origin/proton_11.0` @ `0a64ebc8d69` (AHB Present + SET_BUFFER_COUNT).
- imagefs `wip/ahb-wcp-0a64ebc8d69` @ `555a08b` pins that tip; wine release
  asset published; `content_manifest` main `967cc67` pins
  `Proton-11.0-0a64ebc8d-x86_64`.

**Earlier trail:** v5 PARTIAL on 651198953; v4 FAIL on a856835d0; v9/v9b
hung when stale `ab04edc` box64/wineserver survived `am force-stop` — kill
orphans before Present smoke.

**Shell next (not Present):** docs/16 hostScale unit tests landed (`034b38e`);
IME InputConnection still open. Ban knife `.so` sideload.

**Also:** `amphora-dxvk-smoke.exe` is **not** in content_manifest / releases
(only Graphics-Test-* are). Locate on Mac `/Users/sky/co/tmp/amphora-dxvk-smoke.exe`.

**Do not** revive knife `.so` sideload / `su cp` of `wineandroid.so` /
`win32u.so` as truth — that was knife13 proof only, not a ship path.
