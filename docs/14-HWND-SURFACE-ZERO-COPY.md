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

## Next — CI Present smoke (unblocked pin as of 2026-09-16)

**Goal:** smoke **CI/release** Amphora APK + Proton WCP on HA262AAH until
`AHB_SC … import=ok` / Present≥50. **Not** knife-manual `.so` sideload.

**Status (2026-09-16 ~11:35 Asia/Shanghai):**

- **CI Present smoke v8b PASSED** (~11:26): amphora `21034ee` + WCP
  `Proton-11.0-df643f5db` + long smoke 175 frames. DIRECT hwnd-ANW,
  `SET_BUFFER_COUNT want=5 ret=0`, `import=ok`, Present≥50, guest-readback
  CLASS=MAGENTA @50/@100, screen center magenta.
  Mac ART: `/Users/sky/co/src/amphora-dev/smoke-artifacts/ci-present-20260916-112604-v8b/`
  Box note: `/workspace/ha262-ci-present-v8b-pass.md`.
- Wine client branch `wip/ahb-dxvk-from-knife-tip` tip now `0a64ebc8d69`
  (`fix(wineandroid): SET_BUFFER_COUNT ≥ MIN_UNDEQUEUED+1 for BLAST`) —
  one commit ahead of smoked `df643f5db06`. Still **not** merged to `proton_11.0`.
- imagefs `wip/ahb-wcp-69bc79bcc6e` @ `7fcc19a` still pins `df643f5db06`;
  local WCP built: `/workspace/wcp-df643f5-out/artifacts/Proton-11.0-df643f5db-x86_64.wcp`
  (sha256 `f60305913e16…`).
- `content_manifest` main `c391774` pins `Proton-11.0-df643f5db-x86_64`
  (wine release asset already published).

**Earlier trail:** v5 PARTIAL on 651198953 (DIRECT+WSI size, no CreateSwapchain);
v4 FAIL on a856835d0 (no DIRECT hwnd-ANW).

**Now do:** decide continue-vs-switch — either pin/rebuild WCP for tip
`0a64ebc8d69` (wine-side SET_BUFFER_COUNT to match amphora host) or treat
df643f5+v8b as enough and merge/pin subset to `proton_11.0`. Ban DPI/IME /
knife `.so` sideload.

**Also:** `amphora-dxvk-smoke.exe` is **not** in content_manifest / releases
(only Graphics-Test-* are). Locate on Mac knife/smoke dirs or vendor later.

**Do not** revive knife `.so` sideload / `su cp` of `wineandroid.so` /
`win32u.so` as truth — that was knife13 proof only, not a ship path.
