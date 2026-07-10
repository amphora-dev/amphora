# Amphora

A modern, minimal-first, long-term-engineered Android Wine emulator.

> Status: RFC stage. See [`docs/01-RFC.md`](docs/01-RFC.md). Research basis in [`docs/00-RESEARCH.md`](docs/00-RESEARCH.md).

Name from *amphora* — the ancient two-handled vessel that carried wine. A container that holds Wine containers.

## Principles

1. **Engine/feature isolation** — the Wine/render/input kernel is stable; features depend on it, never the reverse.
2. **Pluggable content source** — `BundledContentSource` (MVP) → `RemoteContentSource` (later), engine-agnostic.
3. **Stable native ABI** — C/C++ layer exposes versioned JNI; Kotlin never touches native internals.
4. **Reproducible builds** — every external binary version-locked + hash-verified.
