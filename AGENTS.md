# Amphora

Android 上的 Wine 模拟器。模块与启动链见 `docs/05-ARCHITECTURE.md`。

**现在是开发阶段。** 当前设计是唯一真源。设备上的旧 prefix、旧 applied mark、旧 WinNative/Winlator 布局都不是兼容面。

## 不要做

- 为上一版磁盘形态加迁移：applied-mark bump、wipe/rebind、把私有副本改成软链、legacy-backup、旧字体路径、digest-only pin 升级，等等。
- 改落地方式时保留「如果还是旧文件就……」的分支。直接按新设计写。

改坏了就重建容器 / 清 imagefs，不要在代码里兼容上一版。

## 仍要做

按**当前** pin 和 AppliedMarks 做幂等应用（想要 ≠ 已装才做），并删掉 manifest 不再 pin 的组件。这是当前状态同步，不是旧版迁移。
