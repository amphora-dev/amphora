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

**Pin progress (2026-09-16 ~11:05 Asia/Shanghai):**

- Wine client branch `wip/ahb-dxvk-from-knife-tip` tip `df643f5db06`
  (`feat(wineandroid): take host AMPHORA_BUF sock via IOCTL_GET_BUFFER_SOCK`).
  Prior tip `65119895331` added WSI size IPC; still not merged to `proton_11.0`.
- imagefs branch `wip/ahb-wcp-69bc79bcc6e` @ `7fcc19a` pins proton-wine
  `df643f5db06` / `PROTON_COMMIT` same; local `bst build l1/proton-wine-wcp.bst`
  **in progress** for `Proton-11.0-df643f5…` (log `/workspace/bst-proton-wine-wcp-df643f5.log`).
- `content_manifest` main still on `Proton-11.0-651198953-x86_64` (`869f821`) —
  bump only after df643f5 WCP is green + published.

**CI Present smoke — PARTIAL v5 (2026-09-16 ~11:00 Asia/Shanghai):**

- Device HA262AAH via Mac mini ADB; WCP `Proton-11.0-651198953` (manifest
  `869f821`); APK `da962a7`; `amphora-dxvk-smoke.exe` (no knife `.so`).
- PASS vs v4 hang: `DIRECT hwnd-ANW` + WSI size IPC (`seed req`, bridge
  reply ret=0); device stash + AHB_SC surface register.
- Still FAIL: no CreateSwapchain / `import=ok` / MAGENTA / Present≥50.
  Stops after query what=6/7; sock-backed queries (8/…) never log — guest
  stream adapter not viable vs tip host sock.
- Artifacts (Mac):
  `/Users/sky/co/src/amphora-dev/smoke-artifacts/ci-present-20260916-110005-v5/`
  Box note: `/workspace/ha262-ci-present-smoke-result.md`.

**Earlier FAIL v4 (~10:26):** a856835d0 WCP hung after device stash with no
`DIRECT hwnd-ANW` / CreateSwapchain (artifacts `…-102359-v4/`).

**Now do:** finish df643f5 WCP → publish → bump content_manifest → re-run CI
Present smoke to verify host AMPHORA_BUF serve + SCM_RIGHTS sock restores
sock-backed queries / CreateSwapchain. Ban DPI/IME / knife `.so` sideload.

**Also:** `amphora-dxvk-smoke.exe` is **not** in content_manifest / releases
(only Graphics-Test-* are). Locate on Mac knife/smoke dirs or vendor later.

**Do not** revive knife `.so` sideload / `su cp` of `wineandroid.so` /
`win32u.so` as truth — that was knife13 proof only, not a ship path.
