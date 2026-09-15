# 15 · 开发态 content pin overlay（`dev_pins.json`）

## 目的

在**不改**远程 `content_manifest.json`、也不发 GitHub release 的前提下，把本地编好的 WCP / runtime 资产钉进设备上的 **effective catalog pin**，让 Prepare / Health / Settings 按新 pin 工作。

这不是发版通道。正式发版仍走 imagefs publish + bump-manifest。

## 文件

路径：`filesDir/content/dev_pins.json`（与 `content_manifest.json` 同目录）。

```json
{
  "version": 1,
  "components": {
    "box64": {
      "sha256": "<64 hex>",
      "size": 2702324,
      "assetPath": "Box64-0.4.5-0db8df775.wcp",
      "remoteUrl": null,
      "version": "Box64-0.4.5-0db8df775-0",
      "verName": "0.4.5-0db8df775",
      "verCode": 0,
      "contentType": "Box64",
      "kind": "WCP"
    }
  },
  "runtimeAssets": {
    "graphics_driver/wrapper.tzst": {
      "sha256": "...",
      "size": 123,
      "remoteUrl": null
    }
  }
}
```

- 只覆盖 JSON 里**出现且非 null**的字段；`remoteUrl: null` / 省略 = 保留远程条目的 URL。
- **WCP identity:** component pins for `.wcp` must include `version` / `verName` / `verCode` / `contentType` / `kind` (from the package `profile.json`). Digest-only pins leave the remote identity in place and Prepare fails with `WCP profile does not match manifest: expected=… actual=…`. `inject-dev-pin.sh --component` fills these automatically.
- 不能发明远程 catalog 里没有的 component / runtime `assetPath`。
- 损坏或缺失时忽略（打 warn），不抛异常。

## 流程

1. **注入**：`scripts/inject-dev-pin.sh --component box64 /path/to/Box64-….wcp`
   - 把包推进 `cache/amphora-packages/<assetPath>`（+ `.sha256` sidecar）
   - 合并写入 `dev_pins.json`
2. **Catalog**：每次成功 load（磁盘缓存或 refresh）都 `DevPinOverlay.read` → 得到 **effective** manifest。
3. **Prepare**：按 effective pin 装包；本地 verified cache 命中则不必 HTTPS。
4. **Health / UI**：被 overlay 覆盖且已装到 effective pin → `LOCAL_OVERRIDE` / Settings「Local override」。

清除：

```bash
scripts/inject-dev-pin.sh --clear
scripts/inject-dev-pin.sh --clear-component box64
scripts/inject-dev-pin.sh --clear-runtime graphics_driver/wrapper.tzst
```

清掉 overlay 后下一次 Catalog load 恢复远程 pin（Prepare 可能把官方包盖回来）。

## 禁止

- 不要再用 `<file>.local-override` 旁路（已删除）。
- 不要当 release 渠道；不要把未经验收的包 bump 进远程 manifest。
- 不要在真机上丢临时 `.so` 顶替。
- 注入后若 APK 尚未含本 overlay 代码，需先装含 `DevPinOverlay` 的 debug APK。

## 相关代码

- `DevPinOverlay` / `ContentCatalog`（`:core:content`）
- `ContentHealthScanner`（`LOCAL_OVERRIDE`）
- `scripts/inject-dev-pin.sh`
