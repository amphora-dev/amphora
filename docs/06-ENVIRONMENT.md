# Development environment

This document describes the reproducible cloud build and physical-device test
environment used for Amphora. The verified device is a Lenovo TB322FC
(`arm64-v8a`, API 36, Adreno 830), but the same setup applies to other Android
devices.

## 1. Build environment

Amphora requires:

- JDK 17
- Android SDK platform and build tools selected by the Gradle version catalog
- Android NDK selected by the Android convention plugin
- `adb` from Android platform tools

Cursor Cloud initialization is defined in `.cursor/environment.json`. The SDK
bootstrap helper is `scripts/setup-android-sdk.sh`.

Verify the environment:

```bash
java -version
./gradlew --version
adb version
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

Generated APKs:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

The debug APK is intentionally slim. Rootfs, Proton, Box64, DXVK and
kernel-direct archives are not required in `app/src/main/assets`. On first
launch, the device downloads pinned HTTPS artifacts from
`content_manifest.json`, verifies their SHA-256 and size, and atomically installs
them under app-private storage. Later launches use marker/size checks and do not
download them again.

The first provisioning run downloads roughly 450 MB and expands to about 3.7 GB.
Allow enough device storage and keep the device online. A warm launch normally
takes about 6–7 seconds on the verified device.

`stageBundledContent` remains available for legacy or diagnostic workflows, but
it is not needed for the production remote-provisioning path.

## 2. Connect a Mac and cloud agent with Tailscale

Tailscale is preferred over HTTP/WebSocket tunnels for ADB. It provides
WireGuard end-to-end encryption, stable addressing and automatic relay fallback.

Both machines must join the same tailnet.

### 2.1 Mac

The CLI-only Homebrew formula works well for a dedicated test host:

```bash
brew install tailscale
sudo brew services start tailscale
tailscale up --accept-dns=false
tailscale set --shields-up=false
tailscale status
tailscale ip -4
```

`--accept-dns=false` leaves the Mac's DNS configuration unchanged.
`--shields-up=false` permits inbound connections from devices allowed by the
tailnet ACL; it does not expose the Mac directly to the public internet.

Homebrew warns that starting the formula with `sudo brew services` gives some
formula paths `root:admin` ownership. This is expected for a system-domain
LaunchDaemon but can require ownership repair before a Homebrew
upgrade/reinstall. The official standalone Tailscale macOS app avoids this
Homebrew caveat and is preferred for ordinary desktop use.

Do not run the GUI/App Store and CLI-only variants at the same time.

### 2.2 Cloud host

On a normal systemd Linux host:

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo systemctl enable --now tailscaled
sudo tailscale up --hostname=cursor-amphora --accept-dns=false
tailscale ip -4
```

Cursor Cloud containers do not use systemd. Start `tailscaled` in a persistent
tmux session instead:

```bash
sudo mkdir -p /tmp/tailscale-amphora

sudo tailscaled \
  --state=/tmp/tailscale-amphora/tailscaled.state \
  --socket=/tmp/tailscale-amphora/tailscaled.sock \
  --tun=tailscale0
```

In another terminal:

```bash
sudo tailscale \
  --socket=/tmp/tailscale-amphora/tailscaled.sock \
  up --hostname=cursor-amphora --accept-dns=false

sudo tailscale \
  --socket=/tmp/tailscale-amphora/tailscaled.sock \
  status
```

Open the one-time authentication URL and authorize the cloud node with the same
Tailscale account as the Mac.

If `tailscale ping <mac-ip>` reports `via DERP(...)`, traffic is relayed rather
than direct. DERP remains end-to-end encrypted and is included in Tailscale
plans, but adds latency and applies fair-use throughput limits. A direct path is
faster but is not required for ADB.

## 3. Expose only the Mac ADB server to the tailnet

Keep the normal ADB server on loopback and forward only the Mac's Tailscale
address with `socat`:

```bash
brew install socat

adb start-server
TS_IP="$(tailscale ip -4)"

sudo socat \
  "TCP-LISTEN:5037,bind=${TS_IP},reuseaddr,fork" \
  "TCP:127.0.0.1:5037"
```

Keep `socat` running. This produces:

```text
Mac Tailscale IP:5037 -> Mac 127.0.0.1:5037 -> USB Android device
```

Alternatively, run ADB itself in the foreground on the Tailscale address:

```bash
adb kill-server
adb -L "tcp:${TS_IP}:5037" nodaemon server
```

Do not bind ADB to `0.0.0.0`. Restrict tailnet access with Tailscale ACLs when
the tailnet has multiple users.

Verify from the cloud host:

```bash
export MAC_TS_IP=100.x.y.z
export ADB_SERVER_SOCKET="tcp:${MAC_TS_IP}:5037"

adb devices -l
```

Expected output includes the physical device in the `device` state.

## 4. Build, install and test

Some Android Gradle Plugin test tasks assume an ADB server on cloud
`localhost:5037`. For a remote server, sequential manual commands are more
reliable and avoid concurrent ADB bridge creation.

Build:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

Install:

```bash
export ADB_SERVER_SOCKET="tcp:${MAC_TS_IP}:5037"

adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Run one test class:

```bash
adb shell am instrument -w -r \
  -e class app.amphora.GameSessionLaunchTest \
  app.amphora.test/app.amphora.HiltTestRunner
```

Run the complete suite with the state-mutating preparer test last:

```bash
TEST_CLASSES="\
app.amphora.GameSessionLaunchTest,\
app.amphora.RemoteContentSourceTest,\
app.amphora.ImagefsExtractionTest,\
app.amphora.XServerSurfaceViewInitTest,\
app.amphora.PreparerGraphicsDriverTest"

adb shell am instrument -w -r \
  -e class "$TEST_CLASSES" \
  app.amphora.test/app.amphora.HiltTestRunner
```

The verified warm-cache result is 7/7 tests passing with no skipped tests in
about 10 seconds of device-side test time.

Use an outer timeout and cleanup while diagnosing:

```bash
timeout --signal=INT 300s adb shell am instrument -w -r \
  -e class "$TEST_CLASSES" \
  app.amphora.test/app.amphora.HiltTestRunner

adb shell am force-stop app.amphora
```

## 5. Troubleshooting

### `adb devices` hangs

Check each layer independently:

```bash
tailscale ping "$MAC_TS_IP"
nc -vz "$MAC_TS_IP" 5037
ADB_SERVER_SOCKET="tcp:${MAC_TS_IP}:5037" adb devices -l
```

Confirm that the Mac has:

- Tailscale online in the same tailnet
- `shields-up` disabled
- ADB listening on `127.0.0.1:5037`
- `socat` listening on the Mac Tailscale IP
- the USB device authorized and in the `device` state

### A test remains active after the cloud command exits

Killing the local `adb` client does not always stop device-side instrumentation.
Clean it explicitly:

```bash
adb shell am force-stop app.amphora
adb shell am force-stop app.amphora.test
```

### First launch is slow

Cold provisioning includes HTTPS downloads, SHA-256 verification, Rootfs
extraction, WCP installation and Wine prefix creation. Do not use cold-run timing
as the steady-state benchmark. Preserve app data between routine tests to
exercise the installed fast path.

### Rootfs tests hang during recursive traversal

An active Wine imagefs contains directory symlinks and can contain cycles. Tests
must verify fixed structural paths and must not recursively follow the entire
installed tree.

## 6. CNB continuous testing

Amphora uses Cloud Native Build (CNB) via a single root `.cnb.yml`, written with
reusable `.fragment` keys and `!reference` (see CNB simplify-configuration).

### 6.1 `continuous-test` (amd64)

Triggers on every branch `push` (`$`) and on pull requests targeting `main`.

1. `scripts/setup-android-sdk.sh`
2. `scripts/ci-jvm-test.sh` (`:core:common:test`, `:core:content:test`)
3. `:app:assembleDebug` + `:app:assembleDebugAndroidTest`

Runner tag: `cnb:arch:amd64`.

### 6.2 `redroid-test` (arm64, manual)

**Not** on every push/PR. Trigger via CNB `web_trigger_redroid` /
`api_trigger_redroid` only.

Observed on CNB SaaS `cnb:arch:arm64:v8` DinD (2026-07-26):

- Docker **rootless** + `no-new-privileges`
- **No** `binder_linux` / ashmem device nodes
- Google SDK `platform-tools/adb` is **x86_64-only** → on arm64:
  `qemu-x86_64: Could not open '/lib64/ld-linux-x86-64.so.2'`

So automatic redroid CI is not viable on current CNB SaaS. The script now:

1. Prefers distro/native `adb` (`scripts/ensure-native-adb.sh`)
2. Pulls/runs redroid with `--platform linux/arm64`
3. **Fails fast** on rootless Docker or missing binder

Viable later on a self-hosted Linux arm64 runner with binder + non-rootless
Docker. Until then, instrumented coverage stays on physical devices
(Tailscale ADB).

Local redroid (capable host):

```bash
bash scripts/setup-android-sdk.sh
bash scripts/ensure-native-adb.sh
# sudo modprobe binder_linux devices="binder,hwbinder,vndbinder"
bash scripts/ci-redroid-test.sh
```

## 7. Teardown

Stop the Mac forward:

```bash
# In the socat terminal:
Ctrl+C
```

Stop the Homebrew service when the dedicated test host is no longer needed:

```bash
sudo brew services stop tailscale
```

On the cloud host, stop the `tailscaled` tmux session. Remove the cloud node from
the Tailscale admin console if it was created only for an ephemeral agent.
