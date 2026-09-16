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

**Pin progress (2026-09-16 ~10:05 Asia/Shanghai):**

- Wine client branch `wip/ahb-dxvk-from-knife-tip` tip `a856835d02b` (AHB/WSI
  client in `wineandroid.so` / `win32u.so`; not yet merged to `proton_11.0`).
- imagefs local WCP `Proton-11.0-a856835d0-x86_64.wcp` built (sha256
  `ad7b0926352e458cea0b365a944edcd1de10366ca11324321e8c216d8f770ec2`) and
  uploaded to `amphora-dev/imagefs` release tag `wine`.
- `content_manifest` main wine pin bumped to that asset
  (`Proton-11.0-a856835d0-x86_64-0`, commit `2d6732f`).
- Still not on `proton_11.0`; ship merge can follow after Present smoke.

**Now do:** CI Present smoke on HA262AAH (APK + new WCP; no knife `.so`).
Mac mini ADB path is available. Optional checklist:
box `/workspace/ha262-ci-present-smoke-plan.md`.

**Also:** `amphora-dxvk-smoke.exe` is **not** in content_manifest / releases
(only Graphics-Test-* are). Locate on Mac knife/smoke dirs or vendor later.

**Do not** revive knife `.so` sideload / `su cp` of `wineandroid.so` /
`win32u.so` as truth — that was knife13 proof only, not a ship path.
